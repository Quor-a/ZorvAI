import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.ai.assistance.quro.dshmobile"
  compileSdk = 36

  defaultConfig {
    minSdk = 26
    // targetSdk 是 APK 级约束（Android 15+ 禁 exec app-data ELF）：本模块是 library，
    // 由宿主 ZorvAI 的 targetSdk=34 承载，此处不设（AGP 9.0 起 library DSL 不再接受）。
    buildConfigField("String", "TERMUX_VERSION", "\"0.118.3\"")
  }

  buildFeatures {
    buildConfig = true
    // ShizukuUserService.aidl 生成 Stub（虚拟屏线 bindUserService 需要）。
    aidl = true
  }

  androidResources {
    // snapshot.tar.xz is already xz-compressed; double-compressing it breaks openFd.
    noCompress += "xz"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  lint {
    // Offline environments lack the lint-gradle dependency cache (CN networks); lint is not on the release-critical path.
    checkReleaseBuilds = false
    abortOnError = false
  }

  testOptions {
    // Snapshot extraction/tests touch android.util.Log; default stubs keep the JVM
    // unit tests runnable without Robolectric.
    unitTests.isReturnDefaultValues = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.JVM_17
  }
}

// The runtime snapshot is built from our own patched engine tree; the build fails
// with guidance when it is missing.
tasks.whenTaskAdded {
  if (name == "mergeDebugAssets" || name == "mergeReleaseAssets") {
    doFirst {
      val snap = file("src/main/assets/snapshot.tar.xz")
      if (!snap.exists()) {
        throw GradleException(
          "缺少运行时快照 assets/snapshot.tar.xz —— 由暂存引擎树转换 usr/ 布局后 tar.xz 生成（见 notes.md）",
        )
      }
    }
  }
}

dependencies {
  // Shizuku 特权通道（虚拟屏线 P0-0）：Maven Central 13.1.5。许可 MIT，
  // minSdk 26 >= aar 的 24/23，无需 desugaring。
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
  implementation("androidx.activity:activity-ktx:1.10.1")
  // 隔离浏览器身份面：document-start 脚本注入与 UA-CH（WebView >= 116 能力门）。
  implementation("androidx.webkit:webkit:1.12.1")
  // androidx.core: FileProvider；ViewCompat/WindowInsetsCompat。
  implementation("androidx.core:core-ktx:1.15.0")
  // 悬浮球 v2 动效：Material 3 Expressive spring 物理。
  implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
  implementation("org.apache.commons:commons-compress:1.28.0")
  implementation("org.tukaani:xz:1.10")
  testImplementation("junit:junit:4.13.2")
  // 本地单测用真实 org.json（android.jar 桩在 JVM 里抛 Stub!）。
  testImplementation("org.json:json:20240303")
}

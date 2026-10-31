import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ai.assistance.quro.termux"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 官方 TermuxApplication 需要 TERMUX_PACKAGE_VARIANT 初始化 TermuxBootstrap
        buildConfigField("String", "TERMUX_PACKAGE_VARIANT", "\"apt-android-7\"")
        // manifest 占位符：注入去品牌化后的包名（权限/authorities/action 均以此为准）
        manifestPlaceholders["TERMUX_PACKAGE_NAME"] = "com.ai.assistance.quro"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 官方源码使用 java.time / java.nio，需 desugar 兼容低版本 Android
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        // termux-host 使用经典 View 体系（Activity/DrawerLayout/ViewPager），无需 Compose
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.annotation)
    implementation(libs.material)
    // DrawerLayout + ViewPager + Preference：Termux 宿主 UI 基础
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.viewpager:viewpager:1.0.0")
    implementation("androidx.preference:preference:1.2.1")
    // Markwon（官方设置页 Markdown 渲染）
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("io.noties.markwon:recycler:4.6.2")
    // Guava（官方 ImmutableBiMap / Joiner / Strings / RecursiveDeleteOption）
    implementation("com.google.guava:guava:24.1-jre")
    implementation("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")
    // commons-io（官方文件工具）
    implementation("commons-io:commons-io:2.5")
    // HiddenApiBypass（官方 Android 隐藏 API 绕过）
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // termux-am-library（官方 Am 活动管理器通信库，包名 com.termux.am）
    implementation("com.termux:termux-am-library:v2.0.0")
    // androidx.window（官方 ViewUtils 使用 WindowMetrics）
    implementation("androidx.window:window:1.1.0")
    // vtlib — 完整 VT 终端模拟器/视图（去品牌化 terminal-emulator + terminal-view）
    implementation(project(":vtlib"))
    // 终端核心：proot 启动参数 / rootfs 准备 / 构建 ProotLaunch
    implementation(project(":terminal-core"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    // desugar（官方 java.time / java.nio 兼容）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:1.1.5")
}
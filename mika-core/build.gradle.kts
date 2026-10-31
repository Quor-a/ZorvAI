import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// mika-core — Mika 内核（原 core 模块）完整移植，供后台虚拟屏使用。
//
// 为什么是「整模块」而不是挑子集：
//   Mika 的后台虚拟屏（cap/phone/a11y）不是可切分的能力单元 —— 以它自身为种子跑依赖闭包
//   （docs/tools/mika_closure_pkg.py），包级稳定点覆盖 core 的 624/627 文件。
//   也就是说「后台虚拟屏 + 它需要的一切」在代码组织上就等于整个 core 模块。
//   所以这里原样 vendor（338 个主源码文件 / 8.2 万行 + 1.5MB 资源），只做包名替换
//   （com.wangzan101.mika.core → com.ai.assistance.quro.core.mika），不改任何逻辑。
//
// 用 android library 而不是 kotlin-jvm：工程里已有 com.android.library 插件别名；
// core 源码零 android import（已验证 0 处），走 library 不会引入任何 Android 语义。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ai.assistance.quro.core.mika"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    // 上游把源码放在 src/main/kotlin，这里沿用，避免搬运时改目录结构
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
            // knowledge / prompts / skills 三套资源随 core 一起搬进来（1.5MB）
            resources.srcDirs("src/main/resources")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // 上游代码只做搬运，不参与 lint 门禁
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Kotlin 侧必须与 Java 侧同目标，否则 AGP 直接拒绝（Kotlin 默认跟本机 JDK）
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 上游 core 的全部第三方依赖（对应 Mika core/build.gradle.kts）
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.coroutines.core)
    implementation(libs.commons.compress)
}

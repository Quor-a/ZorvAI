import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ai.assistance.quro.plugin.contract"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// ★ 契约层必须是「纯接口 + 极小依赖」：插件 APK 与宿主各自持有同一个 contract jar 的副本，
//   所以这里只用 compileOnly/implementation 的最小集合，不要让 contract 传递出任何重依赖，
//   否则插件编译 classpath 会与宿主冲突。
dependencies {
    implementation(libs.coroutines.core)
}

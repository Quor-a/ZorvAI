import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ai.assistance.quro.browser"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.ai.assistance.quro.browser"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "1.0.14"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 原生 Uinput 注入器（L3 事件面）：注册虚拟多点触摸屏并写出 /dev/uinput 内核事件。
    // NDK r27 + arm64-v8a 已在 defaultConfig.ndk 配置。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // ACI 协议层（受控端）：与主工程同一个 :aci-core 模块（本仓源码），保证协议一致
    implementation(project(":aidl-aci-core"))
    // HTTP 传输能力（http_request）：与主应用对称，复用同一 okhttp 版本（libs.versions.toml）
    implementation(libs.okhttp)
}

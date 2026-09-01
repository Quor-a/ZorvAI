import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ai.assistance.quro.aciapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ai.assistance.quro.aciapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // 使用系统默认 debug 签名，第三方开发者无需持有主应用私钥
            signingConfig = signingConfigs.getByName("debug")
        }
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

dependencies {
    // ACI 协议层（受控端）：与主工程同一个 :aidl-aci-core 模块（本仓源码），保证协议一致
    implementation(project(":aidl-aci-core"))
}

// 禁用 versionControlInfo 生成任务（AGP 8.13 的 packaging.excludes 拦不住此任务）
// 该任务在签名后写入 META-INF/version-control-info.textproto，导致部分厂商安装器报"安装包异常"
tasks.configureEach {
    if (name.contains("VersionControlInfo", ignoreCase = true)) {
        enabled = false
        println("Disabled task: $name")
    }
}

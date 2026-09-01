import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ai.assistance.quro.capmain"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // 框架层（CapabilitySpec / AciHandler / AciRouter / PermissionGuard / CapabilityRegistry）
    implementation(project(":lib_aci"))
    // ACI 协议层（BaseAidlAciService / AidlAciRequest / AidlAciResponse / Capability）：
    // implementation 依赖不传递，MainAciService 直接继承 BaseAidlAciService，需显式引入。
    implementation(project(":aidl-aci-core"))
    implementation("androidx.annotation:annotation:1.7.1")
}

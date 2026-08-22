plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ai.aidl.aci.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // ACI 协议层含 AIDL 接口定义（IAidlAciService / IAidlAciCallback / AidlAciRequest / AidlAciResponse），
    // 必须开启 aidl 编译。源码全部来自本仓（原 aci-core-debug.aar 的反向工程重建），
    // 不再依赖任何跨仓预编译 AAR。
    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Capability.toJSON / fromJSONArray 依赖 org.json
    implementation(libs.org.json)
}

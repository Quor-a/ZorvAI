plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yuanbao.miniapp"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // No consumerProguardFiles needed: the SDK keeps no reflection-based entry points.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
    }

    buildFeatures {
        // The SDK ships no Android resources of its own.
        buildConfig = false
    }

    // Debug builds keep assertions on for the native engine tests.
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    // Zero external dependencies: everything (JS engine, layout, renderer,
    // JSON, HTTP, storage) is implemented inside this module.
}

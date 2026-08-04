import java.net.URL
import java.io.FileOutputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ai.assistance.mnn"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // 支持的 ABI（与主 app 保持一致）
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fno-emulated-tls")
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-26",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DMNN_BUILD_SHARED_LIBS=ON",
                    "-DMNN_SEP_BUILD=OFF",
                    "-DMNN_BUILD_TOOLS=OFF",
                    "-DMNN_BUILD_DEMO=OFF",
                    "-DMNN_BUILD_CONVERTER=OFF",
                    "-DMNN_USE_LOGCAT=ON",
                    "-DMNN_BUILD_TEST=OFF",
                    "-DMNN_BUILD_BENCHMARK=OFF",
                    "-DMNN_BUILD_QUANTOOLS=OFF",
                    "-DMNN_OPENCL=OFF",
                    "-DMNN_OPENGL=OFF",
                    "-DMNN_VULKAN=OFF",
                    "-DMNN_ARM82=ON",
                    // 启用 LLM 支持
                    "-DMNN_BUILD_LLM=ON",
                    "-DMNN_SUPPORT_TRANSFORMER_FUSE=ON",
                    "-DMNN_LOW_MEMORY=ON",
                    "-DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON"
                )
            }
        }
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

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // JVM 单元测试：抗复读兜底（RepetitionGuard）与采样配置键名（buildSamplerConfigs）
    // 都是纯 JVM 逻辑，必须能脱离真机做回归。android.util.Log 走 mockable android.jar
    // 的默认返回值，避免 "Method ... not mocked" 异常。
    testOptions {
        unitTests.isReturnDefaultValues = true
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

    testImplementation("junit:junit:4.13.2")
    // 真实 org.json 实现：mockable android.jar 里的 org.json 是桩（返回默认值），
    // 会让 JSONObject.quote() 返回 null。显式引入真实实现，AGP 把 mockable jar 排在
    // classpath 末尾，因此这里的实现优先生效（测试内有前置断言兜底校验）。
    testImplementation(libs.org.json)
}


import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ai.assistance.quro"
    compileSdk = 36
    ndkVersion = "27.0.12077973" // 与独立编译 libquroplugin.so 的 NDK 一致（r27）

    defaultConfig {
        applicationId = "com.ai.assistance.quro"
        minSdk = 26
        targetSdk = 34
        versionCode = 448
        versionName = "1.0.12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // 插件逻辑层引擎（QuickJS）只编 arm64-v8a（首页插件 Demo 足够，缩小包体）
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 原生库解压到磁盘（而非从 APK mmap 加载），以便 LD_PRELOAD 能按路径引用 libquro_exec.so
        jniLibs {
            useLegacyPackaging = true
        }
    }

        aaptOptions {
            // 端侧 ASR 模型在运行期下载到应用私有目录（不再内置 assets）。此处 noCompress 以备 ncnn 文件误入 assets
            noCompress("onnx", "txt", "ncnn", "param", "bin", "gz", "crt", "so")
            // 注：早期 Sherpa-ONNX AAR 会自带约百 MB 示例模型，曾用 ignoreAssetsPattern = "sherpa*" 剔除；
            // 现引擎改为源码打入 + jniLibs 预编译 .so，不再有 AAR 内置模型，故移除该忽略规则。
        }

    // 插件运行时：把 QuickJS 引擎 + JNI 桥编进 app（libquroplugin.so）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // AGP 自带 CMake；若本地版本异常可显式指定 version = "3.22.1"
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
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // 插件运行时：PluginRuntime.kt（manifest 解析 / 权限网关）依赖 org.json
    implementation(libs.org.json)

    // ACI（Agent Capability Interface）协议层：让 QuroAI 成为 ACI 控制方（AI 中枢），
    // 发现并调用第三方 App 通过 ACI Service 暴露的能力。AAR 仅含协议层
    // （ai.aci.core.*：IACIService / IACICallback AIDL、ACIRequest / ACIResponse / Capability）。
    // 控制方客户端 QuroAciManager 为 QuroAI 内 Kotlin 单例（端口自 aci-aihub 的 ACIManager）。
    implementation(files("../aci-browser/libs/aci-core-debug.aar"))

    // 头像裁剪（原创集成，用于人格卡上传图片后裁剪）
    // 注意：canhub 已将库移交 vanniktech，坐标已变更（包名 com.canhub.cropper.* 不变）
    implementation("com.vanniktech:android-image-cropper:4.7.0")

    // 端侧（手机本地）语音转文本：Sherpa-NCNN（SenseVoice，离线、不连云）
    // 说明：Sherpa-NCNN 没有发布到 Maven Central / JitPack 的 AAR（JitPack 对 k2-fsa/sherpa-ncnn
    // 全部构建失败：仓库根仅含 CMake，无 Gradle 库模块）。官方 Android 集成方式即把 Kotlin 封装源码
    // 直接拷入工程（见 app/src/main/java/com/k2fsa/sherpa/ncnn/），并把预编译 native 库
    //   libsherpa-ncnn-jni.so / libsherpa-ncnn-core.so / libncnn.so / libkaldi-native-fbank-core.so
    // 放入 app/src/main/jniLibs/arm64-v8a/（从 release 的 sherpa-ncnn-v2.1.15-android.tar.bz2 取，
    // 或用 ./build-android-arm64-v8a.sh 自编译）。本工程 abiFilters 仅 arm64-v8a，故只需该目录。
    // 因此此处不需要 implementation(...) 依赖；如改用第三方/自托管 AAR，请把坐标加在此处。
    // 端侧模型压缩包解压（zip / tar.gz / tar.bz2 / tar）
    implementation("org.apache.commons:commons-compress:1.26.1")

    // 显式保活 concurrent-futures，确保 androidx.concurrent.futures.AbstractResolvableFuture 必然进 dex。
    implementation("androidx.concurrent:concurrent-futures:1.2.0")

    // 自包含终端（v127 起）：交互式 shell 用 QuroShellSession（常驻进程 + 流式读入，无 Termux/PTY 依赖），
    // 应用内 Linux 环境走 QuroLinuxEnv（proot + Alpine aarch64）。不再依赖 Termux terminal-view。

    // Shizuku（L2 通道：ADB 级 IPC，免 Root 系统命令执行 / 应用冻结 / 静默安装）
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // 视频/音频播放引擎：经全量源码 grep 确认，本工程从未引用 org.videolan.*，
    // 实际播放走 android.media.MediaPlayer（框架层，Apache-2.0，已 100% 开源）。
    // 原 libvlc-all:3.6.5（GPLv2/v3，强 copyleft）为「声明但零使用」的死依赖，
    // 仅带来无谓的 GPL 义务 → 已移除（见 license-audit-full-2026-07-28.md P1）。
    // 若后续需更强格式/流式支持，再单独评估引入 Media3 ExoPlayer（Apache-2.0）并迁移播放层。

    // 内置浏览器引擎：GeckoView（Mozilla 开源浏览器引擎，MPL-2.0）
    // 用开源引擎替换系统 WebView，对应元宝清单里 Firefox 系开源浏览器（Iceraven/IronFox）。
    // 开源地址：https://github.com/fork-maintainers/iceraven-browser
    implementation("org.mozilla.geckoview:geckoview:140.0.20250707120347")

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.org.json)
}

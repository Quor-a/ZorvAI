import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// 读取 keystore.properties
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ai.assistance.quro"
    compileSdk = 36
    ndkVersion = "27.0.12077973" // 与独立编译 libquroplugin.so 的 NDK 一致（r27）

    signingConfigs {
        // 使用 keystore.properties 中配置的签名证书
        create("release") {
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            // 启用 V1+V2 签名：V1 签名兼容旧安装器，V2 签名提供更好的安全性
            // version-control-info 已被禁用，不会污染 V1 签名链
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.ai.assistance.quro"
        minSdk = 26
        targetSdk = 34
        versionCode = 499
        versionName = "1.0.61"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // 插件逻辑层引擎（QuickJS）只编 arm64-v8a（首页插件 Demo 足够，缩小包体）
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // F-Droid 合规：fdroid 风味剔除预编译原生库（离线 ASR + 应用内 Linux 沙箱），
    // 仅保留源码编出的 libquroplugin.so；full 风味保持完整原生特性（Google Play / 自有分发）。
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            // 默认风味：包含 src/full/jniLibs 下的预编译原生库（ncnn / sherpa / proot / talloc）
            // 同时接入源码编译的本地离线 LLM 引擎（:mnn + :llama，即 MNN / llama.cpp）
        }
        create("fdroid") {
            dimension = "distribution"
            // F-Droid 构建风味：不含任何预编译原生库，满足「仓库内无预编译二进制」红线
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        // Release构建时忽略lint错误（QuroShellService的AIDL Stub lint误报）
        abortOnError = false
        checkReleaseBuilds = false
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
            // 排除 release-only 元数据文件（部分厂商安装器不识别）
            excludes += "META-INF/version-control-info.textproto"
            excludes += "kotlin-tooling-metadata.json"
            // 排除 AGP 8.13 自动生成的 .version 文件（部分厂商安装器可能不识别）
            excludes += "META-INF/*.version"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

        androidResources {
            // 端侧 ASR 模型在运行期下载到应用私有目录（不再内置 assets）。此处 noCompress 以备 ncnn 文件误入 assets
            noCompress += listOf("onnx", "txt", "ncnn", "param", "bin", "gz", "crt", "so")
            // 注：早期 Sherpa-ONNX AAR 会自带约百 MB 示例模型，曾用 ignoreAssetsPattern = "sherpa*" 剔除；
            // 现引擎改为源码打入 + jniLibs 预编译 .so，不再有 AAR 内置模型，故移除该忽略规则。
        }

    // JVM 单测（src/test、src/testFull）：被测逻辑是纯 JVM 的（.gguf 路径解析、门禁身份判定、
    // 提示文案分支），必须能脱离真机回归——用户侧无 adb，真机验证不可用。
    // android.os.Process.myPid() / android.os.Environment.* 走 mockable android.jar 的默认返回值，
    // 避免 "Method ... not mocked" 异常打断纯逻辑测试。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // 插件运行时：把 QuickJS 引擎 + JNI 桥编进 app（libquroplugin.so）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // AGP 自带 CMake；若本地版本异常可显式指定 version = "3.22.1"
        }
    }
}

// 禁用 versionControlInfo 生成任务（AGP 8.13 的 packaging.excludes 拦不住此任务）
// 该任务在签名后写入 META-INF/version-control-info.textproto，导致部分厂商安装器报"安装包异常"
tasks.configureEach {
    if (name.contains("VersionControlInfo", ignoreCase = true)) {
        enabled = false
        println("Disabled task: $name")
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
    implementation(libs.zxing.core)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // 插件运行时：PluginRuntime.kt（manifest 解析 / 权限网关）依赖 org.json
    implementation(libs.org.json)

    // 应用内文档预览：WebViewAssetLoader（RC-A 内存炸弹 + RC-B pdf.js Worker 同源修复）
    implementation(libs.androidx.webkit)

    // ACI（Agent Capability Interface）协议层：让 QuroAI 成为 ACI 控制方（AI 中枢），
    // 发现并调用第三方 App 通过 ACI Service 暴露的能力。源码现已收进本仓 :aci-core 模块
    // （ai.aci.core.*：IAidlAciService / IAidlAciCallback AIDL、AidlAciRequest / AidlAciResponse / Capability），
    // 不再依赖任何跨仓预编译 AAR。受控端 aci-browser 同样依赖 :aci-core，保证协议一致。
    implementation(project(":aidl-aci-core"))

    // 旧契约兼容 AAR（ai.aci.core.*）：浏览器等第三方旧受控端在「ACI→AIDL ACI 重命名」重构前
    // 基于该契约构建，其 Service 描述符为 ai.aci.core.IACIService。控制端必须持有字节一致的旧类，
    // 才能 IAidlAciService.Stub.asInterface 成功并正确（反）序列化 ACIRequest/ACIResponse。
    // 用于双契约绑定：新契约优先，旧契约兜底，保证老受控端能力仍可被发现与调用。
    implementation(files("libs/aci-core-legacy.aar"))

    // 本地离线 LLM 引擎（MNN / llama.cpp）：仅 full 风味依赖，源码编译，满足 F-Droid 红线
    // 注意：fullImplementation 访问器此前因 kotlin-dsl 配置探针死锁（deprecation error 阻断脚本编译 → 访问器永不生成）而
    // unresolved；改用 DependencyHandler.add() 显式按配置名注入，绕过访问器依赖，确保脚本成功编译一次后死锁解除。
    add("fullImplementation", project(":mnn"))
    add("fullImplementation", project(":llama"))

    // 头像裁剪（原创集成，用于人格卡上传图片后裁剪）
    // 注意：canhub 已将库移交 vanniktech，坐标已变更（包名 com.canhub.cropper.* 不变）
    implementation("com.vanniktech:android-image-cropper:4.7.0")

    // 端侧（手机本地）语音转文本：Sherpa-NCNN（SenseVoice，离线、不连云）
    // 说明：Sherpa-NCNN 没有发布到 Maven Central / JitPack 的 AAR（JitPack 对 k2-fsa/sherpa-ncnn
    // 全部构建失败：仓库根仅含 CMake，无 Gradle 库模块）。官方 Android 集成方式即把 Kotlin 封装源码
    // 直接拷入工程（见 app/src/main/java/com/k2fsa/sherpa/ncnn/），并把预编译 native 库
    //   libsherpa-ncnn-jni.so / libsherpa-ncnn-core.so / libncnn.so / libkaldi-native-fbank-core.so
    // 放入 app/src/full/jniLibs/arm64-v8a/（从 release 的 sherpa-ncnn-v2.1.15-android.tar.bz2 取，
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
    // 用开源引擎替换系统 WebView，采用 Firefox 系开源浏览器（Iceraven/IronFox）。
    // 开源地址：https://github.com/fork-maintainers/iceraven-browser
    implementation("org.mozilla.geckoview:geckoview:140.0.20250707120347")

    // 健康数据（Health Connect，Android 13+）：Android 14+ 系统内置，旧版经此库桥接，二者 API 一致。
    // 提供 HealthConnectClient / 权限契约 / Steps·HeartRate·Sleep·Exercise 等 Record 读写。
    // 注意 artifact 为 connect-client（非 health-connect-client）。
    // 与 Manifest 的 <meta-data android:name="health_permissions"> + res/values/health_permissions.xml 对应。
    implementation("androidx.health.connect:connect-client:1.1.0-alpha06")

    // Apache POI for Android：本地Office文档编辑SDK（Word/Excel/PPT）
    // 开源免费，无需服务器，支持离线编辑
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    implementation("stax:stax-api:1.0.1")

    // 微信 iLink Bot：纯 OkHttp + org.json 实现（移植自 Andclaw 1.2.0，不依赖 SDK）

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.org.json)
}

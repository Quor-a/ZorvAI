import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// 与宿主共用同一份签名配置 —— 插件必须与宿主同签名，宿主 PluginInstaller 才会放行安装。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}
val keystorePath = (keystoreProperties["storeFile"] as String?).orEmpty().removePrefix("../")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * zx_widget 气泡内可交互 UI 组件族插件（独立 APK）。
 *
 * 由「气泡内可交互 UI 组件族（极限版）」作为 ZorvAI 插件落地：
 *  - 注册 AI 工具（zx_render / zx_select / …）+ 对话卡片（chatCard 数百个组件）+ UI 表面；
 *  - 渲染全部用 Compose，颜色只用宿主 token，自动跟随深色模式；
 *  - 插件不写自己的 Activity，全屏界面走宿主 PluginSurfaceActivity。
 */
android {
    namespace = "com.zorv.plugin.zxwidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zorv.plugin.zxwidget"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (keystorePath.isNotEmpty()) {
                storeFile = rootProject.file(keystorePath)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (keystorePath.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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

dependencies {
    // 契约层：编译期可见、运行期由宿主提供（绝不能 api/implementation 打进包）
    compileOnly(project(":plugin-contract"))
    // 协程：宿主已有（父类加载器优先共享 kotlinx.*），插件不重复打包
    compileOnly(libs.coroutines.core)
    // Compose：宿主已有 androidx.*（PluginClassLoader 共享前缀含 androidx.），
    // 插件编译期依赖、运行期由宿主提供，避免两份 Compose 类。
    compileOnly(platform(libs.compose.bom))
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.ui.graphics)
    compileOnly(libs.compose.foundation)
    compileOnly(libs.compose.material3)
    compileOnly(libs.compose.material.icons.extended)
    compileOnly(libs.compose.animation)
    compileOnly(libs.compose.runtime)
    compileOnly(libs.activity.compose)
}
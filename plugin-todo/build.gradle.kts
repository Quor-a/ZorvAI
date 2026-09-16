import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// 与宿主共用同一份签名配置 —— 插件必须与宿主同签名，宿主 PluginInstaller 才会放行安装。
// 这里用 rootProject.file 解析，避免 keystore.properties 里 "../app/..." 相对路径语义错位。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}
val keystorePath = (keystoreProperties["storeFile"] as String?).orEmpty().removePrefix("../")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * 示例插件模块（独立 APK）。
 *
 * 构建要点：
 *  - compileOnly(project(":plugin-contract"))：契约类运行时由宿主提供，**不要**打进插件 APK，
 *    否则插件与宿主各持一份接口 Class，调用时直接 ClassCastException（自研插件框架第一大坑）。
 *  - 签名与宿主一致：宿主安装插件时会校验 SHA-256 证书一致。
 *
 * 产出：`./gradlew :plugin-express:assembleRelease` → plugin-express/build/outputs/apk/release/
 */
android {
    namespace = "com.zorv.plugin.todo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zorv.plugin.todo"
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
}

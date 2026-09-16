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
}

/**
 * ZorvWeb 网页引擎插件（独立 APK）。
 *
 * 由 ZorvBrowser 的「受控端浏览器」改写为宿主内插件：
 *  - 去掉 AIDL 受控端 ACI Service / aci-core AAR / 唤醒 Receiver；
 *  - 去掉 Uinput 触摸注入（需 root，且属受控端 L3 事件面，插件不需要）与 NDK；
 *  - 去掉自带的浏览器 Activity（插件没有自己的界面）与 SDUI 控制台；
 *  - 保留网页引擎与能力实现，改为插件扩展点：AI 工具 + ACI 能力。
 */
android {
    namespace = "com.zorv.plugin.zorvweb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zorv.plugin.zorvweb"
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
    // 注：不再依赖 aci-core AAR（那是受控端 AIDL 协议层，插件架构下不需要）。
    //     不再依赖 okhttp（HTTP 传输改用 JDK HttpURLConnection，零额外依赖）。
}

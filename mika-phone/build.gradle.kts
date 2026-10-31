import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// mika-phone — Mika 的后台虚拟屏操作层（cap/phone/a11y + display）完整移植。
//
// 组成（22 个文件，全部来自 Mika，只改包名与宿主绑定）：
//   a11y/    15 个：AndroidScreen（动作编排 1810 行）、ScreenReader、ScreenAccessibility、
//                  LazyPhoneScreen、MainScreenCapture、MainScreenSpill、PhoneOcr、WheelDriver、
//                  OverlayHost、NodeIds、ChangeWait、AppSwitch、AppCopyReader、StillFrameRead、SystemPickers
//   display/  3 个：AgentDisplay（ImageReader 出图）、FrameMath、LazyDisplay
//   shell/    3 个：SystemIntents（系统动作白名单）、MainScreenLog、MainScreenWatch（主屏监视）
//   i18n/     2 个：AppLanguage、UiText
//   vision/   1 个：BitmapImageOps（依赖 :mika-core 的 core.vision.ImageOps）
//
// 与上游的唯一差别是「宿主绑定」：上游 AndroidScreen 直接吃 AIDL 的 IMikaShell 与
// ShellService 常量；这里改成 MikaShellPort + ShellCodes（同签名、同返回码语义），
// 由 :app 的 QuroShellPortAdapter 把 IQuroShellService 适配进来。
// 操作逻辑（读树、定位、等待界面安静、指纹核实、手势编排）一行未改。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ai.assistance.quro.core.mika.phone"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // OverlayHost（屏幕上给用户看的操作提示层）是 Compose 写的，必须开
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 内核（uistate 的 ScreenSnapshot/ActionResult/ScreenPort、visual.FrameGrid、vision.ImageOps 等）
    api(project(":mika-core"))
    implementation(libs.coroutines.core)

    // OverlayHost 的 Compose 依赖（与 :app 同一套 BOM，避免版本漂移）
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    // PhoneOcr：静止画面读文字（MLKit 中文识别，与上游同版本）
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
}

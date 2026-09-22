import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * GenUI-Agent 渲染 SDK（内置自 github.com/Quor-a/GenUI-Agent 的 sdk/ 模块，已去品牌）。
 *
 * 能力：声明式 GenUI JSON DSL → Compose 原生组件渲染。
 * 入口：DslParser.parse(json) → GenUI.Screen(spec, host) → collectFrom 表单值聚合回传。
 *
 * 与上游的差异（为对齐 ZorvAI 基座）：
 *   · 上游用 composeOptions.kotlinCompilerExtensionVersion = "1.5.14"（Kotlin 1.x 写法），
 *     在 ZorvAI 的 Kotlin 2.3.10 下已不兼容 → 改用 kotlin.plugin.compose 插件驱动；
 *   · Compose BOM / compileSdk / 序列化 / 协程版本统一走 ZorvAI 的 version catalog；
 *   · 包名 com.genui.sdk → com.ai.assistance.quro.genui.sdk。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ai.assistance.quro.genui.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
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

    // Compose：版本统一由 BOM 管理；foundation / material-icons-core 无 catalog 别名，直写坐标
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.compose.material.icons.extended)

    // 图片 / JSON / 协程 / 宿主 Activity
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.activity.compose)
}

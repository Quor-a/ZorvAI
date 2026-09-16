import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ai.assistance.quro.kaleidobox"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}

// ── 生成端侧编译器用的 kaleidobox API jar ──
// 把本模块 core.* 编出的类打成 assets/libs/kaleido/kaleidobox_api.jar，
// 作为 [PluginCompiler] 在设备内 ecj→d8 编译插件源码时的 classpath（插件 import 的
// KaleidoToolkit / KValue / UiNode / Json 等都在里面）。只收 core 包，排除 android/samples。
val kaleidoboxApiJar by tasks.registering(Jar::class) {
    archiveBaseName.set("kaleidobox_api")
    archiveVersion.set("")
    destinationDirectory.set(layout.projectDirectory.dir("src/main/assets/libs/kaleido"))
    val kotlinOut = tasks.named("compileReleaseKotlin", KotlinCompile::class.java)
        .map { it.destinationDirectory.get().asFile }
    val javaOut = tasks.named("compileReleaseJavaWithJavac", JavaCompile::class.java)
        .map { it.destinationDirectory.get().asFile }
    from(kotlinOut) { include("com/ai/assistance/quro/kaleidobox/core/**") }
    from(javaOut) { include("com/ai/assistance/quro/kaleidobox/core/**") }
    dependsOn("compileReleaseKotlin", "compileReleaseJavaWithJavac")
    onlyIf { kotlinOut.get().exists() }
}

// 在把资产打进 APK 前先生成 api jar（库模块无 flavor，变体任务即 mergeReleaseAssets）。
tasks.configureEach {
    if (name in setOf("mergeReleaseAssets", "mergeFullReleaseAssets")) dependsOn(kaleidoboxApiJar)
}

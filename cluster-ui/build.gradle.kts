import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 多角色集群编排·Compose 界面层（移植自 AI集群SDK 的 :sdk:ui，已去品牌化）。
 *
 * 集群看板 / 任务控制台 / 角色编辑三类界面。
 * 依赖方向 ui → engine → bridge → model。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ai.assistance.quro.cluster.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    api(project(":cluster-model"))
    api(project(":cluster-bridge"))
    api(project(":cluster-engine"))
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
}
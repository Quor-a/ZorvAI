import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 多角色集群编排·测试替身（移植自 AI集群SDK 的 :sdk:fake，已去品牌化）。
 *
 * FakeHostModelBridge + InMemoryClusterStore：主持状态机能不能真的把事办成（M2 生死线）
 * 不花钱就能验证 —— 先用假宿主模型跑通闭环，再接真模型。
 * 依赖方向 fake → engine → bridge → model（单向，无环）。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ai.assistance.quro.cluster.fake"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    api(project(":cluster-bridge"))
    api(project(":cluster-engine"))
    api(libs.coroutines.core)
}
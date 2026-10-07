import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 多角色集群编排·领域层（移植自 AI集群SDK 的 :sdk:model，已去品牌化）。
 *
 * 只放纯数据/枚举/不变量：Agent（角色）、Task（任务）、Host（主持）、
 * ModelBinding（模型绑定）、Skill（技能）、Events（事件）、Ids（ID 生成）、Records（快照记录）。
 *
 * 关键约束（与 ZorvAI 基座一致）：
 *  · 本层**零 Android 依赖**，可在 JVM 上直接跑单测；
 *  · 主持 Host 不可变：id 固定 __host__、不可增删改名、字段全 val —— 这是产品级不变量，不是风格偏好。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ai.assistance.quro.cluster.model"
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
    implementation(libs.kotlinx.serialization.json)
}
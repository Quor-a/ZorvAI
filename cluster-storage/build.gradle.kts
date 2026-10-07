import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 多角色集群编排·持久层（移植自 AI集群SDK 的 :sdk:storage，已去品牌化）。
 *
 * 独立数据库 qurocluster.db（**不与宿主 Room 库混用**，避免迁移互相打架），
 * Room + KSP 代码生成，SQLCipher 加密（口令由宿主传入，推荐从 AndroidKeystore 派生）。
 *
 * ZorvAI 首次引入 Room：本模块是工程里唯一的 Room 消费者。
 * 存储决策沿用上游：领域对象走 JSON 列，高频字段（cluster_id / task_id / state / ts）
 * 单独成列并建索引，事件表每任务只留最近 2000 条。
 *
 * 打开失败（口令变更 / 库损坏）时自动重建 —— 宁可丢缓存也不能让整个 App 崩。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ai.assistance.quro.cluster.storage"
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

// Room schema 导出：ZorvAI 首个 Room 消费者，必须显式给 schemaLocation，
// 否则 KSP 每次都警告「无法导出 schema」，后续 schema 变更无法做迁移校验。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    api(project(":cluster-model"))
    api(project(":cluster-engine"))
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.room.compiler)
}
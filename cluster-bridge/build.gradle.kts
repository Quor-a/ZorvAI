import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 多角色集群编排·宿主桥接层（移植自 AI集群SDK 的 :sdk:bridge，已去品牌化）。
 *
 * 宿主唯一必须实现的适配面：`HostModelBridge`（约60 行）。
 * SDK 不自带模型实现，也不认识任何厂商、不接触 API Key —— 只拿宿主给的 modelId 当句柄。
 *
 * ModelGateway 六件事：模型清单缓存 30s / 按 hostModelId 的并发闸门 / 降级链（指数退避）/
 *                      token 预算记账与单模型熔断 / 输出协议适配 / 超时保护（默认 120s）。
 *
 * 三条硬性约定（违反任一都会静默降级或挂死）：
 *  1. 流结束**必须**发 HostChatChunk.Done，出错**必须**发 Error —— 否则挂到超时才降级；
 *  2. 宿主侧**不要重试** —— 两层重试会放大流量；
 *  3. chat() 必须是**冷的**：只有被 collect 时才发请求，否则 ModelGateway 的并发闸门失效。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ai.assistance.quro.cluster.bridge"
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
    api(project(":cluster-model"))
    api(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
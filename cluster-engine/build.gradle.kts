import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 多角色集群编排·引擎层（移植自 AI集群SDK 的 :sdk:engine，已去品牌化）。
 *
 * 依赖方向：engine → bridge → model（L2 以下不知道「角色」存在）。
 * 构成：ClusterEngine（门面）/ AgentRunner / PromptAssembler（9 步装配）/
 *      SkillLoader（三级加载）/ ToolRegistry（六道关）/ Blackboard / HostAgent（状态机）/ Router（打分选人）。
 *
 * 与上游 SDK 的差异：
 *  · 上游把 :sdk:fake 声明为 testImplementation 会造成 engine↔fake 循环依赖，
 *    这里把 fake 拆成独立模块 :cluster-fake（依赖 engine），单测放在 app 侧跑。
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ai.assistance.quro.cluster.engine"
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
    api(project(":cluster-bridge"))
    api(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
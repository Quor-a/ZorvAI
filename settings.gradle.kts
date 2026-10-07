pluginManagement {
    repositories {
        // 阿里云镜像优先：本机直连 Maven Central / Google 不通，先走镜像避免超时重试导致的解析失败
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像优先：本机直连 Maven Central / Google 不通，先走镜像避免超时
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // GeckoView（Mozilla 开源浏览器引擎）官方仓库
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "Quro AI"
include(":app", ":aidl-aci-browser", ":aidl-aci-core", ":mnn", ":llama", ":lib_aci", ":cap_main", ":xposed-stub", ":aci-app", ":terminal-core", ":genuiagent-sdk", ":miniapp-sdk", ":kaleidobox", ":plugin-contract", ":plugin-engine", ":plugin-express", ":plugin-devkit", ":plugin-units", ":plugin-todo", ":plugin-sysinfo", ":plugin-zorvweb", ":plugin-signcheck",
    // 多角色集群编排（移植自 AI集群SDK，已去品牌化为 com.ai.assistance.quro.cluster.*）
    // 依赖方向：cluster-ui → cluster-engine → cluster-bridge → cluster-model
    ":cluster-model", ":cluster-bridge", ":cluster-engine", ":cluster-storage", ":cluster-ui", ":cluster-fake",
    // CodeCanvas 出图 SDK（脚本驱动多引擎多后端，纯端侧渲染，不依赖任何服务器）
    // 依赖方向：app → renderer-compose / renderer-canvas / renderer-svg / engine-kotlindsl / llm-connector → codecanvas-core
    ":codecanvas-core", ":engine-kotlindsl", ":renderer-canvas", ":renderer-compose", ":renderer-svg", ":llm-connector")
project(":mnn").projectDir = file("llm/mnn")
project(":llama").projectDir = file("llm/llama")
// CodeCanvas 子模块实际位于 codecanvas/<模块名>/，模块名与目录名不同名，必须显式指路
for (cc in listOf("codecanvas-core", "engine-kotlindsl",
    "renderer-canvas", "renderer-compose", "renderer-svg", "llm-connector")) {
    project(":$cc").projectDir = file("codecanvas/$cc")
}

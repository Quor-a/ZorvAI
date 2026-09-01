pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://jitpack.io") }
        // GeckoView（Mozilla 开源浏览器引擎）官方仓库
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "Quro AI"
include(":app", ":aidl-aci-browser", ":aidl-aci-core", ":mnn", ":llama", ":lib_aci", ":cap_main")
project(":mnn").projectDir = file("llm/mnn")
project(":llama").projectDir = file("llm/llama")

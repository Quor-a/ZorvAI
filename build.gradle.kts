// 顶层构建文件
plugins {
    // AGP 在根工程集中管理：application 与 library 两种插件都声明 apply false，
    // 让所有子模块（:app / :aci-browser / :aci-core）用 alias 取同一版本，
    // 避免子模块单独带版本请求时与已在 classpath 的 AGP 冲突。
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

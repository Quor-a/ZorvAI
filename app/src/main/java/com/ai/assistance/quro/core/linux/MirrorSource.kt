package com.ai.assistance.quro.core.linux

/**
 * 镜像源信息
 */
data class MirrorSource(
    val id: String,
    val name: String,
    val url: String,
    val isChinese: Boolean = true
)

/**
 * 包管理器类型
 */
enum class PackageManagerType {
    APT,
    PIP,
    NPM,
    RUST
}

/**
 * 源配置
 */
data class SourceConfig(
    val packageManager: PackageManagerType,
    val selectedSourceId: String,
    val sources: List<MirrorSource>
)
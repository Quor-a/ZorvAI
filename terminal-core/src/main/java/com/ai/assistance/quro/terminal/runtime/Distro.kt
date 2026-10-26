package com.ai.assistance.quro.terminal.runtime

/**
 * 终端可选发行版。
 *
 * - UBUNTU：proot + Ubuntu rootfs（APK 内置 asset，离线可用）。
 *
 * 每个发行版对应一套独立的 proot rootfs 目录（installed-rootfs/<rootfsDirName>），
 * 互不干扰；切换发行版只改变 common.sh 生成与启动目标，不动另一套已解压的 rootfs。
 *
 * 注：历史上支持多发行版切换，第二发行版于 2026-10-23 移除（内置资产约 400MB，体积过大），
 * 发行版抽象整体于 2026-10-26 收敛为唯一 Ubuntu；兼容 API 的 distro 参数默认 [UBUNTU]。
 */
enum class Distro(
    val id: String,
    val displayName: String,
    val rootfsDirName: String,
    /** rootfs 安装包文件名（内置 asset）。 */
    val rootfsFilename: String,
) {
    UBUNTU(
        id = "ubuntu",
        displayName = "Ubuntu",
        rootfsDirName = "ubuntu",
        rootfsFilename = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz",
    );

    companion object {
        fun fromId(id: String?): Distro =
            entries.firstOrNull { it.id == id } ?: UBUNTU
    }
}

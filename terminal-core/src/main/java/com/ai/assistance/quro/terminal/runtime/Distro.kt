package com.ai.assistance.quro.terminal.runtime

/**
 * 终端「双系统」可选发行版。
 *
 * - UBUNTU：沿用既有 proot + Ubuntu rootfs（APK 内置 asset，离线可用）。
 * - LANXIU：澜岫 安卓版 rootfs（首次使用时按需下载，约 88MB）。
 *
 * 每个发行版对应一套独立的 proot rootfs 目录（installed-rootfs/<rootfsDirName>），
 * 互不干扰；切换发行版只改变 common.sh 生成与启动目标，不动另一套已解压的 rootfs。
 */
enum class Distro(
    val id: String,
    val displayName: String,
    val rootfsDirName: String,
    /** 是否为 Debian/Ubuntu 系（写 apt/pip/npm/rust 源）；澜岫 用自家 qypkg，不需要。 */
    val isDebian: Boolean,
    /** rootfs 安装包文件名（Ubuntu 为内置 asset；LANXIU 为下载产物）。 */
    val rootfsFilename: String,
) {
    UBUNTU(
        id = "ubuntu",
        displayName = "Ubuntu",
        rootfsDirName = "ubuntu",
        isDebian = true,
        rootfsFilename = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz",
    ),
    LANXIU(
        id = "lanxiu",
        displayName = "LANXIU",
        rootfsDirName = "lanxiu",
        isDebian = false,
        rootfsFilename = "lanxiu-aarch64-rootfs-20261020-r17.tar.xz",
    );

    companion object {
        fun fromId(id: String?): Distro =
            entries.firstOrNull { it.id == id } ?: UBUNTU
    }
}

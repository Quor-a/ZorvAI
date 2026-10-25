package com.ai.assistance.quro.dshmobile

/** A bundled snapshot without a usable SHA must not be refreshed or accepted as current. */
internal object SnapshotFingerprintPolicy {
  data class Bundled(val fingerprint: String?, val failureCode: String?, val detail: String?)

  fun read(raw: String?): Bundled {
    if (raw.isNullOrBlank()) return Bundled(
      null, "snapshot-bundled-sha-missing",
      "内嵌运行时缺少 SHA-256 指纹，已停止更新与启动；请安装包含完整快照指纹的安装包。现有用户数据未改动。",
    )
    val value = raw.trim()
    if (!Regex("[0-9a-fA-F]{64}").matches(value)) return Bundled(
      null, "snapshot-bundled-sha-invalid",
      "内嵌运行时 SHA-256 指纹格式无效，已停止更新与启动；请安装包含完整快照指纹的安装包。现有用户数据未改动。",
    )
    return Bundled(value.lowercase(java.util.Locale.ROOT), null, null)
  }

  fun fresh(nodeExists: Boolean, bundled: Bundled, committed: String?): Boolean =
    nodeExists && bundled.fingerprint != null &&
      committed?.trim()?.lowercase(java.util.Locale.ROOT) == bundled.fingerprint

  /** Online runtime archives remain fresh for the APK snapshot against which they were installed. */
  fun onlineFresh(
    nodeExists: Boolean,
    bundledFingerprint: String?,
    committedFingerprint: String?,
    onlineBaseFingerprint: String?,
    onlineArchiveFingerprint: String?,
  ): Boolean {
    fun normalized(value: String?): String? = value?.trim()?.lowercase(java.util.Locale.ROOT)
      ?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
    val bundled = normalized(bundledFingerprint) ?: return false
    val committed = normalized(committedFingerprint) ?: return false
    val base = normalized(onlineBaseFingerprint) ?: return false
    val archive = normalized(onlineArchiveFingerprint) ?: return false
    return nodeExists && bundled == base && committed == archive
  }
}

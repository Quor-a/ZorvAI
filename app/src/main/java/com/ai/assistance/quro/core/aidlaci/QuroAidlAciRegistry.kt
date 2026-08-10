package com.ai.assistance.quro.core.aidlaci

import ai.aidl.aci.core.Capability
import java.util.concurrent.ConcurrentHashMap

/**
 * ACI 2.0 能力注册表（分层能力发现地基）。
 *
 * 主应用侧的能力元数据层：aci-core 的 [Capability] 当前无 tags 字段，
 * 这里独立维护「能力 id → 标签/归属」的索引，支撑按标签检索与动态热加载/卸载，
 * 不改动 aci-core AAR（tags 字段的正式落地在 aci-core 侧，见 SDK 2.0 Roadmap）。
 *
 * 标签由 id/描述启发式推导（如 http/browser/file…），并允许显式追加。
 */
object QuroAidlAciRegistry {

    /** key = "$pkg::$id" */
    private val store = ConcurrentHashMap<String, AciCapabilityMeta>()

    /** 注册单条能力（从 aci-core Capability 推导元数据 + 启发式标签）。 */
    fun register(pkg: String, cap: Capability) {
        store["$pkg::${cap.id}"] = AciCapabilityMeta(
            id = cap.id,
            description = cap.description,
            tags = inferTags(cap.id, cap.description),
            ownerPackage = pkg
        )
    }

    /** 注册带显式标签的能力（动态插件可主动声明语义标签）。 */
    fun registerMeta(meta: AciCapabilityMeta) {
        store["${meta.ownerPackage}::${meta.id}"] = meta
    }

    /** 动态卸载（运行时热卸载插件时调用）。 */
    fun unregister(pkg: String, id: String) {
        store.remove("$pkg::$id")
    }

    /** 清空某包全部能力（重绑/卸载时）。 */
    fun clearPackage(pkg: String) {
        store.keys.removeIf { it.startsWith("$pkg::") }
    }

    /** 从发现的能力清单批量同步（QuroAidlAciManager.fetchCapabilities 后调用）。 */
    fun syncFromCapabilities(pkg: String, caps: List<Capability>) {
        clearPackage(pkg)
        for (c in caps) register(pkg, c)
    }

    fun all(): List<AciCapabilityMeta> = store.values.toList()

    fun byPackage(pkg: String): List<AciCapabilityMeta> =
        store.values.filter { it.ownerPackage == pkg }

    /** 按单个标签检索（命中即返回）。 */
    fun queryByTag(tag: String): List<AciCapabilityMeta> =
        store.values.filter { tag in it.tags }

    /** 按任意标签命中（OR）。 */
    fun queryByTagsAny(tags: List<String>): List<AciCapabilityMeta> =
        store.values.filter { it.tags.any { t -> t in tags } }

    /** 须同时满足全部标签（AND）。 */
    fun queryByTagsAll(tags: List<String>): List<AciCapabilityMeta> =
        store.values.filter { tags.all { t -> t in it.tags } }

    /** 启发式标签推导：依据能力 id / 描述关键词映射到语义标签。 */
    private fun inferTags(id: String, description: String): List<String> {
        val text = "$id $description".lowercase()
        val tags = mutableSetOf<String>()
        if (Regex("""\b(http|request|url|api|webhook|fetch|download|upload)\b""").containsMatchIn(text)) tags += "network"
        if (Regex("""\b(browser|web|crawl|scrap|nav|tab|click|page|bookmark)\b""").containsMatchIn(text)) tags += "web"
        if (Regex("""\b(file|read|write|dir|path|fs|document|pdf|folder)\b""").containsMatchIn(text)) tags += "fs"
        if (Regex("""\b(sms|message|mail|chat|notify|notification|im)\b""").containsMatchIn(text)) tags += "messaging"
        if (Regex("""\b(calendar|schedule|alarm|reminder|event|meeting)\b""").containsMatchIn(text)) tags += "calendar"
        if (Regex("""\b(media|audio|video|image|photo|capture|camera|record)\b""").containsMatchIn(text)) tags += "media"
        if (Regex("""\b(location|map|gps|geo|navigate)\b""").containsMatchIn(text)) tags += "location"
        if (Regex("""\b(shell|execute|run|term|node|python|script|cmd|command)\b""").containsMatchIn(text)) tags += "execute"
        if (Regex("""\b(ui|console|screen|render|display|draw|widget)\b""").containsMatchIn(text)) tags += "ui"
        if (Regex("""\b(auth|login|token|credential|account|vault|oauth)\b""").containsMatchIn(text)) tags += "auth"
        if (tags.isEmpty()) tags += "misc"
        return tags.toList()
    }
}

/**
 * 能力元数据（注册表条目）。
 * @param id 能力 id（与 Capability.id 一致）
 * @param description 人类可读描述
 * @param tags 语义标签（用于检索/分组）
 * @param ownerPackage 暴露该能力的 App 包名
 * @param source 来源：discovered=自动发现 / plugin=动态插件声明
 */
data class AciCapabilityMeta(
    val id: String,
    val description: String,
    val tags: List<String>,
    val ownerPackage: String,
    val source: String = "discovered"
)

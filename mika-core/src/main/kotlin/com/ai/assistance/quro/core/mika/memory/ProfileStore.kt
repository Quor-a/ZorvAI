package com.ai.assistance.quro.core.mika.memory

import kotlinx.serialization.Serializable

@Serializable
enum class ProfileStatus { active, superseded }

@Serializable
data class ProfileEntry(val id: String, val text: String, val atMs: Long, val status: ProfileStatus,
    val supersededAtMs: Long? = null)

interface ProfileBackend {
    fun read(): List<ProfileEntry>
    fun write(entries: List<ProfileEntry>)
}

sealed interface ProfileChange {
    data class Applied(val entry: ProfileEntry?) : ProfileChange
    data class Rejected(val code: String, val existing: List<ProfileEntry>) : ProfileChange
}

/** 小型独立画像；所有改动都按唯一片段定位，写入只影响下一次 Run 的快照。 */
class ProfileStore(private val backend: ProfileBackend, private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String, private val capacity: Int = 20) {
    @Synchronized fun snapshot(): List<ProfileEntry> = backend.read().filter { it.status == ProfileStatus.active }.map { it.copy() }

    @Synchronized fun add(text: String): ProfileChange {
        val all = backend.read()
        val active = all.filter { it.status == ProfileStatus.active }
        val clean = text.trim()
        if (!valid(clean)) return ProfileChange.Rejected("invalid_sentence", active)
        if (active.any { it.text == clean }) return ProfileChange.Rejected("duplicate", active)
        if (active.size >= capacity) return ProfileChange.Rejected("profile_full", active)
        val entry = ProfileEntry(ids(), clean, clock(), ProfileStatus.active)
        backend.write(all + entry)
        return ProfileChange.Applied(entry)
    }

    @Synchronized fun replace(segment: String, replacement: String): ProfileChange {
        val all = backend.read()
        val active = all.filter { it.status == ProfileStatus.active }
        val match = unique(active, segment) ?: return ProfileChange.Rejected("match_not_unique", active)
        val clean = replacement.trim()
        val nextText = match.text.replace(segment, clean)
        if (!valid(nextText) || clean.isEmpty()) return ProfileChange.Rejected("invalid_sentence", active)
        val next = ProfileEntry(ids(), nextText, clock(), ProfileStatus.active)
        backend.write(all.map { if (it.id == match.id) it.copy(status = ProfileStatus.superseded, supersededAtMs = next.atMs) else it } + next)
        return ProfileChange.Applied(next)
    }

    @Synchronized fun delete(segment: String): ProfileChange {
        val all = backend.read()
        val active = all.filter { it.status == ProfileStatus.active }
        val match = unique(active, segment) ?: return ProfileChange.Rejected("match_not_unique", active)
        backend.write(all.map { if (it.id == match.id) it.copy(status = ProfileStatus.superseded, supersededAtMs = clock()) else it })
        return ProfileChange.Applied(null)
    }

    private fun unique(active: List<ProfileEntry>, segment: String): ProfileEntry? =
        segment.takeIf { it.isNotBlank() }?.let { key -> active.singleOrNull { key in it.text } }
            ?.takeIf { row -> row.text.windowed(segment.length).count { it == segment } == 1 }

    private fun valid(text: String) = text.isNotBlank() && text.length <= 160 && '\n' !in text && '\r' !in text && SensitiveFilter.hit(text) == null &&
        text.count { it in "。！？.!?" } <= 1
}

object ProfileContext {
    fun render(snapshot: List<ProfileEntry>): String? = snapshot.takeIf { it.isNotEmpty() }
        ?.joinToString("\n", "User profile (for reference only; what the user says in this turn takes precedence):\n") { "- ${it.text}" }
}

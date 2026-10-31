package com.ai.assistance.quro.core.mika.memory

import kotlinx.serialization.Serializable

@Suppress("EnumEntryName")
enum class MemoryKind { fact, preference, procedure }

/** 只有做法会处于 pending(待用户批准)。 */
@Suppress("EnumEntryName")
enum class MemoryStatus { active, pending }

/** [type]:`user_said`(带用户原话 [quote])或 `manual`(用户在设置里手写)。 */
@Serializable
data class MemorySource(
    val type: String,
    val quote: String? = null,
    val conversationId: String? = null,
    val atMs: Long,
    val channel: String? = null,
)

/** [replaces]:被这次更新掉的旧内容,只留一版(界面「原来:…」)。 */
@Serializable
data class MemoryItem(
    val id: String,
    val kind: MemoryKind,
    val content: String,
    val keywords: List<String>,
    val source: MemorySource,
    val status: MemoryStatus,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
    val useCount: Int = 0,
    val replaces: String? = null,
)

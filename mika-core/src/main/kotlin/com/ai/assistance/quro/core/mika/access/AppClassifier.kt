package com.ai.assistance.quro.core.mika.access

import com.ai.assistance.quro.core.mika.model.*
import com.ai.assistance.quro.core.mika.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/**
 * 按主要用途给应用分类(只用于整理和推荐,不改变权限)。
 * [language] 是分类理由用的语言(界面语言),由调用方给;分类名本身是固定的英文枚举。
 */
class AppClassifier(
    private val model: ChatModel,
    private val fallback: ChatModel? = null,
    private val language: String = "English",
) {
    suspend fun classify(apps: List<Pair<String, String>>): Map<String, AppClassification> {
        if (apps.isEmpty()) return emptyMap()
        val input = buildJsonObject {
            put("language", language)
            putJsonArray("apps") {
                apps.forEach { (pkg, label) -> add(buildJsonObject { put("package", pkg); put("label", label) }) }
            }
        }
        for (candidate in listOfNotNull(model, fallback?.takeUnless { it === model })) {
            val result = try {
                withTimeoutOrNull(30_000L) {
                    val reply = candidate.complete(Prompts.role("app_classifier"),
                        listOf(Message(Role.User, listOf(Part.Text(input.toString())), Author.User)), emptyList())
                    parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }, apps.map { it.first }.toSet())
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (!result.isNullOrEmpty()) return result
        }
        return emptyMap()
    }

    companion object {
        fun parse(raw: String, packages: Set<String>): Map<String, AppClassification> = runCatching {
            val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            Json.parseToJsonElement(json).jsonArray.mapNotNull { item ->
                val o = item.jsonObject
                val pkg = o["package"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (pkg !in packages) return@mapNotNull null
                val category = o["category"]?.jsonPrimitive?.content?.let { value -> AppCategory.entries.firstOrNull { it.name == value } }
                    ?: AppCategory.Unknown
                pkg to AppClassification(category, o["reason"]?.jsonPrimitive?.content.orEmpty().take(240))
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}

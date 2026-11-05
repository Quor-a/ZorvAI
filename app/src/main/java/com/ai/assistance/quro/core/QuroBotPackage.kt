package com.ai.assistance.quro.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.ai.assistance.quro.core.memory.QuroMemoryEntry
import com.ai.assistance.quro.core.memory.QuroMemoryKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 *
 *  - bot.json   : 人格卡元数据（name/description/default_model_id/temperature/…/chat_setting/tags）
 *  - prompt.md  : 系统提示词（= roleSetting）
 *  - soul.md    : 人格画像（= soul）
 *  - memory.md  : 记忆条目（每行一条，导入时入记忆库）
 *
 * 导出：persona → zip；导入：zip → 新 id 的人格卡 + 记忆入库。技能/MCP 不入包
 */
object QuroBotPackage {

    /** 导出人格卡为 .bot.zip 字节流。 */
    fun export(context: Context, persona: QuroPersona, memoryLines: List<String>): ByteArray {
        val botJson = JSONObject().apply {
            put("format_version", "1.0")
            put("id", persona.id)
            put("name", persona.name)
            put("description", persona.description)
            if (persona.defaultModelId.isNotBlank()) put("default_model_id", persona.defaultModelId)
            put("temperature", persona.temperature.toDouble())
            put("temperature_enabled", persona.temperatureEnabled)
            put("memory_enabled", persona.memoryEnabled)
            put("evolution_enabled", persona.evolutionEnabled)
            put("chat_setting", persona.chatSetting)
            put("voice_setting", persona.voiceSetting)
            put("avatar", persona.avatarEmoji)
            put("tags", JSONArray().apply { persona.tags.forEach { put(it) } })
        }
        val memoryMd = memoryLines.joinToString("\n")
        return zipOf(
            "bot.json" to botJson.toString(2),
            "prompt.md" to persona.roleSetting,
            "soul.md" to persona.soul,
            "memory.md" to memoryMd,
        )
    }

    /**
     * 导入 .bot.zip → 新 id 的人格卡（名字冲突时自动加后缀）+ memory.md 每行入记忆库。
     * @return 导入后的人格卡（已落库）。
     */
    fun import(context: Context, bytes: ByteArray): QuroPersona {
        val files = unzip(bytes)
        val botJson = JSONObject(files["bot.json"] ?: throw IllegalArgumentException("包内缺少 bot.json"))
        val promptMd = files["prompt.md"] ?: ""
        val soulMd = files["soul.md"] ?: ""
        val memoryMd = files["memory.md"] ?: ""

        val repo = QuroPersonaRepository(context.applicationContext)
        val existingNames = repo.loadAll().map { it.name }.toSet()
        var name = botJson.optString("name", "导入的 Bot").ifBlank { "导入的 Bot" }
        if (name in existingNames) name = "$name (导入)"

        val persona = QuroPersona(
            id = UUID.randomUUID().toString(),
            name = name,
            description = botJson.optString("description", ""),
            roleSetting = promptMd.ifBlank { botJson.optString("prompt", "") },
            chatSetting = botJson.optString("chat_setting", ""),
            voiceSetting = botJson.optString("voice_setting", ""),
            soul = soulMd,
            soulUpdatedAt = if (soulMd.isNotBlank()) System.currentTimeMillis() else 0,
            temperature = botJson.optDouble("temperature", 1.0).toFloat(),
            temperatureEnabled = botJson.optBoolean("temperature_enabled", false),
            memoryEnabled = botJson.optBoolean("memory_enabled", true),
            evolutionEnabled = botJson.optBoolean("evolution_enabled", true),
            defaultModelId = botJson.optString("default_model_id", ""),
            avatarEmoji = botJson.optString("avatar", "🤖").ifBlank { "🤖" },
            tags = botJson.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
            } ?: emptyList(),
        )
        repo.upsert(persona)

        // memory.md 每行一条入记忆库（非空行，跳过 markdown 标题）
        val memoryRepo = com.ai.assistance.quro.core.memory.QuroMemoryRepository(context.applicationContext)
        memoryMd.lines().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(200)
            .forEach { line ->
                memoryRepo.add(
                    QuroMemoryEntry(
                        personaId = persona.id,
                        group = "Bot 包导入",
                        title = line.take(40),
                        content = line,
                        kind = QuroMemoryKind.EPISODIC.key, // 情节型：导入的历史记忆带时间性，TTL 自然衰减
                    ),
                )
            }
        return persona
    }

    // ── zip 工具 ────────────────────────────────────────────────────

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            files.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory) out[e.name] = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
            }
        }
        return out
    }
}

package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.assistant.AssistantCategory
import com.ai.assistance.quro.core.assistant.AssistantRequest
import com.ai.assistance.quro.core.assistant.AssistantSkillRegistry
import com.ai.assistance.quro.core.assistant.AssistantSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 让 AI 自己也能看见并调用「能力表」。
 *
 * 这是把能力表接进闭环的最后一环：此前新增的能力只有**外部指令**和**用户点按**两条路，
 * 模型完全不知道它们存在——而小布最核心的能力恰恰是"助手自己选用哪个技能"（它 6724 个
 * 类的 skill 层就是干这个的）。
 *
 * 两个动作：
 * - `list`：按关键词/分类列出能力（id / 标题 / 说明 / 是否可用），模型据此决定调谁；
 * - `run`：按其 id 执行，返回统一封套字段（ok / code / message / data 的 JSON）。
 *
 * 与 [com.ai.assistance.quro.core.assistant.AssistantDispatcher] 共用同一张表，
 * 因此「模型能调的」和「用户能点的」「外部能发的」永远一致，不会有第三份事实。
 */
class AssistantSkillTool(private val ctx: Context) : QuroTool {

    override val name: String = "assistant_skill"

    override val description: String =
        "查看或调用 ZorvAI 的内置能力（与「能力广场」同一张表）。" +
            "action=list 按关键词或分类列出能力及其 id；action=run 用 skill_id 执行。" +
            "能力包含：查手机(device.find-phone)、终端环境检查(terminal.env-check)、" +
            "设备信息(device.info)、驾驶模式(device.driving-mode)、诊断目录(sys.diagnostics)、" +
            "跨设备 Agent(link.peers/link.discover)、能力清单(skill.catalog) 等。" +
            "不确定用户想要什么能力时先 list 再 run。"

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["list", "run"],
              "description": "list=列能力（默认），run=执行能力"
            },
            "query": {
              "type": "string",
              "description": "list 时的搜索关键词（可选，匹配标题/关键词/说明）"
            },
            "category": {
              "type": "string",
              "description": "list 时的分类过滤（可选）：chat/terminal/device/reminder/knowledge/system/link/permission"
            },
            "skill_id": {
              "type": "string",
              "description": "run 时必填，能力 id（如 device.find-phone）"
            },
            "text": {
              "type": "string",
              "description": "run 时传给能力的文本参数（可选）"
            }
          },
          "required": ["action"]
        }
    """.trimIndent()

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val action = obj.optString("action", "list").ifBlank { "list" }
        return when (action) {
            "run" -> runSkill(obj)
            else -> listSkills(obj)
        }
    }

    private fun listSkills(obj: JSONObject): String {
        val query = obj.optString("query")
        val catWire = obj.optString("category")
        val cat = AssistantCategory.entries.firstOrNull { it.wire.equals(catWire, ignoreCase = true) }
        val list = AssistantSkillRegistry.search(query, cat)
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("summary", s.summary)
                    put("category", s.category.wire)
                    put("enabled", AssistantSkillRegistry.isEnabled(s.id))
                    put("runnable", s.runnable)
                    put("requirement", s.requirement)
                }
            )
        }
        return JSONObject().apply {
            put("ok", true)
            put("total", AssistantSkillRegistry.all().size)
            put("matched", list.size)
            put("skills", arr)
        }.toString()
    }

    private fun runSkill(obj: JSONObject): String {
        val id = obj.optString("skill_id")
        if (id.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("code", com.ai.assistance.quro.core.assistant.AssistantCodes.SKILL_BAD_ARGS)
                put("message", "缺少 skill_id")
            }.toString()
        }
        val req = AssistantRequest(
            text = obj.optString("text"),
            args = buildMap {
                obj.optString("text").takeIf { it.isNotBlank() }?.let { put("text", it) }
            },
            source = AssistantSource.AI,
        )
        // QuroTool.run 是阻塞式接口，这里同步等技能结果（技能内部最多几十秒，受外层超时保护）
        val r = runBlocking { AssistantSkillRegistry.run(id, req) }
        return JSONObject().apply {
            put("ok", r.ok)
            put("code", r.code)
            put("message", r.message)
            put("skill_id", r.skillId)
            put("elapsed_ms", r.elapsedMs)
            put(
                "data",
                JSONObject().apply { r.data.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
            )
        }.toString()
    }
}

/** 与其它 `Register*Tool` 同风格：Application 里调一次。 */
object RegisterAssistantSkillTool {
    fun register(context: Context) {
        runCatching {
            QuroToolRegistry.active?.register(AssistantSkillTool(context.applicationContext))
        }
    }
}

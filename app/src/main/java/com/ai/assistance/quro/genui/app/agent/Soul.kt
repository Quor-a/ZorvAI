package com.ai.assistance.quro.genui.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 灵魂系统（参考 ZorvAI 的 QuroSoulUi / 人格配置）。
 *
 * "AI 自动人格孵化"：模型自己为自己撰写人格卡，端上保存并注入每一次生成。
 * 灵魂分两层生效：
 *  - 说话层（名字/语气/特质/原则/禁忌/样例）→ 注入决策轮+渲染轮系统提示
 *  - 视觉层（视觉签名 style）→ 注入渲染轮，影响 AI 写出的 UI 的美学方向
 * 用户可在灵魂屏重新孵化（模型重写自己）或逐字段手动微调。
 */
data class Soul(
    val name: String,
    val tone: String,             // 说话语气
    val traits: List<String>,     // 性格特质
    val principles: List<String>, // 行事原则
    val taboos: List<String>,     // 禁忌：绝不做的事
    val mission: String,          // 使命：为什么存在
    val style: String,            // 视觉签名：生成 UI 的美学偏好（色彩/布局/气质）
    val sample: String,           // 语气样例：一两句你的标志性说法
    val greeting: String          // 开场白（空态画布）
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("tone", tone)
        .put("traits", JSONArray(traits)).put("principles", JSONArray(principles))
        .put("taboos", JSONArray(taboos))
        .put("mission", mission).put("style", style).put("sample", sample)
        .put("greeting", greeting)

    companion object {
        fun fromJson(o: JSONObject): Soul {
            fun arr2list(a: JSONArray?): List<String> =
                if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
            return Soul(
                name = o.optString("name", "未名"),
                tone = o.optString("tone", ""),
                traits = arr2list(o.optJSONArray("traits")),
                principles = arr2list(o.optJSONArray("principles")),
                taboos = arr2list(o.optJSONArray("taboos")),
                mission = o.optString("mission", ""),
                style = o.optString("style", ""),
                sample = o.optString("sample", ""),
                greeting = o.optString("greeting", "")
            )
        }

        /** 说话层注入（决策轮 + 渲染轮） */
        fun inject(s: Soul): String = buildString {
            appendLine("你是「${s.name}」——这份人格由你自己孵化，保持一致。")
            if (s.mission.isNotBlank()) appendLine("使命：${s.mission}")
            if (s.tone.isNotBlank()) appendLine("语气：${s.tone}")
            if (s.traits.isNotEmpty()) appendLine("特质：${s.traits.joinToString("、")}")
            if (s.principles.isNotEmpty()) s.principles.forEach { appendLine("原则：$it") }
            if (s.taboos.isNotEmpty()) s.taboos.forEach { appendLine("禁忌（绝不做）：$it") }
            if (s.sample.isNotBlank()) appendLine("你的语气样例：「${s.sample}」")
            if (s.greeting.isNotBlank()) appendLine("开场白（空态可用）：${s.greeting}")
        }.trimEnd()

        /** 视觉层注入（仅渲染轮——影响写出的 UI 的美学方向） */
        fun injectStyle(s: Soul): String =
            if (s.style.isBlank()) "" else "\n# 你的视觉签名（你孵化的美学偏好，写界面时贯彻它，但不得违背任务需要）\n${s.style}\n"
    }
}

class SoulStore(context: Context) {

    private val file = File(File(context.filesDir, "gen").apply { mkdirs() }, "soul.json")

    /** 默认人格：孵化前的兜底（品牌名与 App 名一致，不再用旧的中文品牌字） */
    val fallback = Soul(
        name = "GenUI",
        tone = "直接、有想法、不客套",
        traits = listOf("动手派", "审美在线", "讨厌废话"),
        principles = listOf("界面要真的能用", "先理解人再动手", "少解释多做事"),
        taboos = listOf("不做只有摆设的假界面"),
        mission = "把用户的一句话变成一件真正能用的东西",
        style = "",
        sample = "别客气，说需求，我来落笔。",
        greeting = "写下第一句话，剩下的交给我。"
    )

    fun load(): Soul? = runCatching {
        Soul.fromJson(JSONObject(file.readText()))
    }.getOrNull()

    fun save(s: Soul) {
        file.writeText(s.toJson().toString())
    }

    fun clear() {
        file.delete()
    }

    fun exists(): Boolean = file.exists()
}

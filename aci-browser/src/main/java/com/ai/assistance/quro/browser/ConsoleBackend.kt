package com.ai.assistance.quro.browser

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 受控浏览器「控制台」后端业务状态（v1.0.12 新增，SDUI 范式）。
 *
 * 这是把主应用 LAN 控制台的「后端驱动 UI」范式移植到受控浏览器（后端）的实现：
 * - buildUiSnapshot() 生成 UI 描述 JSON（组件化，前端免发版渲染）；
 * - applyAction() 处理前端回传的 action。
 * 经 ACI 能力 console_ui / console_action 暴露给通用前端（ZorvAI 主程序），
 * 前端只负责渲染与回传，业务状态与界面模板全在后端 —— 即「前端是前端、后端是后端」。
 *
 * 组件词汇与主应用 LanUiModel 保持一致（heading/text/card/button/divider/input/spacer/listitem），
 * 以便任何通用前端都能直接渲染。
 */
object ConsoleBackend {

    private val counter = AtomicInteger(0)
    private val lastAction = AtomicLong(System.currentTimeMillis())
    @Volatile private var note: String = ""

    /** 生成当前 UI 快照（后端下发给前端渲染的描述）。 */
    fun buildUiSnapshot(): JSONObject {
        val now = System.currentTimeMillis()
        val components = JSONArray()
        components.put(JSONObject().put("type", "heading").put("text", "受控浏览器控制台"))
        components.put(
            JSONObject().put("type", "text")
                .put("text", "服务器时间：${fmt(now)}")
        )
        components.put(
            JSONObject().put("type", "text")
                .put("text", "计数触发次数：${counter.get()}")
        )
        components.put(
            JSONObject().put("type", "card")
                .put("title", "计数器卡片")
                .put("body", "按钮共被点击 ${counter.get()} 次；最近动作时间 ${fmt(lastAction.get())}")
        )
        components.put(JSONObject().put("type", "button").put("action", "increment").put("label", "+1 计数"))
        components.put(JSONObject().put("type", "button").put("action", "reset").put("label", "重置计数"))
        components.put(JSONObject().put("type", "divider"))
        components.put(
            JSONObject().put("type", "input")
                .put("key", "note")
                .put("label", "备注")
                .put("placeholder", "输入内容后点提交")
                .put("value", note)
                .put("action", "submit_note")
        )
        components.put(
            JSONObject().put("type", "text")
                .put("text", if (note.isBlank()) "（暂无备注）" else "当前备注：$note")
        )
        components.put(JSONObject().put("type", "spacer"))
        components.put(
            JSONObject().put("type", "listitem")
                .put("text", "受控浏览器：com.ai.assistance.quro.browser")
        )
        components.put(
            JSONObject().put("type", "listitem")
                .put("text", "经 ACI 由 ZorvAI 主程序驱动")
        )

        return JSONObject()
            .put("title", "ZorvAI 浏览器控制台")
            .put("subtitle", "后端驱动渲染 · 前端免发版（ACI）")
            .put("updatedAt", now)
            .put("components", components)
    }

    /** 处理前端回传的 action，返回执行结果。 */
    fun applyAction(action: String, payload: JSONObject?): JSONObject {
        when (action) {
            "increment" -> counter.incrementAndGet()
            "reset" -> counter.set(0)
            "submit_note" -> {
                note = payload?.optString("value") ?: payload?.optString("note") ?: ""
            }
            else -> { /* 未知 action：忽略，仅更新最近动作时间 */ }
        }
        lastAction.set(System.currentTimeMillis())
        return JSONObject().put("ok", true).put("action", action)
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(ts))
}

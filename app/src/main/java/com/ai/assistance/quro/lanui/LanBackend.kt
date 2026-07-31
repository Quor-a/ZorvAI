package com.ai.assistance.quro.lanui

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地 LAN 后端「业务状态」（demo 用），模拟一个被后端驱动的前端界面。
 *
 * 真实场景里这部分会换成你自己的开源后端（下发 UI 描述 JSON + 实时数据）。
 * 这里仅用于验证「前端渲染 ← 后端 JSON」这条链路在同设备 / 局域网下跑得通。
 *
 * 所有状态用 Atomic*，因为 HTTP 请求在独立线程处理，需线程安全。
 */
class LanBackend {

    private val counter = AtomicInteger(0)
    private val lastAction = AtomicLong(System.currentTimeMillis())
    @Volatile private var note: String = ""

    /** 生成当前 UI 快照（后端下发给前端渲染的描述）。前端 [LanUiModel] 解析后渲染。 */
    fun buildUiSnapshot(): JSONObject {
        val now = System.currentTimeMillis()
        val components = JSONArray()
        components.put(JSONObject().put("type", "heading").put("text", "实时状态面板"))
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
        components.put(JSONObject().put("type", "listitem").put("text", "条目 A · 静态示例"))
        components.put(JSONObject().put("type", "listitem").put("text", "条目 B · 静态示例"))

        return JSONObject()
            .put("title", "ZorvAI LAN 控制台")
            .put("subtitle", "后端驱动渲染 · 前端免发版（demo）")
            .put("updatedAt", now)
            .put("components", components)
    }

    /** 处理前端回传的 action，返回执行结果（仍回前端展示）。 */
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

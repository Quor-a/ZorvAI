package com.ai.assistance.quro.tools

import android.content.Context
import com.ai.assistance.quro.core.tools.MiniAppTool
import com.ai.assistance.quro.core.tools.QuroTool
import org.json.JSONObject

/**
 * 「小程序」工具的**别名外壳**（工具名 workbench，仅用于兼容旧调用）。
 *
 * 历史上工具中心有两个并存的入口：
 *  · 「小程序」（原 workbench）→ 单入口多文件，无路由、无原生桥
 *  · 「小程序」（统一 Web 应用工具，吸收原「小程序工作室」/workbench）→ app.json 路由 + native.* 原生桥
 * 二者现已合并为**唯一的「小程序」**（工具 id = miniapp），工程统一存放在 filesDir/miniapp/。
 *
 * 这里不删旧工具名（历史对话、能力目录、旧手册都还可能引用它），
 * 而是把每个 action 翻译后转发给 [MiniAppTool]，行为完全一致。
 * 新代码请直接调用 miniapp 工具。
 */
class MiniAppAliasTool : QuroTool {
    override val name = "workbench"
    override val description = """（已与「小程序」合并，本工具是 miniapp 的别名，仅保留用于兼容旧调用）

请优先使用 miniapp 工具，它包含本工具的全部能力并多出：app.json 多页面路由、
native.* 原生桥（存储/设备/网络/SQLite/定位/加密/第三方 App 关联启动）、save/wrap/manual。

保留的旧操作（内部转发到 miniapp）：create / edit / run / list / get / delete / clean
新增转发：save（整段 HTML 存成小程序）、wrap（js/python/css 包装成可渲染页面）

工程统一存放在手机私有目录 filesDir/miniapp/<name>/，与工具中心「小程序」面板共享同一份文件。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作：create|edit|run|list|get|delete|clean|save|wrap"},
            "name":{"type":"string","description":"项目名称（缺省时用 workbench 作为工程名）"},
            "files":{"type":"array","description":"文件数组（仅 create 时需要），每项含 path 和 content","items":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}}}},
            "file":{"type":"string","description":"文件路径（edit/get/delete 时需要）"},
            "content":{"type":"string","description":"文件内容（edit 时需要）"},
            "entry":{"type":"string","description":"入口文件路径（run 时需要，默认 index.html）"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val json = JSONObject(arguments)
        val action = json.optString("action", "").lowercase()
        // 旧调用常常只给 file 不给 name，统一兜底到一个固定工程名下，保证文件仍然有处可去
        val name = json.optString("name", "").ifBlank { DEFAULT_PROJECT }
        val mapped = JSONObject()

        when (action) {
            "create" -> {
                mapped.put("action", "create")
                mapped.put("name", name)
                if (json.has("files")) mapped.put("files", json.get("files"))
            }
            "edit" -> {
                mapped.put("action", "write")
                mapped.put("name", name)
                mapped.put("path", json.optString("file", "index.html"))
                mapped.put("content", json.optString("content", ""))
            }
            "run" -> {
                mapped.put("action", "run")
                mapped.put("name", name)
                mapped.put("entry", json.optString("entry", "index.html").ifBlank { "index.html" })
            }
            "list" -> mapped.put("action", "list")
            "get" -> {
                mapped.put("action", "read")
                mapped.put("name", name)
                mapped.put("path", json.optString("file", "index.html"))
            }
            "delete" -> {
                mapped.put("action", "delete")
                mapped.put("name", name)
                val f = json.optString("file", "")
                if (f.isNotBlank()) mapped.put("path", f)
            }
            "clean" -> mapped.put("action", "clean")
            "save" -> {
                mapped.put("action", "save")
                mapped.put("name", name)
                mapped.put("html", json.optString("html", json.optString("content", "")))
            }
            "wrap" -> {
                mapped.put("action", "wrap")
                mapped.put("lang", json.optString("lang", ""))
                mapped.put("code", json.optString("content", json.optString("code", "")))
                mapped.put("file", json.optString("file", ""))
            }
            else -> return "未知操作：$action。支持：create/edit/run/list/get/delete/clean/save/wrap（均转发到 miniapp 工具）"
        }
        return MiniAppTool().run(context, mapped.toString())
    }

    private companion object {
        const val DEFAULT_PROJECT = "workbench"
    }
}

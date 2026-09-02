package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 可视化编程工具：以「产物 + 可视化」模型管理命名工程（支持多项目保存、干净写入）。
 *
 * 每个工程 = 一份 Mermaid 源码，存储在 filesDir/studio/vispro/<name>.mmd，
 * 与工具中心「可视化编程」面板共享同一份文件：
 * - AI 用 create/save 写入命名工程，面板加载后即时渲染（产物）；
 * - 面板「保存」按工程名写回，支持多个工程并存，写入逻辑单一、无冗余。
 *
 * 用法：
 * - visual(action="create", name="架构图", source="graph TD; A-->B")
 * - visual(action="save", name="架构图", source="...")
 * - visual(action="open", name="架构图")
 * - visual(action="list")
 * - visual(action="delete", name="架构图")
 * - visual(action="render", name="架构图")  // 取出源码供对话框渲染
 */
class VisualStudioTool : QuroTool {
    override val name = "visual"
    override val description = """可视化编程工具：以「产物 + 可视化」模型管理命名 Mermaid 工程，支持多项目保存与干净写入。

工程 = 一份 Mermaid 源码，存储在 filesDir/studio/vispro/<name>.mmd，与工具中心「可视化编程」面板共享同一文件：
- 多项目并存：每个 name 一个文件，互不影响；
- 干净写入：create/save 直接覆盖同名文件，无随机时间戳冗余；
- 产物化：面板加载即渲染成图（产物），源码即可视化定义。

操作：
- create：新建命名工程（name + source 源码）
- save：覆盖保存命名工程
- open：读取工程源码
- list：列出所有可视化工程
- delete：删除工程
- render：取出源码（供对话框渲染为图）

示例：让 AI「用 visual 工具创建一个名为『系统架构』的 Mermaid 图」，面板打开即见产物。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作：create|save|open|list|delete|render"},
            "name":{"type":"string","description":"工程名（create/save/open/delete/render 时必填）"},
            "source":{"type":"string","description":"Mermaid 源码（create/save 时需要）"}
        },
        "required":["action"]
    }"""

    companion object {
        private const val STUDIO = "studio/vispro"
        fun getRoot(context: Context): File {
            val dir = File(context.filesDir, STUDIO)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
        fun fileFor(context: Context, name: String): File {
            val safe = name.replace(Regex("[^A-Za-z0-9_.\\-]"), "_").replace("..", "_")
            return File(getRoot(context), "$safe.mmd")
        }
    }

    override fun run(context: Context, arguments: String): String {
        val json = JSONObject(arguments)
        val action = json.optString("action", "").lowercase()
        return when (action) {
            "create" -> saveProject(context, json, overwrite = false)
            "save" -> saveProject(context, json, overwrite = true)
            "open" -> openProject(context, json)
            "list" -> listProjects(context)
            "delete" -> deleteProject(context, json)
            "render" -> openProject(context, json)
            else -> "未知操作：$action。支持：create/save/open/list/delete/render"
        }
    }

    private fun saveProject(context: Context, json: JSONObject, overwrite: Boolean): String {
        val name = json.optString("name", "").ifBlank { return "缺少 name 参数" }
        val source = json.optString("source", null) ?: return "缺少 source 参数"
        val f = fileFor(context, name)
        if (f.exists() && !overwrite) return "工程已存在：$name（用 action=\"save\" 覆盖，或换个 name）"
        return runCatching {
            f.writeText(source, StandardCharsets.UTF_8)
            "✅ 已${if (overwrite) "保存" else "创建"}可视化工程「$name」（${source.length} 字符）。工具中心「可视化编程」加载即渲染。"
        }.getOrElse { "❌ 写入失败：${it.message}" }
    }

    private fun openProject(context: Context, json: JSONObject): String {
        val name = json.optString("name", "").ifBlank { return "缺少 name 参数" }
        val f = fileFor(context, name)
        if (!f.exists()) return "❌ 工程不存在：$name"
        return runCatching {
            val src = f.readText(StandardCharsets.UTF_8)
            "📊 $name (${src.length} 字符)\n\n```\n$src\n```"
        }.getOrElse { "❌ 读取失败：${it.message}" }
    }

    private fun listProjects(context: Context): String {
        val root = getRoot(context)
        val names = root.listFiles()?.filter { it.extension == "mmd" }?.map { it.nameWithoutExtension } ?: emptyList()
        if (names.isEmpty()) return "可视化工程为空（filesDir/studio/vispro 下还没有 .mmd）。用 visual(action=\"create\", name=\"x\", source=\"graph TD;A-->B\") 创建一个。"
        return buildString {
            appendLine("📊 可视化工程（${names.size}）：")
            names.forEach { appendLine("  • $it") }
        }
    }

    private fun deleteProject(context: Context, json: JSONObject): String {
        val name = json.optString("name", "").ifBlank { return "缺少 name 参数" }
        val f = fileFor(context, name)
        return if (f.exists() && f.delete()) "✅ 已删除可视化工程「$name」" else "❌ 工程不存在：$name"
    }
}

package com.ai.assistance.quro.core.tools

import android.content.Context
import java.io.File

/**
 * 工作区 AI 工具（原创）：让 ZorvAI 的 AI 直接读写 ZorvAI 自己的 QuroWorkspace 沙箱
 * （<外部files>/QuroWorkspace，也就是「工具箱-工作区」里展示的那个目录）。
 *
 * 协作模型（与构建台 ACI 打通）：
 *   1) 构建台经 ACI.create_project 在 QuroWorkspace 下建工程文件夹；
 *   2) AI 经本组工具把源码写进该文件夹（用户在「工作区」UI 里能看到/手动改）；
 *   3) AI 经 ACI.build_apk（project_dir=该文件夹）让构建台编译打包，结果日志经 ACI 回传 AI 读取。
 *
 * 路径以用户选择的工作区为根做相对解析；未选择时使用默认 QuroWorkspace。
 * 用户可在对话框权限模式栏选择工作区（已创建的/创建新的/自定义文件夹）。
 */
internal fun workspaceRoot(context: Context): File {
    // 优先使用用户选择的工作区
    val customPath = WorkspacePreferences.getCurrentWorkspace(context)
    if (customPath != null) {
        val customDir = File(customPath)
        if (customDir.exists() && customDir.isDirectory) {
            return customDir
        }
    }
    // 默认工作区
    return File(context.getExternalFilesDir(null), "QuroWorkspace").apply { mkdirs() }
}

/** 把相对路径安全解析到 workspace 内；越界（含 ../ 逃逸）返回 null。 */
private fun resolveInWorkspace(root: File, relative: String): File? {
    val cleaned = relative.trim().trimStart('/').replace('\\', '/')
    if (cleaned.isEmpty()) return root
    val target = File(root, cleaned).canonicalFile
    val base = root.canonicalFile
    if (!target.path.startsWith(base.path + File.separator) && target != base) return null
    return target
}

class WorkspaceWriteTool : QuroTool {
    override val name = "workspace_write"
    override val description =
        "📁 工作区文件写入：把文本写入工作区（QuroWorkspace）的相对路径文件。" +
            "与 write_file 的区别：workspace_write 用相对路径（相对于工作区根目录），写入后用户在「工具箱-工作区」可见；" +
            "write_file 用绝对路径（如 /sdcard/...），写入设备任意位置。" +
            "与 workspace_doc 的区别：workspace_write 是通用写入；workspace_doc 自动添加扩展名并渲染预览。" +
            "参数：{\"path\":\"相对路径，如 MyApp/src/Main.java\",\"content\":\"内容\",\"append\":false}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"工作区内的相对文件路径，例如 MyApp/src/Main.java"},
            "content":{"type":"string","description":"要写入的完整文本内容（会整体覆盖原文件，除非 append=true）"},
            "append":{"type":"boolean","description":"（可选）true=追加到文件末尾；默认 false=覆盖"}
        },
        "required":["path","content"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val root = workspaceRoot(context)
        val obj = runCatching { org.json.JSONObject(arguments) }.getOrElse {
            return "参数不是合法 JSON：$arguments"
        }
        val rel = (obj.optString("path", "")).trim()
        if (rel.isEmpty()) return "缺少 path（工作区内相对路径，如 MyApp/src/Main.java）。"
        val content = obj.optString("content", "")
        val append = obj.optBoolean("append", false)
        val file = resolveInWorkspace(root, rel) ?: return "⚠️ path 越界（不能 .. 逃逸工作区）：$rel"
        return runCatching {
            file.parentFile?.mkdirs()
            if (append) file.appendText(content) else file.writeText(content)
            "✅ 已写入（${if (append) "追加" else "覆盖"}）：${file.absolutePath}（${file.length()} 字节）"
        }.getOrElse { "⚠️ 写入失败：${it.message}" }
    }
}

class WorkspaceReadTool : QuroTool {
    override val name = "workspace_read"
    override val description =
        "读取 ZorvAI 工作区（QuroWorkspace）里指定相对路径的文本文件内容，返回完整文本。" +
            "用于：把源码写进去前先看现有内容、或构建台 build_apk 后回读产物细节。" +
            "参数：{\"path\":\"相对路径，如 MyApp/src/Main.java\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"工作区内的相对文件路径，例如 MyApp/src/Main.java"}
        },
        "required":["path"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val root = workspaceRoot(context)
        val obj = runCatching { org.json.JSONObject(arguments) }.getOrElse {
            return "参数不是合法 JSON：$arguments"
        }
        val rel = (obj.optString("path", "")).trim()
        if (rel.isEmpty()) return "缺少 path。"
        val file = resolveInWorkspace(root, rel) ?: return "⚠️ path 越界：$rel"
        if (!file.isFile) return "⚠️ 文件不存在或不是普通文件：${file.absolutePath}"
        return runCatching { file.readText() }.getOrElse { "⚠️ 读取失败：${it.message}" }
    }
}

class WorkspaceListTool : QuroTool {
    override val name = "workspace_list"
    override val description =
        "列出 ZorvAI 工作区（QuroWorkspace）里某个目录的内容（子目录/文件），默认列根目录。" +
            "用于：先看构建台 create_project 建了哪些工程文件夹、每个工程里有什么源文件。" +
            "参数：{\"path\":\"（可选）工作区内相对目录路径，默认空=根目录\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"（可选）工作区内相对目录路径，默认空=根目录"}
        }
    }"""

    override fun run(context: Context, arguments: String): String {
        val root = workspaceRoot(context)
        val obj = runCatching { org.json.JSONObject(arguments) }.getOrElse {
            return "参数不是合法 JSON：$arguments"
        }
        val rel = (obj.optString("path", "")).trim()
        val dir = resolveInWorkspace(root, rel) ?: return "⚠️ path 越界：$rel"
        if (!dir.exists()) return "⚠️ 目录不存在：${dir.absolutePath}"
        if (!dir.isDirectory) return "⚠️ 不是目录：${dir.absolutePath}"
        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?: return "⚠️ 读取目录失败：${dir.absolutePath}"
        if (entries.isEmpty()) return "（空目录）${dir.absolutePath}"
        return buildString {
            append("📂 ${dir.absolutePath}\n")
            entries.forEach {
                if (it.isDirectory) append("  📁 ${it.name}/\n")
                else append("  📄 ${it.name}（${it.length()} 字节）\n")
            }
        }.trim()
    }
}

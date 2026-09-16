package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.kaleidobox.android.KaleidoBoxBridge
import com.ai.assistance.quro.kaleidobox.android.KaleidoBoxHost
import com.ai.assistance.quro.kaleidobox.core.KaleidoRuntime
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.registry.DirectoryPackageSource
import com.ai.assistance.quro.kaleidobox.core.registry.InstallSource
import com.ai.assistance.quro.kaleidobox.core.util.Json
import com.ai.assistance.quro.kaleidobox.samples.KaleidoCatalog
import com.ai.assistance.quro.kaleidobox.samples.PluginScaffold
import org.json.JSONObject
import java.io.File

/**
 * KaleidoBox 工具入口 —— 让 AI 直接驱动「工具包运行器」。
 *
 * 动作：
 *  - list        ：列出已安装工具包暴露的 unit（AI 可调用的 function schema）。
 *  - catalog     ：列出插件目录（真实可用插件，含 BUILTIN/SRC 两类，一键可装）。
 *  - install     ：从一个目录（含 kaleido.json）装载工具包进运行期。
 *  - install_file：从本地 zip 文件（含 kaleido.json + classes.dex）装入并持久化。
 *  - install_url ：从网络链接下载 zip 包并装入。
 *  - write       ：AI 写（或粘贴）一段 Java 源码 → 端侧 ecj→d8 编译成 dex → 直接安装运行。
 *  - invoke      ：按 unit 名（或 pkgId:unit）调用，传入 JSON 参数，返回 JSON 结果。
 *  - uninstall   ：卸载一个已安装包。
 *
 * 实现上只是 KaleidoBoxHost / KaleidoBoxBridge 门面的一层薄封装，不引入新逻辑；
 * 真正的能力解析 / 引擎加载 / 宿主桥接 / 端侧编译都在 :kaleidobox 模块内完成。
 */
class QuroKaleidoBoxTool : QuroTool {
    override val name = "kaleidobox"
    override val description = """
        |KaleidoBox 工具包运行器：以进程内 JVM / Dex 引擎运行 Kotlin/Java 工具包，支持"AI 写插件 + 端侧编译 + 安装运行"。
        |动作：
        |  · list         —— 列出已安装工具包的 unit（AI 可调用的函数）。
        |  · catalog      —— 列出插件目录（均为 BUILTIN 已编入安装包的真实工具：AI 助手 / 终端 / 设备信息 / 剪贴板 / 联网请求 / APK 逆向 / WebUI 浏览器 / 世界书），含 type。
        |  · install      —— 从一个目录（必须含 kaleido.json）装载工具包。
        |  · install_file —— 从本地 .zip 包（含 kaleido.json + classes.dex）装入并持久化，参数 path=文件绝对路径。
        |  · install_url  —— 从网络链接下载 .zip 包并装入，参数 url=下载地址。
        |  · write        —— AI 写（或粘贴）Java 源码，端侧 ecj→d8 编译成 dex 并安装。参数：
        |                   source=完整 Java 源码（实现 KaleidoToolkit，public 类名须等于 className 末段）；
        |                   className=入口类全名（如 com.ai.assistance.quro.kaleidobox.gen.MyToolkit）；
        |                   id/name/desc=包标识与展示名（可选，缺省自动生成）；
        |                   manifest=完整 kaleido.json（可选，缺省由脚手架生成）；
        |                   units=逗号分隔的额外 unit 名（可选）。
        |                   源码骨架见本说明"Java 模板"，import 只能用 KaleidoToolkit/ToolkitHost/InvokeContext/KValue。
        |  · invoke       —— 调用指定 unit（unit 名或 pkgId:unit），传入 JSON 参数并取回 JSON 结果。
        |  · uninstall    —— 卸载包，参数 id=包 id。
        |
        |Java 工具包模板（写源码时照此实现，类名改掉即可）：
        |```
        |${PluginScaffold.JAVA_TEMPLATE}
        |```
        |典型流程：先 catalog 看有什么真插件 → install_file / install_url / write 装一个 → list 看 unit → invoke 调它。
    """.trimMargin()

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "description": "动作：list / catalog / install / install_file / install_url / write / invoke / uninstall",
              "enum": ["list", "catalog", "install", "install_file", "install_url", "write", "invoke", "uninstall"]
            },
            "name": { "type": "string", "description": "invoke 时的 unit 名（或 pkgId:unit）" },
            "args": { "type": "string", "description": "invoke 时的 JSON 参数（对象/字符串）" },
            "dir": { "type": "string", "description": "install 时的工具包目录绝对路径（需含 kaleido.json）" },
            "path": { "type": "string", "description": "install_file 时的 .zip 包绝对路径" },
            "url": { "type": "string", "description": "install_url 时的下载地址" },
            "source": { "type": "string", "description": "write 时的完整 Java 源码" },
            "className": { "type": "string", "description": "write 时入口类全名（= 源码 public 类名）" },
            "id": { "type": "string", "description": "write 时的包 id（缺省 dev.kaleidobox.gen.plugin）" },
            "pkgName": { "type": "string", "description": "write 时的包展示名（中文）" },
            "desc": { "type": "string", "description": "write 时的包描述" },
            "manifest": { "type": "string", "description": "write 时的完整 kaleido.json（缺省由脚手架生成）" },
            "units": { "type": "string", "description": "write 时的额外 unit 名（逗号分隔，可选）" },
            "pkgId": { "type": "string", "description": "uninstall 时的包 id" },
            "trusted": { "type": "boolean", "description": "install 时是否信任并预授予危险能力（内置包建议 true），默认 false" }
          },
          "required": ["action"]
        }
    """.trimIndent()

    override fun run(context: Context, arguments: String): String {
        if (!KaleidoBoxHost.isInitialized()) {
            return "KaleidoBox 尚未初始化（应用启动失败或未装载 :kaleidobox 模块），请联系开发者。"
        }
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = args.optString("action", "").trim()
        return try {
            when (action) {
                "list" -> doList()
                "catalog" -> doCatalog()
                "install" -> doInstall(args)
                "install_file" -> doInstallFile(context, args)
                "install_url" -> doInstallUrl(context, args)
                "write" -> doWrite(context, args)
                "invoke" -> doInvoke(args)
                "uninstall" -> doUninstall(args)
                else -> "未知 action: '$action'（支持 list / catalog / install / install_file / install_url / write / invoke / uninstall）"
            }
        } catch (e: Throwable) {
            "KaleidoBox 执行失败: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun doList(): String {
        val schemas = KaleidoBoxHost.get().runtime.toolSchemas()
        return if (schemas.isEmpty()) {
            "当前没有已安装的工具包单元（先用 catalog 看可用插件，再用 install / write 装载）。"
        } else {
            "已安装工具包单元（${schemas.size}）:\n" + Json.write(schemas)
        }
    }

    private fun doCatalog(): String {
        val entries = KaleidoCatalog.entries
        val arr = entries.map { e ->
            mapOf(
                "id" to e.id,
                "name" to e.displayName,
                "type" to e.kind.name,
                "group" to e.group.label,
                "tags" to e.tags,
                "tagLabels" to e.displayTags,
                "desc" to e.descZh,
                "entryClass" to e.entryClass,
                "hasSource" to (e.source != null),
            )
        }
        return "插件目录（${entries.size} 个，BUILTIN=已编入安装包 / SRC=设备内编译）：\n" + Json.write(arr)
    }

    private fun doInstall(args: JSONObject): String {
        val dir = args.optString("dir", "").trim()
        if (dir.isEmpty()) return "install 需要提供 dir（工具包目录绝对路径，需含 kaleido.json）。"
        val root = File(dir)
        if (!root.isDirectory) return "工具包目录不存在或非目录: $dir"
        if (!File(root, "kaleido.json").exists())
            return "目录 $dir 缺少 kaleido.json，不是合法的 Kaleido 工具包。"

        val source = DirectoryPackageSource(root)
        val trusted = args.optBoolean("trusted", false)
        val rec = KaleidoBoxHost.get().runtime.install(
            source,
            KaleidoRuntime.InstallOptions(trusted = trusted, source = InstallSource.SIDELOAD)
        )
        val units = rec.manifest.units.joinToString(", ") { "${it.name}(${it.title["zh"] ?: it.title["en"] ?: ""})" }
        return "已安装工具包 ${rec.id}@${rec.version}：units=[$units]"
    }

    private fun doInstallFile(context: Context, args: JSONObject): String {
        val path = args.optString("path", "").trim()
        if (path.isEmpty()) return "install_file 需要提供 path（.zip 包绝对路径）。"
        val file = File(path)
        if (!file.isFile) return "包文件不存在: $path"
        val out = KaleidoBoxBridge.installZip(context, file)
        return if (out is KValue.Obj && out.value["ok"]?.asBoolOr() == true)
            "已安装插件：${out.value["id"]?.asString() ?: ""}@${out.value["version"]?.asString() ?: ""}"
        else "安装失败：${out.asString()}"
    }

    private fun doInstallUrl(context: Context, args: JSONObject): String {
        val url = args.optString("url", "").trim()
        if (url.isEmpty()) return "install_url 需要提供 url（.zip 下载地址）。"
        val out = KaleidoBoxBridge.installUrl(context, url)
        return if (out is KValue.Obj && out.value["ok"]?.asBoolOr() == true)
            "已安装插件：${out.value["id"]?.asString() ?: ""}@${out.value["version"]?.asString() ?: ""}"
        else "安装失败：${out.asString()}"
    }

    private fun doWrite(context: Context, args: JSONObject): String {
        val source = args.optString("source", "").trim()
        if (source.isEmpty()) return "write 需要提供 source（完整 Java 源码）。"
        val className = args.optString("className", "").trim()
        if (className.isEmpty()) return "write 需要提供 className（入口类全名，= 源码 public 类名）。"
        val manifest = if (args.has("manifest") && args.optString("manifest").isNotBlank()) {
            args.optString("manifest")
        } else {
            val id = args.optString("id", "dev.kaleidobox.gen.plugin").trim()
            val nameZh = args.optString("pkgName", id).trim()
            val nameEn = args.optString("nameEn", nameZh).trim()
            val desc = args.optString("desc", "AI 生成的 KaleidoBox 工具包").trim()
            val units = args.optString("units", "").split(",").map { it.trim() }.filter { it.isNotBlank() }
                .map { it to "AI 生成的单元" }
            PluginScaffold.buildManifest(id, nameZh, nameEn, desc, className, units)
        }
        val out = KaleidoBoxBridge.writeAndInstall(context, source, className, manifest, args.optString("id", ""))
        return if (out is KValue.Obj && out.value["ok"]?.asBoolOr() == true) {
            val log = out.value["log"]?.asString() ?: ""
            "✔ 已编译并安装插件：${out.value["id"]?.asString() ?: ""}@${out.value["version"]?.asString() ?: ""}（dex ${out.value["dexSize"]?.asString() ?: "?"} 字节）\n$log"
        } else {
            "✘ 编译/安装失败：${out.asString()}"
        }
    }

    private fun doInvoke(args: JSONObject): String {
        val unit = args.optString("name", "").trim()
        if (unit.isEmpty()) return "invoke 需要提供 name（unit 名或 pkgId:unit）。"
        val rawArgs = args.optString("args", "{}").ifBlank { "{}" }
        val kArgs: KValue = runCatching {
            Json.toK(Json.parse(rawArgs))
        }.getOrElse { KValue.obj("__error" to "args 不是合法 JSON") }
        val out = KaleidoBoxHost.get().runtime.invokeUnit(unit, kArgs)
        return when (out) {
            is KValue.Err -> "调用失败 [${out.code}] ${out.message}"
            else -> runCatching { Json.write(Json.fromK(out)) }
                .getOrDefault("调用成功（结果无法序列化为 JSON）：${out.asString()}")
        }
    }

    private fun doUninstall(args: JSONObject): String {
        val id = args.optString("pkgId", args.optString("id", "")).trim()
        if (id.isEmpty()) return "uninstall 需要提供 pkgId。"
        val out = KaleidoBoxBridge.uninstall(id)
        return if (out is KValue.Obj && out.value["ok"]?.asBoolOr() == true) "已卸载：$id"
        else "卸载失败：${out.asString()}"
    }
}

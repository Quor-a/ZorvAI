package com.ai.assistance.quro.core.tools

import android.content.Context
import android.os.Environment
import com.ai.assistance.quro.core.privilege.QuroLSPosed
import com.ai.assistance.quro.core.privilege.QuroLsposeBridgeReceiver
import com.ai.assistance.quro.core.privilege.QuroRootGateway
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * LSPosed / Xposed 模块 AI 直驱工具（完整对接）。
 *
 * 让 AI 能直接：
 *  - status     ：LSPosed 安装 / 作用域状态 + 桥最近上报的前台 App + 当前桥配置；
 *  - foreground ：读取 LSPosed 跨应用注入桥最近上报的前台 App（补 get_foreground_app 无障碍盲区）；
 *  - enable     ：写外部存储 lsposed_bridge.json，开启跨应用注入（及可选系统重定向）桥，让模块真正产出数据；
 *  - disable    ：关闭桥（enabled=false）。
 *
 * 不引用任何 de.robv.android.xposed.*；桥配置读写在应用侧独立完成，Xposed 类仅在模块被框架加载时由框架注入。
 * 模块（QuroXposedModule）读取同一份 lsposed_bridge.json（Environment.getExternalStorageDirectory() 根），
 * 故本工具写出的配置会立即被钩中目标包时加载。
 */
class QuroLsposeTool : QuroTool {
    override val name = "lsposed"
    override val description =
        "LSPosed/Xposed 模块 AI 直驱（完整对接）。" +
            "status=查安装/作用域状态+桥上报的前台App+当前配置；" +
            "foreground=读 LSPosed 跨应用注入桥最近上报的前台 App（补无障碍盲区）；" +
            "enable=写 lsposed_bridge.json 开启跨应用注入桥(可带 target_packages)与可选系统重定向桥；" +
            "disable=关闭桥。需本应用已被 LSPosed 纳入作用域才会真正生效。" +
            "参数 {\"action\":\"status|foreground|enable|disable\",\"target_packages\":[\"com.x\"],\"system_redirect\":false," +
            "\"redirect_rules\":[{\"when_package\":\"com.x\",\"when_action\":null,\"when_data_host\":null,\"to_package\":\"com.ai.assistance.quro\",\"to_class\":\"...\"}]}"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"status=状态 / foreground=桥上报的前台App / enable=开启桥 / disable=关闭桥"},
            "target_packages":{"type":"array","items":{"type":"string"},"description":"enable 时跨应用注入的目标包名列表（必填，桥只对列表内的包生效）"},
            "system_redirect":{"type":"boolean","description":"enable 时是否同时开启系统重定向桥（默认 false）"},
            "redirect_rules":{"type":"array","items":{"type":"object"},"description":"system_redirect=true 时的重定向规则"}
        },
        "required":["action"]
    }"""

    private val configFile: File
        get() = File(Environment.getExternalStorageDirectory(), "lsposed_bridge.json")

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        val action = jo.optString("action", "").trim().lowercase()
        return when (action) {
            "status" -> status(context)
            "foreground" -> foreground(context)
            "enable" -> enable(context, jo)
            "disable" -> writeConfig(context, enabled = false)
            else -> "未知 action: $action（支持 status / foreground / enable / disable）"
        }
    }

    private fun status(ctx: Context): String {
        val sb = StringBuilder()
        sb.append(QuroLSPosed.statusText(ctx)).append('\n')
        sb.append("作用域: ").append(if (QuroLSPosed.isAppInScope(ctx)) "已纳入" else "未纳入").append('\n')
        val fg = QuroLsposeBridgeReceiver.lastForegroundApp(ctx)
        sb.append("桥上报前台App: ").append(
            fg?.let { "${it.first} / ${it.second} @${it.third}" } ?: "（无，需 enable 并打开目标App）"
        ).append('\n')
        sb.append("桥配置: ").append(
            runCatching { configFile.readText() }.getOrNull() ?: "（未配置，桥默认关闭）"
        )
        return sb.toString().trim()
    }

    private fun foreground(ctx: Context): String {
        val fg = QuroLsposeBridgeReceiver.lastForegroundApp(ctx)
            ?: return "❌ 桥尚未上报前台 App：请先 enable 跨应用注入桥，本应用纳入 LSPosed 作用域，并打开目标 App"
        val (pkg, activity, ts) = fg
        if (pkg.isNullOrEmpty()) return "❌ 桥数据为空"
        val label = runCatching {
            ctx.packageManager.getPackageInfo(pkg, 0)
                .applicationInfo?.loadLabel(ctx.packageManager)?.toString()
        }.getOrNull() ?: pkg
        return """{"package":"$pkg","label":"$label","activity":"$activity","ts":$ts,"source":"lsposed_bridge"}"""
    }

    private fun enable(ctx: Context, jo: JSONObject): String {
        val targetPkgs = jo.optJSONArray("target_packages")
        val sysRedirect = jo.optBoolean("system_redirect", false)
        val redirectRules = jo.optJSONArray("redirect_rules")

        val cai = JSONObject().apply {
            put("enabled", true)
            put("broadcast_action", QuroLsposeBridgeReceiver.ACTION_APP_OPENED)
            val arr = JSONArray()
            if (targetPkgs != null) for (i in 0 until targetPkgs.length()) arr.put(targetPkgs.optString(i))
            put("target_packages", arr)
        }
        val sr = JSONObject().apply {
            put("enabled", sysRedirect)
            val arr = JSONArray()
            if (redirectRules != null) for (i in 0 until redirectRules.length()) arr.put(redirectRules.opt(i))
            put("rules", arr)
        }
        val cfg = JSONObject().apply {
            put("enabled", true)
            put("cross_app_injection", cai)
            put("system_redirect", sr)
        }
        return writeConfigRaw(ctx, cfg, noteIfEmptyTargets = targetPkgs == null || targetPkgs.length() == 0)
    }

    private fun writeConfig(ctx: Context, enabled: Boolean): String {
        val cfg = JSONObject().apply { put("enabled", enabled) }
        return writeConfigRaw(ctx, cfg, noteIfEmptyTargets = false)
    }

    private fun writeConfigRaw(ctx: Context, cfg: JSONObject, noteIfEmptyTargets: Boolean): String {
        val json = cfg.toString(2)
        val err = runCatching {
            try {
                configFile.writeText(json)
            } catch (e: Exception) {
                // 外部存储根目录无写权限时（Android 11+ scoped storage），尝试以 root cp
                // （LSPosed 设备通常已 ROOT；模块在钩中进程读取同路径文件）
                val tmp = File(ctx.cacheDir, "lsposed_bridge.json")
                tmp.writeText(json)
                val r = QuroRootGateway.exec(
                    ctx,
                    "cp '${tmp.absolutePath}' '${configFile.absolutePath}' && chmod 644 '${configFile.absolutePath}'",
                    QuroRootGateway.DEFAULT_TIMEOUT_MS,
                    "capos.lsposed.config"
                )
                if (r.channel == QuroRootGateway.Channel.NONE) throw e
            }
        }.exceptionOrNull()
        val warn = if (noteIfEmptyTargets) {
            "\n⚠️ 未提供 target_packages：跨应用注入桥不会对任何包生效，请 enable 时带上目标包名列表。"
        } else ""
        return if (err == null) {
            "✅ 已写入 LSPosed 桥配置：${configFile.absolutePath}$warn\n$json\n" +
                "（本应用需已被 LSPosed 纳入作用域；打开目标 App 后桥即上报前台 App，可用 lsposed foreground / get_foreground_app 读取）"
        } else {
            "❌ 写入失败：${err.message}。请授予「所有文件访问」权限，或手动把以下配置写到 ${configFile.absolutePath}：$warn\n$json"
        }
    }
}

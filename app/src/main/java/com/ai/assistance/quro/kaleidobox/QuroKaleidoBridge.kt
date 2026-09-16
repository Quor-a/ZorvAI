package com.ai.assistance.quro.kaleidobox

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.terminal.QuroTerminalBridge
import com.ai.assistance.quro.kaleidobox.android.KaleidoAiResult
import com.ai.assistance.quro.kaleidobox.android.KaleidoAppBridge
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * App 级宿主桥：把 QuroAI 真实的 AI 对话引擎与终端/Linux 环境暴露给 KaleidoBox 插件。
 *
 * 关键点：
 *  - AI：**直接复用 ZorvAI 主对话的全局模型配置**（[QuroModelConfigRepository]，
 *    即用户在主对话「设置 → 模型」里填的同一个 API Key / BaseURL / 模型），通过
 *    [QuroLlmClient] 调同一个引擎。插件不再要求用户为它单独再配一套 provider，
 *    真正"APP 级"——用户在 ZorvAI 能聊，插件就能聊。
 *  - 终端：[QuroTerminalBridge.run] 走 proot Linux 环境，同步返回 (退出码, 输出)。
 *  - 此对象在 IO 线程被插件调用（工具中心 invoke 在 Dispatchers.IO），内部 runBlocking 安全。
 */
class QuroKaleidoBridge(private val context: Context) : KaleidoAppBridge {

    override fun aiChat(messages: List<Pair<String, String>>, system: String?): KaleidoAiResult {
        return try {
            // 直接复用 ZorvAI 主对话的全局模型配置，不要求用户为插件单独再配 provider。
            val cfg = QuroModelConfigRepository(context).load()
            if (cfg.apiKey.isBlank() || cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
                return KaleidoAiResult.Err(
                    "AI 引擎未配置：请在 ZorvAI 主对话「设置 → 模型」里填写 API Key / BaseURL / 模型，本插件会直接复用主对话的同一个 AI。"
                )
            }
            val chatMessages = mutableListOf<QuroChatMessage>().apply {
                if (system != null && !system.isBlank()) add(QuroChatMessage("system", system))
                messages.forEach { (role, content) ->
                    if (!content.isBlank()) add(QuroChatMessage(role, content))
                }
            }
            if (chatMessages.isEmpty()) return KaleidoAiResult.Err("消息为空")
            val tools = buildAgentTools()
            val trace = mutableListOf<String>()
            val maxRounds = 6
            // ReAct 循环：模型可在每一步请求调用工具，App 层真实执行后回填结果，
            // 直到模型给出最终文本回复或达到轮数上限。
            repeat(maxRounds) {
                val result = runBlocking {
                    QuroLlmClient().chat(
                        baseUrl = cfg.baseUrl,
                        apiKey = cfg.apiKey,
                        model = cfg.model,
                        messages = chatMessages,
                        temperature = cfg.temperature,
                        maxTokens = cfg.maxTokens,
                        tools = tools,
                    )
                }
                when (result) {
                    is QuroLlmResult.Text -> return KaleidoAiResult.Ok(result.content, trace)
                    is QuroLlmResult.Error -> return KaleidoAiResult.Err(result.message)
                    is QuroLlmResult.ToolCalls -> {
                        // 把 assistant 的工具调用记入历史（供模型下一轮回放），再逐个执行并回填 tool 结果。
                        chatMessages.add(QuroChatMessage("assistant", result.content ?: "", toolCalls = result.calls, reasoning = result.reasoning))
                        result.calls.forEach { call ->
                            val (out, _dur) = execAgentTool(call, 30_000L)
                            trace.add("🛠 ${call.name} → ${out.take(400)}")
                            chatMessages.add(
                                QuroChatMessage(
                                    "tool",
                                    if (out.isBlank()) "(工具无输出)" else out.take(8000),
                                    toolCallId = call.id,
                                    toolName = call.name,
                                )
                            )
                        }
                    }
                }
            }
            KaleidoAiResult.Err("已达到最大工具调用轮数（$maxRounds），请简化请求或分多次进行。")
        } catch (e: Throwable) {
            KaleidoAiResult.Err(e.message ?: "aiChat 失败")
        }
    }

    /** 下发给 LLM 的工具集：终端 / 联网 / 设备信息 / 剪贴板读写。 */
    private fun buildAgentTools(): List<QuroToolSpec> = listOf(
        QuroToolSpec(
            "terminal_run",
            "在设备的 Linux 终端环境执行 shell 命令，返回 (退出码, 标准输出/错误)。可用来列出/读写文件、运行程序、查询系统状态等。",
            """{"type":"object","properties":{"command":{"type":"string","description":"要执行的 shell 命令"}},"required":["command"]}""",
        ),
        QuroToolSpec(
            "web_fetch",
            "抓取指定 URL 的网页内容（纯文本/HTML，最多约 8000 字符）。用于查资料、读网页、调公开 API。",
            """{"type":"object","properties":{"url":{"type":"string","description":"完整 URL（含 http/https）"}},"required":["url"]}""",
        ),
        QuroToolSpec(
            "device_info",
            "返回当前设备信息（型号、品牌、Android 版本、应用版本、可用内存）。无需参数。",
            """{"type":"object","properties":{}}""",
        ),
        QuroToolSpec(
            "clipboard_get",
            "读取系统剪贴板当前文本内容。无需参数。",
            """{"type":"object","properties":{}}""",
        ),
        QuroToolSpec(
            "clipboard_set",
            "把文本写入系统剪贴板，便于跨应用粘贴。",
            """{"type":"object","properties":{"text":{"type":"string","description":"要写入剪贴板的文本"}},"required":["text"]}""",
        ),
    )

    /** 在 App 层执行一个工具调用，返回 (结果文本, 耗时毫秒)。 */
    private fun execAgentTool(call: QuroToolCall, timeoutMs: Long): Pair<String, Long> {
        val t0 = System.currentTimeMillis()
        val out = try {
            val args = runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }
            when (call.name) {
                "terminal_run" -> {
                    val cmd = args.optString("command", "")
                    if (cmd.isBlank()) "（缺少 command 参数）"
                    else {
                        val (code, res) = QuroTerminalBridge.run(context, cmd, timeoutMs)
                        "exit=$code\n${res.take(6000)}"
                    }
                }
                "web_fetch" -> fetchUrl(args.optString("url", ""))
                "device_info" -> readDeviceInfo()
                "clipboard_get" -> {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val t = cm?.primaryClip?.let { if (it.itemCount > 0) it.getItemAt(0)?.text?.toString() else null }
                    if (t != null && t.isNotEmpty()) t else "（剪贴板为空或不可读）"
                }
                "clipboard_set" -> {
                    val text = args.optString("text", "")
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (cm != null && text.trim().isNotEmpty()) {
                        cm.setPrimaryClip(ClipData.newPlainText("kaleido", text))
                        "已写入剪贴板（${text.length} 字）"
                    } else "（缺少 text 或无权访问剪贴板）"
                }
                else -> "（未知工具：${call.name}）"
            }
        } catch (e: Throwable) {
            "（工具执行异常：${e.message ?: e.javaClass.simpleName}）"
        }
        return out to (System.currentTimeMillis() - t0)
    }

    private fun fetchUrl(url: String): String {
        if (url.isBlank()) return "（缺少 url 参数）"
        val req = Request.Builder().url(url).header("User-Agent", "ZorvAI-Kaleido/1.0").get().build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) "HTTP ${resp.code}：${resp.message}"
                else body.take(8000)
            }
        } catch (e: Throwable) {
            "（抓取失败：${e.message ?: e.javaClass.simpleName}）"
        }
    }

    private fun readDeviceInfo(): String = buildString {
        appendLine("型号: ${Build.MODEL}")
        appendLine("品牌: ${Build.BRAND}")
        appendLine("Android SDK: ${Build.VERSION.SDK_INT}（${Build.VERSION.RELEASE}）")
        runCatching {
            @Suppress("DEPRECATION")
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            appendLine("应用版本: ${pi.versionName}")
        }
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            appendLine("可用内存: ${mi.availMem / 1048576} MB / 总 ${mi.totalMem / 1048576} MB")
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun aiAvailable(): Boolean = try {
        val cfg = QuroModelConfigRepository(context).load()
        !cfg.apiKey.isBlank() && !cfg.baseUrl.isBlank() && !cfg.model.isBlank()
    } catch (_: Throwable) { false }

    override fun termRun(command: String, timeoutMs: Long): Pair<Int, String> {
        return try {
            QuroTerminalBridge.run(context, command, timeoutMs)
        } catch (e: Throwable) {
            -1 to (e.message ?: "termRun 失败")
        }
    }
}

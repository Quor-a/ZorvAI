package com.ai.assistance.quro.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ai.assistance.quro.core.directive.AssistantDirectiveBus
import com.ai.assistance.quro.util.QuroDiag

/**
 * 分享 / 选中文本 → 交给 ZorvAI。**一个 Activity 管三个 action，形态照抄小布**。
 *
 * 清单实证，小布是一个 Activity 同时注册三种入口：
 * ```
 * [activity] com.heytap.speechassist.sharereceive.AIChatShareReceiveActivity  exported=true
 *   actions = android.intent.action.SEND
 *             android.intent.action.SEND_MULTIPLE
 *             android.intent.action.PROCESS_TEXT
 *   mimeTypes = text/plain + 7 个资源引用（图片/文件类）
 * ```
 * 我先前把 SEND 拆成独立 BroadcastReceiver、PROCESS_TEXT 单独一个 Activity —— 这是错的：
 *  - **Receiver 不会出现在系统文本选择菜单里**（那个浮动菜单只枚举 Activity）；
 *  - 分享面板按 Activity 列条目，拆成两个会出现两条"发给 ZorvAI"的重名项。
 * 合成一个 Activity 后，三种入口共用同一套文本抽取与投递逻辑，行为一致。
 *
 * 返回值的处理：PROCESS_TEXT 的语义是"处理完把结果替换回原文本"。我们的语义是
 * **"发给 AI 继续聊"**，所以 `RESULT_OK` 里原样回传原文（不篡改用户选中的内容），
 * 避免用户在选择菜单里点一下就把原文本改掉。
 */
class AssistantTextProcessActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.ai.assistance.quro.util.QuroLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        val processText = action == Intent.ACTION_PROCESS_TEXT
        val text = extractText(intent)

        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            QuroDiag.log(TAG, "empty payload action=$action, finish")
            finish()
            return
        }

        // 闸门：助手服务关了 / 「分享与选中文本」通道关了 → 一个字符都不接，
        // 原样回传原文（不能让用户点一下菜单就把选中内容弄丢）。
        val denied = com.ai.assistance.quro.core.assistant.AssistantServiceState
            .gate(this, com.ai.assistance.quro.core.assistant.AssistantChannel.SHARE)
        if (denied != null) {
            QuroDiag.log(TAG, "denied action=$action: ${denied.wire()}")
            runCatching { Toast.makeText(this, denied.message, Toast.LENGTH_SHORT).show() }
            if (processText) setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, trimmed))
            finish()
            return
        }

        val source = when (action) {
            Intent.ACTION_PROCESS_TEXT -> "process-text"
            Intent.ACTION_SEND_MULTIPLE -> "share-multiple"
            else -> "share"
        }
        val posted = AssistantDirectiveBus.post(trimmed, source)
        QuroDiag.log(TAG, "$source chars=${trimmed.length} posted=$posted")

        // 拉主界面（后台/未启动时也能让用户看到对话）；投递成功才拉，避免空跳转
        if (posted) {
            runCatching {
                startActivity(
                    Intent(this, QuroMainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
            }.onFailure { QuroDiag.log(TAG, "open main failed: ${it.message}") }
            runCatching { Toast.makeText(this, "已发给 ZorvAI", Toast.LENGTH_SHORT).show() }
        }

        // PROCESS_TEXT：原样回传，不改用户选中内容
        if (processText) {
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, trimmed))
        }
        finish()
    }

    /**
     * 从三种入口里抽文本。
     *
     * - PROCESS_TEXT → `EXTRA_PROCESS_TEXT`（也兼容个别 ROM 塞在 EXTRA_TEXT）
     * - SEND         → `EXTRA_TEXT`；只有链接时用 `EXTRA_STREAM` 的 uri 或其文字描述
     * - SEND_MULTIPLE→ `EXTRA_TEXT` 列表（多选分享），逐段拼起来
     *
     * 图片/文件分享拿不到可读文本时**不假装成功**：返回一句明确的话，让用户知道我们收到了什么。
     */
    private fun extractText(i: Intent?): String {
        if (i == null) return ""
        i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?.let { return it }
        i.getStringExtra(Intent.EXTRA_PROCESS_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }

        when (i.action) {
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = i.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
                if (!list.isNullOrEmpty()) {
                    return list.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }.joinToString("\n")
                }
                val uris = i.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) {
                    return "（分享了 ${uris.size} 个文件）\n" + uris.joinToString("\n") { it.toString() }
                }
            }

            else -> {
                i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
                i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }
                i.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }?.let { subj ->
                    return subj
                }
                @Suppress("DEPRECATION")
                val uri = i.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (uri != null) return "（分享了文件）\n$uri"
            }
        }
        return ""
    }

    companion object {
        private const val TAG = "assistant-process-text"

        /** 供清单/测试引用，避免两处各写一遍。 */
        const val ACTION_PROCESS_TEXT = Intent.ACTION_PROCESS_TEXT
    }
}

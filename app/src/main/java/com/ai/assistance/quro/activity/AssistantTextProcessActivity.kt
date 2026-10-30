package com.ai.assistance.quro.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ai.assistance.quro.core.directive.AssistantDirectiveBus
import com.ai.assistance.quro.util.QuroDiag

/**
 * 选中文本 → 交给 ZorvAI。
 *
 * 小布的 `AIChatShareReceiveActivity`（注册了 `android.intent.action.PROCESS_TEXT`）。
 *
 * 为什么必须是 **Activity** 而不是 Receiver：系统的文本选择浮动菜单（复制/粘贴那一排）
 * 只枚举 **Activity**，任何 Receiver 都不会出现在那个菜单里。用小布同款做法：
 * 透明 Activity 收文本 → 投进对话信箱 → 立刻 finish，用户感觉不到界面切换。
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
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent?.getStringExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: ""

        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            QuroDiag.log(TAG, "process-text: empty payload, finish")
            finish()
            return
        }

        // 闸门：助手服务关了 / 「分享与选中文本」通道关了 → 一个字符都不接，
        // 原样回传原文（不能让用户点一下菜单就把选中内容弄丢）。
        val denied = com.ai.assistance.quro.core.assistant.AssistantServiceState
            .gate(this, com.ai.assistance.quro.core.assistant.AssistantChannel.SHARE)
        if (denied != null) {
            QuroDiag.log(TAG, "process-text denied: ${denied.wire()}")
            runCatching { Toast.makeText(this, denied.message, Toast.LENGTH_SHORT).show() }
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, trimmed))
            finish()
            return
        }

        val posted = AssistantDirectiveBus.post(trimmed, "process-text")
        QuroDiag.log(TAG, "process-text chars=${trimmed.length} posted=$posted")

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

        // 原样回传，不改用户选中内容
        val reply = Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, trimmed)
        setResult(RESULT_OK, reply)
        finish()
    }

    companion object {
        private const val TAG = "assistant-process-text"

        /** 供清单/测试引用，避免两处各写一遍。 */
        const val ACTION_PROCESS_TEXT = Intent.ACTION_PROCESS_TEXT
    }
}

package com.ai.assistance.quro.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ai.assistance.quro.core.assistant.AssistantDispatcher
import com.ai.assistance.quro.core.assistant.AssistantRequest
import com.ai.assistance.quro.core.assistant.AssistantSource
import com.ai.assistance.quro.util.QuroDiag

/**
 * 快捷方式的落地页（透明）。
 *
 * ## 为什么需要它
 * 小布（以及任何读 `ShortcutManager` 的助手）执行第三方 App 的快捷方式时，只会做一件事：
 * **按 shortcut 里声明的 intent 发出去**。它不知道也不关心 ZorvAI 内部有多少能力。
 * 所以需要有一个统一的落地 Activity 把「哪个 shortcut」翻译成「执行哪个技能」，
 * 这样加能力不用动 shortcut 定义，小布也不必理解我们的能力表。
 *
 * 小布那条链是从它 dex 里确认的：`execShortcutAsync(shortcutId)` / `getShortcuts()` /
 * `ShortcutManagerCompat` —— 见 [com.ai.assistance.quro.core.assistant.BreenoBridge.publishShortcuts]。
 *
 * ## 参数来源（尽可能兼容）
 * 助手填参数的方式不统一，所以按优先级全试一遍：
 * `skill` extra → `android.intent.extra.TEXT` → `query` → `text` → data 查询参数。
 */
class AssistantShortcutActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "quro-shortcut"
        const val ACTION_SHORTCUT = "com.ai.assistance.quro.action.SHORTCUT"
        const val EXTRA_SKILL = "skill"

        /** `skill` 取这个值时打开能力广场（不是技能，是页面）。 */
        const val SKILL_PLAZA = "plaza"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.ai.assistance.quro.util.QuroLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val skill = intent?.getStringExtra(EXTRA_SKILL).orEmpty()
        val text = intent?.let { i ->
            i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: i.getStringExtra("query")
                ?: i.getStringExtra("text")
                ?: i.data?.getQueryParameter("text")
        }.orEmpty()

        QuroDiag.log(TAG, "landed skill=$skill textLen=${text.length} action=${intent?.action}")

        if (skill == SKILL_PLAZA || skill.isBlank()) {
            // 没指定技能 = 打开能力广场，让用户自己挑（比"什么都不发生"强）
            runCatching {
                startActivity(
                    Intent(this, AssistantSkillPlazaActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { QuroDiag.log(TAG, "打开能力广场失败: ${it.message}") }
            finish()
            return
        }

        // 走统一分发器：闸门、技能匹配、日志、结果封套全都在那一层，不在这里另搞一套
        AssistantDispatcher.submit(
            applicationContext,
            AssistantRequest(
                text = text,
                args = if (text.isNotBlank()) mapOf("text" to text) else emptyMap(),
                source = AssistantSource.TILE,
            ),
        ) { r ->
            QuroDiag.log(TAG, "shortcut result skill=$skill ${r.wire()}")
        }

        if (text.isBlank()) {
            runCatching { Toast.makeText(this, "ZorvAI：已执行 $skill", Toast.LENGTH_SHORT).show() }
        }
        finish()
    }
}

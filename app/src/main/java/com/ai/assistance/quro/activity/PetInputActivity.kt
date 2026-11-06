package com.ai.assistance.quro.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「对宠物说」透明输入条（对齐 BotHub PetActionInputActivity）：
 * 桌宠长按菜单 → 💬 对它说 → 本 Activity（屏幕底部透明输入条）→
 * 发送 = 经 QuroShareBridge 自动产生对话回合（pet_message 语义）。
 */
class PetInputActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.apply {
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }

        val input = EditText(this).apply {
            hint = "对它说点什么…"
            setSingleLine(true)
            setPadding(28, 24, 28, 24)
        }
        val send = TextView(this).apply {
            text = "发送"
            textSize = 15f
            setTextColor(0xFFB45A3C.toInt())
            gravity = Gravity.CENTER
            setPadding(32, 24, 32, 24)
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    com.ai.assistance.quro.ui.QuroShareBridge.emitAutoSend(text)
                    // 打开主对话框（与"戳了戳桌宠"同一入口），send 由 ChatScreen 消费
                    startActivity(
                        packageManager.getLaunchIntentForPackage(packageName)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                finish()
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(row)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        // 点空白处关闭
        row.setOnClickListener { }
        input.requestFocus()
    }
}

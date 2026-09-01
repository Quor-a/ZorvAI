package com.ai.assistance.quro.aciapp

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView

/**
 * 受控端主界面（Launcher）。仅作存在性展示与 ACI Service 保活：
 * 应用在前台期间绑定 [AciAppService]，使受控端可被 ZorvAI 主程序经 ACI 协议发现与调用。
 * 实际能力由 ACI Service 暴露，无需人工操作本界面。
 */
class MainActivity : Activity() {

    private var bound = false
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = buildString {
                append("Zorv AI ACI 受控端（Application Module 示例）\n\n")
                append("本应用是 ACI 受控端的规范参考实现，由 Zorv AI 主程序经 ACI 协议自动发现并调用：\n")
                append("• echo        —— 文本回显（连通性自测）\n")
                append("• device_info —— 设备信息\n")
                append("• health      —— 健康状态\n\n")
                append("请勿手动操作；保持安装即可被 AI 在对话中静默调用。")
            }
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
        setContentView(tv)

        // 绑定 ACI Service，使受控端在应用存活期间保持可被绑定
        try {
            val intent = Intent(this, AciAppService::class.java)
            bindService(intent, conn, BIND_AUTO_CREATE)
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        if (bound) {
            try {
                unbindService(conn)
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }
}

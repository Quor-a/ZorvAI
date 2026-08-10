package com.ai.assistance.quro.browser.consolekit

import org.json.JSONObject

/**
 * 同进程端点：直接委派给本地 [AciConsoleContract] 实现（如浏览器的 [com.ai.assistance.quro.browser.ConsoleBackend]）。
 * 手动控制台与 AI 控制台（经 ACI 的 console_ui / console_action）走到的是同一个后端实例，
 * 因此行为完全一致 —— 这就是「走 ACI 通道」的同一份真相。
 */
class LocalConsoleEndpoint(private val backend: AciConsoleContract) : AciConsoleContract {
    override fun getSnapshot(): JSONObject = backend.getSnapshot()
    override fun sendAction(action: String, payload: Map<String, String>): JSONObject =
        backend.sendAction(action, payload)
}

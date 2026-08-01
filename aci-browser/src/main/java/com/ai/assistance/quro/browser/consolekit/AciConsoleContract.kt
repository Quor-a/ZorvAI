package com.ai.assistance.quro.browser.consolekit

import org.json.JSONObject

/**
 * 通用 ACI 控制台契约（operit「外部调用」解耦范式）。
 *
 * 设计意图：手动控制台 / AI 控制台都只认识这个契约，不认识任何具体业务（BrowserCore / 第 2/3 个 App 的内部实现）。
 * 任意受控 App 只要满足此契约（要么同进程直接实现 [AciConsoleContract]，要么经 ACI 暴露
 * `console_ui` / `console_action` 两个能力），就能被同一套「控制台 UI」驱动 —— UI 永不硬编码业务逻辑，
 * 因此开发第 2、第 3、第 N 个受控软件时，控制台无需重写。
 *
 * 线程约束：[getSnapshot] 与 [sendAction] 必须在线程池 / Binder 线程等非 UI 线程调用：
 * 后端可能同步等待 WebView 主线程（post + CountDownLatch），在主线程调用会死锁。
 */
interface AciConsoleContract {
    /** 拉取当前 UI 快照（SDUI JSON，含组件数组）。非 UI 线程调用。 */
    fun getSnapshot(): JSONObject

    /** 执行一个 action，返回结果 JSON。非 UI 线程调用。 */
    fun sendAction(action: String, payload: Map<String, String>): JSONObject
}

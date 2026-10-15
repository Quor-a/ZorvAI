package com.ai.assistance.quro.core.tools

import android.os.Handler
import android.os.Looper

/**
 * 可视化工具答案回流桥（非阻塞、非时间等待）。
 *
 * 背景：visual_question / visual_action / visual_popup / visual_custom_popup 四个工具原本用
 * `CountDownLatch.await(timeout)` 把 agent 循环整个阻塞，等用户在界面上点选；模型常传 timeout=120，
 * 于是「AI 一直干等 120 秒」，用户不想操作就傻等。直接删掉工具又丢失了动态互交能力。
 *
 * 改造：工具入队后立即返回，不再阻塞等待；用户作答/操作后由对应队列的 submit 调用
 * [deliver]，把答案作为一条新的用户消息发回主对话，由 AI 在下一轮继续处理。
 * 这样彻底去掉时间等待，又保留「随时作答、作答即续」的动态互交。
 *
 * 用法：QuroChatViewModel 在初始化时注入 `VisualAnswerBridge.onAnswer = { send(it) }`。
 * 各可视化工具在用户提交答案时通过 pending 的 `onDeliver` 钩子回调本桥。
 *
 * ⚠️ GenUI 的渲染通道询问直接构造 pending 且自身阻塞在 latch 上消费答案，不设置 onDeliver，
 *   因此不会触发回流，保持原有语义（见 genui/.../ChatViewModel.askRenderChannel）。
 */
object VisualAnswerBridge {
    /** 由 QuroChatViewModel 注入：把答案作为新用户消息发回主对话。 */
    @Volatile
    var onAnswer: ((String) -> Unit)? = null

    /**
     * 把答案作为新用户消息投递回主对话。
     * - text 为空直接忽略（跳过/未作答场景，不触发新一轮）。
     * - 始终切回主线程再回调，确保 send 在正确的线程触发新一轮对话。
     */
    fun deliver(text: String) {
        val cb = onAnswer ?: return
        if (text.isBlank()) return
        Handler(Looper.getMainLooper()).post { cb(text) }
    }
}

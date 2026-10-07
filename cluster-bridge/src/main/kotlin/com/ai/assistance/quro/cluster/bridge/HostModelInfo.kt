package com.ai.assistance.quro.cluster.bridge

import com.ai.assistance.quro.cluster.model.ModelCapabilities
import kotlinx.serialization.Serializable

/**
 * 宿主 App 模型配置界面里的「一条模型配置」在 SDK 侧的投影。
 *
 * SDK 不认识厂商、不存 Key、不发 HTTP。宿主把自己在配置界面里已有的那些模型
 * 用这个结构报给 SDK，SDK 只拿 id 当句柄，真正发请求时再回调宿主。
 *
 * @param id 宿主侧唯一 ID（数据库主键或配置别名均可，稳定不变即可）
 * @param displayName 配置界面里的显示名，直接用于集群里的模型下拉
 * @param capabilities 能力声明。请如实填写 —— SDK 靠它决定用哪种输出协议、能否给这个角色挂工具
 * @param contextWindowTokens 上下文窗口，用于裁剪历史
 * @param maxConcurrency 该模型能同时承载几个请求。本地模型通常填 1
 * @param available 当前是否可用（Key 没配、被禁用、离线时置 false）
 */
@Serializable
data class HostModelInfo(
    val id: String,
    val displayName: String,
    val capabilities: ModelCapabilities,
    val contextWindowTokens: Int = 8_000,
    val maxConcurrency: Int = 1,
    val available: Boolean = true,
    /** 分组标签，如 "云端" / "本地" / "免费"，便于 UI 分组展示 */
    val group: String? = null
)

/** 宿主模型配置发生变化（增删改、可用性变化）时回调 SDK */
fun interface HostModelChangeListener {
    fun onChanged()
}

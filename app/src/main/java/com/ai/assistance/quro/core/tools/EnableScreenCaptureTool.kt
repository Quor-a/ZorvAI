package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.vision.MediaProjectionRequester

/**
 * enable_screen_capture —— 让 AI 自己发起「屏幕捕获（MediaProjection）」系统授权。
 *
 * 这是 #666「AI 需要的时候自己请求」的关键落点：AI 一旦判断需要看到真实屏幕像素
 * （视频/游戏/WebView/复杂图像/当前 App 界面），或用户说「看懂屏幕/截图看下/看看现在屏幕」，
 * 就调用本工具发起授权；用户在系统弹窗点「立即开始」后即启用像素级抓帧，
 * 比无障碍节点树更完整，且无需用户去手动长按开关。
 *
 * 若用户取消授权，系统会自动 fallback 到无障碍快照（AI 仍可读屏幕结构）。
 */
class EnableScreenCaptureTool : QuroTool {
    override val name = "enable_screen_capture"
    override val description: String =
        """发起屏幕捕获（MediaProjection / 媒体投影 / 录屏投屏）系统授权，让 AI 真正"看到"当前屏幕像素画面。
当以下情况时必须调用：用户要求"看懂屏幕 / 截图看下 / 看看现在屏幕 / 分析当前界面"；
或你判断需要识别视频、游戏、WebView、图形、相册、第三方 App 界面等无障碍节点树看不到的内容。
调用后系统会弹出授权框，请提示用户点「立即开始」。授权成功后即可用像素级截图理解屏幕；
若用户取消，会自动 fallback 到无障碍快照（仍可读取屏幕 UI 结构）。参数为空 {}。"""

    override val parametersJson: String = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        if (!MediaProjectionRequester.available) {
            return "当前界面未就绪，无法发起屏幕捕获授权（请在主对话界面操作）。"
        }
        MediaProjectionRequester.request()
        return "已发起屏幕捕获授权请求，请在弹出的系统对话框点「立即开始」。授权成功后即可用像素级截图理解屏幕；若取消则 fallback 到无障碍快照。"
    }
}

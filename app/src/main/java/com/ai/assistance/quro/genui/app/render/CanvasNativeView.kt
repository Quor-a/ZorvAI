package com.ai.assistance.quro.genui.app.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.gencanvas.runtime.GenCanvas
import io.gencanvas.runtime.Sanitizer

/**
 * GenCanvas 原生画布层 —— AI 在 `<script type="text/x-canvas">` 里写一份绘制指令 JSON，
 * 端上把它解释为**真实安卓原生画面**：Yoga 算布局 → DrawScope 真绘制 → 真动画 → 真命中测试。
 *
 * 与 XML/Compose 通道同一套承载逻辑：HTML 仍是宿主，canvas 块叠在上方成为原生渲染层。
 * 这里不做任何白名单——op 集（rect/rrect/circle/svg/clip/group…）由 AI 自由组合，
 * 解析层（Sanitizer）只负责把风险挡在入口（树深/节点数/字符串长度/未知 op 静默丢弃）。
 *
 * 与 WebView 的关系：canvas 层是纯 Compose 渲染，**不依赖 WebView**，因此它的事件
 * 通过 onAction 回传，由上层转成 mo:canvas 事件派发给当前 AI 页面。
 */
@Composable
fun CanvasNativeView(
    json: String,
    modifier: Modifier = Modifier,
    onAction: (String) -> Unit = {}
) {
    val doc = remember(json) { Sanitizer.parse(json) }
    if (doc != null) {
        GenCanvas(
            document = doc,
            modifier = modifier,
            onAction = { name, _ -> onAction(name) },
            onError = { /* 渲染错误由 GenCanvas 内部错误边界兜底，不向上抛 */ }
        )
    }
}

package com.zorv.plugin.zxwidget

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.json.JSONObject

/**
 * 容器型卡片的注册壳 —— 把 ZxNest 的递归渲染包成 RenderedCard。
 *
 * 单独成文件是为了让入口处保持干净：
 *   chatCard("zx_tabs", "分页容器") { d, c -> ZxNestCard.Tabs(d, c) }
 */
object ZxNestCard {

    @Composable fun Tabs(d: JSONObject, c: RenderContext): RenderedCard =
        wrap(d) { ZxNest.render(d, c, 0) }

    @Composable fun Accordion(d: JSONObject, c: RenderContext): RenderedCard =
        wrap(d) { ZxNest.render(d.apply { put("type", "zx_accordion") }, c, 0) }

    @Composable fun Split(d: JSONObject, c: RenderContext): RenderedCard =
        wrap(d) { ZxNest.render(d.apply { put("type", "zx_split") }, c, 0) }

    @Composable fun Carousel(d: JSONObject, c: RenderContext): RenderedCard =
        wrap(d) { ZxNest.render(d.apply { put("type", "zx_carousel") }, c, 0) }

    private fun wrap(d: JSONObject, body: @Composable () -> Unit): RenderedCard {
        // 容器本身不产生内容，子节点渲染时才消耗 RenderContext
        return RenderedCard(title = d.optString("title", "容器"), body = d.toString())
    }
}

/** 让 CardShell2 能被容器直接复用 */
@Composable
internal fun NestHost(d: JSONObject, c: RenderContext) {
    ZxNest.render(d, c, 0)
}

@Composable
internal fun NestBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier) { content() }
}

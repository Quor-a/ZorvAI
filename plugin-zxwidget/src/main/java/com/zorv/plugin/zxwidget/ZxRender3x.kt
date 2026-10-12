package com.zorv.plugin.zxwidget

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 第三批容器：zx_grid（等宽网格）/ zx_master（主从）/ zx_stagger（瀑布流）
 *
 * 单独成文件是因为这三个容器要做「按可用宽度动态决定怎么摆」，
 * 跟前四个容器（tabs/accordion/split/carousel 是"切换"语义）不是一回事——
 * 那四个是同一时刻只显示一个（或固定两栏），这三个是同一时刻要摆下多个。
 *
 * 共同的三条约束：
 *   1. 子节点必须已在组件目录里，未注册类型直接拒绝（不静默跳过）
 *   2. 网格列数随可用宽度退化：宽屏按 cols，窄屏强制降到 2，再窄就降 1
 *   3. 子节点共用同一个 RenderContext，回传时靠各自 DSL 里的 key 区分
 */
object ZxRender3x {

    /* ── zx_grid 等宽网格 ────────────────────────────────────── */
    @Composable
    fun Grid(o: JSONObject, ctx: RenderContext) {
        val kids = o.optJSONArray("children") ?: return
        val n = minOf(kids.length(), 12)
        val want = o.optInt("cols", 2).coerceIn(2, 4)
        // 窄屏退化：ZxRouter 已经算过可用宽度，这里直接用它判定
        val widthDp = ZxRouter.snapshot().widthDp
        val cols = when {
            widthDp < 280 -> 1
            widthDp < 340 -> minOf(want, 2)
            else -> want
        }

        Column(Modifier.fillMaxWidth()) {
            for (r in 0 until (n + cols - 1) / cols) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        if (i >= n) { Spacer(Modifier.weight(1f)); continue }
                        val kid = kids.optJSONObject(i) ?: continue
                        Box(Modifier.weight(1f)
                            .padding(end = if (c < cols - 1) 7.dp else 0.dp,
                                     bottom = 7.dp)) {
                            ZxNest.render(kid, ctx, 1)
                        }
                    }
                }
            }
        }
    }

    /* ── zx_master 主从布局 ──────────────────────────────────── */
    @Composable
    fun Master(o: JSONObject, ctx: RenderContext) {
        val kids = o.optJSONArray("children") ?: return
        if (kids.length() < 2) return
        val ratio = o.optDouble("ratio", 0.4).toFloat().coerceIn(0.25f, 0.6f)
        val widthDp = ZxRouter.snapshot().widthDp

        if (widthDp < 300) {
            // 极窄屏不做并排，主列表在上、详情在下，否则两边都读不了
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth()) { ZxNest.render(kids.optJSONObject(0) ?: JSONObject(), ctx, 1) }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth()) { ZxNest.render(kids.optJSONObject(1) ?: JSONObject(), ctx, 1) }
            }
            return
        }
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(ratio)) {
                ZxNest.render(kids.optJSONObject(0) ?: JSONObject(), ctx, 1)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f - ratio)) {
                ZxNest.render(kids.optJSONObject(1) ?: JSONObject(), ctx, 1)
            }
        }
    }

    /* ── zx_stagger 瀑布流 ───────────────────────────────────── */
    @Composable
    fun Stagger(o: JSONObject, ctx: RenderContext) {
        val kids = o.optJSONArray("children") ?: return
        val n = minOf(kids.length(), 16)
        // 按奇偶分两列，高度各自累加——不加 equal weight，才是瀑布而非等高网格
        val left = mutableListOf<JSONObject>()
        val right = mutableListOf<JSONObject>()
        for (i in 0 until n) {
            val k = kids.optJSONObject(i) ?: continue
            if (i % 2 == 0) left.add(k) else right.add(k)
        }

        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                left.forEach { k ->
                    Box(Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 7.dp)) {
                        ZxNest.render(k, ctx, 1)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                right.forEach { k ->
                    Box(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 7.dp)) {
                        ZxNest.render(k, ctx, 1)
                    }
                }
            }
        }
    }
}

/** 让 ZxNest.render 能认出第三批组件与容器 */
@Composable
internal fun renderThirdBatchIfAny(node: JSONObject, ctx: RenderContext): Boolean {
    when (node.optString("type")) {
        "zx_grid" -> ZxRender3x.Grid(node, ctx)
        "zx_master" -> ZxRender3x.Master(node, ctx)
        "zx_stagger" -> ZxRender3x.Stagger(node, ctx)
        else -> return false
    }
    return true
}

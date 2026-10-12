package com.zorv.plugin.zxwidget

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 动效协议 —— 卡与卡之间怎么过渡，也是可声明的。
 *
 * 为什么需要：卡片是"一张张长出来"的。同样的内容，
 * 瞬间出现像是闪了一下，慢慢淡入又让人等；
 * 更新时是原地突变还是滑动替换，观感差很多。
 *
 * 但动效不能由渲染器硬编码——AI 输出的卡片语义不同：
 *   · 一条新消息 → 淡入上移，自然
 *   · 同一张卡的数据更新 → 不该整卡重入，否则闪一下
 *   · 错误卡 → 轻微抖动，引起注意
 *   · 卡片被替换 → 旧的滑出新的滑入
 *
 * 所以把动效做成 DSL 里可声明的字段，缺省按语义自动选。
 *
 * 三条硬规则：
 *   1. 默认时长必须短——气泡里超过 300ms 的入场就是"卡"
 *   2. 尊重系统「减少动画」设置，开启时一律降到 0
 *   3. 动效不能改变布局结果，只改变到达过程
 */
object ZxMotion {

    enum class Kind(val label: String, val defaultMs: Int) {
        ENTER("入场", 220),
        UPDATE("更新", 180),
        REPLACE("替换", 260),
        EXIT("退出", 160),
        ATTENTION("引起注意", 400),
        NONE("无", 0)
    }

    /** 缓动：只保留四种，多了模型选不过来 */
    enum class Ease(val label: String) {
        STANDARD("标准"),      // 快出慢入
        EMPHASIS("强调"),      // 带回弹
        LINEAR("线性"),
        NONE("无缓动")
    }

    data class Spec(
        val kind: Kind,
        val ms: Int,
        val ease: Ease,
        /** 位移方向，dp。 ENTER 默认从下往上 8dp */
        val slideDp: Int,
        /** 抖动幅度，仅 ATTENTION 用 */
        val shakeDp: Int
    ) {
        val duration: Int get() = if (reduceMotion) 0 else ms
    }

    /** 系统「减少动画」开关，宿主注入 */
    private var reduceMotion = false
    fun setReduceMotion(on: Boolean) { reduceMotion = on }
    fun reduce(): Boolean = reduceMotion

    /** 按语义自动选缺省动效 */
    fun autoFor(kind: Kind): Spec = when (kind) {
        Kind.ENTER -> Spec(Kind.ENTER, 220, Ease.STANDARD, 8, 0)
        Kind.UPDATE -> Spec(Kind.UPDATE, 180, Ease.STANDARD, 0, 0)
        Kind.REPLACE -> Spec(Kind.REPLACE, 260, Ease.EMPHASIS, 12, 0)
        Kind.EXIT -> Spec(Kind.EXIT, 160, Ease.LINEAR, -6, 0)
        Kind.ATTENTION -> Spec(Kind.ATTENTION, 400, Ease.EMPHASIS, 0, 4)
        Kind.NONE -> Spec(Kind.NONE, 0, Ease.NONE, 0, 0)
    }

    /** 从 DSL 的 _motion 字段解析，解析不出就按语义兜底 */
    fun parse(dsl: String, fallback: Kind): Spec {
        val raw = runCatching { org.json.JSONObject(dsl).optString("_motion") }.getOrNull()
        if (raw.isNullOrBlank()) return autoFor(fallback)
        // 支持 "enter" / "enter:300" / "enter:300:bounce" 三种写法
        val parts = raw.split(":")
        val kind = Kind.entries.firstOrNull { it.name.equals(parts[0], true) }
            ?: return autoFor(fallback)
        val ms = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 1000) ?: kind.defaultMs
        val ease = when (parts.getOrNull(2)?.lowercase()) {
            "emphasis", "bounce", "spring" -> Ease.EMPHASIS
            "linear" -> Ease.LINEAR
            "none" -> Ease.NONE
            else -> if (kind == Kind.ATTENTION) Ease.EMPHASIS else Ease.STANDARD
        }
        val base = autoFor(kind)
        return base.copy(ms = ms, ease = ease)
    }

    /** 转成 Compose 的动画规格 */
    fun <T> specOf(s: Spec): FiniteAnimationSpec<T> = when {
        s.duration == 0 -> tween(0)
        s.ease == Ease.EMPHASIS -> spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
            visibilityThreshold = 0.01f
        ).let { @Suppress("UNCHECKED_CAST") (it as FiniteAnimationSpec<T>) }
        s.ease == Ease.LINEAR -> tween(s.duration, easing = androidx.compose.animation.core.LinearEasing)
        else -> tween(s.duration, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    }

    /**
     * 更新时判定要不要整卡重入。
     * 同一张卡（id 相同）数据变了，应该走 UPDATE 而不是 ENTER——
     * 整卡重入会闪一下，用户会以为消息被重新发了。
     */
    fun kindFor(previousId: String?, nextId: String, hadError: Boolean): Kind = when {
        hadError -> Kind.ATTENTION
        previousId == null -> Kind.ENTER
        previousId == nextId -> Kind.UPDATE
        else -> Kind.REPLACE
    }

    fun spec(): String = buildString {
        appendLine("可用动效（写在 DSL 的 _motion 字段）：")
        Kind.entries.filter { it != Kind.NONE }.forEach { k ->
            appendLine("  ${k.name.lowercase().padEnd(10)} ${k.label}  默认 ${k.defaultMs}ms")
        }
        appendLine()
        appendLine("写法：\"_motion\":\"enter\"  /  \"enter:300\"  /  \"enter:300:emphasis\"")
        appendLine("缓动可选：standard(默认) / emphasis(回弹) / linear / none")
        appendLine()
        appendLine("系统「减少动画」开启时全部降到 0ms（当前：${if (reduceMotion) "已开启" else "未开启"}）")
    }

    fun guide(): String = """
        _motion 写在卡片 DSL 里，缺省按语义自动选：
          新卡        → enter 220ms 上移 8dp
          同卡更新    → update 180ms（不整卡重入，避免闪）
          换卡        → replace 260ms 带回弹
          错误卡      → attention 400ms 抖动 4dp

        超过 300ms 的入场在气泡里就是"卡"，"别加。
    """.trimIndent()
}

package com.ai.assistance.quro.genui.sdk.style

import androidx.compose.ui.graphics.Color

/**
 * ZorvAI 设计令牌 —— GenUI SDK 的配色/美术底座
 *
 * 与 ZorvAI 宿主 `QuroTheme` 同源：**暖纸底 + 陶土强调 + 墨色文字 + 鼠尾草（思考/工具）+ 点缀金**。
 * 刻意避开「AI 默认审美」的蓝紫（Material3 默认 #6750A4 / Tailwind indigo #6C5CE7 / blue-500 #3B82F6），
 * 保证生成式界面与 App 本体是同一套视觉体系，而不是贴进来的一块外来 UI。
 *
 * 命名约定：
 *  - 品牌色：terracotta / sage / gold / paper / ink / line
 *  - 语义色：success / warning / info / rise（涨，红）/ fall（跌，绿，符合中国股市习惯）
 *  - 表面层叠：surfaceContainerLowest → Highest（纸张叠层，替代插画/灰阶堆砌）
 */
object ZorvPalette {

    // ==================== 品牌主色（亮）====================
    val Terracotta = Color(0xFFC25A38)        // 主强调（陶土）
    val TerracottaPress = Color(0xFFA8482B)   // 按下/深
    val TerracottaSoft = Color(0xFFF4E4DB)    // 浅陶土底
    val TerracottaTint = Color(0xFFFAEFE9)    // 极浅陶土（浮层）

    val Sage = Color(0xFF6E7C62)              // 鼠尾草绿（思考/工具）
    val SagePress = Color(0xFF4E5A45)
    val SageSoft = Color(0xFFE7ECE2)
    val SageTint = Color(0xFFF1F4EC)

    val Gold = Color(0xFFB8902F)              // 点缀金
    val GoldPress = Color(0xFF8A6A1F)
    val GoldSoft = Color(0xFFF6ECD5)

    // ==================== 纸 / 墨 / 线（亮）====================
    val PaperBright = Color(0xFFFBF9F4)       // 最亮纸（顶部微光）
    val Paper = Color(0xFFF4F1EA)             // 主底色
    val Paper2 = Color(0xFFECE7DC)            // 次底色
    val Paper3 = Color(0xFFE3DDD0)            // 压深纸
    val Card = Color(0xFFFFFFFF)              // 卡片
    val Ink = Color(0xFF211E1A)               // 主文字
    val InkSoft = Color(0xFF544D44)           // 次文字
    val Muted = Color(0xFF938A7E)             // 弱文字/占位
    val LineSoft = Color(0xFFE3DDD0)          // 弱分隔线
    val Line = Color(0xFFD8D0C0)              // 可见边框

    // ==================== 语义色（亮）====================
    val ErrorWarm = Color(0xFFB23A2E)
    val ErrorSoft = Color(0xFFF6E3DF)

    val Success = Color(0xFF4E7A46)           // 暖绿（与鼠尾草同族，比 Tailwind green 收敛）
    val SuccessSoft = Color(0xFFE4EDDF)
    val Warning = Color(0xFFB08320)           // 暖琥珀
    val WarningSoft = Color(0xFFF7EBD2)
    val Info = Color(0xFF3F6E8C)              // 暖靛蓝（低饱和，非 AI 蓝）
    val InfoSoft = Color(0xFFE1ECF2)

    /** 涨（中国股市习惯：红涨） */
    val Rise = Color(0xFFC0392B)
    /** 跌（中国股市习惯：绿跌） */
    val Fall = Color(0xFF2E7D5B)

    // ==================== 品牌主色（暗，暖近黑体系）====================
    val TerracottaDark = Color(0xFFD9785A)
    val TerracottaDarkContainer = Color(0xFF3A2A22)
    val TerracottaDarkOn = Color(0xFF211409)

    val SageDark = Color(0xFF9AA98C)
    val SageDarkContainer = Color(0xFF2C3426)
    val SageDarkOn = Color(0xFF1E241A)

    val GoldDark = Color(0xFFD8B45E)
    val GoldDarkContainer = Color(0xFF43350F)
    val GoldDarkOn = Color(0xFF2A1F08)

    val BrandBackgroundDark = Color(0xFF16130F)
    val SurfaceDark = Color(0xFF211D18)
    val OnDark = Color(0xFFEDE6DA)
    val OnDarkSoft = Color(0xFFA89E90)
    val ContainerDark = Color(0xFF2C2820)
    val LineDark = Color(0xFF4A4439)
    val LineSoftDark = Color(0xFF3A352B)

    /** 暗色表面层叠：Lowest → Highest（低→高） */
    val SurfaceLowestDark = Color(0xFF1A1611)
    val SurfaceLowDark = Color(0xFF1E1A15)
    val SurfaceHighDark = Color(0xFF2A251E)
    val SurfaceHighestDark = Color(0xFF332D24)

    val ErrorDark = Color(0xFFE8836F)
    val ErrorDarkContainer = Color(0xFF43201A)
    val ErrorDarkOn = Color(0xFF3A0F08)

    val SuccessDark = Color(0xFF8FBF84)
    val SuccessDarkContainer = Color(0xFF24331F)
    val SuccessDarkOn = Color(0xFFDCEBD5)
    val WarningDark = Color(0xFFE0B75C)
    val WarningDarkContainer = Color(0xFF3D3015)
    val WarningDarkOn = Color(0xFFF5E3B8)
    val InfoDark = Color(0xFF8FB4CC)
    val InfoDarkContainer = Color(0xFF1F2E38)
    val InfoDarkOn = Color(0xFFD3E4EF)

    val RiseDark = Color(0xFFE0736A)
    val FallDark = Color(0xFF6FB58C)

    // ==================== 工具 ====================

    /**
     * 在基色上叠一层纸感（把纯白/纯黑替换为暖色偏移），
     * 让 AI 直接写 `#FFFFFF` / `#000000` 时也不会跳出暖色体系。
     */
    fun warmify(color: Color): Color {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val sat = if (max <= 0f) 0f else (max - min) / max
        // 纯灰/纯白/纯黑（饱和度极低）→ 轻微暖偏
        if (sat < 0.06f) {
            return Color(
                red = (r * 1.0f + 0.012f).coerceIn(0f, 1f),
                green = (g * 0.995f + 0.004f).coerceIn(0f, 1f),
                blue = (b * 0.985f).coerceIn(0f, 1f),
                alpha = color.alpha
            )
        }
        return color
    }

    /** 线性混色（t=0 取 a，t=1 取 b），用于派生 container / 浅底色 */
    fun mix(a: Color, b: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * k,
            green = a.green + (b.green - a.green) * k,
            blue = a.blue + (b.blue - a.blue) * k,
            alpha = a.alpha + (b.alpha - a.alpha) * k
        )
    }

    /** 品牌三色渐变（陶土 → 金），用于 hero/banner 类组件的默认美术 */
    fun brandGradient(): List<Color> = listOf(Terracotta, Color(0xFFD9814B), Gold)

    /** 纸感渐变（顶部微光 → 纸 → 次纸），页面底默认美术 */
    fun paperGradient(): List<Color> = listOf(PaperBright, Paper, Paper2)

    // ==================== 审美收敛（harmonize）====================

    /**
     * 把 AI 手写的十六进制颜色「收敛」进 ZorvAI 的暖色审美体系。
     *
     * 只做无损的三件事，不改变颜色语义（红仍是红、蓝仍是蓝）：
     *  1. **过饱和收敛**：S > 0.70 的霓虹色降饱和（Tailwind 500/400 系典型值 0.8+ → 收到 0.6 上下）；
     *  2. **发光压制**：高饱和 + 极亮（S>0.35, L>0.88）压到 0.88，去掉「AI 塑料感」的荧光；
     *  3. **纯白/纯黑替纸墨**：饱和度≈0 且亮度极端的，替换为纸感白 / 墨色黑（暖偏移）。
     *
     * 低饱和、中间亮度的颜色（正常的文字/边框/浅底）原样返回。
     */
    fun harmonize(color: Color, dark: Boolean = false): Color {
        if (color.alpha == 0f) return color
        val (h, s0, l0) = toHsl(color)
        var s = s0
        var l = l0

        if (s > 0.70f) s = 0.58f + (s - 0.70f) * 0.35f
        if (s > 0.35f && l > 0.88f) l = 0.88f

        // 纯白 / 纯黑 → 纸 / 墨（暖偏移，肉眼几乎无差但不再「冷硬」）
        if (s0 < 0.035f) {
            return when {
                l0 > 0.96f -> if (dark) Color(0xFFF3EDE3) else PaperBright
                l0 < 0.045f -> if (dark) SurfaceLowestDark else Ink
                else -> fromHsl(h, s, l, color.alpha)
            }
        }
        return fromHsl(h, s, l, color.alpha)
    }

    // ---- 手写 HSL 转换（不依赖 Compose 版本提供的扩展）----

    /** @return Triple(hue 0..360, saturation 0..1, lightness 0..1) */
    fun toHsl(c: Color): Triple<Float, Float, Float> {
        val r = c.red
        val g = c.green
        val b = c.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        val d = max - min
        if (d < 1e-6f) return Triple(0f, 0f, l)
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return Triple(if (h < 0f) h + 360f else h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    fun fromHsl(h: Float, s: Float, l: Float, alpha: Float = 1f): Color {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return Color(
            red = (r1 + m).coerceIn(0f, 1f),
            green = (g1 + m).coerceIn(0f, 1f),
            blue = (b1 + m).coerceIn(0f, 1f),
            alpha = alpha.coerceIn(0f, 1f)
        )
    }
}

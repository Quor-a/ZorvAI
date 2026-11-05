package com.ai.assistance.quro.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Zorv AI 专属主题（原创，暖纸 + 陶土 风）。
 * 配色取自用户「对话框设计稿（墨问）」：纸感底色 + 陶土强调色 + 墨色文字 + 鼠尾草绿（思考/工具）。
 * 设计稿原话刻意避开 AI 蓝紫，故全站统一为暖色系，品牌「墨问」更名为 Zorv AI。
 */

// ---- 暖色 token（陶土 / 纸 / 墨）----
private val QuroTerracotta = Color(0xFFC25A38)      // 主强调（陶土）
private val QuroTerracottaPress = Color(0xFFA8482B) // 按下态
private val QuroTerracottaSoft = Color(0xFFF4E4DB)  // 浅陶土底
private val QuroSage = Color(0xFF6E7C62)            // 思考/工具（鼠尾草绿）
private val QuroGold = Color(0xFFB8902F)             // 点缀金
private val QuroPaper = Color(0xFFF4F1EA)            // 主底色
private val QuroPaper2 = Color(0xFFECE7DC)           // 次底色
private val QuroCard = Color(0xFFFFFFFF)              // 卡片/表面
private val QuroInk = Color(0xFF211E1A)             // 主文字
private val QuroInkSoft = Color(0xFF544D44)          // 次文字
private val QuroMuted = Color(0xFF938A7E)           // 弱文字/占位
private val QuroLine = Color(0xFFE3DDD0)            // 边框
private val QuroLine2 = Color(0xFFD8D0C0)           // 强边框
private val QuroErrorWarm = Color(0xFFB23A2E)       // 暖红错误
private val QuroErrorSoft = Color(0xFFF6E3DF)       // 浅红底

/**
 * 主题强调色预设（用户可在「外观」里切换，运行时立即生效）。
 * 默认陶土 = 旧版硬编码强调色，迁移无感；其余为新增可选色板。
 */
data class QuroAccentPreset(val key: String, val name: String, val hex: String)
val QuroAccentPresets = listOf(
    QuroAccentPreset("terracotta", "陶土", "C25A38"),
    QuroAccentPreset("indigo", "靛蓝", "5856D6"),
    QuroAccentPreset("emerald", "翠绿", "2E9E6B"),
    QuroAccentPreset("rose", "玫红", "D6457E"),
    QuroAccentPreset("purple", "紫晶", "8B5CF6"),
    QuroAccentPreset("amber", "琥珀", "E08A1E"),
    QuroAccentPreset("cyan", "青蓝", "1FA2B8"),
    QuroAccentPreset("red", "赤红", "D23B3B"),
    QuroAccentPreset("graphite", "石墨", "4A4A55"),
)

/** 把 hex（可带 #，6 位）解析为 Color；非法或空回退默认陶土。 */
fun parseQuroAccent(hex: String?): Color {
    val h = hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 } ?: "C25A38"
    return try {
        Color(android.graphics.Color.parseColor("#$h"))
    } catch (_: Exception) {
        Color(0xFFC25A38)
    }
}

/** 往白色混合 ratio 比例（ratio=1 纯白）。 */
private fun Color.blendWhite(r: Float) = Color(
    red = red + (1f - red) * r, green = green + (1f - green) * r,
    blue = blue + (1f - blue) * r, alpha = 1f,
)
/** 往黑色混合 ratio 比例（ratio=1 纯黑）。 */
private fun Color.blendBlack(r: Float) = Color(
    red = red * (1f - r), green = green * (1f - r), blue = blue * (1f - r), alpha = 1f,
)
/** WCAG 相对亮度。 */
private fun Color.lum(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
/** 强调色上的前景色：暗色强调用白字，亮色强调用墨字。 */
internal fun onAccent(a: Color): Color = if (a.lum() < 0.55f) Color.White else Color(0xFF211E1A)

/**
 * 亮色方案：纸感底 + 白色卡片 + 用户强调色。
 * 背景/表面/文字/边框等暖色 token 固定；仅 primary 族随 accent 变化。
 */
private fun buildQuroLightScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = onAccent(accent),
    primaryContainer = accent.blendWhite(0.85f),
    onPrimaryContainer = accent.blendBlack(0.6f),
    secondary = QuroSage,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7ECE2),
    onSecondaryContainer = Color(0xFF3E4A36),
    tertiary = accent,
    onTertiary = onAccent(accent),
    background = QuroPaper,
    onBackground = QuroInk,
    surface = QuroCard,
    onSurface = QuroInk,
    surfaceVariant = QuroPaper2,
    onSurfaceVariant = QuroInkSoft,
    outline = QuroLine,
    outlineVariant = QuroLine2,
    error = QuroErrorWarm,
    onError = Color.White,
    errorContainer = QuroErrorSoft,
    onErrorContainer = Color(0xFF7A2417),
)

/**
 * 暗色方案：暖近黑底 + 用户强调色（提亮一档保证可见）。
 */
private fun buildQuroDarkScheme(accent: Color) = darkColorScheme(
    primary = accent.blendWhite(0.3f),
    onPrimary = Color(0xFF211409),
    primaryContainer = accent.blendBlack(0.35f),
    onPrimaryContainer = accent.blendWhite(0.78f),
    secondary = Color(0xFF9AA98C),
    onSecondary = Color(0xFF1E241A),
    secondaryContainer = Color(0xFF2C3426),
    onSecondaryContainer = Color(0xFFC7D2BB),
    tertiary = accent.blendWhite(0.3f),
    onTertiary = Color(0xFF211409),
    background = Color(0xFF16130F),
    onBackground = Color(0xFFEDE6DA),
    surface = Color(0xFF211D18),
    onSurface = Color(0xFFEDE6DA),
    surfaceVariant = Color(0xFF2C2820),
    onSurfaceVariant = Color(0xFFA89E90),
    outline = Color(0xFF3A352B),
    outlineVariant = Color(0xFF4A4439),
    error = Color(0xFFE8836F),
    onError = Color(0xFF3A0F08),
    errorContainer = Color(0xFF43201A),
    onErrorContainer = Color(0xFFF2C9C0),
)

/** 纸感背景（亮）：顶部微光 → 纸 → 次纸，自上而下。 */
private val QuroPaperBrushLight = Brush.verticalGradient(
    colors = listOf(
        Color(0xFFFBF9F4),
        QuroPaper,
        QuroPaper2,
    ),
)

/** 纸感背景（暗）：暖近黑渐变。 */
private val QuroPaperBrushDark = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF1C1813),
        Color(0xFF16130F),
        Color(0xFF120F0B),
    ),
)

@Composable
fun QuroTheme(darkOverride: Boolean? = null, accentHex: String? = null, content: @Composable () -> Unit) {
    val dark = darkOverride ?: isSystemInDarkTheme()
    val accent = parseQuroAccent(accentHex)
    val scheme = if (dark) buildQuroDarkScheme(accent) else buildQuroLightScheme(accent)
    val brush = if (dark) QuroPaperBrushDark else QuroPaperBrushLight
    Box(Modifier.fillMaxSize().background(brush)) {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}

/**
 * 当前 App 主题是否为深色（跟随 [QuroTheme] 实际生效的 colorScheme，而非系统设置）。
 *
 * 历史 bug：Mermaid 图表、工具中心、天气图标等子组件直接用 isSystemInDarkTheme()，
 * 导致用户手动开深色但系统仍浅色时，这些局部「反向渲染」成亮色。统一走本函数，
 * 以 MaterialTheme.colorScheme.background 的亮度为判据 —— 它才是 App 真正生效的深浅。
 */
@Composable
fun isDarkTheme(): Boolean {
    val bg = MaterialTheme.colorScheme.background
    // 用相对亮度判深色（threshold 0.5，标准 WCAG 相对亮度口径）。
    val lum = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    return lum < 0.5f
}

/**
 * 设置页专属主题：iOS 灰底 + 靛蓝图标方块。
 * 仅包裹设置页子树，不影响对话页的暖纸/陶土主题。
 */
private val QuroIOSIndigo = Color(0xFF5856D6)
private val QuroIOSBlue = Color(0xFF007AFF)
private val QuroIOSBg = Color(0xFFF2F2F7)
private val QuroIOSCard = Color(0xFFFFFFFF)
private val QuroIOSInk = Color(0xFF1C1C1E)
private val QuroIOSInkSoft = Color(0xFF8A8A8E)
private val QuroIOSLine = Color(0xFFE5E5EA)
private val QuroIOSMuted = Color(0xFFC7C7CC)

private val QuroIOSScheme = lightColorScheme(
    primary = QuroIOSIndigo,
    onPrimary = Color.White,
    primaryContainer = QuroIOSIndigo.copy(alpha = 0.12f),
    onPrimaryContainer = QuroIOSIndigo,
    secondary = QuroIOSBlue,
    onSecondary = Color.White,
    background = QuroIOSBg,
    onBackground = QuroIOSInk,
    surface = QuroIOSCard,
    onSurface = QuroIOSInk,
    surfaceVariant = QuroIOSBg,
    onSurfaceVariant = QuroIOSInkSoft,
    outline = QuroIOSLine,
    outlineVariant = QuroIOSLine,
    error = Color(0xFFD23B3B),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E8),
    onErrorContainer = Color(0xFF8A1F1B),
)

/** iOS 风暗色方案（本轮补齐：此前只有亮色，深色模式下设置页子树被强制亮色）。 */
private val QuroIOSDarkScheme = darkColorScheme(
    primary = Color(0xFF7C7AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2E2C66),
    onPrimaryContainer = Color(0xFFC6C4FF),
    secondary = Color(0xFF4DA3FF),
    onSecondary = Color.White,
    background = Color(0xFF0E0E12),
    onBackground = Color(0xFFE5E5EA),
    surface = Color(0xFF1C1C22),
    onSurface = Color(0xFFE5E5EA),
    surfaceVariant = Color(0xFF242428),
    onSurfaceVariant = Color(0xFFA8A8B0),
    outline = Color(0xFF3A3A40),
    outlineVariant = Color(0xFF3A3A40),
    error = Color(0xFFE8836F),
    onError = Color(0xFF3A0F08),
    errorContainer = Color(0xFF43201A),
    onErrorContainer = Color(0xFFF2C9C0),
)

@Composable
fun QuroSettingsTheme(darkOverride: Boolean? = null, accentHex: String? = null, content: @Composable () -> Unit) {
    val dark = darkOverride ?: isSystemInDarkTheme()
    val accent = parseQuroAccent(accentHex)
    val scheme = if (dark) QuroIOSDarkScheme else QuroIOSScheme
    val tinted = scheme.copy(
        primary = if (dark) accent.blendWhite(0.3f) else accent,
        onPrimary = if (dark) Color(0xFF211409) else onAccent(accent),
        primaryContainer = if (dark) accent.blendBlack(0.35f) else accent.blendWhite(0.85f),
        onPrimaryContainer = if (dark) accent.blendWhite(0.78f) else accent.blendBlack(0.6f),
    )
    MaterialTheme(colorScheme = tinted, content = content)
}

// ---- 聊天 UI 配色别名 ----
// 🔴 历史遗留 bug（本轮修复）：这些别名曾是顶层静态 `val`（恒亮色），
// 导致深色模式下设置页/聊天页的卡片、边框、弱文字仍是亮色常量、不随主题切换
// （「全局深色、局部亮色」的割裂）。现改为 @Composable getter，统一映射到
// MaterialTheme.colorScheme 的语义色，使所有引用点自动跟随 QuroTheme 的深浅。
val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
/**
 * 🔴 #外部连接/思考工具显示：本别名此前与 [AccentSoft] 同为 `primaryContainer`，
 * 而**全部引用点都把它当文字前景色用**（选中项标题、摘要标签文字、模型名强调），
 * 于是「前景 == 背景」→ 同色吞字：截图里表现为「只有说明没有标题」+
 * 「那排没字的浅色胶囊」。语义上它表达的是"在 primaryContainer 底上要能看清的字"，
 * 因此正确取值是 `onPrimaryContainer`（浅色底配深字 / 深色底配亮字）。
 */
val AccentPress: Color @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer
/** 浅色强调底（选中态胶囊 / 标签背景）。 */
val AccentSoft: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
val Card: Color @Composable get() = MaterialTheme.colorScheme.surface
val CardD: Color @Composable get() = MaterialTheme.colorScheme.surface
val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val InkD: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val InkSoft: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Line: Color @Composable get() = MaterialTheme.colorScheme.outline
val Line2: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
val Sage: Color @Composable get() = MaterialTheme.colorScheme.secondary

/** 衬线标题字体（MoWen 纸质风），供聊天页使用。 */
val QuroChatTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        displayMedium = base.displayMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.Default),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.Default),
        labelLarge = base.labelLarge.copy(fontFamily = FontFamily.Default)
    )
}

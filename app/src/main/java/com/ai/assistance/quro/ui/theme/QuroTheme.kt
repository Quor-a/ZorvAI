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

/** 亮色方案：纸感底 + 白色卡片 + 陶土强调。 */
private val QuroLightColorScheme = lightColorScheme(
    primary = QuroTerracotta,
    onPrimary = Color.White,
    primaryContainer = QuroTerracottaSoft,
    onPrimaryContainer = QuroTerracottaPress,
    secondary = QuroSage,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7ECE2),
    onSecondaryContainer = Color(0xFF3E4A36),
    tertiary = QuroGold,
    onTertiary = Color.White,
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

/** 暗色方案：暖近黑底 + 浅陶土强调。 */
private val QuroDarkColorScheme = darkColorScheme(
    primary = Color(0xFFD9785A),
    onPrimary = Color(0xFF211409),
    primaryContainer = Color(0xFF3A2A22),
    onPrimaryContainer = QuroTerracottaSoft,
    secondary = Color(0xFF9AA98C),
    onSecondary = Color(0xFF1E241A),
    secondaryContainer = Color(0xFF2C3426),
    onSecondaryContainer = Color(0xFFC7D2BB),
    tertiary = Color(0xFFD8B45E),
    onTertiary = Color(0xFF2A1F08),
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
fun QuroTheme(darkOverride: Boolean? = null, content: @Composable () -> Unit) {
    val dark = darkOverride ?: isSystemInDarkTheme()
    val scheme = if (dark) QuroDarkColorScheme else QuroLightColorScheme
    val brush = if (dark) QuroPaperBrushDark else QuroPaperBrushLight
    Box(Modifier.fillMaxSize().background(brush)) {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
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

@Composable
fun QuroSettingsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = QuroIOSScheme, content = content)
}

// ---- 聊天 UI 配色别名 ----
val Accent = QuroTerracotta
val AccentPress = QuroTerracottaPress
val AccentSoft = QuroTerracottaSoft
val Card = QuroCard
val CardD = Color(0xFF24201B)
val Ink = QuroInk
val InkD = Color(0xFFEDE8DF)
val InkSoft = QuroInkSoft
val Line = QuroLine
val Line2 = QuroLine2
val Muted = QuroMuted
val Paper = QuroPaper
val Sage = QuroSage

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

package com.ai.assistance.quro.genui.sdk.style

import androidx.compose.ui.graphics.Color

/**
 * GenUI 颜色方案 —— ZorvAI 暖纸/陶土体系（20 个基础角色 + 语义扩展角色）
 *
 * 设计基准：与宿主 ZorvAI 的 `QuroTheme` 同源，见 [ZorvPalette]。
 * 原为 Material3 默认紫（#6750A4），与 App 本体割裂，生成界面像「贴进来的外来 UI」——
 * 现已整体替换为陶土/纸/墨体系，并补齐语义色（成功/警告/信息/涨跌）与纸感表面层叠。
 *
 * 向后兼容：前 20 个字段顺序与语义保持不变，新增角色一律带默认值。
 */
data class GenUIColorScheme(
    // ---- 以下 20 个为原始角色，顺序不可变（外部可能按位置构造）----
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceContainer: Color,
    val error: Color,
    val onError: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,

    // ---- 扩展：container / 反色（补齐语义，避免组件回落 MaterialTheme）----
    val onSecondaryContainer: Color = onSecondary,
    val secondaryContainer: Color = surfaceVariant,
    val onTertiaryContainer: Color = onTertiary,
    val tertiaryContainer: Color = primaryContainer,
    val errorContainer: Color = error,
    val onErrorContainer: Color = onError,
    val inverseSurface: Color = onSurface,
    val inverseOnSurface: Color = surface,
    val inversePrimary: Color = primary,
    val surfaceTint: Color = primary,

    // ---- 扩展：纸感表面层叠（Lowest 最亮，Highest 最暗）----
    val surfaceContainerLowest: Color = surface,
    val surfaceContainerLow: Color = surface,
    val surfaceContainerHigh: Color = surfaceVariant,
    val surfaceContainerHighest: Color = surfaceVariant,

    // ---- 扩展：语义色（AI 可直接用 role 名取色，不必写死十六进制）----
    val success: Color = primary,
    val onSuccess: Color = onPrimary,
    val successContainer: Color = primaryContainer,
    val onSuccessContainer: Color = onPrimaryContainer,
    val warning: Color = tertiary,
    val onWarning: Color = onTertiary,
    val warningContainer: Color = primaryContainer,
    val onWarningContainer: Color = onPrimaryContainer,
    val info: Color = secondary,
    val onInfo: Color = onSecondary,
    val infoContainer: Color = secondaryContainer,
    val onInfoContainer: Color = onSecondaryContainer,
    /** 涨：中国股市习惯用红 */
    val rise: Color = error,
    /** 跌：中国股市习惯用绿 */
    val fall: Color = success,
    /** 品牌点缀金 */
    val gold: Color = tertiary
) {
    companion object {

        /** 亮色：纸感底 + 白卡 + 陶土强调 */
        val Light = GenUIColorScheme(
            primary = ZorvPalette.Terracotta,
            onPrimary = Color.White,
            primaryContainer = ZorvPalette.TerracottaSoft,
            onPrimaryContainer = ZorvPalette.TerracottaPress,
            secondary = ZorvPalette.Sage,
            onSecondary = Color.White,
            tertiary = ZorvPalette.Gold,
            onTertiary = Color.White,
            background = ZorvPalette.Paper,
            onBackground = ZorvPalette.Ink,
            surface = ZorvPalette.Card,
            onSurface = ZorvPalette.Ink,
            surfaceVariant = ZorvPalette.Paper2,
            onSurfaceVariant = ZorvPalette.InkSoft,
            surfaceContainer = ZorvPalette.Paper,
            error = ZorvPalette.ErrorWarm,
            onError = Color.White,
            outline = ZorvPalette.Line,
            outlineVariant = ZorvPalette.LineSoft,
            scrim = Color(0xFF000000),

            secondaryContainer = ZorvPalette.SageSoft,
            onSecondaryContainer = Color(0xFF3E4A36),
            tertiaryContainer = ZorvPalette.GoldSoft,
            onTertiaryContainer = ZorvPalette.GoldPress,
            errorContainer = ZorvPalette.ErrorSoft,
            onErrorContainer = Color(0xFF7A2417),
            inverseSurface = ZorvPalette.Ink,
            inverseOnSurface = ZorvPalette.PaperBright,
            inversePrimary = ZorvPalette.TerracottaDark,
            surfaceTint = ZorvPalette.Terracotta,

            surfaceContainerLowest = Color.White,
            surfaceContainerLow = ZorvPalette.PaperBright,
            surfaceContainerHigh = ZorvPalette.Paper2,
            surfaceContainerHighest = ZorvPalette.Paper3,

            success = ZorvPalette.Success,
            onSuccess = Color.White,
            successContainer = ZorvPalette.SuccessSoft,
            onSuccessContainer = Color(0xFF2F4A29),
            warning = ZorvPalette.Warning,
            onWarning = Color.White,
            warningContainer = ZorvPalette.WarningSoft,
            onWarningContainer = Color(0xFF5E4410),
            info = ZorvPalette.Info,
            onInfo = Color.White,
            infoContainer = ZorvPalette.InfoSoft,
            onInfoContainer = Color(0xFF24455A),
            rise = ZorvPalette.Rise,
            fall = ZorvPalette.Fall,
            gold = ZorvPalette.Gold
        )

        /** 暗色：暖近黑底 + 浅陶土强调（不是纯黑，保留纸的温度） */
        val Dark = GenUIColorScheme(
            primary = ZorvPalette.TerracottaDark,
            onPrimary = ZorvPalette.TerracottaDarkOn,
            primaryContainer = ZorvPalette.TerracottaDarkContainer,
            onPrimaryContainer = ZorvPalette.TerracottaSoft,
            secondary = ZorvPalette.SageDark,
            onSecondary = ZorvPalette.SageDarkOn,
            tertiary = ZorvPalette.GoldDark,
            onTertiary = ZorvPalette.GoldDarkOn,
            background = ZorvPalette.BrandBackgroundDark,
            onBackground = ZorvPalette.OnDark,
            surface = ZorvPalette.SurfaceDark,
            onSurface = ZorvPalette.OnDark,
            surfaceVariant = ZorvPalette.ContainerDark,
            onSurfaceVariant = ZorvPalette.OnDarkSoft,
            surfaceContainer = ZorvPalette.SurfaceDark,
            error = ZorvPalette.ErrorDark,
            onError = ZorvPalette.ErrorDarkOn,
            outline = ZorvPalette.LineDark,
            outlineVariant = ZorvPalette.LineSoftDark,
            scrim = Color(0xFF000000),

            secondaryContainer = ZorvPalette.SageDarkContainer,
            onSecondaryContainer = Color(0xFFC7D2BB),
            tertiaryContainer = ZorvPalette.GoldDarkContainer,
            onTertiaryContainer = Color(0xFFF0DFAE),
            errorContainer = ZorvPalette.ErrorDarkContainer,
            onErrorContainer = Color(0xFFF2C9C0),
            inverseSurface = ZorvPalette.OnDark,
            inverseOnSurface = ZorvPalette.BrandBackgroundDark,
            inversePrimary = ZorvPalette.Terracotta,
            surfaceTint = ZorvPalette.TerracottaDark,

            surfaceContainerLowest = ZorvPalette.SurfaceLowestDark,
            surfaceContainerLow = ZorvPalette.SurfaceLowDark,
            surfaceContainerHigh = ZorvPalette.SurfaceHighDark,
            surfaceContainerHighest = ZorvPalette.SurfaceHighestDark,

            success = ZorvPalette.SuccessDark,
            onSuccess = ZorvPalette.SuccessDarkOn,
            successContainer = ZorvPalette.SuccessDarkContainer,
            onSuccessContainer = ZorvPalette.SuccessDarkOn,
            warning = ZorvPalette.WarningDark,
            onWarning = ZorvPalette.WarningDarkOn,
            warningContainer = ZorvPalette.WarningDarkContainer,
            onWarningContainer = ZorvPalette.WarningDarkOn,
            info = ZorvPalette.InfoDark,
            onInfo = ZorvPalette.InfoDarkOn,
            infoContainer = ZorvPalette.InfoDarkContainer,
            onInfoContainer = ZorvPalette.InfoDarkOn,
            rise = ZorvPalette.RiseDark,
            fall = ZorvPalette.FallDark,
            gold = ZorvPalette.GoldDark
        )
    }

    /**
     * 语义取色：把 AI 写的「语义词」翻译成本主题颜色。
     * 组件内部一律走这里，不再硬编码十六进制（换肤/暗色自动生效）。
     *
     * 支持：primary/secondary/tertiary/gold、success/warning/info/error、
     * rise/fall（涨红跌绿）、surface 系列、on* 系列。
     */
    fun semantic(role: String?): Color? = when (role?.trim()?.lowercase()?.replace("_", "")) {
        null, "" -> null
        "primary", "brand", "accent" -> primary
        "onprimary" -> onPrimary
        "primarycontainer" -> primaryContainer
        "secondary" -> secondary
        "onsecondary" -> onSecondary
        "secondarycontainer" -> secondaryContainer
        "tertiary" -> tertiary
        "ontertiary" -> onTertiary
        "gold" -> gold
        "success", "ok", "positive" -> success
        "onsuccess" -> onSuccess
        "successcontainer" -> successContainer
        "warning", "warn", "caution" -> warning
        "onwarning" -> onWarning
        "warningcontainer" -> warningContainer
        "info", "notice" -> info
        "oninfo" -> onInfo
        "infocontainer" -> infoContainer
        "error", "danger", "dangerous", "critical" -> error
        "onerror" -> onError
        "errorcontainer" -> errorContainer
        "rise", "up", "bull" -> rise
        "fall", "down", "bear" -> fall
        "background", "bg" -> background
        "onbackground" -> onBackground
        "surface" -> surface
        "onsurface" -> onSurface
        "surfacevariant" -> surfaceVariant
        "onsurfacevariant" -> onSurfaceVariant
        "surfacecontainer", "container" -> surfaceContainer
        "surfacecontainerlowest" -> surfaceContainerLowest
        "surfacecontainerlow" -> surfaceContainerLow
        "surfacecontainerhigh" -> surfaceContainerHigh
        "surfacecontainerhighest" -> surfaceContainerHighest
        "outline", "border", "line" -> outline
        "outlinevariant" -> outlineVariant
        "muted", "subtle", "placeholder" -> onSurfaceVariant
        "inverse" -> inverseSurface
        "inverseonsurface" -> inverseOnSurface
        else -> null
    }

    /** 语义容器底色：与 [semantic] 配对，用于淡底 + 深字的组合 */
    fun semanticContainer(role: String?): Color? = when (role?.trim()?.lowercase()?.replace("_", "")) {
        "success", "ok", "positive" -> successContainer
        "warning", "warn", "caution" -> warningContainer
        "info", "notice" -> infoContainer
        "error", "danger", "dangerous", "critical" -> errorContainer
        "primary", "brand", "accent" -> primaryContainer
        "secondary" -> secondaryContainer
        "tertiary", "gold" -> tertiaryContainer
        else -> null
    }
}

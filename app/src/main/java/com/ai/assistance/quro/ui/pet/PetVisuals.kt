package com.ai.assistance.quro.ui.pet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.pet.PetScreenBrain

/**
 * 桌宠悬浮层的统一视觉语言。
 *
 * 旧实现混用了系统 AlertDialog、emoji 菜单项（💬 🎭 ⇄ ✖）、半透明灰底状态条、
 * 纯白 LinearLayout 输入框和 Material 默认圆角气泡 —— 风格彼此冲突且缺乏精度。
 * 这里统一为：深墨蓝纯色底 + 单一青蓝强调 + 1.5dp 细描边 + 24 网格矢量图标，
 * 不使用 emoji、不使用渐变滥用、不使用系统默认控件。
 */
object PetVisuals {
    val Ink = Color(0xFF111A2B)
    val InkRaised = Color(0xFF1B2740)
    val Line = Color(0xFF31405C)
    val LineSoft = Color(0xFF243350)
    val Accent = Color(0xFF86ACFF)
    val AccentDim = Color(0xFF2A4270)
    val Violet = Color(0xFFB79BFF)
    val On = Color(0xFFF2F6FF)
    val Muted = Color(0xFF94A5C2)
    val Danger = Color(0xFFFF9488)
    val Success = Color(0xFF7FDEB2)

    val CardShape = RoundedCornerShape(18.dp)
    val PillShape = RoundedCornerShape(999.dp)
    val FieldShape = RoundedCornerShape(14.dp)

    val TitleStyle = TextStyle(color = On, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val BodyStyle = TextStyle(color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Normal)
}

/** 24dp 网格的精确矢量图标，取代此前所有 emoji 图标。 */
enum class PetGlyph {
    CHAT, KEYBOARD, EYE, SCREEN, SPARK, MODE, OPEN, CLOSE, SEND, TASK, CHECK, ARROW, SETTINGS, DRAG,
    TRASH, PAW,
    ;

    companion object {
        /**
         * 宠物包 interaction.icon 的语义 key → 矢量图标。
         * 兼容旧包里残留的 emoji（一律回退到 SPARK），保证任何导入包都不会显示 emoji 图标。
         */
        fun fromIcon(icon: String?): PetGlyph = when (icon?.trim()?.lowercase()) {
            "wave", "hi", "hello" -> CHAT
            "hug", "comfort" -> PAW
            "joke", "laugh" -> SPARK
            "idea", "knowledge", "tip" -> SETTINGS
            "music", "sing" -> SPARK
            "eat", "food" -> TASK
            "sleep", "rest" -> MODE
            "wake", "sun" -> SPARK
            "wallet", "money" -> TASK
            "work", "computer" -> SCREEN
            "celebrate", "party" -> CHECK
            "pet", "paw" -> PAW
            "screen", "look" -> EYE
            "task", "todo" -> TASK
            "chat", "talk" -> CHAT
            else -> SPARK
        }
    }
}

@Composable
fun PetIcon(
    glyph: PetGlyph,
    modifier: Modifier = Modifier.size(20.dp),
    tint: Color = PetVisuals.Accent,
) {
    Canvas(modifier = modifier) {
        val s = size.width.coerceAtMost(size.height)
        val u = s / 24f
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round)
        when (glyph) {
            PetGlyph.CHAT -> {
                drawRoundRect(
                    color = tint, topLeft = Offset(3f * u, 4f * u),
                    size = Size(18f * u, 13f * u), cornerRadius = CornerRadius(4f * u),
                    style = stroke,
                )
                drawPath(Path().apply {
                    moveTo(8f * u, 17f * u); lineTo(7f * u, 21f * u); lineTo(12f * u, 17f * u)
                }, color = tint, style = stroke)
                drawLine(tint, Offset(7.5f * u, 10.5f * u), Offset(16.5f * u, 10.5f * u), 1.8f * u, StrokeCap.Round)
            }
            PetGlyph.KEYBOARD -> {
                drawRoundRect(
                    color = tint, topLeft = Offset(2.5f * u, 6f * u),
                    size = Size(19f * u, 12f * u), cornerRadius = CornerRadius(3f * u),
                    style = stroke,
                )
                for (r in 0..1) for (c in 0..3) {
                    drawCircle(tint, radius = 0.85f * u, center = Offset((6f + c * 3.3f) * u, (9.3f + r * 3f) * u))
                }
                drawLine(tint, Offset(8f * u, 15.5f * u), Offset(16f * u, 15.5f * u), 1.6f * u, StrokeCap.Round)
            }
            PetGlyph.EYE -> {
                drawPath(Path().apply {
                    moveTo(2.5f * u, 12f * u)
                    quadraticBezierTo(12f * u, 2.5f * u, 21.5f * u, 12f * u)
                    quadraticBezierTo(12f * u, 21.5f * u, 2.5f * u, 12f * u)
                    close()
                }, color = tint, style = stroke)
                drawCircle(tint, radius = 3.1f * u, center = Offset(12f * u, 12f * u), style = stroke)
                drawCircle(tint, radius = 1.15f * u, center = Offset(12f * u, 12f * u))
            }
            PetGlyph.SCREEN -> {
                drawRoundRect(
                    color = tint, topLeft = Offset(3f * u, 3.5f * u),
                    size = Size(18f * u, 13f * u), cornerRadius = CornerRadius(2.5f * u),
                    style = stroke,
                )
                drawLine(tint, Offset(12f * u, 16.5f * u), Offset(12f * u, 20f * u), 1.8f * u, StrokeCap.Round)
                drawLine(tint, Offset(8f * u, 20f * u), Offset(16f * u, 20f * u), 1.8f * u, StrokeCap.Round)
                drawLine(tint, Offset(6.5f * u, 8f * u), Offset(13f * u, 8f * u), 1.5f * u, StrokeCap.Round)
            }
            PetGlyph.SPARK -> {
                drawPath(Path().apply {
                    moveTo(12f * u, 2.8f * u)
                    quadraticBezierTo(13.4f * u, 10.6f * u, 21.2f * u, 12f * u)
                    quadraticBezierTo(13.4f * u, 13.4f * u, 12f * u, 21.2f * u)
                    quadraticBezierTo(10.6f * u, 13.4f * u, 2.8f * u, 12f * u)
                    quadraticBezierTo(10.6f * u, 10.6f * u, 12f * u, 2.8f * u)
                    close()
                }, color = tint, style = stroke)
                drawCircle(tint, radius = 1.2f * u, center = Offset(19.2f * u, 4.8f * u))
                drawCircle(tint, radius = 0.9f * u, center = Offset(4.6f * u, 19.2f * u))
            }
            PetGlyph.MODE -> {
                drawLine(tint, Offset(3.5f * u, 9f * u), Offset(20.5f * u, 9f * u), 1.8f * u, StrokeCap.Round)
                drawLine(tint, Offset(3.5f * u, 15f * u), Offset(20.5f * u, 15f * u), 1.8f * u, StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(18f * u, 6.5f * u); lineTo(21f * u, 9f * u); lineTo(18f * u, 11.5f * u)
                }, color = tint, style = stroke)
                drawPath(Path().apply {
                    moveTo(6f * u, 12.5f * u); lineTo(3f * u, 15f * u); lineTo(6f * u, 17.5f * u)
                }, color = tint, style = stroke)
            }
            PetGlyph.OPEN -> {
                drawRoundRect(
                    color = tint, topLeft = Offset(4f * u, 4f * u),
                    size = Size(16f * u, 16f * u), cornerRadius = CornerRadius(3.5f * u),
                    style = stroke,
                )
                drawLine(tint, Offset(11f * u, 13f * u), Offset(19f * u, 5f * u), 1.8f * u, StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(14.5f * u, 5f * u); lineTo(19f * u, 5f * u); lineTo(19f * u, 9.5f * u)
                }, color = tint, style = stroke)
            }
            PetGlyph.CLOSE -> {
                drawLine(tint, Offset(6f * u, 6f * u), Offset(18f * u, 18f * u), 1.8f * u, StrokeCap.Round)
                drawLine(tint, Offset(18f * u, 6f * u), Offset(6f * u, 18f * u), 1.8f * u, StrokeCap.Round)
            }
            PetGlyph.SEND -> {
                drawPath(Path().apply {
                    moveTo(3f * u, 12f * u); lineTo(21f * u, 3.5f * u)
                    lineTo(14.5f * u, 21f * u); lineTo(11f * u, 13.6f * u); close()
                }, color = tint, style = stroke)
                drawLine(tint, Offset(11f * u, 13.6f * u), Offset(21f * u, 3.5f * u), 1.6f * u, StrokeCap.Round)
            }
            PetGlyph.TASK -> {
                drawRoundRect(
                    color = tint, topLeft = Offset(4f * u, 3.5f * u),
                    size = Size(16f * u, 17f * u), cornerRadius = CornerRadius(3f * u),
                    style = stroke,
                )
                drawPath(Path().apply {
                    moveTo(8f * u, 11.8f * u); lineTo(11f * u, 14.8f * u); lineTo(16.3f * u, 8.8f * u)
                }, color = tint, style = stroke)
            }
            PetGlyph.CHECK -> drawPath(Path().apply {
                moveTo(4f * u, 12.5f * u); lineTo(10f * u, 18f * u); lineTo(20f * u, 6f * u)
            }, color = tint, style = stroke)
            PetGlyph.ARROW -> {
                drawLine(tint, Offset(4f * u, 12f * u), Offset(19f * u, 12f * u), 1.8f * u, StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(15f * u, 8f * u); lineTo(19f * u, 12f * u); lineTo(15f * u, 16f * u)
                }, color = tint, style = stroke)
            }
            PetGlyph.SETTINGS -> {
                drawCircle(tint, radius = 4.2f * u, center = Offset(12f * u, 12f * u), style = stroke)
                drawCircle(tint, radius = 8.2f * u, center = Offset(12f * u, 12f * u), style = stroke)
                for (i in 0 until 6) {
                    val a = Math.toRadians(i * 60.0)
                    val c = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
                    drawLine(
                        tint, Offset((12f + c * 8.2f) * u, (12f + sn * 8.2f) * u),
                        Offset((12f + c * 11f) * u, (12f + sn * 11f) * u), 1.8f * u, StrokeCap.Round,
                    )
                }
            }
            PetGlyph.DRAG -> {
                for (r in 0..2) for (c in 0..1) {
                    drawCircle(tint, radius = 1.35f * u, center = Offset((9f + c * 6f) * u, (6f + r * 6f) * u))
                }
            }
            PetGlyph.TRASH -> {
                drawLine(tint, Offset(4f * u, 6.5f * u), Offset(20f * u, 6.5f * u), 1.8f * u, StrokeCap.Round)
                drawLine(tint, Offset(9.5f * u, 6.5f * u), Offset(9.5f * u, 4.5f * u), 1.8f * u, StrokeCap.Round)
                drawLine(tint, Offset(14.5f * u, 6.5f * u), Offset(14.5f * u, 4.5f * u), 1.8f * u, StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(6f * u, 6.5f * u); lineTo(7f * u, 20f * u)
                    lineTo(17f * u, 20f * u); lineTo(18f * u, 6.5f * u)
                }, color = tint, style = stroke)
                drawLine(tint, Offset(10.5f * u, 10f * u), Offset(10.5f * u, 16.5f * u), 1.5f * u, StrokeCap.Round)
                drawLine(tint, Offset(13.5f * u, 10f * u), Offset(13.5f * u, 16.5f * u), 1.5f * u, StrokeCap.Round)
            }
            PetGlyph.PAW -> {
                drawCircle(tint, radius = 2.1f * u, center = Offset(7f * u, 8.5f * u))
                drawCircle(tint, radius = 2.1f * u, center = Offset(12f * u, 6.8f * u))
                drawCircle(tint, radius = 2.1f * u, center = Offset(17f * u, 8.5f * u))
                drawPath(Path().apply {
                    moveTo(12f * u, 11f * u)
                    quadraticBezierTo(5.5f * u, 12.5f * u, 7.5f * u, 17.5f * u)
                    quadraticBezierTo(12f * u, 21f * u, 16.5f * u, 17.5f * u)
                    quadraticBezierTo(18.5f * u, 12.5f * u, 12f * u, 11f * u)
                    close()
                }, color = tint)
            }
        }
    }
}

/** 细描边胶囊气泡：取代 Material Surface 默认圆角与半透明灰底。 */
@Composable
fun PetBubble(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = PetVisuals.Accent,
    tail: Boolean = true,
) {
    if (text.isBlank()) return
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .background(PetVisuals.Ink.copy(alpha = 0.96f), PetVisuals.PillShape)
                .border(1.dp, PetVisuals.Line, PetVisuals.PillShape)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(accent, CircleShape)
                )
                Spacer(Modifier.width(7.dp))
                Text(text, color = PetVisuals.On, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 3)
            }
        }
        if (tail) {
            Canvas(Modifier.size(10.dp, 6.dp)) {
                drawPath(Path().apply {
                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
                }, color = PetVisuals.Ink.copy(alpha = 0.96f))
                drawPath(Path().apply {
                    moveTo(0f, 0f); lineTo(size.width / 2f, size.height); lineTo(size.width, 0f)
                }, color = PetVisuals.Line, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
            }
        }
    }
}

/** 状态小胶囊：取代原来的 Color(0xFF555555) 半透明灰条。 */
@Composable
fun PetStatusPill(text: String, modifier: Modifier = Modifier, busy: Boolean = false) {
    if (text.isBlank()) return
    Row(
        modifier = modifier
            .background(PetVisuals.InkRaised, PetVisuals.PillShape)
            .border(1.dp, PetVisuals.LineSoft, PetVisuals.PillShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(if (busy) PetVisuals.Violet else PetVisuals.Success, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = PetVisuals.Muted, fontSize = 10.sp, maxLines = 1)
    }
}

data class PetMenuItem(
    val glyph: PetGlyph,
    val title: String,
    val subtitle: String = "",
    val accent: Color = PetVisuals.Accent,
    val id: String,
)

/** 卡片式快捷菜单：取代系统 AlertDialog + emoji 条目。 */
@Composable
fun PetQuickMenu(
    items: List<PetMenuItem>,
    onSelect: (PetMenuItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(228.dp)
            .background(PetVisuals.Ink, PetVisuals.CardShape)
            .border(1.dp, PetVisuals.Line, PetVisuals.CardShape)
            .padding(8.dp),
    ) {
        // 顶部收起条：长按展开后卡片会一直挂在桌面上，此前只能随便选一项（或直接关掉
        // 整个桌宠）才能让它消失。这里给一个明确的收起按钮，与输入框面板的 X 视觉一致。
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PetIcon(PetGlyph.PAW, Modifier.size(15.dp), PetVisuals.Accent)
            Spacer(Modifier.width(7.dp))
            Text("桌宠菜单", color = PetVisuals.On, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(26.dp)
                    .background(PetVisuals.InkRaised, CircleShape)
                    .border(1.dp, PetVisuals.LineSoft, CircleShape)
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center,
            ) { PetIcon(PetGlyph.CLOSE, Modifier.size(13.dp), PetVisuals.Muted) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(PetVisuals.LineSoft))
        Spacer(Modifier.height(4.dp))
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (index == 0) PetVisuals.AccentDim.copy(alpha = 0.30f) else Color.Transparent,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(item) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(PetVisuals.InkRaised, RoundedCornerShape(9.dp))
                        .border(1.dp, PetVisuals.LineSoft, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) { PetIcon(item.glyph, Modifier.size(16.dp), item.accent) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = PetVisuals.On, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (item.subtitle.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(item.subtitle, color = PetVisuals.Muted, fontSize = 10.sp, maxLines = 1)
                    }
                }
                PetIcon(PetGlyph.ARROW, Modifier.size(13.dp), PetVisuals.Muted.copy(alpha = 0.6f))
            }
        }
    }
}

/** 悬浮输入框：直接长在桌宠悬浮窗里，不需要独立 Activity（也因此不需要动 Manifest）。 */
@Composable
fun PetInputPanel(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScreen: () -> Unit,
    onTask: () -> Unit,
    onOpenChat: () -> Unit,
    onClose: () -> Unit,
    status: String? = null,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Column(
        modifier = modifier
            .width(268.dp)
            .background(PetVisuals.Ink, PetVisuals.CardShape)
            .border(1.dp, PetVisuals.Line, PetVisuals.CardShape)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PetIcon(PetGlyph.KEYBOARD, Modifier.size(15.dp), PetVisuals.Accent)
            Spacer(Modifier.width(7.dp))
            Text("对桌宠说", color = PetVisuals.On, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(24.dp).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { PetIcon(PetGlyph.CLOSE, Modifier.size(14.dp), PetVisuals.Muted) }
        }
        AnimatedVisibility(visible = !status.isNullOrBlank(), enter = fadeIn(), exit = fadeOut()) {
            PetStatusPill(status.orEmpty(), Modifier.padding(top = 8.dp), busy = busy)
        }
        Spacer(Modifier.height(9.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PetVisuals.InkRaised, PetVisuals.FieldShape)
                .border(1.dp, PetVisuals.LineSoft, PetVisuals.FieldShape)
                .padding(start = 11.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                textStyle = TextStyle(color = PetVisuals.On, fontSize = 12.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text("说点什么，或者直接让它看屏幕…", color = PetVisuals.Muted, fontSize = 11.sp)
                    inner()
                },
            )
            Box(
                Modifier
                    .size(30.dp)
                    .background(
                        if (value.isBlank()) PetVisuals.LineSoft else PetVisuals.Accent,
                        CircleShape,
                    )
                    .clickable(enabled = value.isNotBlank()) { onSubmit() },
                contentAlignment = Alignment.Center,
            ) {
                PetIcon(
                    PetGlyph.SEND, Modifier.size(15.dp),
                    if (value.isBlank()) PetVisuals.Muted else PetVisuals.Ink,
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PetChip(PetGlyph.EYE, "看屏幕", onScreen, Modifier.weight(1f))
            PetChip(PetGlyph.TASK, "做任务", onTask, Modifier.weight(1f))
            PetChip(PetGlyph.OPEN, "对话", onOpenChat, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PetChip(glyph: PetGlyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(PetVisuals.InkRaised, PetVisuals.PillShape)
            .border(1.dp, PetVisuals.LineSoft, PetVisuals.PillShape)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PetIcon(glyph, Modifier.size(13.dp), PetVisuals.Accent)
        Spacer(Modifier.width(4.dp))
        Text(label, color = PetVisuals.On, fontSize = 10.sp, maxLines = 1)
    }
}

/** 屏幕观察结果的极简状态标签，用于桌宠上方提示（不进对话框）。 */
@Composable
fun PetScreenBadge(mode: PetScreenBrain.Mode?, modifier: Modifier = Modifier) {
    val label = when (mode) {
        PetScreenBrain.Mode.VIRTUAL -> "后台虚拟屏"
        PetScreenBrain.Mode.VISION -> "已截图"
        PetScreenBrain.Mode.ACCESSIBILITY -> "已读屏"
        else -> "屏幕分析"
    }
    Row(
        modifier = modifier
            .background(PetVisuals.AccentDim.copy(alpha = 0.55f), PetVisuals.PillShape)
            .border(1.dp, PetVisuals.Accent.copy(alpha = 0.35f), PetVisuals.PillShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PetIcon(PetGlyph.EYE, Modifier.size(12.dp), PetVisuals.Accent)
        Spacer(Modifier.width(5.dp))
        Text(label, color = PetVisuals.On, fontSize = 10.sp)
    }
}

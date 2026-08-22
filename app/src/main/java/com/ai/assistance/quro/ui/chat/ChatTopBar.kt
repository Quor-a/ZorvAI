package com.ai.assistance.quro.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.ui.AvatarContent
import com.ai.assistance.quro.ui.data.Persona
import com.ai.assistance.quro.ui.icons.LucideIcon
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted

/**
 * 对话页顶栏（从 ChatScreen.kt 抽出，API 与行为保持不变）。
 *
 * ## 布局塌陷根因与修复（#问题1）
 * Compose 的 [Row] 先测量所有【没有 weight】的子项（按声明顺序、逐个扣减剩余宽度），
 * 再把剩下的空间按比例分给带 weight 的子项。因此只要中间存在一个**无约束的可变长文本**
 * （模型名 / 人格名），它会吃满整行宽度，导致排在它之后的固定控件（设置图标）拿到
 * `maxWidth = 0` —— 注意不是"被推出屏幕外"，而是真的被测量成 0 宽，任何
 * `horizontalScroll` / `clipToBounds` 都救不回来。
 *
 * 修复策略（三条同时成立才安全）：
 * 1. 两端常驻的图标按钮**不带 weight**且显式 [Modifier.size]，保证在"非 weight 阶段"先拿到真实宽度；
 * 2. 中间可伸缩区域整体套一层 `Modifier.weight(1f)` 的容器，吃掉全部剩余空间（fill = true，
 *    避免行尾出现空隙把设置图标从右边缘挤开）；
 * 3. 容器内部的每个 chip 再各自 `weight(1f, fill = false)`，chip 内部的文本同样带 weight +
 *    `maxLines = 1` + [TextOverflow.Ellipsis]，使 chip 里的圆点/箭头等固定件也不会被文本吃掉。
 */
@Composable
internal fun ChatTopBar(
    modelName: String,
    onMenu: () -> Unit,
    onModel: () -> Unit,
    onSettings: () -> Unit,
    persona: Persona? = null,
    onPick: () -> Unit = {},
    scaled: (Int) -> TextUnit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(cs.background)
            // 图标热区由 40dp 提到 48dp（无障碍最小点击区），上下 padding 各减 4dp，整体高度保持不变
            .padding(top = 16.dp, bottom = 8.dp, start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ① 左侧固定：历史抽屉。无 weight → 先被测量，永远拿得到 48dp
        IconButton(onClick = onMenu, modifier = Modifier.size(TOP_BAR_TOUCH)) {
            LucideIcon("panel_left", "对话历史", Modifier.size(22.dp), tint = cs.onBackground)
        }
        // ② 中间弹性区：吃满剩余空间。有人格时两端分布（人格靠左、模型靠右），无人格时模型整体靠右
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = if (persona != null) Arrangement.SpaceBetween else Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (persona != null) {
                PersonaChip(
                    persona = persona,
                    onPick = onPick,
                    scaled = scaled,
                    // fill = false：人格名短时不硬撑，最多占中间区一半，绝不吃掉模型 chip
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
            }
            ModelChip(
                modelName = modelName,
                onModel = onModel,
                scaled = scaled,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        // ③ 右侧固定：终端 + 设置。无 weight → 与 ① 同批被测量，长模型名/长人格名都挤不掉它
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onSettings, modifier = Modifier.size(TOP_BAR_TOUCH)) {
            LucideIcon("settings", "设置", Modifier.size(21.dp), tint = cs.onBackground)
        }
    }
}

/** 顶栏图标按钮的点击热区（≥48dp，满足无障碍最小触控目标）。 */
private val TOP_BAR_TOUCH = 48.dp

/** 顶栏 chip 的最小高度（保证胶囊本身的点击热区不至于过小）。 */
private val CHIP_MIN_HEIGHT = 36.dp

/**
 * 模型 chip：圆点 + 模型名 + 下拉箭头。
 *
 * 圆点与箭头是固定尺寸且**不带 weight**，模型名带 `weight(1f, fill = false)`，
 * 因此长模型名只会自身省略号截断，不会把箭头压成 0 宽。
 */
@Composable
private fun ModelChip(
    modelName: String,
    onModel: () -> Unit,
    scaled: (Int) -> TextUnit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .heightIn(min = CHIP_MIN_HEIGHT)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, Line, RoundedCornerShape(999.dp))
            .clickable(onClickLabel = "切换模型", onClick = onModel)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Accent))
        Spacer(Modifier.width(7.dp))
        Text(
            modelName,
            fontSize = scaled(13),
            color = cs.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        LucideIcon("chevron_down", null, Modifier.size(15.dp), tint = Muted)
    }
}

/**
 * 人格（灵魂卡）chip：头像 + 人格名 + 下拉箭头。
 *
 * 与 [ModelChip] 同构：头像/箭头固定尺寸不带 weight，人格名带 weight 并省略号截断。
 * 修复了原实现中"长人格名把顶栏后面的模型 chip 与设置图标一起压成 0 宽"的隐患。
 */
@Composable
private fun PersonaChip(
    persona: Persona,
    onPick: () -> Unit,
    scaled: (Int) -> TextUnit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .heightIn(min = CHIP_MIN_HEIGHT)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, Line, RoundedCornerShape(999.dp))
            .clickable(onClickLabel = "切换人格", onClick = onPick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarContent(persona.avatarUri, persona.name, 20)
        Spacer(Modifier.width(8.dp))
        Text(
            persona.name,
            fontSize = scaled(13),
            color = cs.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        LucideIcon("chevron_down", null, Modifier.size(15.dp), tint = Muted)
    }
}

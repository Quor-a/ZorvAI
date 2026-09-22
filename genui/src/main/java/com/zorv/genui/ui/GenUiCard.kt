package com.zorv.genui.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import com.zorv.genui.controller.CardStatus
import com.zorv.genui.controller.GenUiController

/**
 * 对话框动态 UI 组件（#631）—— 全链路最关键的"缺失环节"。
 *
 * 把 [GenUiController] 持有的 WebView 卡片嵌进聊天列表的 Compose 项里：
 *  - [AndroidView] 提供容器 [ViewGroup]，首帧绑定时调用 [GenUiController.bind] 挂载卡片；
 *  - 项滚出视口 / 列表回收时 [GenUiController.releaseCard] 自动释放 WebView 回池；
 *  - 观察 [GenUiController.cardStatus] 驱动骨架屏 / 错误条 / 降级代码块覆盖层。
 *
 * 用法（聊天列表项内）：
 *   GenUiCard(artifactId = ref.id, controller = genUi)
 */
@Composable
fun GenUiCard(
    artifactId: String,
    controller: GenUiController,
    modifier: Modifier = Modifier
) {
    val statusMap by controller.cardStatus.collectAsState()
    val status = statusMap[artifactId]
    val bound = remember(artifactId) { mutableStateOf(false) }

    Box(modifier.fillMaxWidth()) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            },
            update = { view ->
                if (!bound.value) {
                    bound.value = true
                    controller.bind(artifactId, view as ViewGroup)
                }
            },
            onRelease = { controller.releaseCard(artifactId) }
        )

        when (status) {
            is CardStatus.Loading -> CardSkeleton()
            is CardStatus.Error -> ErrorBar(status.message)
            is CardStatus.Degraded -> DegradedCard(artifactId, controller, status.reason)
            else -> { /* Ready：WebView 自身已渲染，无需覆盖层 */ }
        }
    }
}

/** 降级：展示原因 + 原始代码块（用户可复制交 AI 重做） */
@Composable
private fun DegradedCard(artifactId: String, controller: GenUiController, reason: String) {
    val code = controller.getCode(artifactId).orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Text(
            text = "⚠️ 该卡片已降级：$reason",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = code.ifEmpty { "（无可恢复代码）" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorBar(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = "正在修复…（$message）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 加载骨架（仅在宿主 WebView 尚未 ready 的空档显示，shell 内部也有骨架） */
@Composable
private fun CardSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(4) { i ->
            val w = listOf(0.4f, 1f, 0.85f, 0.6f)[i]
            Box(
                modifier = Modifier
                    .fillMaxWidth(w)
                    .height(14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

/** 供聊天层在"纯文本降级"场景下直接展示代码的兜底 Composable */
@Composable
fun GenUiCodeFallback(code: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}

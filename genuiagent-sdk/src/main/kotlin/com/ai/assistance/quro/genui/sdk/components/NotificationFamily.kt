package com.ai.assistance.quro.genui.sdk.components

import com.ai.assistance.quro.genui.sdk.style.ZorvPalette

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.StyleResolver

/**
 * ══════════════════════════════════════════════════════════════
 *  通知组件家族（30 类型 × 变体/效果引擎裂变 100000+）
 *
 *  按场景分组：
 *  · 基础横幅：notification_banner（可关闭横幅）
 *  · 状态条：status_toast / snackbar_view / inline_alert
 *  · 走进/走离：arrive_card / depart_card
 *  · 系统级：system_alert / priority_callout / lockscreen_widget
 *  · 消息流：message_digest / mention_ping / reply_preview
 *  · 社交：follow_alert / like_burst / comment_card / share_toast
 *  · 内容：new_post_banner / update_available / sync_status
 *  · 警示：battery_warning / storage_alert / network_lost
 *  · 后台：job_progress / upload_card / download_card / backup_state
 *  · 聚合：notification_group / digest_stack / unread_counter
 * ══════════════════════════════════════════════════════════════
 */
object NotificationFamily {
    val KINDS = listOf(
        "notification_banner", "status_toast", "snackbar_view", "inline_alert",
        "arrive_card", "depart_card",
        "system_alert", "priority_callout", "lockscreen_widget",
        "message_digest", "mention_ping", "reply_preview",
        "follow_alert", "like_burst", "comment_card", "share_toast",
        "new_post_banner", "update_available", "sync_status",
        "battery_warning", "storage_alert", "network_lost",
        "job_progress", "upload_card", "download_card", "backup_state",
        "notification_group", "digest_stack", "unread_counter",
        "dismiss_chip"
    )

    /** 严重度 → 颜色语义（可被 style 覆盖） */
    fun severityColor(sev: String?): Color = when (sev?.lowercase()) {
        "info" -> ZorvPalette.Info
        "success", "ok" -> ZorvPalette.Success
        "warning", "warn" -> ZorvPalette.Terracotta
        "error", "critical" -> ZorvPalette.ErrorWarm
        "neutral" -> ZorvPalette.Info
        else -> ZorvPalette.Terracotta
    }

    /** 图标位映射 */
    fun iconFor(kind: String, sev: String?): Any = when {
        kind.contains("battery") || kind.contains("warning") || kind.contains("storage") || sev == "warning" -> Icons.Filled.Warning
        kind.contains("sync") || kind.contains("progress") || kind.contains("upload") || kind.contains("download") || sev == "info" -> Icons.Filled.Notifications
        sev == "success" || sev == "ok" -> Icons.Filled.CheckCircle
        else -> Icons.Filled.Notifications
    }

    fun registerAll(registry: com.ai.assistance.quro.genui.sdk.render.ComponentRegistry) {
        KINDS.forEach { kind ->
            registry.register(kind) { c, ctx -> NotificationRenderer(c, ctx, kind) }
        }
    }
}

@Composable
fun NotificationRenderer(component: UIComponent, ctx: RenderContext, kind: String, modifier: Modifier = Modifier) {
    val props = component.properties
    fun str(k: String, def: String = ""): String =
        (props[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: def
    fun int(k: String, def: Int): Int =
        (props[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: def

    val title = str("title", when (kind) {
        "battery_warning" -> "电量不足"
        "network_lost" -> "网络已断开"
        "storage_alert" -> "存储空间不足"
        "update_available" -> "发现新版本"
        "sync_status" -> "同步中…"
        else -> "通知"
    })
    val message = str("message", str("text", ""))
    val sev = str("severity", when (kind) {
        "battery_warning", "network_lost", "storage_alert" -> "warning"
        "like_burst", "follow_alert" -> "success"
        "mention_ping", "reply_preview" -> "info"
        else -> "neutral"
    })
    val accent = NotificationFamily.severityColor(sev)
    val count = int("count", int("value", 1))
    val progress = (props["progress"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()

    when (kind) {
        // ── 极简角标/计数 ──
        "unread_counter", "mention_ping" -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.padding(vertical = 2.dp)
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                Text(if (count > 99) "99+" else "$count", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // ── 纤细 toast ──
        "status_toast", "share_toast", "dismiss_chip" -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clip(RoundedCornerShape(50))
                .background(ZorvPalette.Ink)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(if (message.isNotBlank()) message else title, fontSize = 12.sp, color = ZorvPalette.InfoSoft, maxLines = 1)
        }

        // ── 进度型（任务/上传/下载/同步/备份）──
        "job_progress", "upload_card", "download_card", "backup_state", "sync_status" -> Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ZorvPalette.Ink)
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ZorvPalette.InfoSoft)
                Spacer(Modifier.weight(1f))
                Text(
                    if (progress != null) "${(progress * 100).toInt()}%" else str("status", "进行中"),
                    fontSize = 11.sp, color = accent
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (progress ?: 0.4f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(6.dp)),
                color = accent,
                trackColor = ZorvPalette.LineSoft
            )
            if (message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(message, fontSize = 11.sp, color = ZorvPalette.Info, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        // ── 社交爆发（点赞心形）──
        "like_burst" -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.padding(6.dp)
        ) {
            Text("❤", fontSize = 20.sp, color = accent)
            Spacer(Modifier.width(6.dp))
            Text("$count", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ZorvPalette.InfoSoft)
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 12.sp, color = ZorvPalette.Info, maxLines = 1)
        }

        // ── 聚合堆栈 ──
        "notification_group", "digest_stack" -> Box(modifier) {
            // 层叠视觉：三层卡片错位
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp).height(8.dp).clip(RoundedCornerShape(10.dp)).background(ZorvPalette.Muted))
            Box(Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp).height(8.dp).clip(RoundedCornerShape(10.dp)).background(ZorvPalette.Info))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ZorvPalette.Ink)
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔔", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ZorvPalette.InfoSoft)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.clip(CircleShape).background(accent).padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("$count 条", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(message, fontSize = 11.sp, color = ZorvPalette.Info, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // ── 默认：标准通知卡（横幅/走进走离/系统级/社交/内容/警示 全用此壳）──
        else -> Row(
            verticalAlignment = Alignment.Top,
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ZorvPalette.Ink)
                .padding(12.dp)
        ) {
            // 左侧色条 + 图标位
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        NotificationFamily.iconFor(kind, sev) as androidx.compose.ui.graphics.vector.ImageVector,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ZorvPalette.InfoSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(message, fontSize = 12.sp, color = ZorvPalette.Info, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (str("time").isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(str("time"), fontSize = 11.sp, color = ZorvPalette.InkSoft)
                }
            }
            // 优先级角标
            if (sev == "critical" || sev == "error") {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            }
        }
    }
}

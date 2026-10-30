package com.ai.assistance.quro.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 终端失败态的统一封套（2026-10-30，范式取自 OPPO Breeno / `results.proto` 的
 * `SealedResult{resultCode, data, errorCode, message}`）：
 *
 * 为什么要它：我们此前的失败形态是「黑屏 + 一个光标」——用户拿不到任何线索，只能靠截图
 * 反推。Breeno 的做法是**每一种失败都归类成稳定错误码 + 给用户一句能照做的话 + 一个主按钮**，
 * 并且连"服务端崩了"都有专门动效（server_died_dark.json）。这里把同一范式收敛到终端：
 * 码是稳定的（可 grep / 可上报），文案是可执行的，动作是唯一的主按钮。
 *
 * `code` 会进日志，**改名等于破坏既有排查口径**，要加只加不改。
 */
enum class TerminalFailureCode(val wire: String) {
    /** 内置 Ubuntu rootfs 未就绪（未安装 / 被清 / 目录空）。 */
    ENV_NOT_READY("terminal-env-not-ready"),

    /** 会话进程已退出（用户 exit、shell 崩溃、被系统杀）。 */
    PROCESS_EXITED("terminal-process-exited"),

    /** 会话进程根本起不来（bash 缺失 / exec 失败 / PTY 分配失败）。 */
    PROCESS_START_FAILED("terminal-process-start-failed"),

    /** 未归类的异常。 */
    UNKNOWN("terminal-unknown"),
}

/**
 * 一次终端失败的完整描述。
 *
 * @param code 稳定错误码（进日志）
 * @param title 给用户看的一行结论
 * @param detail 真因（异常消息 / 退出码），不折叠技术细节——排障靠它
 * @param hint 可照做的建议（空则不显示）
 * @param exitCode 进程退出码（PROCESS_EXITED 时有值）
 */
data class TerminalFailure(
    val code: TerminalFailureCode,
    val title: String,
    val detail: String = "",
    val hint: String = "",
    val exitCode: Int? = null,
)

/**
 * 覆盖在终端画布上的失败卡。刻意**不遮挡输入栏**（只盖画布区域），
 * 让用户仍能看到最后几行输出，同时在卡片上做决策。
 */
@Composable
fun TerminalFailureOverlay(
    failure: TerminalFailure,
    onReconnect: () -> Unit,
    onCloseSession: () -> Unit,
    onOpenSetup: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xCC0B0E14)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .background(Color(0xFF141A24), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF2A3444), RoundedCornerShape(16.dp))
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 出错抖动动效：字节级搬自小布 assets/lottie/ca_ui_common_shake.json
                TerminalErrorShake(Modifier.size(40.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = failure.title,
                    color = Color(0xFFF2F5F9),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            // 稳定错误码：排障时用户截图即可定位（与 boot-fail 的 stage= 同思路）
            Text(
                text = failure.code.wire + (failure.exitCode?.let { " · exit=" + it } ?: ""),
                color = Color(0xFF7C8798),
                fontSize = 11.sp,
            )
            if (failure.detail.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = failure.detail,
                    color = Color(0xFFC8D2E0),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            if (failure.hint.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = failure.hint,
                    color = Color(0xFF9AA6B8),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TerminalActionButton(
                    label = "重连",
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = onReconnect,
                )
                TerminalActionButton(
                    label = "关闭会话",
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = onCloseSession,
                )
            }
            if (onOpenSetup != null && failure.code == TerminalFailureCode.ENV_NOT_READY) {
                Spacer(Modifier.height(10.dp))
                TerminalActionButton(
                    label = "去安装 / 配置环境",
                    primary = false,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenSetup,
                )
            }
        }
    }
}

@Composable
private fun TerminalActionButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(42.dp)
            .background(
                if (primary) Color(0xFF2DD4BF) else Color(0xFF1D2634),
                RoundedCornerShape(10.dp),
            )
            .border(
                1.dp,
                if (primary) Color(0x00000000) else Color(0xFF2A3444),
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (primary) Color(0xFF08221F) else Color(0xFFC8D2E0),
            fontSize = 13.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * 出错抖动动效 —— 直接引用 OPPO 小布包内的 `assets/lottie/ca_ui_common_shake.json`
 * （字节级搬到本仓 `app/src/main/assets/lottie/breeno_shake.json`，8,123 B）。
 *
 * 只播一次（不循环）：抖动是"刚刚出事了"的一次性提示，循环抖会让人焦虑。
 * 资源缺失 / 解析失败时 LottieAnimation 不画任何东西，失败卡本身照常显示 —— 不允许因为一个动效白屏。
 */
@androidx.compose.runtime.Composable
private fun TerminalErrorShake(modifier: Modifier = Modifier) {
    // 不用 `by` 委托：本文件没有 androidx.compose.runtime.getValue 导入，
    // 显式取 .value 少一个隐式依赖，也不会和其他文件的重载打架。
    val comp = com.airbnb.lottie.compose
        .rememberLottieComposition(com.airbnb.lottie.compose.LottieCompositionSpec.Asset("lottie/breeno_shake.json"))
        .value
    val state = com.airbnb.lottie.compose.animateLottieCompositionAsState(
        composition = comp,
        isPlaying = comp != null,
        iterations = 1,
    )
    com.airbnb.lottie.compose.LottieAnimation(
        composition = comp,
        progress = { state.value },
        modifier = modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
    )
}

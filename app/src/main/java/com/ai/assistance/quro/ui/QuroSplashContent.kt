package com.ai.assistance.quro.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.R

/**
 * 品牌启动页内容（Compose 版）。
 * v196 起由 QuroMainActivity 在首帧以全屏覆盖层渲染 800ms 后淡出，
 * 取代原先独立的 QuroSplashActivity——这样挂后台返回（进程存活）不会再重放开屏。
 */
@Composable
fun QuroSplashContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(R.color.quro_splash_bg)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.quro_logo),
                contentDescription = "Quro AI",
                modifier = Modifier.size(120.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "Quro AI",
                color = colorResource(R.color.quro_splash_text),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "你的 AI 执行体",
                color = colorResource(R.color.quro_brand_cyan),
                fontSize = 14.sp,
            )
        }
    }
}

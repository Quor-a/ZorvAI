package com.ai.assistance.quro.genui.aiapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ai.assistance.quro.genui.aiapp.ui.theme.GenUITheme

/**
 * 主 Activity
 * 应用的入口 Activity，承载 Compose 导航
 */
class GenUiAgentActivity : ComponentActivity() {

    companion object {
        /**
         * 可选入参：一进来就替用户发出去的需求（由 ZorvAI 的 `genui_agent_open` 工具写入）。
         * 留空则只打开界面，等用户自己说。
         */
        const val EXTRA_PROMPT = "genui_agent_prompt"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 只取一次：Activity 重建（转屏等）时不再重复发送。
        val initialPrompt = savedInstanceState?.let { null }
            ?: intent?.getStringExtra(EXTRA_PROMPT)?.trim()?.takeIf { it.isNotEmpty() }

        setContent {
            GenUITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(initialPrompt = initialPrompt)
                }
            }
        }
    }
}

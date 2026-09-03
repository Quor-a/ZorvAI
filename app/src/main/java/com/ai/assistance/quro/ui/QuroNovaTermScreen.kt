package com.ai.assistance.quro.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.quro.core.novaterm.ui.TerminalScreen
import com.ai.assistance.quro.core.novaterm.ui.TerminalViewModel

/**
 * QuroTerm 自研沙盒终端界面（集成自 NovaTerm，已命名为 QuroTerm）。
 * 复用 NovaTerm 的 TerminalScreen + TerminalViewModel（AndroidViewModel），
 * 与 proot 重终端（terminal-core）形成「轻量自研 + 重终端」互补。
 */
@Composable
fun QuroNovaTermScreen(onClose: () -> Unit) {
    val vm: TerminalViewModel = viewModel()
    Box(Modifier.fillMaxSize()) {
        TerminalScreen(vm)
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).zIndex(50f)
        ) {
            Icon(Icons.Filled.Close, contentDescription = "关闭沙盒终端", tint = Color.White)
        }
    }
}

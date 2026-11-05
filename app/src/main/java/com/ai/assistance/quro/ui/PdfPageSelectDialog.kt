package com.ai.assistance.quro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * PDF 选页：选择要发送给 AI 的页（前 N 页预览选择）。
 */

data class PdfPageSelection(
    val selectedPages: Set<Int>,
    val totalPages: Int,
)

@Composable
fun PdfPageSelectDialog(
    totalPages: Int,
    selectedPages: Set<Int> = emptySet(),
    onTogglePage: (Int) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onConfirm: (PdfPageSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val effectiveTotal = totalPages.coerceAtLeast(1)
    val selectAll = selectedPages.size == effectiveTotal

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("选择 PDF 页", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "共 $effectiveTotal 页，选择要发给 AI 的页",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已选 ${selectedPages.size} 页",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = { onSelectAll(!selectAll) }) {
                        Text(if (selectAll) "取消全选" else "全选")
                    }
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed((0 until effectiveTotal).chunked(3)) { _, row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (pageIdx in row) {
                                val page = pageIdx + 1
                                val isSelected = pageIdx in selectedPages
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(0.75f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .then(
                                            if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                                        )
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        )
                                        .clickable { onTogglePage(pageIdx) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "$page",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        textAlign = TextAlign.Center,
                                    )
                                    if (isSelected) {
                                        Box(
                                            Modifier
                                                .align(Alignment.TopStart)
                                                .padding(4.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(Color.Black.copy(alpha = 0.70f))
                                                .padding(2.dp),
                                        ) {
                                            Checkbox(
                                                checked = true,
                                                onCheckedChange = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, shape = RoundedCornerShape(50)) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(PdfPageSelection(selectedPages, effectiveTotal)) },
                        shape = RoundedCornerShape(50),
                        enabled = selectedPages.isNotEmpty(),
                    ) {
                        Text("发送 ${selectedPages.size} 页")
                    }
                }
            }
        }
    }
}

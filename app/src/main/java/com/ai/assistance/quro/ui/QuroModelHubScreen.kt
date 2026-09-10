package com.ai.assistance.quro.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.model.QuroGgufNaming
import com.ai.assistance.quro.core.model.QuroHuggingFace
import com.ai.assistance.quro.core.model.QuroLocalModel
import com.ai.assistance.quro.core.model.QuroLocalModelRepository
import com.ai.assistance.quro.core.model.QuroLocalModelType
import com.ai.assistance.quro.core.network.LocalModelLoaders
import com.ai.assistance.quro.core.network.LocalModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 离线模型下载中心：搜索官方模型（HuggingFace）→ 下载 → 真正动态加载进本地推理引擎。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroModelHubScreen(onClose: () -> Unit) {
    val tabs = listOf("GGUF (llama.cpp)", "MNN", "我的模型")
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("离线模型下载中心") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "返回") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> SearchHubTab(type = "GGUF")
                1 -> SearchHubTab(type = "MNN")
                2 -> MyModelsTab()
            }
        }
    }
}

/** 模型目录（落盘目录名 + 加载 key）。同一 repo+文件恒为同一 id，重下会覆盖更新。 */
private fun modelDir(id: String, ctx: Context) = File(ctx.filesDir, "local_models/$id")
private fun hfId(repoId: String, fileName: String, type: String): String =
    "hf_" + "$type|$repoId/$fileName".hashCode().toString(36)

/** 下载 GGUF 并登记 + 真正动态加载进 llama.cpp 引擎。 */
private suspend fun downloadAndLoadGguf(
    ctx: Context,
    entry: QuroHuggingFace.HfModelFile,
    onProgress: (Float) -> Unit,
): String = withContext(Dispatchers.IO) {
    val id = hfId(entry.repoId, entry.fileName, "GGUF")
    val dir = modelDir(id, ctx); dir.mkdirs()
    val target = File(dir, entry.fileName)
    val r = QuroHuggingFace.downloadFile(entry.repoId, entry.fileName, target, onProgress)
    if (!r.startsWith("OK")) { target.delete(); return@withContext "下载失败：$r" }
    val stem = QuroGgufNaming.stem(entry.fileName)
    val model = QuroLocalModel(
        id = id,
        type = QuroLocalModelType.LLAMA_CPP,
        name = "${entry.repoId} · ${entry.fileName}",
        path = dir.absolutePath,
        modelNames = listOf(stem),
    )
    QuroLocalModelRepository(ctx).upsert(model)
    return@withContext when (val res = LocalModelLoaders.get().load(model)) {
        is LocalModelLoader.LoadResult.Success -> "OK:已下载并动态加载（llama.cpp）"
        is LocalModelLoader.LoadResult.Failure -> "已下载，但引擎加载失败：${res.message}"
    }
}

/** 下载 MNN 模型（llm_config.json + .mnn）并登记 + 真正动态加载进 MNN 引擎。 */
private suspend fun downloadAndLoadMnn(
    ctx: Context,
    entry: QuroHuggingFace.HfModelFile,
    onProgress: (Float) -> Unit,
): String = withContext(Dispatchers.IO) {
    val id = hfId(entry.repoId, entry.fileName, "MNN")
    val dir = modelDir(id, ctx); dir.mkdirs()
    // 传入 source：HuggingFace("hf") 走 HF 链路，ModelScope("ms") 走阿里 OSS CDN 链路（国内稳定）。
    // 否则 ModelScope 来源的模型会被误判为 HF 而下载失败（直接对应「MNN 下载没搞对」）。
    val r = QuroHuggingFace.downloadMnnModel(entry.repoId, dir, onProgress, entry.source)
    if (!r.startsWith("OK")) return@withContext "下载失败：$r"
    val model = QuroLocalModel(
        id = id,
        type = QuroLocalModelType.MNN,
        name = "${entry.repoId} · ${entry.fileName}",
        path = dir.absolutePath,
    )
    QuroLocalModelRepository(ctx).upsert(model)
    return@withContext when (val res = LocalModelLoaders.get().load(model)) {
        is LocalModelLoader.LoadResult.Success -> "OK:已下载并动态加载（MNN）"
        is LocalModelLoader.LoadResult.Failure -> "已下载，但引擎加载失败：${res.message}"
    }
}

@Composable
private fun SearchHubTab(type: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(if (type == "GGUF") "Qwen2.5 GGUF" else "Qwen MNN") }
    var results by remember { mutableStateOf<List<QuroHuggingFace.HfModelFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 快捷搜索预设（避免用户不知道搜什么）
    val presets = if (type == "GGUF") listOf("Qwen2.5 GGUF", "Llama-3.2 GGUF", "DeepSeek GGUF", "Gemma GGUF")
    else listOf("Qwen MNN", "Llama MNN", "ChatGLM MNN", "Phi MNN")

    fun doSearch() {
        val q = query.trim()
        if (q.isBlank()) return
        loading = true
        errorMsg = null
        scope.launch {
            results = withContext(Dispatchers.IO) {
                if (type == "GGUF") QuroHuggingFace.searchGguf(q) else QuroHuggingFace.searchMnn(q)
            }
            loading = false
            if (results.isEmpty()) errorMsg = "未找到可下载的${if (type == "GGUF") "GGUF" else "MNN"}模型，换个关键词试试"
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(if (type == "GGUF") "搜索 GGUF 模型" else "搜索 MNN 模型") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.padding(start = 8.dp))
            IconButton(onClick = { doSearch() }) { Icon(Icons.Filled.Search, "搜索") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { p ->
                Button(onClick = { query = p; doSearch() }, modifier = Modifier.weight(1f)) { Text(p, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (errorMsg != null) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(errorMsg!!, color = MaterialTheme.colorScheme.error)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(results) { entry -> ResultRow(ctx, scope, entry, type) }
            }
        }
    }
}

@Composable
private fun ResultRow(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, entry: QuroHuggingFace.HfModelFile, type: String) {
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var downloading by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth().padding(6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(entry.repoId, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                if (entry.type == "MNN" && entry.fileName.isBlank()) "(完整模型包：配置 + 全部权重文件)"
                else entry.fileName,
                style = MaterialTheme.typography.bodySmall,
            )
            Text("大小约 ${entry.sizeMB} MB", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (downloading) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("${(progress * 100).toInt()}% 下载中…", style = MaterialTheme.typography.bodySmall)
            }
            if (status != null && !downloading) {
                Spacer(Modifier.height(4.dp))
                Text(status!!, style = MaterialTheme.typography.bodySmall,
                    color = if (status!!.startsWith("OK")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    if (downloading) return@Button
                    downloading = true
                    progress = 0f
                    status = "下载中…"
                    scope.launch {
                        val r = if (type == "GGUF") downloadAndLoadGguf(ctx, entry) { progress = it }
                        else downloadAndLoadMnn(ctx, entry) { progress = it }
                        downloading = false
                        status = r
                    }
                },
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, "下载", modifier = Modifier.padding(end = 4.dp))
                Text("下载并加载")
            }
        }
    }
}

@Composable
private fun MyModelsTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf(QuroLocalModelRepository(ctx).loadAll()) }
    var refreshKey by remember { mutableStateOf(0) }
    val state = LocalModelLoaders.get().getState()
    val loadedId = (state as? LocalModelLoader.State.Loaded)?.model?.id

    fun reload() {
        models = QuroLocalModelRepository(ctx).loadAll()
        refreshKey++
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已登记 ${models.size} 个模型", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { reload() }) { Icon(Icons.Filled.Refresh, "刷新") }
        }
        if (models.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("还没有模型。去上方 GGUF / MNN 标签页搜索并下载一个吧。")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(models) { m ->
                    MyModelCard(
                        model = m,
                        isLoaded = loadedId == m.id,
                        onLoad = {
                            scope.launch(Dispatchers.IO) {
                                LocalModelLoaders.get().load(m)
                                withContext(Dispatchers.Main) { reload() }
                            }
                        },
                        onUnload = {
                            scope.launch(Dispatchers.IO) {
                                LocalModelLoaders.get().unload()
                                withContext(Dispatchers.Main) { reload() }
                            }
                        },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                if (loadedId == m.id) LocalModelLoaders.get().unload()
                                QuroLocalModelRepository(ctx).delete(m.id)
                                File(m.path).deleteRecursively()
                                withContext(Dispatchers.Main) { reload() }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MyModelCard(
    model: QuroLocalModel,
    isLoaded: Boolean,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
) {
    val typeLabel = when (model.type) {
        QuroLocalModelType.MNN -> "MNN"
        QuroLocalModelType.LLAMA_CPP -> "llama.cpp"
    }
    Card(
        Modifier.fillMaxWidth().padding(6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (isLoaded) {
                    Icon(Icons.Filled.CheckCircle, "已加载", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.padding(start = 4.dp))
                    Text("已加载", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("类型：$typeLabel", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isLoaded) {
                    Button(onClick = onUnload) { Text("卸载") }
                } else {
                    Button(onClick = onLoad) { Text("加载") }
                }
                Button(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

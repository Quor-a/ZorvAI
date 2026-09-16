package com.ai.assistance.quro.kaleidobox

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.kaleidobox.android.KaleidoAppContract
import com.ai.assistance.quro.kaleidobox.android.KaleidoBoxHost
import com.ai.assistance.quro.kaleidobox.android.KaleidoShortcuts
import com.ai.assistance.quro.kaleidobox.android.ui.KaleidoCompose
import com.ai.assistance.quro.kaleidobox.core.engine.AppLifecycle
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.ui.UiAction
import com.ai.assistance.quro.kaleidobox.core.util.Json
import com.ai.assistance.quro.ui.theme.QuroTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 真 Android 组件：把某个 KaleidoBox 插件的 UI 表面全屏承载成一个可独立启动的 Activity。
 *
 * 入口两条：
 *   1. 显式 Intent（工具中心"打开"按钮）：带 [KaleidoAppContract.EXTRA_PKG_ID] / [EXTRA_SURFACE_ID]；
 *   2. 隐式 deep-link（桌面快捷方式 / 浏览器 / 分享）：kaleido://plugin/<pkgId>?surface=<id>。
 *
 * 外壳走应用级主题（[QuroTheme]）：本地化标题 + 版本副标题、表面用选中高亮的分段控件切换、
 * "加到桌面"走 Snackbar 反馈，让插件在用户眼里就是"一个 App"，而不是工具中心里的一块 widget。
 *
 * 生命周期按"插件 = App"转交：本 Activity 的 onResume/onPause/onDestroy 会转发给插件，
 * 让插件能响应前后台切换与销毁（如暂停计时器、保存状态）。
 */
class KaleidoActivity : ComponentActivity() {
    private var pkgId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawPkgId = intent.getStringExtra(KaleidoAppContract.EXTRA_PKG_ID)
            ?: intent.data?.let { if (it.host == KaleidoAppContract.DEEP_LINK_HOST) it.pathSegments.firstOrNull() else null }
        val pkgId = rawPkgId?.takeIf { it.isNotBlank() }
        val surfaceIdExtra = intent.getStringExtra(KaleidoAppContract.EXTRA_SURFACE_ID)
            ?: intent.data?.getQueryParameter("surface")

        if (pkgId == null) {
            finish()
            return
        }
        this.pkgId = pkgId
        fireLifecycle(AppLifecycle.CREATE)

        setContent {
            QuroTheme {
                KaleidoPluginScreen(
                    context = this,
                    pkgId = pkgId,
                    initialSurface = surfaceIdExtra,
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        fireLifecycle(AppLifecycle.RESUME)
    }

    override fun onPause() {
        fireLifecycle(AppLifecycle.PAUSE)
        super.onPause()
    }

    override fun onDestroy() {
        fireLifecycle(AppLifecycle.DESTROY)
        super.onDestroy()
    }

    /** 把生命周期事件转发给插件运行时（宿主未初始化时静默跳过）。 */
    private fun fireLifecycle(event: AppLifecycle) {
        pkgId ?: return
        runCatching { KaleidoBoxHost.get().runtime.notifyLifecycle(pkgId!!, event) }
    }
}

/** 从多语言 Map 里挑一个适合当前语言环境的字符串，逐级回退。 */
private fun pickLocalized(m: Map<String, String>?, fallback: String): String {
    if (m.isNullOrEmpty()) return fallback
    val lang = Locale.getDefault().language.lowercase(Locale.ROOT)
    return m[lang]
        ?: m["zh"]
        ?: m["zh-CN"]
        ?: m["en"]
        ?: m.values.firstOrNull()
        ?: fallback
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KaleidoPluginScreen(
    context: Context,
    pkgId: String,
    initialSurface: String?,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val runtime = remember { runCatching { KaleidoBoxHost.get().runtime }.getOrNull() }

    var uiState by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }

    val rec = remember(pkgId) { runtime?.installedPackages()?.firstOrNull { it.id == pkgId } }
    val manifest = rec?.manifest
    val surfaces = remember(pkgId) { manifest?.ui ?: emptyList() }
    var surfaceId by remember(pkgId) { mutableStateOf(initialSurface ?: surfaces.firstOrNull()?.id) }

    val sid = surfaceId
    val node = remember(pkgId, sid, uiState) {
        if (sid != null) runtime?.renderSurface(pkgId, sid, uiState) else null
    }
    val displayName = pickLocalized(manifest?.name, pkgId)
    val surfaceLabel: (id: String) -> String = { id ->
        surfaces.firstOrNull { it.id == id }?.let { pickLocalized(it.title, it.id) } ?: id
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        manifest?.let {
                            Text(
                                "v${it.version}",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val out = withContext(Dispatchers.IO) {
                                KaleidoShortcuts.pinPluginShortcut(
                                    context.applicationContext,
                                    pkgId,
                                    surfaceId,
                                    displayName,
                                )
                            }
                            val msg = if (out is KValue.Obj && out.value["ok"]?.asBoolOr() == true) {
                                "已固定到桌面（若系统支持）"
                            } else {
                                "固定失败：${out.asString()}"
                            }
                            snackbarHostState.showSnackbar(msg)
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "加到桌面")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            // 多表面：选中高亮的分段控件（FilterChip），像 App 的底部/顶部标签栏
            if (surfaces.size > 1) {
                Surface(
                    color = cs.surfaceContainerLow,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        surfaces.forEach { s ->
                            FilterChip(
                                selected = s.id == surfaceId,
                                onClick = {
                                    if (s.id != surfaceId) {
                                        surfaceId = s.id
                                        uiState = emptyMap()
                                    }
                                },
                                label = { Text(pickLocalized(s.title, s.id), maxLines = 1) },
                            )
                        }
                    }
                }
            }

            // 内容区
            Box(Modifier.fillMaxSize().weight(1f).padding(16.dp)) {
                when {
                    runtime == null -> {
                        CenteredState(
                            loading = true,
                            text = "插件运行时未就绪，请稍候…",
                        )
                    }
                    node == null -> {
                        CenteredState(
                            text = "该插件暂未提供可交互界面",
                            sub = "可在对话框里让 AI 调用它的能力",
                        )
                    }
                    else -> {
                        KaleidoCompose.Render(
                            node = node,
                            state = uiState,
                            onAction = { act: UiAction ->
                                scope.launch(Dispatchers.IO) {
                                    val out = runCatching {
                                        runtime?.dispatchUiAction(pkgId, act) ?: KValue.Null
                                    }.getOrElse { KValue.fail("E", it.message ?: "") }
                                    val ns = (Json.fromK(out) as? Map<*, *>)?.mapKeys { it.key.toString() } ?: uiState
                                    withContext(Dispatchers.Main) { uiState = ns }
                                }
                            },
                            surfaceId = surfaceId ?: "",
                        )
                    }
                }
            }
        }
    }
}

/** 居中占位：加载中（转圈）/ 空界面 / 运行时许态，避免裸文字像调试屏。 */
@Composable
private fun CenteredState(
    loading: Boolean = false,
    text: String,
    sub: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurfaceVariant,
        )
        if (sub != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

package com.ai.assistance.quro.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.BuildConfig
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.github.QuroGitHubClient
import com.ai.assistance.quro.core.github.QuroGitHubOAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 打开外部链接（GitHub 网页）。 */
private fun openUrl(ctx: Context, url: String) {
    runCatching {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}

/** GitHub 管理屏：登录 + 概览 / 仓库 / Issue / 搜索。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroGitHubScreen(onClose: () -> Unit, initialQuery: String = "") {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(QuroGitHubClient.isLoggedIn(ctx)) }

    if (!loggedIn) {
        GitHubLoginScreen(
            onLoggedIn = { loggedIn = true },
            onClose = onClose,
        )
        return
    }

    var tab by remember { mutableStateOf(if (initialQuery.isNotBlank()) 4 else 0) }
    val tabs = listOf("概览", "仓库", "Issue", "通知", "搜索")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = {
                        QuroGitHubClient.logout(ctx)
                        loggedIn = false
                    }) { Icon(Icons.Filled.Logout, "退出登录") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> GitHubOverviewTab(ctx, scope)
                1 -> GitHubReposTab(ctx, scope)
                2 -> GitHubIssuesTab(ctx, scope)
                3 -> GitHubNotificationsTab(ctx, scope)
                4 -> GitHubSearchTab(ctx, scope, initialQuery)
            }
        }
    }
}

/**
 * GitHub 登录屏：应用内 WebView 走官方 OAuth 授权码流程（PKCE，无需 client_secret）。
 * 流程：点「用 GitHub 登录」→ WebView 打开 GitHub 授权页 → 用户用 GitHub 账号授权 →
 * GitHub 重定向到 zorv://github-oauth-callback?code=... → WebView 拦截取码 → 换 access_token → 登录。
 * client_id 由开发者在 gradle.properties 烤进 BuildConfig（普通用户看不到、也不会被要求填任何东西）。
 */
@Composable
private fun GitHubLoginScreen(onLoggedIn: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clientId = BuildConfig.GITHUB_CLIENT_ID
    var phase by remember { mutableStateOf(if (clientId.isBlank()) "unconfigured" else "entry") }
    var busy by remember { mutableStateOf(false) }
    var authUrl by remember { mutableStateOf("") }
    var codeVerifier by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }

    when (phase) {
        "entry" -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("登录 GitHub", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "点击下方按钮，在弹出页面用你的 GitHub 账号授权即可完成登录。无需填写任何 Token 或密钥。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    state = QuroGitHubOAuth.randomState()
                    codeVerifier = QuroGitHubOAuth.randomCodeVerifier()
                    authUrl = QuroGitHubOAuth.buildAuthUrl(clientId, state, QuroGitHubOAuth.codeChallengeS256(codeVerifier))
                    phase = "webview"
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("用 GitHub 登录") }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("返回") }
        }
        "webview" -> GitHubLoginWebView(
            authUrl = authUrl,
            busy = busy,
            expectedState = state,
            onCode = { code ->
                busy = true
                scope.launch {
                    val token = QuroGitHubOAuth.exchangeCodeForToken(clientId, code, codeVerifier)
                    busy = false
                    if (token == null) { phase = "entry"; return@launch }
                    if (QuroGitHubOAuth.applyLogin(ctx, token)) onLoggedIn()
                    else phase = "entry"
                }
            },
            onError = { phase = "entry" },
            onCancel = { phase = "entry" },
        )
        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("GitHub 登录未配置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "开发者：在 gradle.properties 填入 GITHUB_CLIENT_ID 后重新构建，即可启用一键 OAuth 登录。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("返回") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitHubLoginWebView(
    authUrl: String,
    busy: Boolean,
    expectedState: String,
    onCode: (String) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub 授权中…") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Filled.ArrowBack, "取消") } },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView(
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = "ZorvAI"
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                return intercept(url, expectedState, onCode, onError)
                            }

                            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                if (url == null) return false
                                return intercept(url, expectedState, onCode, onError)
                            }
                        }
                        loadUrl(authUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/** 拦截 OAuth 回调重定向：zorv://github-oauth-callback?code=...&state=... → 取出 code。返回 true 表示已消费该导航。 */
private fun intercept(url: String, expectedState: String, onCode: (String) -> Unit, onError: (String) -> Unit): Boolean {
    if (!QuroGitHubOAuth.isOAuthRedirect(url)) return false
    val code = QuroGitHubOAuth.parseCode(url, expectedState)
    if (code != null) onCode(code) else onError("GitHub 回调缺少授权码")
    return true
}

@Composable
private fun LoadingOrError(state: LoadState) {
    when (state) {
        is LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is LoadState.Error -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(state.msg, color = MaterialTheme.colorScheme.error)
        }
        else -> {}
    }
}

sealed class LoadState {
    object Idle : LoadState()
    object Loading : LoadState()
    data class Error(val msg: String) : LoadState()
}

@Composable
private fun GitHubOverviewTab(ctx: Context, scope: kotlinx.coroutines.CoroutineScope) {
    var account by remember { mutableStateOf<QuroGitHubClient.Account?>(null) }
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }

    fun load() {
        state = LoadState.Loading
        scope.launch {
            val acc = withContext(Dispatchers.IO) { QuroGitHubClient.getAccount(ctx) }
            if (acc == null) state = LoadState.Error("获取账户失败（Token 无效或网络异常）")
            else { account = acc; state = LoadState.Idle }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { load() }

    when {
        state is LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state is LoadState.Error -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text((state as LoadState.Error).msg, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { load() }) { Text("重试") }
            }
        }
        account != null -> {
            val a = account!!
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(a.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("@${a.login}", style = MaterialTheme.typography.bodyMedium)
                if (a.bio.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(a.bio) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("仓库 ${a.publicRepos}")
                    Text("关注者 ${a.followers}")
                    Text("关注中 ${a.following}")
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { openUrl(ctx, a.htmlUrl) }) { Text("在浏览器打开主页") }
            }
        }
    }
}

@Composable
private fun GitHubReposTab(ctx: Context, scope: kotlinx.coroutines.CoroutineScope) {
    var repos by remember { mutableStateOf<List<QuroGitHubClient.Repo>>(emptyList()) }
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }

    fun load() {
        state = LoadState.Loading
        scope.launch {
            val list = withContext(Dispatchers.IO) { QuroGitHubClient.listRepos(ctx) }
            if (list.isEmpty()) state = LoadState.Error("没有仓库或获取失败")
            else { repos = list; state = LoadState.Idle }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { load() }

    if (state is LoadState.Loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state is LoadState.Error) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text((state as LoadState.Error).msg, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { load() }) { Text("重试") }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(repos) { r ->
            RepoCard(ctx, scope, r)
        }
    }
}

@Composable
private fun RepoCard(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, repo: QuroGitHubClient.Repo) {
    var starred by remember { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(repo.fullName) {
        starred = withContext(Dispatchers.IO) {
            QuroGitHubClient.isStarred(ctx, repo.owner, repo.name)
        }
    }
    Card(
        Modifier.fillMaxWidth().padding(4.dp).clickable { openUrl(ctx, repo.htmlUrl) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(repo.fullName, fontWeight = FontWeight.Bold)
                if (repo.description.isNotBlank()) Text(repo.description, style = MaterialTheme.typography.bodySmall)
                Row {
                    if (repo.language.isNotBlank()) Text("${repo.language}  ", style = MaterialTheme.typography.bodySmall)
                    Text("★${repo.stars}  ", style = MaterialTheme.typography.bodySmall)
                    Text("更新 ${repo.updatedAt}", style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = {
                scope.launch {
                    val target = starred != true
                    val ok = withContext(Dispatchers.IO) {
                        QuroGitHubClient.setStar(ctx, repo.owner, repo.name, target)
                    }
                    if (ok) starred = target
                }
            }) {
                when (starred) {
                    true -> Icon(Icons.Filled.Star, "取消 Star", tint = MaterialTheme.colorScheme.primary)
                    false -> Icon(Icons.Filled.StarBorder, "Star")
                    null -> CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun GitHubIssuesTab(ctx: Context, scope: kotlinx.coroutines.CoroutineScope) {
    var issues by remember { mutableStateOf<List<QuroGitHubClient.Issue>>(emptyList()) }
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }

    fun load() {
        state = LoadState.Loading
        scope.launch {
            val list = withContext(Dispatchers.IO) { QuroGitHubClient.listMyIssues(ctx) }
            if (list.isEmpty()) state = LoadState.Error("没有 Issue 或获取失败")
            else { issues = list; state = LoadState.Idle }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { load() }

    if (state is LoadState.Loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state is LoadState.Error) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text((state as LoadState.Error).msg, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { load() }) { Text("重试") }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(issues) { it ->
            Card(Modifier.fillMaxWidth().padding(4.dp).clickable { openUrl(ctx, it.htmlUrl) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("[${it.state}] ${it.repo}#${it.number} ${it.title}", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun GitHubNotificationsTab(ctx: Context, scope: kotlinx.coroutines.CoroutineScope) {
    var notes by remember { mutableStateOf<List<QuroGitHubClient.Notification>>(emptyList()) }
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }

    fun load() {
        state = LoadState.Loading
        scope.launch {
            val list = withContext(Dispatchers.IO) { QuroGitHubClient.listNotifications(ctx) }
            if (list.isEmpty()) state = LoadState.Error("没有通知或获取失败")
            else { notes = list; state = LoadState.Idle }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { load() }

    if (state is LoadState.Loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state is LoadState.Error) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text((state as LoadState.Error).msg, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { load() }) { Text("重试") }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(notes) { n ->
            Card(Modifier.fillMaxWidth().padding(4.dp).clickable { openUrl(ctx, n.htmlUrl) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (n.unread) {
                            Text("● ", color = MaterialTheme.colorScheme.primary)
                        }
                        Text("[${n.reason}] ${n.repo}", fontWeight = FontWeight.Bold)
                    }
                    Text(n.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun GitHubSearchTab(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, initialQuery: String = "") {
    var query by remember { mutableStateOf(initialQuery) }
    var type by remember { mutableStateOf("repositories") }
    var results by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val types = listOf("repositories", "code", "issues", "users")

    fun doSearch() {
        if (query.isBlank()) return
        loading = true
        scope.launch {
            results = withContext(Dispatchers.IO) {
                when (type) {
                    "code" -> QuroGitHubClient.searchCode(ctx, query).joinToString("\n") { "• ${it.repo}/${it.path}\n  ${it.htmlUrl}" }.ifBlank { "无结果" }
                    "issues" -> QuroGitHubClient.searchIssues(ctx, query).joinToString("\n") { "• [${it.state}] ${it.repo}#${it.number} ${it.title}\n  ${it.htmlUrl}" }.ifBlank { "无结果" }
                    "users" -> QuroGitHubClient.searchUsers(ctx, query).joinToString("\n") { "• ${it.login} (${it.type}) ★${it.followers}\n  ${it.htmlUrl}" }.ifBlank { "无结果" }
                    else -> QuroGitHubClient.searchRepositories(ctx, query).joinToString("\n") { "• ${it.fullName} ★${it.stars}${if (it.language.isNotBlank()) " · ${it.language}" else ""}\n  ${it.htmlUrl}" }.ifBlank { "无结果" }
                }
            }
            loading = false
        }
    }

    // 从 /gh 命令预填后自动触发一次搜索
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank()) doSearch()
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索 GitHub") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { doSearch() }) { Icon(Icons.Filled.Search, "搜索") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            types.forEach { t ->
                Button(onClick = {
                    type = t
                    if (query.isNotBlank()) doSearch()
                },
                    modifier = Modifier.weight(1f)) {
                    Text(when (t) {
                        "repositories" -> "仓库"
                        "code" -> "代码"
                        "issues" -> "Issue"
                        else -> "用户"
                    }, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (results != null) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { Text(results!!, style = MaterialTheme.typography.bodyMedium) }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("输入关键词后点搜索（仓库 / 代码 / Issue / 用户）")
            }
        }
    }
}

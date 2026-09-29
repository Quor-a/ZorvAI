package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
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
    val tabs = listOf(stringResource(R.string.qk_01943), stringResource(R.string.qk_01944), "Issue", stringResource(R.string.qk_01945), stringResource(R.string.qk_00002))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, stringResource(R.string.qk_00143)) }
                },
                actions = {
                    IconButton(onClick = {
                        QuroGitHubClient.logout(ctx)
                        loggedIn = false
                    }) { Icon(Icons.Filled.Logout, stringResource(R.string.qk_01946)) }
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
 * GitHub 登录屏：两种「登录官方 GitHub」的方式，任选其一，都是真实登录（取回账户/仓库/Issue/通知）。
 *  - PAT：在 github.com/settings/tokens 生成 Personal Access Token 粘贴即可，零注册、立即可用。
 *  - 设备流：官方 OAuth 设备授权（与 gh CLI 同源），仅需填一次 OAuth App 的 Client ID，
 *    在浏览器输入验证码即完成授权，无需注册回调地址（这正是 WebView 授权码流程在客户端卡死的原因）。
 */
@Composable
private fun GitHubLoginScreen(onLoggedIn: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("menu") }        // menu | pat | device
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var pat by remember { mutableStateOf("") }             // PAT 输入
    var clientId by remember { mutableStateOf(QuroGitHubClient.getClientId(ctx)) }
    var dev by remember { mutableStateOf<QuroGitHubClient.DeviceCode?>(null) }
    var cancelled by remember { mutableStateOf(false) }

    // GitHub 镜像：设备无法直连 github.com（体现为 SocketTimeout）时，切到镜像域名即可连通。
    var mirror by remember { mutableStateOf(QuroGitHubClient.getMirrorDomain(ctx)) }
    var mirrorExpanded by remember { mutableStateOf(false) }
    // 候选镜像（含若干公开镜像，连通性需自测；也可手填任意域名）。
    val mirrorPresets = listOf("github.com", "kkgithub.com", "bgithub.xyz", "ghproxy.net", "mirror.ghproxy.com", "github.moeyy.xyz")
    var mirrorStatus by remember { mutableStateOf<String?>(null) }
    var mirrorTesting by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.qk_01947), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.qk_01948),
            style = MaterialTheme.typography.bodySmall,
        )

        // GitHub 镜像：设备无法直连 github.com（SocketTimeout）时，切到镜像域名即可连通。
        Text(stringResource(R.string.qk_01949), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = mirror,
                onValueChange = {
                    mirror = it.trim().lowercase()
                    QuroGitHubClient.setMirrorDomain(ctx, mirror)
                },
                label = { Text(stringResource(R.string.qk_01950)) },
                placeholder = { Text("github.com") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { mirrorExpanded = true }) {
                Icon(Icons.Filled.ArrowDropDown, stringResource(R.string.qk_01951))
            }
            DropdownMenu(expanded = mirrorExpanded, onDismissRequest = { mirrorExpanded = false }) {
                mirrorPresets.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(if (p == "github.com") qstr(R.string.qk_01952) else p) },
                        onClick = {
                            mirror = p
                            QuroGitHubClient.setMirrorDomain(ctx, p)
                            mirrorExpanded = false
                        },
                    )
                }
            }
        }

        // 镜像连通性自检：登录前先测一下，避免盲选已挂的镜像导致请求被截断/闪退。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    mirrorStatus = null; mirrorTesting = true
                    scope.launch {
                        val (ok, ms) = withContext(Dispatchers.IO) { QuroGitHubClient.probeMirror(mirror) }
                        mirrorTesting = false
                        mirrorStatus = if (ok) "✅ 镜像可达（约 ${ms ?: "?"} ms），可登录"
                        else qstr(R.string.qk_01955)
                    }
                },
                modifier = Modifier.weight(1f), enabled = !mirrorTesting,
            ) { Text(if (mirrorTesting) stringResource(R.string.qk_01956) else stringResource(R.string.qk_01957)) }
        }
        mirrorStatus?.let {
            Text(it, color = if (it.startsWith("✅")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        if (mirror != "github.com") {
            Text(stringResource(R.string.qk_01958),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when (mode) {
            "menu" -> {
                Button(onClick = { mode = "pat" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.qk_01959)) }
                Button(onClick = { mode = "device" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.qk_01960)) }
            }
            "pat" -> {
                OutlinedTextField(
                    value = pat, onValueChange = { pat = it },
                    label = { Text("Personal Access Token") },
                    placeholder = { Text("ghp_xxx / github_pat_xxx") },
                    singleLine = false, modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (pat.isBlank()) { errorMsg = qstr(R.string.qk_01961); return@Button }
                        errorMsg = null; busy = true
                        scope.launch {
                            val acc = withContext(Dispatchers.IO) { QuroGitHubClient.loginWithToken(ctx, pat) }
                            busy = false
                            if (acc != null) onLoggedIn() else errorMsg = qstr(R.string.qk_01962)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy,
                ) { Text(if (busy) stringResource(R.string.qk_01963) else stringResource(R.string.qk_01964)) }
                Button(onClick = { mode = "menu"; pat = "" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.qk_00143)) }
            }
            "device" -> {
                if (dev == null) {
                    OutlinedTextField(
                        value = clientId, onValueChange = { clientId = it },
                        label = { Text("OAuth App Client ID") },
                        placeholder = { Text(stringResource(R.string.qk_01965)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val cid = clientId.trim()
                            if (cid.isBlank()) { errorMsg = qstr(R.string.qk_01966); return@Button }
                            errorMsg = null; busy = true
                            scope.launch {
                                val d = withContext(Dispatchers.IO) {
                                    QuroGitHubClient.setClientId(ctx, cid)
                                    QuroGitHubClient.startDeviceFlow(ctx, cid)
                                }
                                busy = false
                                if (d == null) errorMsg = qstr(R.string.qk_01967) else dev = d
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    ) { Text(if (busy) stringResource(R.string.qk_01968) else stringResource(R.string.qk_01969)) }
                    Text(stringResource(R.string.qk_01970),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { mode = "menu" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.qk_00143)) }
                } else {
                    val d = dev!!
                    Text(stringResource(R.string.qk_01971), style = MaterialTheme.typography.bodyMedium)
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(
                                QuroGitHubClient.mirrorUrl(ctx, d.verificationUriComplete.ifBlank { d.verificationUri }),
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.qk_01972, (d.userCode).toString()), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    LaunchedEffect(d) {
                        cancelled = false
                            val res = withContext(Dispatchers.IO) {
                                QuroGitHubClient.pollForToken(ctx, clientId, d.deviceCode, d.interval) { cancelled }
                            }
                        when (res) {
                            is QuroGitHubClient.DeviceLoginResult.Token -> {
                                if (QuroGitHubClient.login(ctx, res.value)) onLoggedIn()
                                else errorMsg = qstr(R.string.qk_01973)
                            }
                            is QuroGitHubClient.DeviceLoginResult.Error -> errorMsg = res.message
                            QuroGitHubClient.DeviceLoginResult.Cancelled -> {}
                        }
                    }
                    errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { cancelled = true; dev = null; errorMsg = null }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.qk_00011)) }
                }
            }
        }

        errorMsg?.let { if (mode != "device" || dev == null) Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (mode != "device" || dev == null) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.qk_00065)) }
        }
    }
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
            if (acc == null) state = LoadState.Error(qstr(R.string.qk_01974))
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
                Button(onClick = { load() }) { Text(stringResource(R.string.qk_00092)) }
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
                    Text(stringResource(R.string.qk_01975, (a.publicRepos).toString()))
                    Text(stringResource(R.string.qk_01976, (a.followers).toString()))
                    Text(stringResource(R.string.qk_01977, (a.following).toString()))
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { openUrl(ctx, a.htmlUrl) }) { Text(stringResource(R.string.qk_01978)) }
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
            if (list.isEmpty()) state = LoadState.Error(qstr(R.string.qk_01979))
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
                Button(onClick = { load() }) { Text(stringResource(R.string.qk_00092)) }
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
                    Text(stringResource(R.string.qk_01980, (repo.updatedAt).toString()), style = MaterialTheme.typography.bodySmall)
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
                    true -> Icon(Icons.Filled.Star, stringResource(R.string.qk_01981), tint = MaterialTheme.colorScheme.primary)
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
            if (list.isEmpty()) state = LoadState.Error(qstr(R.string.qk_01982))
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
                Button(onClick = { load() }) { Text(stringResource(R.string.qk_00092)) }
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
            if (list.isEmpty()) state = LoadState.Error(qstr(R.string.qk_01983))
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
                Button(onClick = { load() }) { Text(stringResource(R.string.qk_00092)) }
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
                    "code" -> QuroGitHubClient.searchCode(ctx, query).joinToString("\n") { "• ${it.repo}/${it.path}\n  ${it.htmlUrl}" }.ifBlank { qstr(R.string.qk_01984) }
                    "issues" -> QuroGitHubClient.searchIssues(ctx, query).joinToString("\n") { "• [${it.state}] ${it.repo}#${it.number} ${it.title}\n  ${it.htmlUrl}" }.ifBlank { qstr(R.string.qk_01984) }
                    "users" -> QuroGitHubClient.searchUsers(ctx, query).joinToString("\n") { "• ${it.login} (${it.type}) ★${it.followers}\n  ${it.htmlUrl}" }.ifBlank { qstr(R.string.qk_01984) }
                    else -> QuroGitHubClient.searchRepositories(ctx, query).joinToString("\n") { "• ${it.fullName} ★${it.stars}${if (it.language.isNotBlank()) " · ${it.language}" else ""}\n  ${it.htmlUrl}" }.ifBlank { qstr(R.string.qk_01984) }
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
            label = { Text(qstr(R.string.qk_01985)) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { doSearch() }) { Icon(Icons.Filled.Search, stringResource(R.string.qk_00002)) }
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
                        "repositories" -> qstr(R.string.qk_01944)
                        "code" -> qstr(R.string.qk_00148)
                        "issues" -> "Issue"
                        else -> qstr(R.string.qk_01986)
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
                Text(stringResource(R.string.qk_01987))
            }
        }
    }
}
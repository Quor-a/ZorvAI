package com.ai.assistance.quro.terminal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.terminal.moyu.MoyuLocal
import com.ai.assistance.quro.terminal.moyu.MoyuNative
import com.ai.assistance.quro.terminal.moyu.MoyuPkg
import com.ai.assistance.quro.terminal.moyu.MoyuRuntime
import com.ai.assistance.quro.terminal.moyu.MoyuUbuntu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 墨语言控制台。
 *
 * 设计原则（2026-10-24 按「完整运行在 Ubuntu」重写）：**Ubuntu 是唯一主面孔**。
 *
 * 架构（界面上必须一眼看得出）：
 * ```
 * proot → Ubuntu（aarch64 rootfs，APK 内置）
 *            └── 墨语言工具链 + zig + 自举系统(uspace2) + 自举内核(kernel64.elf)
 * ```
 * 所以首页第一块就是「墨语言融合于 Ubuntu」的状态，宿主辅助运行时退到次要位置
 * （它只服务依赖包 / 测试 / 网络这些外围能力）。资源页读的不再是宿主 rt.moHome，
 * 而是 guest rootfs 里的 /opt/molang —— 那才是现在的唯一真源。
 */
@Composable
fun MoyuScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rt = remember { MoyuRuntime.get(context) }
    val scope = rememberCoroutineScope()
    val pkg = remember { MoyuPkg(context, rt) }
    // AArch64 原生目标：把 .mo 编成 ARM64 静态 ELF
    val native = remember { MoyuNative(context, rt) }
    // 墨语言 ⇢ Ubuntu 融合通道：工具链/编译器/自举系统/自举内核全部装进 Ubuntu rootfs
    val env = remember { com.ai.assistance.quro.terminal.runtime.TerminalEnvironment(context) }
    val ubuntu = remember { MoyuUbuntu(context, env, rt) }

    // 墨语言融合进 Ubuntu：工具链/编译器/自举系统都随 APK 内置，
    // 进这个页面就在后台解压落地 + 写 guest 适配 —— 用户不需要点任何「构建」按钮，
    // 因为一切都打进 ZorvAI 之前准备好了。没有系统，proot 就是空转。
    // 进度直接写进 status，首页的「状态」块会实时显示，用户知道后台在干什么。
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { ubuntu.ensure { m -> status = m } }
        }
    }

    // 原生通道（/data/local/tmp + Shizuku）：与 Ubuntu 相互独立的一份复制。
    val local = remember { MoyuLocal(context) }
    var localProgress by remember { mutableStateOf("") }
    // 核心层部署失败原因：顶到状态卡里，不再只沉在页面底部的「输出」块
    // （真机事故：用户看到的永远是「未部署」三个字，失败文案在屏幕外）。
    var localDeployError by remember { mutableStateOf("") }
    var confirmZig by remember { mutableStateOf(false) }
    var confirmClean by remember { mutableStateOf(false) }

    // 页面路由：null = 首页
    var page by remember { mutableStateOf<String?>(null) }

    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }

    var editing by remember { mutableStateOf<File?>(null) }
    var editText by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    fun refresh() { tick++ }

    fun runSafe(block: suspend () -> String) {
        busy = true
        scope.launch {
            output = withContext(Dispatchers.IO) { block() }
            refresh()
            busy = false
        }
    }

    /**
     * 带实时进度的执行。
     *
     * 部署要把 6.4MB（甚至 176MB）分块推过去，几十秒里界面不能装死，
     * 所以 [MoyuLocal.deployCore] 的 onProgress 直接写 [localProgress] ——
     * Compose 的 MutableState 后台线程写是安全的，会自己回到主线程重组。
     */
    fun runLive(block: suspend (onProgress: (String) -> Unit) -> String) {
        busy = true
        localProgress = "准备中 …"
        scope.launch {
            output = withContext(Dispatchers.IO) { block { m -> localProgress = m } }
            refresh()
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MoyuTheme.backgroundColor)
    ) {
        // ── 顶栏 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (page != null) page = null else onBack() },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.ArrowBack, "返回", tint = MoyuTheme.onSurfaceColor)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (page) {
                        null -> "墨语言"
                        "examples" -> "示例"
                        "docs" -> "文档"
                        "libs" -> "标准库"
                        "deps" -> "依赖包"
                        "net" -> "网络"
                        "native" -> "编译与产物"
                        "local" -> "原生通道"
                        "system" -> "自举系统"
                        "kernel" -> "自举内核"
                        else -> "墨语言"
                    },
                    color = MoyuTheme.onSurfaceColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    // #475：页标题走衬线体（ZorvAI 的标题族），与终端里的无衬线正文区分开。
                    fontFamily = FontFamily.Serif
                )
                if (page == null) {
                    Text(
                        "v" + rt.version() + " · 完整运行在 Ubuntu 内 · " + MoyuUbuntu.BUILD_STAMP,
                        color = MoyuTheme.onSurfaceVariant,
                        fontSize = 10.5.sp
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(
                    Modifier.size(15.dp), strokeWidth = 2.dp,
                    color = MoyuTheme.accentColor
                )
                Spacer(Modifier.width(8.dp))
            }
        }

        // ── 内容 ──
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (page) {
                null -> HomeContent(
                    rt = rt, ubuntu = ubuntu, busy = busy, tick = tick,
                    runSafe = { blk -> runSafe(blk) },
                    onNew = { newName = "demo" + (System.currentTimeMillis() % 1000) + ".mo"; showNew = true },
                    onEdit = { f -> editing = f; editText = runCatching { f.readText() }.getOrDefault("") },
                    onRun = { f ->
                        runSafe {
                            // 唯一路径：墨语言完整运行在 Ubuntu 里（转译 → zig → Ubuntu 内执行）。
                            // 非 .mo（已产出的 AArch64 静态 ELF）走原生执行，不经 guest。
                            if (f.name.endsWith(".mo")) {
                                ubuntu.runSource(f).fold({ it }, { "失败：" + (it.message ?: "") })
                            } else {
                                val r = native.runAuto(f, emptyList(), 180_000)
                                "退出码 " + r.code + (if (r.viaLauncher == true) "（memfd 原生）" else "") +
                                    "\n" + r.output.trim()
                            }
                        }
                    },
                    onDelete = { f -> deleteTarget = f },
                    onOpen = { p -> page = p },
                )

                "examples" -> ResourcePage(
                    title = "examples", root = ubuntu.rootfsFile("opt/molang/examples"),
                    tick = tick, allowCopy = true, workspace = rt.workspace,
                    onCopied = { name -> status = "已复制 " + name; refresh() },
                    onView = { f -> viewing = f.name to runCatching { f.readText() }.getOrDefault("") }
                )

                "docs" -> ResourcePage(
                    title = "docs", root = ubuntu.rootfsFile("opt/molang/docs"),
                    tick = tick, allowCopy = false, workspace = rt.workspace,
                    onCopied = {},
                    onView = { f -> viewing = f.name to runCatching { f.readText() }.getOrDefault("") }
                )

                "libs" -> ResourcePage(
                    title = "lib", root = ubuntu.rootfsFile("opt/molang/lib"),
                    tick = tick, allowCopy = false, workspace = rt.workspace,
                    onCopied = {},
                    onView = { f -> viewing = f.name to runCatching { f.readText() }.getOrDefault("") }
                )

                "deps" -> DepsPage(rt = rt, pkg = pkg, tick = tick, busy = busy, runSafe = { blk -> runSafe(blk) }, onChanged = { refresh() })

                "net" -> NetPage(
                    rt = rt, pkg = pkg, ubuntu = ubuntu, busy = busy,
                    runSafe = { blk -> runSafe(blk) }
                )

                // 编译与产物：AArch64 静态 ELF 的构建/执行/清单
                "native" -> NativePage(
                    rt = rt, native = native, ubuntu = ubuntu, tick = tick, busy = busy,
                    runSafe = { blk -> runSafe(blk) },
                    onChanged = { refresh() }
                )

                // 原生通道：/data/local/tmp + Shizuku，ARM64 产物原生跑
                "local" -> LocalPage(
                    local = local, tick = tick, busy = busy,
                    progress = localProgress,
                    deployError = localDeployError,
                    onDeployError = { localDeployError = it },
                    runSafe = { blk -> runSafe(blk) },
                    runLive = { blk -> runLive(blk) },
                    onAskZig = { if (!busy) confirmZig = true },
                    onAskClean = { if (!busy) confirmClean = true },
                )

                // 自举系统：uspace2 + ISO
                "system" -> SystemPage(
                    ubuntu = ubuntu, tick = tick, busy = busy,
                    runSafe = { blk -> runSafe(blk) }
                )

                // 自举内核：ARM64 裸机 ELF
                "kernel" -> KernelPage(
                    ubuntu = ubuntu, tick = tick, busy = busy,
                    runSafe = { blk -> runSafe(blk) }
                )
            }

            if (status.isNotBlank()) {
                Block { SimpleText(status) }
            }
            if (localProgress.isNotBlank()) {
                Block {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SimpleText("原生通道", MoyuTheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        if (busy) {
                            CircularProgressIndicator(
                                Modifier.size(12.dp), strokeWidth = 2.dp,
                                color = MoyuTheme.accentColor
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        localProgress.takeLast(2000),
                        color = MoyuTheme.onSurfaceColor,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (output.isNotBlank()) {
                // #475：输出块改成**深色控制台窗**（与引导页的控制台窗同色同形态）——
                // 纸底上放一块深窗，一眼分清「这是机器的输出」而不是产品文案。
                // 旧形态是纸卡里一段灰字等宽文本，读长日志时与说明文字糊在一起。
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MoyuTheme.consoleBg,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MoyuTheme.consoleBg)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MoyuTheme.consoleInk.copy(alpha = 0.5f))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "输出",
                                color = MoyuTheme.consoleInk.copy(alpha = 0.75f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.weight(1f))
                            Tap("清空") { output = "" }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            output.takeLast(5000),
                            color = MoyuTheme.consoleInk,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }
    }

    // ── 对话框 ──
    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("新建程序") },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("文件名") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val f = rt.writeSource(newName.trim().ifBlank { "demo.mo" }, SAMPLE)
                    showNew = false; refresh(); editing = f; editText = SAMPLE
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("取消") } }
        )
    }

    editing?.let { file ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(file.name, fontSize = 14.sp) },
            text = {
                OutlinedTextField(
                    value = editText, onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 380.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        runCatching { file.writeText(editText) }
                        editing = null; refresh(); status = "已保存"
                        runSafe { ubuntu.runSource(file).fold({ it }, { "失败：" + (it.message ?: "") }) }
                    }) { Text("保存并运行") }
                    TextButton(onClick = {
                        runCatching { file.writeText(editText) }
                        editing = null; refresh(); status = "已保存"
                    }) { Text("保存") }
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }

    viewing?.let { pair ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(pair.first, fontSize = 13.sp) },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        pair.second.take(20000), color = MoyuTheme.onSurfaceColor,
                        fontSize = 10.5.sp, fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("关闭") } }
        )
    }

    deleteTarget?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除 " + f.name + "？") },
            text = { Text("不可恢复。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { f.delete() }
                    deleteTarget = null; refresh(); status = "已删除"
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    // 编译层（zig）约 176MB，分块传要几十秒 —— 必须用户显式点头。
    if (confirmZig) {
        AlertDialog(
            onDismissRequest = { confirmZig = false },
            title = { Text("部署编译层？") },
            text = {
                Text(
                    "zig 编译器约 176MB，要经 Shizuku 分块写入 /data/local/tmp，耗时几十秒。" +
                        "不部署也能跑预编译的 andromo 基准和已产出的 ARM64 程序；" +
                        "只有在设备上现场把 .mo 编成 ARM64 才需要它。",
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmZig = false
                    runLive { p -> local.deployZig(p).fold({ local.describe() }, { "失败：" + (it.message ?: "") }) }
                }) { Text("部署") }
            },
            dismissButton = { TextButton(onClick = { confirmZig = false }) { Text("取消") } }
        )
    }

    if (confirmClean) {
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            title = { Text("清空原生通道？") },
            text = {
                Text(
                    "会 rm -rf " + MoyuLocal.REMOTE_ROOT + "。只影响这条独立复制出来的通道，" +
                        "Ubuntu 里的墨语言不受任何影响。",
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClean = false
                    runSafe {
                        local.cleanup()
                            .fold({ "已清空 " + MoyuLocal.REMOTE_ROOT }, { "失败：" + (it.message ?: "") })
                    }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClean = false }) { Text("取消") } }
        )
    }
}

// ═════════════ 首页：Ubuntu 融合 / 我的程序 / 资源 ═════════════

@Composable
private fun HomeContent(
    rt: MoyuRuntime,
    ubuntu: MoyuUbuntu,
    busy: Boolean,
    tick: Int,
    runSafe: (suspend () -> String) -> Unit,
    onNew: () -> Unit,
    onEdit: (File) -> Unit,
    onRun: (File) -> Unit,
    onDelete: (File) -> Unit,
    onOpen: (String) -> Unit,
) {
    // ① 墨语言融合于 Ubuntu —— 这是唯一主面孔。
    //    宿主辅助运行时（rt）只服务依赖包/测试/网络，退到第 ④ 块一句话带过。
    //    ensure() 由外层 MoyuScreen 的 LaunchedEffect 触发，这里只读状态。
    val sys = remember(tick) { ubuntu.status() }
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("墨语言 · 运行于 Ubuntu")
            Spacer(Modifier.weight(1f))
            Dot(sys.installed)
            Spacer(Modifier.width(6.dp))
            SimpleText(if (sys.installed) "已融合" else "未就绪", MoyuTheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        SimpleText(
            "proot 承载 Ubuntu（aarch64 rootfs，APK 内置），墨语言工具链、zig 编译器、" +
                "自举系统 uspace2、自举内核全部装在 Ubuntu 的 /opt/molang。"
        )
        Spacer(Modifier.height(4.dp))
        SimpleText(
            "编译运行一条命令在 Ubuntu 内完成：mo 文件.mo → 转译 → zig 交叉编译 → Ubuntu 内执行。",
            MoyuTheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        SimpleText("构建 " + MoyuUbuntu.BUILD_STAMP + " · 增量叠加安装，不动主 rootfs", MoyuTheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        StatusLine("Ubuntu rootfs", sys.ubuntuReady)
        StatusLine("工具链 /opt/molang/bin", sys.pkgReady)
        StatusLine("zig 编译器 /opt/zig", sys.ccReady)
        StatusLine("自举系统 uspace2", sys.systemReady)
        StatusLine("自举内核 kernel64.elf", sys.kernelReady)
        StatusLine("mo 命令 /usr/local/bin/mo", sys.adapted)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction(if (sys.installed) "重新融合" else "融合进 Ubuntu") {
                runSafe {
                    ubuntu.ensure { }.fold(
                        onSuccess = { "墨语言已完整融合进 Ubuntu" },
                        onFailure = { "融合失败：" + (it.message ?: "") },
                    )
                }
            }
            SmallAction("自检") { runSafe { ubuntu.diagnose() } }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("运行示例") {
                runSafe { ubuntu.runSample().fold({ it }, { "失败：" + (it.message ?: "") }) }
            }
            SmallAction("自举系统") { onOpen("system") }
            SmallAction("自举内核") { onOpen("kernel") }
        }
    }

    // ② 我的程序
    val files = remember(tick) {
        rt.workspace.listFiles { f -> f.isFile && f.name.endsWith(".mo") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("我的程序")
            Spacer(Modifier.weight(1f))
            SmallAction("新建") { onNew() }
        }
        Spacer(Modifier.height(6.dp))
        if (files.isEmpty()) {
            SimpleText("还没有程序。点「新建」写第一个。")
        } else {
            files.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        f.name, color = MoyuTheme.onSurfaceColor, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f).clickable { onEdit(f) }
                    )
                    Tap("编辑") { onEdit(f) }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { onRun(f) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.PlayArrow, "运行",
                            tint = MoyuTheme.accentColor, modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { onDelete(f) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.ArrowBack, "删除",
                            tint = MoyuTheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }

    // ③ 资源（读 guest rootfs 里的 /opt/molang，那才是唯一真源）
    Block {
        Title("资源")
        Spacer(Modifier.height(4.dp))
        val exCount = remember(tick) {
            ubuntu.rootfsFile("opt/molang/examples")
                .listFiles { f -> f.isFile && f.name.endsWith(".mo") }?.size ?: 0
        }
        val docCount = remember(tick) {
            ubuntu.rootfsFile("opt/molang/docs")
                .listFiles { f -> f.isFile && f.name.endsWith(".md") }?.size ?: 0
        }
        val libCount = remember(tick) {
            ubuntu.rootfsFile("opt/molang/lib")
                .listFiles { f -> f.isFile && f.name.endsWith(".mo") }?.size ?: 0
        }
        val depCount = remember(tick) {
            File(rt.workspace, "deps").listFiles { f -> f.isDirectory }?.size ?: 0
        }
        NavRow("示例", exCount.toString() + " 个") { onOpen("examples") }
        NavRow("文档", docCount.toString() + " 篇") { onOpen("docs") }
        NavRow("标准库", libCount.toString() + " 个") { onOpen("libs") }
        NavRow("依赖包", if (depCount == 0) "未安装" else depCount.toString() + " 个") { onOpen("deps") }
        NavRow("编译与产物", "zig 交叉编译 · ARM64 ELF") { onOpen("native") }
        NavRow("原生通道", "/data/local/tmp · Shizuku · 原生跑") { onOpen("local") }
        NavRow("网络", "socket / HTTP 服务") { onOpen("net") }
    }

    // ④ 宿主辅助运行时：退成一句话。它只服务依赖包/测试/网络这些外围能力。
    val hostOk = rt.isInstalled()
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("宿主辅助运行时")
            Spacer(Modifier.weight(1f))
            Dot(hostOk)
            Spacer(Modifier.width(6.dp))
            SimpleText(if (hostOk) "就绪" else "未初始化", MoyuTheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        SimpleText(
            "仅服务依赖包 / 测试 / 网络这些外围能力。编译与运行已全部在 Ubuntu 内完成，" +
                "不经过这里。"
        )
        if (!hostOk) {
            Spacer(Modifier.height(10.dp))
            PrimaryAction("初始化", busy) {
                runSafe {
                    val r = rt.ensureInstalled { }
                    if (r.isFailure) "解压失败：" + (r.exceptionOrNull()?.message ?: "")
                    else "宿主辅助运行时已就绪"
                }
            }
        }
    }
}

// ═════════════ 二级：资源列表（读 guest rootfs）═════════════

@Composable
private fun ResourcePage(
    title: String,
    root: File,
    tick: Int,
    allowCopy: Boolean,
    workspace: File,
    onCopied: (String) -> Unit,
    onView: (File) -> Unit,
) {
    val files = remember(tick, root.absolutePath) {
        val ext = if (title == "docs") ".md" else ".mo"
        root.listFiles { f -> f.isFile && f.name.endsWith(ext) }?.sortedBy { it.name } ?: emptyList()
    }
    Block {
        if (!root.isDirectory) {
            SimpleText("墨语言尚未融合进 Ubuntu，/opt/molang/$title 不存在。回首页点「融合进 Ubuntu」。")
            return@Block
        }
        SimpleText("$root · 共 " + files.size + " 项", MoyuTheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        files.forEach { f ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).clickable { onView(f) }) {
                    Text(
                        f.name, color = MoyuTheme.onSurfaceColor, fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        (f.length() / 1024).toString() + " KB",
                        color = MoyuTheme.onSurfaceVariant, fontSize = 9.5.sp
                    )
                }
                if (allowCopy) {
                    Tap("复制") {
                        runCatching { f.copyTo(File(workspace, f.name), overwrite = false) }
                        onCopied(f.name)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Tap("查看") { onView(f) }
            }
        }
    }
}

// ═════════════ 二级：依赖 ═════════════

@Composable
private fun DepsPage(
    rt: MoyuRuntime,
    pkg: MoyuPkg,
    tick: Int,
    busy: Boolean,
    runSafe: (suspend () -> String) -> Unit,
    onChanged: () -> Unit,
) {
    var gitUrl by remember { mutableStateOf("") }
    var gitRef by remember { mutableStateOf("main") }
    val projName = remember(tick) { pkg.packageField("name") }

    Block {
        Title("项目")
        Spacer(Modifier.height(6.dp))
        if (projName == null) {
            SimpleText("还没有 mo.toml。初始化后才能声明依赖。")
            Spacer(Modifier.height(10.dp))
            PrimaryAction("初始化项目", busy) { runSafe { pkg.init("demo") }; onChanged() }
        } else {
            SimpleText(projName + " · deps/ 与 mo.lock 已启用")
        }
    }

    Block {
        Title("安装")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = gitUrl, onValueChange = { gitUrl = it },
            label = { Text("git 地址") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = gitRef, onValueChange = { gitRef = it },
                label = { Text("ref") }, singleLine = true,
                modifier = Modifier.width(110.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            )
            Spacer(Modifier.width(10.dp))
            PrimaryAction("安装", busy || gitUrl.isBlank()) {
                runSafe {
                    pkg.install(gitUrl.trim(), gitRef.trim().ifBlank { "main" })
                        .fold({ it }, { "失败：" + (it.message ?: "") })
                }
                onChanged()
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("按 mo.toml 拉取") { runSafe { pkg.fetch().fold({ it }, { "失败：" + (it.message ?: "") }) } }
            SmallAction("校验") { runSafe { pkg.verify() } }
            SmallAction("依赖树") { runSafe { pkg.tree() } }
        }
    }

    val installed = remember(tick) { pkg.listInstalled() }
    Block {
        Title("已安装 " + installed.size)
        Spacer(Modifier.height(4.dp))
        if (installed.isEmpty()) {
            SimpleText("暂无依赖。")
        } else {
            installed.forEach { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            pair.first.name, color = MoyuTheme.onSurfaceColor,
                            fontSize = 12.5.sp, fontFamily = FontFamily.Monospace
                        )
                        Text(
                            pair.second, color = MoyuTheme.onSurfaceVariant,
                            fontSize = 9.5.sp, maxLines = 1
                        )
                    }
                    Tap("移除") { pkg.remove(pair.first.name); onChanged() }
                }
            }
        }
    }
}

// ═════════════ 二级：网络 ═════════════

@Composable
private fun NetPage(
    rt: MoyuRuntime,
    pkg: MoyuPkg,
    ubuntu: MoyuUbuntu,
    busy: Boolean,
    runSafe: (suspend () -> String) -> Unit,
) {
    var port by remember { mutableStateOf("8080") }
    var host by remember { mutableStateOf("") }

    Block {
        Title("网络")
        Spacer(Modifier.height(6.dp))
        SimpleText(
            "墨语言的 socket 经 qemu 直通 Android 内核，用的是 ZorvAI 的网络，" +
                "可收发外网流量，也可在本机监听端口。"
        )
    }

    // DNS：lib/net.mo 没有域名解析，域名必须在这里先解析成 IP 整数再传给程序
    Block {
        Title("域名解析")
        Spacer(Modifier.height(6.dp))
        SimpleText(
            "标准库 lib/net.mo 只有裸 syscall，**不做 DNS** —— tcp_connect 收的是 4 字节整数 IP" +
                "（127.0.0.1 写作 0x0100007f）。域名要先在这里解析。"
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("域名或 IPv4") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )
            Spacer(Modifier.width(10.dp))
            PrimaryAction("解析", busy || host.isBlank()) {
                runSafe { rt.resolveHost(host) }
            }
        }
    }

    // 端口：可用范围与失败原因
    Block {
        Title("端口约束")
        Spacer(Modifier.height(6.dp))
        listOf(
            "可用范围" to "1024–65535（1024 以下需 root，会 EACCES）",
            "推荐" to "8080 / 3000 / 9000",
            "已被占用" to "SO_REUSEADDR 已开，仍失败换端口",
            "协议" to "仅 AF_INET（IPv4），无 IPv6",
        ).forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(p.first, color = MoyuTheme.onSurfaceColor, fontSize = 12.sp, modifier = Modifier.width(80.dp))
                Text(p.second, color = MoyuTheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
        }
    }

    Block {
        Title("HTTP 服务")
        Spacer(Modifier.height(6.dp))
        SimpleText("用 examples/httpd.mo 现场编译并启动。")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("端口") }, singleLine = true,
                modifier = Modifier.width(110.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            )
            Spacer(Modifier.width(10.dp))
            PrimaryAction("启动", busy) {
                val p = port.toIntOrNull() ?: 8080
                runSafe {
                    // 示例在 guest rootfs 里（/opt/molang/examples），不在宿主 rt.moHome
                    val ex = ubuntu.rootfsFile("opt/molang/examples/httpd.mo")
                    if (!ex.isFile) return@runSafe "找不到 /opt/molang/examples/httpd.mo（墨语言未融合？）"
                    val work = File(rt.workspace, "httpd.mo")
                    runCatching { ex.copyTo(work, overwrite = true) }
                    // 编译 + 起服务都在 Ubuntu 内完成；端口参数经命令行传进墨语言程序
                    ubuntu.runSource(work, listOf(p.toString()))
                        .fold({ it }, { "失败：" + (it.message ?: "") })
                }
            }
        }
    }

    Block {
        Title("能力清单")
        Spacer(Modifier.height(6.dp))
        listOf(
            "TCP 监听/连接" to "lib/net.mo",
            "HTTP 服务端" to "examples/httpd.mo",
            "慢连接超时" to "examples/httpto.mo",
            "异步 IO" to "lib/aio.mo",
            "JSON" to "lib/json.mo",
            "域名解析（DNS）" to "无 —— 用 App 侧 resolve",
            "IPv6" to "无（仅 AF_INET）",
            "TLS/HTTPS" to "无（需自实现）",
        ).forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(p.first, color = MoyuTheme.onSurfaceColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(p.second, color = MoyuTheme.accentColor.copy(alpha = 0.75f), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }

    Block {
        Title("下载源诊断")
        Spacer(Modifier.height(6.dp))
        SimpleText("装依赖走「直连 → ghfast → gh-proxy → ghproxy」四级回退。")
        Spacer(Modifier.height(10.dp))
        SmallAction("测试各源可达性") { runSafe { pkg.netDiag() } }
    }
}

// ═════════════ 二级：自举系统（uspace2）═════════════

/**
 * 自举系统页。
 *
 * uspace2 是墨语言自己写的一个用户空间：AArch64 静态 ELF，无 PT_INTERP、不链 libc，
 * 自己在 `/mo` 里长出层次化 VFS（fmkdir + vmkdir_p），24 个 shell 内建命令、
 * 带 SipHash-2-4 签名的包管理、跨运行状态持久化。它**不依赖宿主提供任何东西** ——
 * 这就是「自举出来的系统」。
 */
@Composable
private fun SystemPage(
    ubuntu: MoyuUbuntu,
    tick: Int,
    busy: Boolean,
    runSafe: (suspend () -> String) -> Unit,
) {
    val sys = remember(tick) { ubuntu.status() }
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("自举系统 uspace2")
            Spacer(Modifier.weight(1f))
            Dot(sys.systemReady)
            Spacer(Modifier.width(6.dp))
            SimpleText(if (sys.systemReady) "就绪" else "未就绪", MoyuTheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        SimpleText(
            "墨语言写的用户空间：层次化 VFS、24 个 shell 内建、带 SipHash-2-4 签名的包管理、" +
                "跨运行状态持久化。第一次运行自己在 /mo 里把目录长出来，不依赖宿主。"
        )
        Spacer(Modifier.height(8.dp))
        StatusLine("uspace2 二进制", sys.systemReady)
        StatusLine("系统 ISO 镜像", ubuntu.rootfsFile("opt/molang/system/boot/molang-system.iso").isFile)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("进入系统") {
                runSafe { ubuntu.enterSystem().fold({ it }, { "失败：" + (it.message ?: "") }) }
            }
            SmallAction("镜像信息") {
                runSafe { ubuntu.imageInfo().fold({ it }, { "失败：" + (it.message ?: "") }) }
            }
        }
    }

    Block {
        Title("系统构成")
        Spacer(Modifier.height(6.dp))
        listOf(
            "系统 init" to "system/bin/uspace2（AArch64 静态 ELF）",
            "系统配置" to "system/etc/uspace.conf（root=/mo · state=/mo/state.db）",
            "登录横幅" to "system/etc/motd",
            "启动盘" to "system/boot/molang-system.iso（ISO9660）",
        ).forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(p.first, color = MoyuTheme.onSurfaceColor, fontSize = 11.5.sp, modifier = Modifier.width(96.dp))
                Text(p.second, color = MoyuTheme.onSurfaceVariant, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ═════════════ 二级：自举内核（kernel64.elf）═════════════

/**
 * 自举内核页。
 *
 * kernel64.elf 是**用墨语言写的 ARM64 裸机内核**：入口 0x40000000（QEMU virt RAM 基址）、
 * 控制台 PL011 UART MMIO 0x09000000、无系统调用、无 libc、无 PT_INTERP。
 *
 * 🔴 它**不能**在 proot / Ubuntu 里执行 —— 它自己就是提供系统调用的那一层。
 * 所以这一页不做「运行」按钮，只做两件事：摊开 ELF 结构自证，以及在 Ubuntu 内现编一个。
 */
@Composable
private fun KernelPage(
    ubuntu: MoyuUbuntu,
    tick: Int,
    busy: Boolean,
    runSafe: (suspend () -> String) -> Unit,
) {
    val sys = remember(tick) { ubuntu.status() }
    val info = remember(tick) { ubuntu.kernelInfo() }
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("自举内核 kernel64.elf")
            Spacer(Modifier.weight(1f))
            Dot(sys.kernelReady)
            Spacer(Modifier.width(6.dp))
            SimpleText(if (sys.kernelReady) "就绪" else "未就绪", MoyuTheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            info,
            color = MoyuTheme.onSurfaceColor,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))
        SimpleText(
            "墨语言写的裸机内核：自建 64KB 栈、逐字节清 bss、跳 main；输出走 PL011 UART，" +
                "停机用 wfi。不调用任何系统调用 —— 它自己就是提供系统调用的那一层。"
        )
        Spacer(Modifier.height(4.dp))
        SimpleText(
            "🔴 不能在本页「运行」：proot 与 Ubuntu 都提供不了裸机环境。验证方式只有两种 ——\n" +
                "  a) qemu-system-aarch64 -M virt -kernel kernel64.elf\n" +
                "  b) 读 ELF 头自证（左侧即为 guest 内实读结果）",
            MoyuTheme.onSurfaceVariant
        )
    }

    Block {
        Title("在 Ubuntu 内现编")
        Spacer(Modifier.height(6.dp))
        SimpleText(
            "mo kernel <file.mo>：mo2x kern-aarch64（PL011 前导）→ zig aarch64-freestanding → " +
                "entry64.S + linker64.ld。全程在 Ubuntu 内，产物是同一个裸机 ELF。"
        )
        Spacer(Modifier.height(4.dp))
        SimpleText(
            "内置示例 examples/kernel64.mo 自带三个自检：fib(10)=55 · sum_to(100)=5050 · fib(20)=6765。",
            MoyuTheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction(if (busy) "构建中…" else "构建内核") {
                runSafe { ubuntu.buildKernel().fold({ it }, { "失败：" + (it.message ?: "") }) }
            }
            SmallAction("内核信息") { runSafe { ubuntu.imageInfo().fold({ it }, { "失败：" + (it.message ?: "") }) } }
        }
        Spacer(Modifier.height(8.dp))
        StatusLine("构建输入 entry64.S", ubuntu.rootfsFile("opt/molang/boot/entry64.S").isFile)
        StatusLine("构建输入 linker64.ld", ubuntu.rootfsFile("opt/molang/boot/linker64.ld").isFile)
        StatusLine("C 前导留档 kernel64.c", ubuntu.rootfsFile("opt/molang/boot/kernel64.c").isFile)
    }
}

// ═════════════ AArch64 原生页 ════════════

/**
 * 编译与产物页。
 *
 * 只讲一件事：墨语言可以产出 **ARM64 静态 ELF**，由 Android 原生执行 ——
 * 不链 libc、不进 proot、不经 qemu 翻译。这是与既有 x86_64 路径的本质差别，
 * 所以页面上把「产物到底是不是 aarch64 静态」直接摊开显示，而不是只说"编译成功"。
 */
@Composable
private fun NativePage(
    rt: MoyuRuntime,
    native: MoyuNative,
    ubuntu: MoyuUbuntu,
    tick: Int,
    busy: Boolean,
    runSafe: (suspend () -> String) -> Unit,
    onChanged: () -> Unit,
) {
    val ccReady = remember(tick) { native.isCcReady() }

    // ① 编译器状态（zig 交叉编译器，装在 Ubuntu 里，供 mo 命令使用）
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("AArch64 编译器")
            Spacer(Modifier.weight(1f))
            Dot(ccReady)
            Spacer(Modifier.width(6.dp))
            SimpleText(if (ccReady) "就绪" else "未就绪", MoyuTheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        SimpleText("产物是 ARM64 静态 ELF：不链 libc、自带系统调用，在 Ubuntu 内由 proot 执行。")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("编译器诊断") { runSafe { native.diagnose() } }
            if (!ccReady) {
                SmallAction("解压编译器库") {
                    runSafe {
                        val r = native.ensureCc { }
                        if (r.isFailure) "解压失败：" + (r.exceptionOrNull()?.message ?: "")
                        else "编译器库已就绪"
                    }
                }
            }
        }
        if (!ccReady) {
            Spacer(Modifier.height(8.dp))
            SimpleText("缺少 zig 编译器。没有它就只能出 C 源码，无法出机器码。")
        }
    }



    // ② 产物
    val arms = remember(tick) {
        rt.workspace.listFiles { f -> f.isFile && f.name.endsWith(".arm64") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("产物")
            Spacer(Modifier.weight(1f))
            SimpleText(arms.size.toString() + " 个", MoyuTheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        if (arms.isEmpty()) {
            SimpleText("还没有 aarch64 产物。回首页「我的程序」里点运行，或用「运行示例」。")
        } else {
            arms.forEach { f ->
                val info = remember(f.path, f.length()) { native.inspect(f) }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            f.name, color = MoyuTheme.onSurfaceColor, fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            info.machineName + " · " + info.linkKind + " · " + info.size + " 字节",
                            color = if (info.isAarch64Static) MoyuTheme.successColor
                            else MoyuTheme.onSurfaceVariant,
                            fontSize = 9.5.sp, maxLines = 1
                        )
                    }
                    Tap("运行") {
                        runSafe {
                            val r = native.runAuto(f, emptyList(), 120_000)
                            "退出码 " + r.code + (if (r.viaLauncher == true) "（memfd 原生）" else "") +
                                "\n" + r.output.trim()
                        }
                    }
                }
            }
        }
    }
}

// ═════════════ 二级：原生通道（/data/local/tmp + Shizuku）═════════════

/**
 * 墨语言 **原生通道** 页。
 *
 * 这条路径是「复制」不是「共用」：它从 APK 资产里重新解一份工具链落到
 * `/data/local/tmp/quro-mo`，与 Ubuntu rootfs 里的 `/opt/molang` 毫无关系，
 * 删一边不影响另一边。
 *
 * 存在的理由：targetSdk 34 下 App 私有目录 **禁止 execve**（SELinux
 * untrusted_app 域，与 chmod 无关），`/data/local/tmp` + shell 域才是官方
 * `adb push && adb shell` 的同一条路。墨语言 cfs-aarch64 后端产出的
 * freestanding 静态 ELF（不链 libc、`svc #0` 直接陷入内核）在这条路上
 * **原生跑**，连 proot 都不经过。
 */
@Composable
private fun LocalPage(
    local: MoyuLocal,
    tick: Int,
    busy: Boolean,
    progress: String,
    deployError: String,
    onDeployError: (String) -> Unit,
    runSafe: (suspend () -> String) -> Unit,
    runLive: (suspend ((String) -> Unit) -> String) -> Unit,
    onAskZig: () -> Unit,
    onAskClean: () -> Unit,
) {
    // status() 是 6 次 fileSize 的 Binder 往返，很快；tick 变化时重读。
    val st = remember(tick) { local.status() }

    // 与 Ubuntu 通道同构的自动部署：进页面就把核心层（约 6.4MB）落好，
    // 用户不需要知道「要先点哪个按钮」。以前这条通道只认手动点击，
    // 而按钮在页面中下部、失败文案更在屏幕外的输出块里 —— 真机上
    // 表现出来的就是用户截图那样：原生通道「未部署」，没人知道为什么。
    fun deployCoreNow() {
        onDeployError("")
        runLive { p ->
            local.deployCore(p).fold(
                onSuccess = { local.describe() },
                onFailure = {
                    onDeployError(it.message ?: "未知错误")
                    "失败：" + (it.message ?: "")
                },
            )
        }
    }
    // shellReady 翻转（授权回来 / 重新进页面）时补一次；失败不自动重试循环，
    // 原因直接显示在状态卡里，用户可点「重新部署核心层」手动重试。
    LaunchedEffect(st.shellReady) {
        if (st.shellReady && !st.coreReady) deployCoreNow()
    }

    // ① 通道状态
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("原生通道")
            Spacer(Modifier.weight(1f))
            Dot(st.usable)
            Spacer(Modifier.width(6.dp))
            SimpleText(if (st.usable) "可用" else "未部署", MoyuTheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        SimpleText("独立复制的一份工具链，落在 " + MoyuLocal.REMOTE_ROOT + "，与 Ubuntu 内的墨语言互不相干。")
        Spacer(Modifier.height(4.dp))
        SimpleText("ARM64 产物由内核直接调度，不经 proot、不经 qemu。", MoyuTheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        SimpleText(local.describe(), MoyuTheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        StatusLine("Shizuku 特权 shell", st.shellReady)
        StatusLine("核心层（qemu / mo2x / 标准库 / uspace2）", st.coreReady)
        StatusLine("基准程序 andromo", st.benchReady)
        StatusLine("编译层（zig，按需）", st.zigReady)
        // 缺件清单：核心层「未就绪」时直接说清缺哪个部件，截图即诊断
        if (!st.coreReady && st.coreMissing.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            SimpleText("核心层缺：${st.coreMissing.joinToString("、")}", MoyuTheme.onSurfaceVariant)
        }
        // 部署失败原因：以前只写进屏幕外的「输出」块，用户永远看不到
        if (deployError.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            SimpleText("⚠ 核心层部署失败：$deployError", MoyuTheme.errorColor)
        }
        Spacer(Modifier.height(6.dp))
        // 铁律：界面看不到这个戳 = 装的不是这个包（版本号被锁死，戳是唯一区分手段）。
        SimpleText(
            "构建 " + MoyuUbuntu.BUILD_STAMP + " · /data/local/tmp + Shizuku，不经 App 沙箱",
            MoyuTheme.onSurfaceVariant
        )
    }

    if (!st.shellReady) {
        Block {
            Title("需要 Shizuku")
            Spacer(Modifier.height(6.dp))
            SimpleText(
                "App 自己写不进 /data/local/tmp，shell 也读不了 App 私有目录，两边 UID 不一样。" +
                    "唯一不依赖额外权限的办法是让特权进程代写，所以这条通道走 Shizuku。"
            )
            Spacer(Modifier.height(8.dp))
            SimpleText("去 系统权限 → L2 Shizuku 请求授权后再回来。", MoyuTheme.onSurfaceVariant)
        }
        return
    }

    // ② 部署
    Block {
        Title("部署")
        Spacer(Modifier.height(6.dp))
        SimpleText(
            "进入本页会自动部署核心层（约 6.4MB：翻译器 + 标准库 + 自举系统 + 基准程序），几秒就好；" +
                "也可以手动重新部署。编译层是 zig，约 176MB，只在你要在设备上现场编译时才需要。"
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryAction(if (st.coreReady) "重新部署核心层" else "部署核心层", busy) {
                deployCoreNow()
            }
            SmallAction(if (st.zigReady) "编译层已在位" else "部署编译层 (176MB)") { onAskZig() }
        }
        if (progress.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            SimpleText(progress.takeLast(500), MoyuTheme.onSurfaceVariant)
        }
    }

    // ③ 原生执行
    Block {
        Title("原生执行")
        Spacer(Modifier.height(6.dp))
        SimpleText(
            "andromo 是墨语言官方安卓原生示例：筛法数 π(n)。" +
                "这个数字就是「ARM64 产物原生跑」的直接证据 —— 一个 AArch64 静态 ELF，" +
                "内核直接调度，毫秒级出结果。"
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("跑基准 π(200000)") {
                runSafe { local.benchmark().fold({ it }, { "失败：" + (it.message ?: "") }) }
            }
            SmallAction("跑基准 π(20000)") {
                runSafe { local.benchmark(20_000).fold({ it }, { "失败：" + (it.message ?: "") }) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("自举系统 uspace2") {
                runSafe { local.enterSystem().fold({ it }, { "失败：" + (it.message ?: "") }) }
            }
            SmallAction("远端自检") {
                runSafe { local.probe().fold({ it }, { "失败：" + (it.message ?: "") }) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("andromo.mo 现场编译") {
                runSafe {
                    val src = local.sourceFromAsset("examples/andromo.mo")
                    if (src == null) "找不到 andromo.mo 源文件"
                    else local.compileAndRun(src).fold({ it }, { "失败：" + (it.message ?: "") })
                }
            }
            SmallAction("清空通道") { onAskClean() }
        }
        if (!st.zigReady) {
            Spacer(Modifier.height(8.dp))
            SimpleText(
                "现场编译需要编译层（zig）。先点上面「部署编译层」，" +
                    "或者直接用预编译好的 andromo 跑基准。",
                MoyuTheme.onSurfaceVariant
            )
        }
    }
}

// ═════════════ 基础组件（极小集合）════════════

@Composable
private fun Block(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // #475：白卡 + 暖线描边（旧的「深色面 + 白 5% 描边」是终端观感，不是 ZorvAI 的纸墨体系）。
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MoyuTheme.surfaceColor,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MoyuTheme.divider)
    ) {
        Column(Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun Title(text: String) {
    // 页面标题用衬线体（与 App 里设置页/引导页的标题同一族），正文用无衬线。
    Text(
        text,
        color = MoyuTheme.onSurfaceColor,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
    )
}

@Composable
private fun SimpleText(
    text: String,
    color: Color = MoyuTheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp, modifier = modifier)
}

@Composable
private fun Dot(ok: Boolean) {
    Box(
        Modifier
            .size(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (ok) MoyuTheme.successColor else MoyuTheme.onSurfaceVariant.copy(alpha = 0.4f))
    )
}

/** 一行「标签 + 就绪点」，用于把融合状态摊开显示。 */
@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SimpleText(label, MoyuTheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Dot(ok)
        Spacer(Modifier.width(6.dp))
        SimpleText(if (ok) "就绪" else "未就绪", MoyuTheme.onSurfaceVariant)
    }
}

@Composable
private fun PrimaryAction(text: String, disabled: Boolean, onClick: () -> Unit) {
    // #475：主操作 = 陶土实心**胶囊**（radius 999），与引导页主按钮同一形态；
    // 旧形态是 11dp 圆角紫色块，那是终端按钮，不是品牌主操作。
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = !disabled) { onClick() },
        color = if (disabled) MoyuTheme.primaryColor.copy(alpha = 0.35f) else MoyuTheme.primaryColor,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    // 次操作 = 纸面胶囊 + 暖线描边（不再用「白色 6% 蒙层」那种终端味的高光块）。
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable { onClick() },
        color = MoyuTheme.surfaceVariant,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, MoyuTheme.divider)
    ) {
        Text(
            text, color = MoyuTheme.onSurfaceColor, fontSize = 11.5.sp,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun Tap(text: String, onClick: () -> Unit) {
    Text(
        text, color = MoyuTheme.accentColor, fontSize = 11.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun NavRow(label: String, meta: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MoyuTheme.onSurfaceColor, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(meta, color = MoyuTheme.onSurfaceVariant, fontSize = 10.5.sp)
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Default.KeyboardArrowRight, null,
            tint = MoyuTheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
    }
}


/**
 * 墨语言页的**品牌调色板**（#475 用户裁定「墨语言的界面还不是 ZorvAI 风格」）。
 *
 * 为什么另起一份而不是改 [TerminalTheme]：那个是**终端**的深色紫主题，终端画布本来就该
 * 深底宽字距（黑底白字读代码才不刺眼）。墨语言这一页不是终端，它是 ZorvAI 里的一个
 * 功能页，必须和引导页、设置页同一套暖纸体系（纸底 #F4F1EA / 白卡 / 墨字 / 陶土主色）。
 * 所以这里只做**页面级**换肤：键名与 TerminalTheme 一一对应，页面内 60 处引用整体切换，
 * 终端界面一行不动。
 */
private object MoyuTheme {
    val primaryColor = Color(0xFFC25A38)        // 陶土（主操作）
    val primaryVariant = Color(0xFFA8482B)
    val accentColor = Color(0xFFC25A38)         // 链接 / 可点文本
    val backgroundColor = Color(0xFFF4F1EA)     // 纸底
    val surfaceColor = Color(0xFFFFFFFF)        // 白卡
    val surfaceVariant = Color(0xFFECE7DC)      // 次级面
    val elevated = Color(0xFFF4E4DB)
    val divider = Color(0xFFE3DDD0)             // 暖线
    val onSurfaceColor = Color(0xFF211E1A)      // 墨
    val onSurfaceVariant = Color(0xFF544D44)    // 次墨
    val successColor = Color(0xFF4E7A46)
    val warningColor = Color(0xFFB08320)
    val errorColor = Color(0xFFB23A2E)
    /** 控制台式输出块的深底（与引导页的控制台窗同色，跨页一致）。 */
    val consoleBg = Color(0xFF211E1A)
    val consoleInk = Color(0xFFF4F1EA)
}

/** 新建程序用的最小示例（墨语言语法：fn main() -> i64，内置 print/print_i64/print_nl）。 */
private const val SAMPLE = "fn main() -> i64 {\n" +
    "    print(\"Hello, Mo!\\n\");\n" +
    "    return 0;\n" +
    "}\n"

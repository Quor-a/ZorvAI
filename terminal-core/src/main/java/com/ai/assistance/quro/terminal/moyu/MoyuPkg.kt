package com.ai.assistance.quro.terminal.moyu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 墨语言包管理器（App 内置实现，对应官方 tools/mopkg.sh 的协议）。
 *
 * 为什么不能直接用官方 mopkg.sh：它依赖 bash + git + awk 三个外部命令，
 * Android 里都没有（内置的只有 busybox，且脚本用了大量 bash 特性）。
 * 所以这里用 Kotlin 重写同一套协议，**不需要 git**：
 *   依赖 = git 仓库 → 把 git URL 换算成托管站的 tarball 下载地址 → 下载 → 解压到 deps/。
 *
 * 与官方一致的约定：
 *  · `mo.toml` 的 `[package]` 段（name/version/mo）+ `[dependencies]` 段（name = { git, ref, version }）
 *  · 依赖目录 `deps/<名字>/`
 *  · 锁文件 `mo.lock`（name / version / git / ref），保证可复现
 *  · 编译时用 `-I deps/<名字>` 指向依赖（moc 的 import 搜索路径）
 */
class MoyuPkg(private val context: Context, private val rt: MoyuRuntime) {

    companion object {
        private const val TAG = "MoyuPkg"
        const val TOML = "mo.toml"
        const val LOCK = "mo.lock"
        const val DEPS = "deps"
    }

    /** 一个依赖声明。 */
    data class Dep(
        val name: String,
        val git: String,
        val ref: String = "main",
        val version: String? = null,
    )

    /** 包操作作用在工作区（对应官方脚本的 `HERE=$PWD`）。 */
    val projectDir: File get() = rt.workspace
    private val depsDir: File get() = File(projectDir, DEPS).apply { mkdirs() }
    private val tomlFile: File get() = File(projectDir, TOML)
    private val lockFile: File get() = File(projectDir, LOCK)

    // ───────────────────── TOML 子集解析 ─────────────────────
    // 只支持本项目需要的形状：段 [package] / [dependencies] + key = "value" +
    // name = { git = "...", ref = "...", version = "..." }

    fun packageField(key: String): String? {
        if (!tomlFile.exists()) return null
        var section = ""
        tomlFile.readLines().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("[")) {
                section = line.trim('[', ']').trim()
                return@forEach
            }
            if (section == "package") {
                val k = line.substringBefore('=', "").trim()
                if (k == key) return line.substringAfter('=', "").trim().trim('"')
            }
        }
        return null
    }

    fun deps(): List<Dep> {
        if (!tomlFile.exists()) return emptyList()
        val out = mutableListOf<Dep>()
        var section = ""
        tomlFile.readLines().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("[")) {
                section = line.trim('[', ']').trim()
                return@forEach
            }
            if (section != "dependencies" || !line.contains('=')) return@forEach
            val name = line.substringBefore('=').trim()
            if (name.isBlank()) return@forEach
            val value = line.substringAfter('=').trim()
            if (value.startsWith("{")) {
                val body = value.trim('{', '}').trim()
                val kv = body.split(',').associate { part ->
                    val k = part.substringBefore('=', "").trim()
                    val v = part.substringAfter('=', "").trim().trim('"')
                    k to v
                }
                val git = kv["git"] ?: kv["path"] ?: ""
                out.add(Dep(name, git, kv["ref"] ?: "main", kv["version"]))
            } else {
                out.add(Dep(name, "", "main", value.trim('"')))
            }
        }
        return out
    }

    // ───────────────────── 操作 ─────────────────────

    /** 初始化一个新项目（等价 `mopkg init <名字>`）。 */
    fun init(name: String): String {
        if (tomlFile.exists()) return "${TOML} 已存在，未覆盖"
        val safe = name.ifBlank { "demo" }
        tomlFile.writeText(
            """
            [package]
            name = "$safe"
            version = "0.1.0"
            mo = ">=1.16"

            [dependencies]
            # 每行格式： 名字 = { git = "https://...", ref = "main" }
            """.trimIndent() + "\n"
        )
        depsDir.mkdirs()
        return "已在 ${projectDir.absolutePath} 创建 ${TOML}"
    }

    /**
     * 从 git URL 推导 tarball 下载地址（不用 git）。
     * 支持 GitHub / Gitee / 直链 tar.gz。
     */
    /**
     * 生成 tarball 候选下载地址（**按可靠性排序，含国内镜像回退**）。
     *
     * 为什么需要镜像：codeload.github.com 在国内经常超时或被限速，单一直连会让
     * 「装依赖」这件事在手机上几乎不可用。这里按序尝试：直连 → 公共加速前缀 → gitee。
     */
    fun tarballUrl(gitUrl: String, ref: String): List<String> {
        val u = gitUrl.trim().removeSuffix("/").removeSuffix(".git")
        // 常见公共加速前缀（把原始 URL 拼在后面即可）
        val prefixes = listOf(
            "",                                        // 直连
            "https://ghfast.top/",                     // 常用加速
            "https://gh-proxy.com/",                   // 备用
            "https://ghproxy.net/",                    // 备用
        )
        fun withMirrors(rawList: List<String>): List<String> {
            val out = mutableListOf<String>()
            rawList.forEach { raw ->
                prefixes.forEach { p -> out.add(if (p.isEmpty()) raw else p + raw) }
            }
            return out.distinct()
        }
        return when {
            u.startsWith("https://github.com/") || u.startsWith("http://github.com/") -> {
                val path = u.substringAfter("github.com/")
                withMirrors(
                    listOf(
                        "https://codeload.github.com/$path/tar.gz/$ref",
                        "https://codeload.github.com/$path/tar.gz/refs/heads/$ref",
                    )
                )
            }
            u.startsWith("https://gitee.com/") -> {
                val path = u.substringAfter("gitee.com/")
                listOf("https://gitee.com/$path/repository/archive/$ref.tar.gz")
            }
            u.endsWith(".tar.gz") || u.endsWith(".tgz") -> withMirrors(listOf(u))
            else -> withMirrors(listOf(u))
        }
    }

    /** 网络诊断：逐个探测常用源是否可达（给 UI / AI 用）。 */
    suspend fun netDiag(): String = withContext(Dispatchers.IO) {
        val targets = listOf(
            "GitHub codeload" to "https://codeload.github.com/",
            "ghfast 镜像" to "https://ghfast.top/",
            "gh-proxy 镜像" to "https://gh-proxy.com/",
            "Gitee" to "https://gitee.com/",
        )
        buildString {
            appendLine("网络可达性（各 8s 超时）：")
            targets.forEach { (name, url) ->
                val t0 = System.currentTimeMillis()
                val ok = probe(url, 8000)
                val ms = System.currentTimeMillis() - t0
                appendLine("  " + (if (ok) "✓ " else "✗ ") + name + "  " + (if (ok) "$ms ms" else "不可达"))
            }
            append("说明：依赖下载会按「直连 → 加速镜像」顺序自动回退；上面全 ✗ 时请检查设备网络/代理。")
        }.trim()
    }

    private fun probe(url: String, timeoutMs: Int): Boolean = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            requestMethod = "HEAD"
            setRequestProperty("User-Agent", "ZorvAI-MoyuPkg")
        }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..399
    } catch (e: Exception) {
        false
    }

    /** 安装一个依赖（等价 `mopkg install <git-url> [ref]`）。 */
    suspend fun install(gitUrl: String, ref: String = "main", nameOverride: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!rt.isInstalled()) error("墨语言运行时未就绪，请先在「代码」页初始化")
                if (gitUrl.isBlank()) error("git 地址为空")
                val name = nameOverride?.ifBlank { null }
                    ?: gitUrl.trim().removeSuffix("/").removeSuffix(".git").substringAfterLast('/')
                if (name.isBlank()) error("无法从地址推断包名，请显式指定名字")

                val dest = File(depsDir, name)
                val tar = File(rt.root, "tmp/$name.download")
                tar.parentFile?.mkdirs()

                var ok = false
                val tried = mutableListOf<String>()
                for (url in tarballUrl(gitUrl, ref)) {
                    tried.add(url)
                    if (download(url, tar)) {
                        if (tried.size > 1) Log.i(TAG, "第 ${tried.size} 个源成功：$url")
                        ok = true
                        break
                    }
                }
                if (!ok) {
                    val brief = tried.take(6).joinToString("\n  ") { it }
                    error("全部下载源均失败（已试 ${tried.size} 个，含镜像回退）：\n  $brief\n" +
                        "请检查网络，或用 action=net_diag 看各源可达性。")
                }

                // 用宿主 busybox 解压（tar.gz），解压到临时目录后把唯一的顶层目录搬成 deps/name
                val staging = File(rt.root, "tmp/staging_$name").apply {
                    deleteRecursively(); mkdirs()
                }
                // 纯 Java 解压（不依赖 busybox —— 它只有终端环境初始化后才存在，
                // 墨语言应当能在没开过终端时也正常工作）
                val n = runCatching { tar.inputStream().buffered().use { rt.extractTarGz(it, staging) } }
                    .getOrElse { error("解压失败：${it.message}") }
                if (n < 1) error("解压后没有文件落盘（依赖包可能损坏）")
                val top = staging.listFiles()?.firstOrNull { it.isDirectory }
                dest.deleteRecursively()
                if (top != null) {
                    if (!top.renameTo(dest)) {
                        top.copyRecursively(dest, overwrite = true)
                        top.deleteRecursively()
                    }
                } else {
                    staging.copyRecursively(dest, overwrite = true)
                }
                staging.deleteRecursively()
                tar.delete()

                appendLock(name, ref, gitUrl, readDepVersion(dest))
                "$name 已安装 → deps/$name（ref=$ref，来源 $gitUrl）"
            }.onFailure { Log.e(TAG, "install 失败", it) }
        }

    /** 按 mo.toml 拉取全部依赖（等价 `mopkg fetch`）。 */
    suspend fun fetch(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val list = deps()
            if (list.isEmpty()) return@runCatching "mo.toml 里没有声明任何依赖"
            val sb = StringBuilder()
            list.forEach { d ->
                if (d.git.isBlank()) {
                    sb.appendLine("⊘ ${d.name}：没有 git 字段，跳过")
                    return@forEach
                }
                val r = install(d.git, d.ref, d.name)
                sb.appendLine(if (r.isSuccess) "✓ ${r.getOrThrow()}" else "✗ ${d.name}：${r.exceptionOrNull()?.message}")
            }
            sb.toString().trim()
        }
    }

    fun listInstalled(): List<Pair<File, String>> {
        val lock = readLock()
        return (depsDir.listFiles { f -> f.isDirectory } ?: emptyArray())
            .sortedBy { it.name }
            .map { d ->
                val info = lock[d.name]
                d to (info ?: "（未记录在 $LOCK）")
            }
    }

    fun remove(name: String): String {
        val d = File(depsDir, name)
        if (!d.exists()) return "deps/$name 不存在"
        d.deleteRecursively()
        rewriteLock(readLock().filterKeys { it != name })
        return "已移除 deps/$name"
    }

    /** 校验 mo.toml 与 mo.lock 是否一致（等价 `mopkg verify`）。 */
    fun verify(): String {
        val declared = deps().map { it.name }.toSet()
        val locked = readLock().keys
        val missing = declared - locked
        val extra = locked - declared
        val notDownloaded = declared.filter { !File(depsDir, it).isDirectory }
        return buildString {
            appendLine("声明 ${declared.size} 个依赖，锁文件记录 ${locked.size} 个")
            if (missing.isNotEmpty()) appendLine("⚠ 锁文件缺少：${missing.joinToString(", ")}")
            if (extra.isNotEmpty()) appendLine("⚠ 锁文件多出：${extra.joinToString(", ")}")
            if (notDownloaded.isNotEmpty()) appendLine("⚠ 尚未下载：${notDownloaded.joinToString(", ")}")
            if (missing.isEmpty() && extra.isEmpty() && notDownloaded.isEmpty()) append("✅ 一致且已就绪")
        }.trim()
    }

    /** 依赖树（单层展示；递归需要读每个依赖的 mo.toml，这里先给可用的概览）。 */
    fun tree(): String {
        val root = packageField("name") ?: projectDir.name
        val list = deps()
        if (list.isEmpty()) return "$root\n└─ (无依赖)"
        return buildString {
            appendLine(root)
            list.forEachIndexed { i, d ->
                val exists = File(depsDir, d.name).isDirectory
                val mark = if (i == list.size - 1) "└─" else "├─"
                appendLine("$mark ${d.name} ${if (exists) "✓" else "✗ 未下载"}  ${d.git} (ref=${d.ref})")
                val sub = File(depsDir, "${d.name}/$TOML")
                if (sub.exists()) {
                    val subDeps = runCatching {
                        val old = tomlFile
                        // 轻量解析子依赖：直接读文本找 [dependencies] 段的名字
                        readDepNames(sub)
                    }.getOrDefault(emptyList())
                    subDeps.forEach { s -> appendLine("│    └─ $s") }
                }
            }
        }.trim()
    }

    /** 编译时需要传的 -I 参数（每个已下载依赖一条）。 */
    fun includeArgs(): List<String> {
        val args = mutableListOf<String>()
        (depsDir.listFiles { f -> f.isDirectory } ?: emptyArray()).forEach { d ->
            args.add("-I")
            args.add(d.absolutePath)
        }
        return args
    }

    // ───────────────────── 内部工具 ─────────────────────

    private fun readDepNames(f: File): List<String> {
        val out = mutableListOf<String>()
        var section = ""
        f.readLines().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("[")) { section = line.trim('[', ']').trim(); return@forEach }
            if (section == "dependencies" && line.contains('=')) {
                val n = line.substringBefore('=').trim()
                if (n.isNotBlank()) out.add(n)
            }
        }
        return out
    }

    private fun readDepVersion(dir: File): String? {
        val t = File(dir, TOML)
        if (!t.exists()) return null
        var section = ""
        t.readLines().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("[")) { section = line.trim('[', ']').trim(); return@forEach }
            if (section == "package" && line.startsWith("version")) {
                return line.substringAfter('=', "").trim().trim('"')
            }
        }
        return null
    }

    private fun download(url: String, dest: File): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ZorvAI-MoyuPkg")
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect(); return false
            }
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            dest.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "下载失败 $url: ${e.message}")
            false
        }
    }


    // 锁文件格式（每行一条，Tab 分隔）：name \t ref \t git \t version
    private fun readLock(): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (!lockFile.exists()) return map
        lockFile.readLines().forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val p = line.split('\t')
            if (p.isNotEmpty()) {
                val name = p[0]
                val ref = p.getOrElse(1) { "" }
                val git = p.getOrElse(2) { "" }
                val ver = p.getOrElse(3) { "" }
                map[name] = "ref=$ref  ${if (ver.isNotBlank()) "v$ver  " else ""}$git"
            }
        }
        return map
    }

    private fun rewriteLock(map: Map<String, String>) {
        // 保留原始行，只删被移除的
        val keep = if (lockFile.exists()) {
            lockFile.readLines().filter { l ->
                l.isNotBlank() && !l.startsWith("#") && l.split('\t').firstOrNull() in map.keys
            }
        } else emptyList()
        lockFile.writeText((listOf("# mo.lock — 由 ZorvAI 墨语言包管理器维护") + keep).joinToString("\n") + "\n")
    }

    private fun appendLock(name: String, ref: String, git: String, version: String?) {
        val lines = if (lockFile.exists()) {
            lockFile.readLines().filter { it.isNotBlank() && it.split('\t').firstOrNull() != name }
        } else listOf("# mo.lock — 由 ZorvAI 墨语言包管理器维护")
        lockFile.writeText((lines + "$name\t$ref\t$git\t${version ?: ""}").joinToString("\n") + "\n")
    }
}

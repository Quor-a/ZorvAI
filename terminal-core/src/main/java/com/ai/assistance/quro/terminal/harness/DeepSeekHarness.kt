package com.ai.assistance.quro.terminal.harness

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.terminal.moyu.MoyuExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * DeepSeek Harness（dsh）端侧运行时。
 *
 * ## 为什么需要这一层
 *
 * dsh 是一个 Node.js 的 agent harness，`dsh web` 会在本机起一个 Web 服务并自带完整前端。
 * 要在 Android 上跑它，需要三样东西，缺一不可：
 *
 *  1. **能在 bionic 上跑的 Node**。Node 官方的 linux-arm64 构建链接 glibc，Android 用
 *     bionic，直接 exec 会 "not executable: 64-bit ELF file for Android" 或找不到
 *     ld-linux。所以这里用的是 Termux 维护的 arm64 构建（bionic 链接），版本 26.x。
 *  2. **它的 9 个动态库**。node 的 DT_NEEDED 里除了系统库，还要 z/cares/sqlite3/ffi/
 *     crypto/ssl/icu×2 与 libc++_shared —— 这些一并随包内置，启动时通过 LD_LIBRARY_PATH
 *     指向私有目录。
 *  3. **可执行权限**。targetSdk ≥ 29 的 App，其私有数据目录在 SELinux 层面禁止 execve，
 *     所以 node 由 nativeLibraryDir 里的 memfd 启动器拉起（见 MoyuExec）。
 *
 * ## 与 App 的关系
 *
 * harness 有自己的完整界面（它自带的前端），所以 UI 层就是一个 WebView 加载
 * `http://127.0.0.1:<port>`；App 侧负责启动、保活、停止、日志与诊断，AI 侧通过工具
 * 驱动它干活。**不裁剪 harness 的功能** —— 插件、设置、会话都走它自己的界面。
 */
class DeepSeekHarness(private val context: Context) {

    companion object {
        private const val TAG = "DeepSeekHarness"

        /** 内置运行时资产（tar.gz：lib/ + dsh/node_modules；node 本体在同包 bin/ 下）。 */
        const val ASSET = "dsh-runtime.pack"

        /**
         * 资产版本：内容变了就 +1，触发重新解压。
         *
         * 16 = 引入「按内容嗅探补执行位」与 engine.log 持久化。**必须 +1**：
         * 老设备上已经用旧的 0644 解压过一次，不升版本就不会重解，node/bash 依旧没有执行位。
         */
        private const val RUNTIME_VERSION = 16
        private const val MARK = ".dsh_v"

        /** dsh 默认端口。 */
        const val DEFAULT_PORT = 3080

        /** 端口就绪等待上限（首次启动要做插件装配，耐心给足）。 */
        private const val READY_TIMEOUT_MS = 90_000L

        /**
         * 从引擎日志里读回它实际监听的端口（--port 0 时 POSIX 由内核分配）。
         */
        private val PORT_RE = Regex("""(?:127\.0\.0\.1|localhost):(\d{2,5})""")

        /**
         * launch token 提取正则。
         *
         * `{40,}` 不是随手写的：token 是 `base64url(randomBytes(32))`，恒为 43 字符。
         * 上游 `EngineAuth.TOKEN_RE` 同样用 `{40,}`；写松了（如 `{20,}`）会把普通日志文本
         * 当成 token，换来一个必然失败的交换。
         */
        private val TOKEN_RE = Regex("""dsh web: \S*/\?token=([A-Za-z0-9_\-]{40,})""")

        /** 整行 URL（顺带把引擎自报的端口抓出来）。 */
        private val ENDPOINT_RE = Regex("""dsh web: (http://127\.0\.0\.1:\d+/\?token=[A-Za-z0-9_\-]{40,})""")

        /** 鉴权 cookie 名前缀（引擎侧 `COOKIE_PREFIX`）。 */
        private const val COOKIE_PREFIX = "dsh-auth-"

        /** 引擎签名密钥在凭据文件里的记录键（`client-connection/browser-session`）。 */
        private const val SECRET_RECORD_KEY = "client-connection/browser-session"

        /** 引擎 cookie 时窗兜底值（天）。引擎 schema 默认 30 天，真实值从 Set-Cookie 的 Max-Age 观测。 */
        private const val DEFAULT_COOKIE_MAX_AGE_DAYS = 30L

        /** 自铸 cookie 比引擎时窗**早**这么久失效 —— 方向写反就会卡死在 401 页。 */
        private const val COOKIE_EXPIRY_SAFETY_MARGIN_MS = 60L * 60 * 1000

        /** engine.log 保留几代（redirectOutput 每次启动截断，不轮转就只剩最后一次现场）。 */
        private const val ENGINE_LOG_GENERATIONS = 3

        /** 日志文件还算「可能是本代引擎写的」的时间窗（App 重启后引擎仍活着时靠它兜底）。 */
        private const val TOKEN_LOG_FRESH_MS = 10L * 60 * 1000

        /**
         * index 就绪判据：bootstrap 批次的 URL 前缀。
         *
         * `dsh-client-modules` 在**自己的 fiber** 里注册 `webserver/index-inject`，
         * 而 fallback 座位（`dsh-host-frontend-static`）在 HTTP 层一起来就能答
         * 200 / 303 / 401 —— **两者之间有窗口期**。窗口期里把 WebView 打开，
         * 拿到的是没有这一行的 index：页面一个脚本都不加载，整屏纯白、无任何文字，
         * 而 compat 的静态占位与 40s 看门狗都挂在 `</head>` 前，同样不会现身
         * （上游注释自己记着这个「自我参照死角」）。
         *
         * 所以「引擎活着」必须和「前端树装配完」分开判，后者只看这一行在不在。
         */
        private val BOOTSTRAP_MARKER = "plugins/??@deepseek-ai/dsh-client-modules/client.js"

        /** `<base href="./">`：`dsh-host-frontend-static` 渲染 index 时插的，文档相对 URL 全靠它。 */
        private val BASE_HREF_MARKER = "<base href=\"./\">"

        /** boot-readiness 钩子（`webserver` 的 `READY_MARKUP`）。 */
        private const val READY_HOOK_MARKER = "__DSH_BOOT_READY__"

        /** 持久化 cookie 的 prefs 键。 */
        private const val KEY_COOKIE = "cookie"

        /** 持久化 pick token 的 prefs 键（跨 App 重启复用同一个引擎时，页面仍拿得到）。 */
        private const val KEY_PICK_TOKEN = "pick_token"

        /**
         * 本次构建的识别戳。
         *
         * versionName / versionCode 是用户的，不许动；于是区分「桌上这个 APK 里装的是哪一版
         * dsh 壳」只能靠它。它同时进 `diagnose()` 输出与 HarnessScreen 顶栏 —— 界面上看不到戳，
         * 就等于装错包，这一条是出包自证链的一部分（同 LANXIU / Moyu 的做法）。
         */
        const val BUILD_STAMP = "B20261024-1945"

        @Volatile
        private var instance: DeepSeekHarness? = null

        fun get(context: Context): DeepSeekHarness =
            instance ?: synchronized(this) {
                instance ?: DeepSeekHarness(context.applicationContext).also { instance = it }
            }
    }

    // ── 路径 ──
    /**
     * 运行时根目录：filesDir/dsh。
     *
     * **架构定位（勿再改）**：dsh 引擎跑在**宿主 app 域** —— Termux 的 node（bionic 链接，
     * 与 bash 等用户态一起解压在本目录）+ Termux 用户态。
     * **dsh 不跑在 Ubuntu 里**；只有**墨语言**自举的那套系统才跑在 proot 的 Ubuntu guest 里。
     */
    val root: File get() = File(context.filesDir, "dsh")

    /**
     * Node 可执行文件（随运行时资产一起解压到 filesDir）。
     *
     * **历史上这里写成「必须来自 nativeLibraryDir/libnode.so」，是错的**：jniLibs 里从来没有
     * `libnode.so`，代码也从没读过它。实测（2026-10-24 真机）可行路径有两条，按上游同序尝试：
     *  1. 直接 `execve(node)` —— targetSdk 34 下部分 ROM 允许；
     *  2. 被拒时退化到 `/system/bin/linker64 <node>` —— 与 JNI 库同一机制，App 私有目录
     *     里的 ELF 也能被加载（上游 `EngineManager.startWithArgs` 就是这个降级链）。
     * memfd 启动器那条路已真机证伪（`execveat` 与 `/proc/self/fd/N` 都是 EACCES），不要回头。
     */
    val nodeBin: File
        get() = File(root, "bin/node")

    /** 内置动态库目录（LD_LIBRARY_PATH）。 */
    val libDir: File get() = File(root, "lib")

    /** dsh 的入口脚本（包内 bin.js）。 */
    val dshEntry: File
        get() = File(root, "dsh/node_modules/@deepseek-ai/dsh/lib/bin.js")

    /** harness 自带前端的 dist 入口。缺它 = WebView 拿到空正文 = **纯白屏**（无任何文字）。 */
    val distIndex: File
        get() = File(root, "dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html")

    /**
     * 引擎日志（持久化到 filesDir，`redirectOutput` 重定向过去）。
     *
     * **为什么必须落盘**：launch token 只由引擎进程打印一次，放在内存 ring buffer 里，
     * App 进程一重启就永远取不到 → 换不到 cookie → WebView 打开就是 401。
     * 上游同样读 `filesDir/engine.log`（`EngineAuth.tokenFromLog`）。
     */
    val engineLog: File get() = File(context.filesDir, "engine.log")

    /**
     * 引擎的持久签名密钥（P1 自铸 cookie 的真源）。
     *
     * 密钥跨引擎重启有效，所以自铸的 cookie 也跨重启有效 —— 这是「日志里没有 token 行」
     * 时唯一还能拿到 cookie 的路。
     */
    private val credentialsFile: File get() = File(File(home, ".dsh"), ".credentials.yaml")

    /** dsh 的用户数据根（DSH_HOME）——会话、附件、设置、凭据都在这里。 */
    val home: File get() = File(root, "home")

    /**
     * 工作区根：会话工作区都建在这里。
     *
     * 上游用 process.cwd() 当新会话的默认 cwd 与文件引用根，而 Android 进程 cwd 恒为 "/"——
     * 那样「@文件」菜单列出来的是设备根目录。所以工作目录必须显式指向工作区根。
     */
    val workspace: File get() = File(home, ".dsh/workspaces")

    /**
     * 宿主提供的 Documents 目录。
     *
     * dsh 的默认工作区建在 ，而它解析系统
     * Documents 的那段逻辑只认 darwin/win32/linux —— Termux 的 node 报告 platform='android'，
     * 于是直接抛错（表现为「无法创建默认工作区，请通过选择工作区选择文件夹」）。
     * 指到私有目录即可，不必依赖公共存储的「所有文件访问」权限。
     */
    val documentsDir: File get() = File(home, ".dsh/documents")

    private val tmpDir: File get() = File(root, "tmp")

    private val markFile: File get() = File(root, MARK)

    // ── 进程状态 ──
    private var proc: Process? = null

    @Volatile
    private var activePort: Int = DEFAULT_PORT

    @Volatile
    private var lastExit: String? = null

    private val logRing = ArrayDeque<String>()

    /** 本代引擎 spawn 时刻（epoch ms）；0 = 未知，token 归属校验从严。 */
    @Volatile
    private var generationStartAt: Long = 0L

    /** 观测到的引擎 cookie 时窗（毫秒）；null = 尚未观测到，自铸时用兜底默认值。 */
    @Volatile
    private var observedMaxAgeMs: Long? = null

    /** 鉴权 cookie 的内存缓存（跨进程重启后由 prefs 兜底）。 */
    @Volatile
    private var cachedCookie: String? = null

    /**
     * dir-pick 端点的鉴权 token（上游 `DSH_PICK_TOKEN`）。
     *
     * 上游壳**每次启动引擎都重新生成**一个，写进引擎 env；页面侧经
     * `window.androidBridge.getPickToken()` 读同一个值，以 `x-dsh-pick-token`
     * 头发回来。缺了它 compat 是 **fail-closed**：`/api/android/dir-pick/poll`
     * 每 500ms 拿一次 403，永远转空。
     */
    @Volatile
    private var pickTokenValue: String? = null

    /**
     * 当前引擎代的 pick token；没启动过就用 prefs 里那份（App 重启后引擎还活着时，
     * 页面仍要能读到同一个值，否则轮询继续 403）。
     */
    fun currentPickToken(): String? =
        pickTokenValue ?: prefs.getString(KEY_PICK_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** 生成（或复用）一个 pick token。base64url(32 随机字节)，与上游同形。 */
    private fun ensurePickToken(): String {
        pickTokenValue?.let { return it }
        val stored = prefs.getString(KEY_PICK_TOKEN, null)?.takeIf { it.isNotBlank() }
        val token = stored ?: Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        pickTokenValue = token
        prefs.edit().putString(KEY_PICK_TOKEN, token).apply()
        return token
    }

    /** 换一代引擎就换一个 pick token（上游同策略：不跨代复用）。 */
    private fun rotatePickToken(): String {
        pickTokenValue = null
        prefs.edit().remove(KEY_PICK_TOKEN).apply()
        return ensurePickToken()
    }

    /**
     * 运行时是否装齐了。
     *
     * **必须连前端 dist 一起查**：node + 入口在、dist 丢了的半成品解压，会让 WebView 拿到
     * 一个空正文 → 整屏纯白、无任何文字，而 `isInstalled()` 还报告"已安装"。
     */
    fun isInstalled(): Boolean = nodeBin.isFile && dshEntry.isFile && distIndex.isFile

    // ── 模型凭据 ──
    // harness 从环境变量读各家 key（DEEPSEEK_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY），
    // 也可以在它自己的界面里写 settings.yaml。这里存一份，启动与 headless 时注入环境变量，
    // 这样"App 里填一次，界面与 AI 调用都能用"。
    private val prefs get() = context.getSharedPreferences("dsh_harness", Context.MODE_PRIVATE)

    private val authPrefs
        get() = context.getSharedPreferences("dsh_harness_auth", Context.MODE_PRIVATE)

    fun apiKey(provider: String = "deepseek"): String =
        prefs.getString("key_$provider", "").orEmpty()

    fun setApiKey(provider: String, key: String) {
        prefs.edit().putString("key_${provider.lowercase()}", key.trim()).apply()
    }

    /** 注入给 node 的凭据环境变量。空 key 不注入（避免覆盖用户在 settings.yaml 里的配置）。 */
    private fun credentialEnv(): Map<String, String> {
        val m = mutableMapOf<String, String>()
        val map = mapOf(
            "deepseek" to "DEEPSEEK_API_KEY",
            "openai" to "OPENAI_API_KEY",
            "anthropic" to "ANTHROPIC_API_KEY",
        )
        map.forEach { (p, envName) ->
            val k = apiKey(p)
            if (k.isNotBlank()) m[envName] = k
        }
        return m
    }

    fun isRunning(): Boolean {
        val p = proc ?: return false
        return p.isAlive
    }

    fun activePort(): Int = activePort

    /** harness 自带前端的访问地址。只绑 127.0.0.1，不对外网暴露。 */
    fun baseUrl(): String = "http://127.0.0.1:$activePort"

    // ─────────────────────────── 安装 ───────────────────────────

    /**
     * 解压内置运行时（幂等）。
     *
     * 解压是纯 Java 的：`GZIPInputStream` + 自写 tar 解析。不走 busybox —— 实测 App 内
     * busybox 的 xz/gzip applet 实际不可用（"applet not found"），依赖它等于把安装成功率
     * 赌在某个裁剪版 busybox 上。
     *
     * **执行位不信任 tar mode**：实测这个包 `bin/node`(50,993,616 B) 与 `bin/bash`(880,416 B)
     * 的 tar mode 都是 `0644`，`node-pty` 的 `spawn-helper` 同理。压缩/打包链路（尤其
     * Windows/WSL 挂载）会丢 mode 位，所以解压后一律按**内容嗅探**补执行位 ——
     * 与上游 `SnapshotExtractor` + `SnapshotFileMode.isDirectlyExecutable` 同一套判据。
     * 这一遍同时跑在「已安装」的早退路径之前，让老设备不重解 240MB 也能补上执行位。
     */
    suspend fun ensureInstalled(onProgress: ((String) -> Unit)? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val alreadyReady = isInstalled() && markFile.isFile &&
                    markFile.readText().trim() == "v=$RUNTIME_VERSION"
                if (alreadyReady) {
                    // 幂等兜底：执行位补齐（廉价，只扫 bin/ lib/ .bin/ prebuilds/）
                    val fixed = restoreExecutableBits()
                    if (fixed > 0) appendLog("[安装] 补齐 $fixed 个文件的执行位")
                    return@withContext Result.success(Unit)
                }
                onProgress?.invoke("准备解压 DeepSeek Harness 运行时…")
                root.mkdirs()
                tmpDir.mkdirs()
                home.mkdirs()
                workspace.mkdirs()

                onProgress?.invoke("解压运行时（约 240MB，首次需几分钟）…")
                val rt = com.ai.assistance.quro.terminal.moyu.MoyuRuntime.get(context)
                context.assets.open(ASSET).use { input ->
                    rt.extractTarGz(input, root)
                }
                val fixed = restoreExecutableBits()
                if (fixed > 0) appendLog("[安装] 按内容嗅探补齐 $fixed 个执行位")

                if (!isInstalled()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "解压后仍缺文件：node=${nodeBin.isFile} entry=${dshEntry.isFile} " +
                                "dist=${distIndex.isFile}"
                        )
                    )
                }
                markFile.writeText("v=$RUNTIME_VERSION")
                onProgress?.invoke("运行时已就绪")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "安装失败", e)
                Result.failure(e)
            }
        }

    /**
     * 按内容嗅探补执行位（移植上游 `SnapshotFileMode` 判据）。
     *
     * ELF 魔数或 `#!` shebang ⇒ 直接可执行，置 `setExecutable(true)`；其余一律不动。
     * 只扫真正可能被 exec 的位置：`bin/`、`lib/`、`node_modules/.bin/`、
     * `node_modules/node-pty/prebuilds/`。`.node` 插件是 dlopen 的，不需要执行位。
     *
     * @return 实际补上的文件数
     */
    private fun restoreExecutableBits(): Int {
        var fixed = 0
        fun sniff(file: File) {
            if (!file.isFile || file.canExecute()) return
            val prefix = ByteArray(4)
            val n = try {
                file.inputStream().use { it.read(prefix) }
            } catch (_: Exception) {
                -1
            }
            if (n <= 0) return
            if (SnapshotFileMode.isDirectlyExecutable(prefix, n)) {
                if (file.setExecutable(true, true)) fixed++
                else Log.w(TAG, "无法置执行位: ${file.absolutePath}")
            }
        }
        fun walk(dir: File, inInterestingDir: Boolean) {
            val kids = dir.listFiles() ?: return
            for (k in kids) {
                if (k.isDirectory) {
                    val interesting = inInterestingDir || k.name == ".bin" || k.name == "prebuilds"
                    walk(k, interesting)
                } else if (inInterestingDir) {
                    sniff(k)
                }
            }
        }
        // bin/ 与 lib/ 整棵树都要扫；其余只在 .bin / prebuilds 里扫
        for (d in listOf(File(root, "bin"), libDir)) if (d.isDirectory) walk(d, true)
        val nm = File(root, "dsh/node_modules")
        if (nm.isDirectory) walk(nm, false)
        return fixed
    }

    // ─────────────────────────── 启动 / 停止 ───────────────────────────

    /**
     * 启动 `dsh --profile web`，等到端口可连后返回端口号。
     *
     * 参数说明（都是上游自己的契约）：
     *  - `--host 127.0.0.1`：上游**拒绝通配地址**（绑定所有网卡等于把远程代码执行暴露到
     *    局域网），所以这里只能给具体地址；
     *  - `--no-open`：不要尝试拉起浏览器（Android 上会失败且没意义，UI 由 WebView 承载）；
     *  - `--port`：端口被占时上层可以换一个再试。
     */
    suspend fun start(port: Int = DEFAULT_PORT): Result<Int> = withContext(Dispatchers.IO) {
        if (!isInstalled()) {
            return@withContext Result.failure(
                IllegalStateException("运行时未安装，先执行 ensureInstalled()")
            )
        }
        if (isRunning()) {
            return@withContext Result.success(activePort)
        }
        lastExit = null
        // 上一轮若在超时/异常路径上退出，引擎进程可能还活着并占着端口 —— 先清干净，
        // 否则这次必然 listen EADDRINUSE（实测就是这个）。
        // 写 profile patch（Android 适配插件的装配面）——必须在引擎启动前落盘
        runCatching { writeProfilePatch() }
            .onSuccess { appendLog("[profile] 已写入插件装配层: ${it.name}") }
            .onFailure { Log.w(TAG, "写 profile patch 失败", it) }

        // 挂上宿主的手机能力：把 ZorvAI 的全部工具经 MCP 桥注册给引擎，
        // harness 的 agent 于是能直接用 read_screen / tap_screen / swipe_screen /
        // launch_app / shizuku_exec / screenshot 等 —— 与「壳侧不直接注册工具、
        // AI 可见能力全部来自插件」是同一个思路，只是这里走 MCP 而不是插件。
        runCatching {
            HarnessBridgeHook.ensureBridge?.invoke(context)?.let { msg ->
                appendLog("[能力桥] $msg")
            }
        }.onFailure { Log.w(TAG, "能力桥启用失败", it) }

        val swept = killStaleEngines()
        if (swept > 0) appendLog("[清理] 结束了 $swept 个残留的引擎进程")
        // 端口：目标被占用就传 0 交给 OS 分配 —— 手动自增探测有竞态（探到空闲、真正 bind 时又被占），
        // OS 分配没有。分配到的端口随后从引擎日志里读回来。
        var usePort = port
        if (portOpen(usePort)) {
            usePort = 0
            appendLog("[端口] $port 已被占用，交由 OS 分配")
        }
        activePort = usePort
        home.mkdirs()
        workspace.mkdirs()
        tmpDir.mkdirs()
        // 预建默认工作区：dsh 会在 <Documents>/deepseek-harness/default-workspace 建它，
        // 目录不存在时它的初始化会失败。
        File(documentsDir, "deepseek-harness/default-workspace").mkdirs()

        val args = buildList {
            // --expose-internals 是 dsh 在这台设备上能起来的关键：
            // cordis-plugin-loader 定位 Node 内部 ESM loader 时，先用 --expose-internals 走
            // require('internal/modules/esm/loader')，**失败才回退**到 node-addon-require-builtin
            // 这个原生模块。而该包的预编译只覆盖 darwin/linux/win32 —— 没有 android-arm64
            // （Termux 的 node 报告 process.platform='android'），于是回退路径必然 MODULE_NOT_FOUND。
            // 带上这个 flag 就走第一条路，根本不需要那个 .node。
            add("--expose-internals")
            add(dshEntry.absolutePath)
            // 桥接层：ZorvAI 生成 patch 后，harness 启动时会多加载一个 MCP 客户端，
            // 从而把 ZorvAI 的工具当成自己的（mcp__zorvai__*）。
            // 注意 launcher 自己的 flag 必须排在 profile 参数之前（上游的规定）。
            val bridge = File(root, "dsh-bridge.patch.yml")
            if (bridge.isFile) {
                add("--patch")
                add(bridge.absolutePath)
            }
            add("--profile"); add("web")
            add("--host"); add("127.0.0.1")
            add("--port"); add(activePort.toString())
            add("--no-open")
        }

        try {
            // 起进程前先轮转 engine.log：redirectOutput 每次启动都会**截断**目标文件，
            // 不轮转就把上一次的崩溃现场（也是唯一能拿到 token 的地方）抹掉了。
            // 上游同样保留 3 代（EngineManager.rotateEngineLog）。
            rotateEngineLog()
            // 代次锚点必须在 spawn 之前打上：token 归属校验靠它区分「本代引擎打的」
            // 和「上一代残留的」。用进程内字段而不是 prefs —— 代次是进程事实。
            generationStartAt = System.currentTimeMillis()

            // 宿主 app 域直接拉起引擎：Termux 的 node 是 bionic 链接，libc++_shared 等依赖库
            // 在 libDir 里，用 LD_LIBRARY_PATH 指过去。
            //
            // spawn 顺序学上游（EngineManager.startWithArgs）：**先试直接 execve**，
            // 只有拿到 "Permission denied" 才退化到 `/system/bin/linker64 <node>`。
            // 后者与 JNI 库同一机制，App 私有目录里的 ELF 也能被加载。
            // （memfd 启动器那条路已真机证伪，不要回头。）
            val env = buildEnv() + mapOf(
                "DSH_MOBILE_MANAGED_ENGINE" to "1",
                "DSH_ENGINE_PORT" to usePort.toString(),
            )
            // 每代引擎换一个 pick token：页面经 androidBridge.getPickToken() 读它，
            // 轮换后旧 token 立即失效，别的本地页面/进程也没法伪造目录选择结果
            rotatePickToken()
            fun build(argv: List<String>): ProcessBuilder =
                ProcessBuilder(argv).also { b ->
                    b.directory(workspace)
                    b.redirectErrorStream(true)
                    // 日志落盘：token 只由引擎进程打印一次，放内存里 App 一重启就永远丢了。
                    b.redirectOutput(engineLog)
                    b.environment().apply { putAll(env) }
                }
            val p = try {
                build(listOf(nodeBin.absolutePath) + args).start()
            } catch (e: java.io.IOException) {
                if (e.message?.contains("Permission denied") != true) throw e
                appendLog("[spawn] 直接 exec 被拒，退化 linker64")
                build(listOf("/system/bin/linker64", nodeBin.absolutePath) + args).start()
            }
            if (!engineLog.isFile) {
                runCatching { p.destroyForcibly() }
                return@withContext Result.failure(
                    IllegalStateException("引擎进程没有产出 engine.log，无法读取 launch token")
                )
            }
            proc = p
            // 双写日志：文件给 token 提取与跨重启取证，ring buffer 给界面。
            Thread({ tailEngineLog(p) }, "dsh-log-tail").apply { isDaemon = true }.start()
            Thread({
                try {
                    val code = p.waitFor()
                    lastExit = "进程退出，退出码 $code"
                    appendLog("[进程退出] code=$code")
                    proc = null
                } catch (_: Exception) {
                }
            }, "dsh-wait").apply { isDaemon = true }.start()

            // 等就绪。**判据是 HTTP 应答，不是 TCP 可连**：端口通只代表"有东西在听"，
            // 可能是别的进程（上游 EngineAvailability.PORT_FOREIGN）；页面能不能服务，
            // 只有拿到 200/303/401 才算数（401 = 引擎活着但缺鉴权）。
            //
            // 但「HTTP 层活着」只算一半：`dsh-client-modules` 的 index-inject 注册在
            // 自己的 fiber 里，晚于 fallback 座位就绪。窗口期里的 index 不带 bootstrap
            // 脚本 → WebView 打开就是一个脚本都不加载的纯白屏。所以 `probe.serving`
            // 之后还要再过一道 [awaitIndexReady]，确认正文里真有那一行。
            val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
            var lastProbe = "（尚未探活）"
            var lastIndex = "（尚未取到 index）"
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) {
                    proc = null
                    return@withContext Result.failure(
                        IllegalStateException(
                            "harness 启动后立即退出。\n" + logs().takeLast(2000)
                        )
                    )
                }
                if (usePort != 0) {
                    val probe = probeEngine(usePort)
                    lastProbe = describeProbe(probe)
                    if (probe.serving) {
                        // HTTP 层活着只是第一半：还要 index 里真的带着 bootstrap 脚本
                        // （dsh-client-modules 装配完才有），否则打开就是纯白屏
                        val idxNote = awaitIndexReady(usePort, deadline) { p.isAlive }
                        if (idxNote == null) {
                            appendLog("[就绪] $lastProbe；index 已带 bootstrap 脚本 ✓")
                            return@withContext Result.success(usePort)
                        }
                        lastIndex = idxNote
                        appendLog("[等待前端装配] $idxNote")
                    }
                } else {
                    // 从引擎自报的 URL 行里抓端口（比裸 PORT_RE 可靠：那行带 token，一定是本引擎）
                    val reported = reportedPortFromLog()
                    if (reported != null && reported > 0) {
                        val probe = probeEngine(reported)
                        lastProbe = describeProbe(probe)
                        if (probe.serving) {
                            val idxNote = awaitIndexReady(reported, deadline) { p.isAlive }
                            if (idxNote == null) {
                                activePort = reported
                                appendLog("[端口] 引擎实际监听 $reported")
                                appendLog("[就绪] $lastProbe；index 已带 bootstrap 脚本 ✓")
                                return@withContext Result.success(reported)
                            }
                            lastIndex = idxNote
                            appendLog("[等待前端装配] $idxNote")
                        }
                    }
                    // 兜底：日志里所有像端口的数字都试一遍
                    for (cand in PORT_RE.findAll(logs()).mapNotNull { it.groupValues[1].toIntOrNull() }) {
                        if (probeEngine(cand).serving) {
                            val idxNote = awaitIndexReady(cand, deadline) { p.isAlive }
                            if (idxNote == null) {
                                activePort = cand
                                appendLog("[端口] 引擎实际监听 $cand（兜底匹配）")
                                appendLog("[就绪] index 已带 bootstrap 脚本 ✓")
                                return@withContext Result.success(cand)
                            }
                            lastIndex = idxNote
                            appendLog("[等待前端装配] $idxNote")
                        }
                    }
                }
                delay(400)
            }
            // 超时必须把这次起的进程收掉：留着它就会一直占着端口，下次启动必然 EADDRINUSE
            p.destroyForcibly()
            proc = null
            Result.failure(
                IllegalStateException(
                    "等待引擎就绪超时（${READY_TIMEOUT_MS / 1000}s），已终止该进程。" +
                        "最后一次探活：$lastProbe\nindex 体检：$lastIndex\n" + logs().takeLast(2000)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "启动失败", e)
            proc?.destroyForcibly()
            proc = null
            Result.failure(e)
        }
    }

    // ─────────────────────────── 鉴权（/api 全被签名 cookie 挡着）───────────────────────────

    /** 探活结果：区分「端口通」「HTTP 在应答」「页面可服务」三件事。 */
    data class Probe(
        val code: Int,
        val listening: Boolean,
        val error: String? = null,
    ) {
        /** 200 已认证 / 303 令牌交换 / 401 缺鉴权 —— 三种都说明引擎 HTTP 层活着。 */
        val serving: Boolean get() = code == 200 || code == 303 || code == 401
        val authRequired: Boolean get() = code == 401
    }

    /**
     * 从引擎日志里取 launch token。
     *
     * 为什么必须这么做：上游 dsh 从 0.1.2-rc.1 起，**每个 /api 请求都被签名 cookie 拦着**
     * （`writeUnauthorized` → 401 text/plain；没有 Authorization/query 绕过、
     * **没有 loopback 豁免**、没有开关）。引擎启动时会打印一行 launch URL，形如
     * `dsh web: http://127.0.0.1:PORT/?token=…`，这就是拿 cookie 的入口。
     * 不做这一步，WebView 打开就是 401 —— 不是"连不上"，是连上了但被拒。
     *
     * **读盘不读内存**：token 只由引擎进程打印一次。放在内存 ring buffer 里，
     * App 进程一重启（引擎还活着）就永远取不到 → 换不到 cookie → 必然 401。
     * 顺序：本进程 ring buffer（最可靠）→ engine.log 文件（跨重启）。
     */
    fun launchToken(): String? {
        TOKEN_RE.findAll(logs()).lastOrNull()?.groupValues?.get(1)?.let { return it }
        return tokenFromLogFile()
    }

    /** engine.log 尾部取 token；代次可证时从严，证不了代次时靠新鲜度兜底。 */
    private fun tokenFromLogFile(): String? {
        val f = engineLog
        if (!f.isFile) return null
        val now = System.currentTimeMillis()
        val generationKnown = generationStartAt > 0
        val fresh = runCatching { now - f.lastModified() <= TOKEN_LOG_FRESH_MS }.getOrDefault(false)
        if (!generationKnown && !fresh) return null
        return try {
            val len = f.length()
            if (len <= 0) return null
            RandomAccessFile(f, "r").use { raf ->
                val tail = 64 * 1024
                val start = (len - tail).coerceAtLeast(0L)
                raf.seek(start)
                val buf = ByteArray((len - start).toInt())
                raf.readFully(buf)
                val text = String(buf, StandardCharsets.UTF_8)
                TOKEN_RE.findAll(text).lastOrNull()?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 engine.log 失败", e)
            null
        }
    }

    /** 引擎自报的监听端口（从 `dsh web: http://127.0.0.1:PORT/?token=` 行抓，比裸数字可靠）。 */
    private fun reportedPortFromLog(): Int? {
        val text = logs().ifBlank { runCatching { engineLog.readText() }.getOrNull().orEmpty() }
        return ENDPOINT_RE.findAll(text).lastOrNull()?.groupValues?.get(1)
            ?.let { Regex(""":(\d+)/""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }

    /**
     * 用 launch token 换鉴权 cookie（`GET /?token=` → 303 + Set-Cookie）。
     *
     * 三处都必须对，少一处就换不到：
     *  1. **`Proxy.NO_PROXY`** —— `URL.openConnection()` 默认走系统 ProxySelector，
     *     WiFi 上配了代理时 loopback 请求会被送去代理网关（它连不回来），于是「页面能打开、
     *     App 说没 cookie」。上游 `EngineProbe`/`EngineAuth` 每条连接都显式带它。
     *  2. `instanceFollowRedirects = false` —— 303 上带的 Set-Cookie 才是要的东西，跟过去就丢了。
     *  3. 只取 `name=value`，**把 `; Path=/; Max-Age=…` 等属性剥掉**再交给 CookieManager，
     *     否则 `setCookie` 拿到的是带属性的整行。
     */
    suspend fun fetchAuthCookie(token: String): String? = withContext(Dispatchers.IO) {
        val conn = try {
            URL("${baseUrl()}/?token=$token").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        } catch (e: Exception) {
            Log.w(TAG, "token 换取 cookie 建连失败", e)
            return@withContext null
        }
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 300..399) {
                Log.w(TAG, "token 换取 cookie 未返回 3xx，实际 $code")
                return@withContext null
            }
            val raw = conn.headerFields["Set-Cookie"].orEmpty()
                .firstOrNull { it.startsWith(COOKIE_PREFIX) }
                ?: return@withContext null
            // 观测引擎真实时窗（Max-Age），供自铸 cookie 复用
            maxAgeFromSetCookie(raw)?.let { observedMaxAgeMs = it }
            raw.substringBefore(';').takeIf { it.contains('=') }
        } catch (e: Exception) {
            Log.w(TAG, "token 换取 cookie 失败", e)
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 拿一个可用的鉴权 cookie。三条路按序退化，全都不行才返回 null：
     *
     *  1. **已持久化的 cookie**（跨 App 重启仍有效，签名密钥不变它就有效）；
     *  2. **P0 令牌交换**：engine.log 里的 launch token → 303 + Set-Cookie；
     *  3. **P1 自铸**：从 `.credentials.yaml` 的持久签名密钥自己签一个 cookie
     *     （算法与引擎一致：`v1.<b64url(payload)>.<b64url(HMAC-SHA256(secret, body))>`）。
     *
     * P1 是「日志里没有 token 行」时唯一还能拿到 cookie 的路 —— 移植上游 `EngineAuth`。
     */
    suspend fun ensureAuthCookie(): String? {
        cachedCookie?.let { if (cookieStillValid(it)) return it }
        authPrefs.getString(KEY_COOKIE, null)?.let {
            if (cookieStillValid(it)) {
                cachedCookie = it
                return it
            }
        }
        val token = launchToken()
        if (token != null) {
            fetchAuthCookie(token)?.let {
                storeCookie(it)
                Log.i(TAG, "cookie acquired via token exchange")
                return it
            }
        } else {
            Log.w(TAG, "engine.log 里没有可用的 launch token 行")
        }
        mintFromCredentials()?.let {
            storeCookie(it)
            Log.i(TAG, "cookie minted from credentials grant")
            return it
        }
        Log.w(TAG, "cookie 获取失败（无 token 行 / 交换出错 / 凭据记录缺失）")
        return null
    }

    /** 当前 cookie（不触发刷新）。UI 与探活都用它。 */
    fun currentCookie(): String? {
        cachedCookie?.let { if (cookieStillValid(it)) return it }
        val stored = authPrefs.getString(KEY_COOKIE, null) ?: return null
        if (!cookieStillValid(stored)) return null
        cachedCookie = stored
        return stored
    }

    private fun storeCookie(cookie: String) {
        cachedCookie = cookie
        authPrefs.edit().putString(KEY_COOKIE, cookie).apply()
    }

    private fun cookieStillValid(cookie: String): Boolean {
        val parts = cookie.substringAfter('=', "").split('.')
        if (parts.size != 3 || parts[0] != "v1") {
            // 交换来的 cookie 对壳侧不透明，没有 v1 结构 —— 只要没被显式失效就继续用，
            // 真失效了由 401 主帧的重试链兜（那时会重新走 ensureAuthCookie）。
            return cookie.startsWith(COOKIE_PREFIX)
        }
        return try {
            val payload = JSONObject(
                String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            )
            payload.optLong("expiresAt", 0L) > System.currentTimeMillis() &&
                payload.optString("authority") == cookieAuthority() &&
                cookie.substringBefore('=') == cookieName(cookieAuthority())
        } catch (_: Exception) {
            false
        }
    }

    /** HTTP Host / cookie authority 形态：`127.0.0.1:3080`。 */
    fun cookieAuthority(): String = "127.0.0.1:$activePort"

    private fun cookieName(authority: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(authority.toByteArray(StandardCharsets.UTF_8))
        return COOKIE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** 从 Set-Cookie 头解析 `Max-Age=<秒>`（毫秒）；解析不出返回 null。 */
    internal fun maxAgeFromSetCookie(setCookie: String?): Long? {
        if (setCookie == null) return null
        val m = Regex("""(?i)(?:^|;)\s*max-age\s*=\s*(\d+)""").find(setCookie) ?: return null
        val seconds = m.groupValues[1].toLongOrNull() ?: return null
        if (seconds <= 0L) return null
        return seconds * 1000L
    }

    /**
     * P1：从 `.credentials.yaml` 的持久签名密钥自铸 cookie。
     *
     * 只扫 `client-connection/browser-session` 那一段，绝不碰其他记录（那里放着用户 API key）。
     */
    private fun mintFromCredentials(): String? {
        val secret = credentialsSecret() ?: return null
        if (secret.size != 32) {
            Log.w(TAG, "凭据里的签名密钥长度异常: ${secret.size}")
            return null
        }
        return try {
            val authority = cookieAuthority()
            val now = System.currentTimeMillis()
            // 壳侧比引擎**早**失效 ⇒ 我们会主动刷新，用户永远看不到 401；
            // 反过来（晚失效）就会卡死在 401 页。
            val maxAgeMs = ((observedMaxAgeMs
                ?: (DEFAULT_COOKIE_MAX_AGE_DAYS * 24 * 60 * 60 * 1000)) -
                COOKIE_EXPIRY_SAFETY_MARGIN_MS).coerceAtLeast(60_000L)
            val payload = JSONObject()
                .put("version", 1)
                .put("authority", authority)
                .put("issuedAt", now)
                .put("expiresAt", now + maxAgeMs)
            val body = Base64.getUrlEncoder().withoutPadding()
                .encode(payload.toString().toByteArray(StandardCharsets.UTF_8))
                .toString(StandardCharsets.US_ASCII)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            val sig = Base64.getUrlEncoder().withoutPadding()
                .encode(mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)))
                .toString(StandardCharsets.US_ASCII)
            "${cookieName(authority)}=v1.$body.$sig"
        } catch (e: Exception) {
            Log.w(TAG, "自铸 cookie 失败: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun credentialsSecret(): ByteArray? {
        val file = credentialsFile
        if (!file.isFile) return null
        return try {
            var inRecords = false
            var inRecord = false
            var secret: String? = null
            for (raw in file.readLines(StandardCharsets.UTF_8)) {
                if (!inRecords) {
                    if (raw.startsWith("records:")) inRecords = true
                    continue
                }
                if (inRecord) {
                    // 记录块在下一个同级（2 空格）key 处结束
                    if (raw.startsWith("  ") && !raw.startsWith("   ")) break
                    val m = Regex("""^\s*secret:\s*([A-Za-z0-9_\-]+)\s*(#.*)?$""").find(raw)
                    if (m != null) {
                        secret = m.groupValues[1]
                        break
                    }
                } else if (raw.startsWith("  $SECRET_RECORD_KEY:")) {
                    inRecord = true
                }
            }
            secret?.let { Base64.getUrlDecoder().decode(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 运行时生成 profile 的 `cordis.patch.yml`，把三个 Android 适配插件挂进 web profile。
     *
     * 为什么要运行时生成而不是随资产写死：`shell-termux` 的配置里是**绝对路径**
     * （bash 在哪、prefix 在哪、home 在哪、workspace 根在哪），这些只有运行期才知道。
     *
     * 三个插件各管一段：
     *  · `shell-termux` —— 提供 dsh 的 shell 服务：让引擎能在 Android 上真正执行命令
     *    （bash / prefix / home / cwd / 写面栅栏 / 超时都在这里配）；
     *  · `ui-responsive` —— 移动端 UI 适配层：只注入 CSS/DOM 与移动外壳（不改布局服务中枢），
     *    让引擎自带的桌面 Web UI 在手机视口下可用；
     *  · `host-web-compat` —— 浏览器兼容层：给旧内核 WebView 注入缺失的 polyfill
     *    （AbortSignal.any / Promise.withResolvers / structuredClone / 全局 Iterator），
     *    没有它，客户端包在 import 期就抛 "Iterator is not defined"，整棵前端树加载失败 → 白屏；
     *  · `android-bridge` —— 手机管理授权桥：授权状态机、失败关闭、审计，
     *    提供 android_* 工具（读屏/点击/截图/启动应用/通知…），执行经由上面那个 shell 服务。
     *
     * 插件放在 profile 的 node_modules 下；它们的 peer 依赖（cordis / dsh-tools / schemastery /
     * dsh-client-ui-settings …）由 Node 的向上查找从引擎的 node_modules 解析得到。
     */
    private fun writeProfilePatch(): File {
        val f = File(home, "profiles/web/cordis.patch.yml")
        f.parentFile?.mkdirs()
        val bash = File(root, "bin/bash")
        f.writeText(
            buildString {
                appendLine("# 由 ZorvAI 生成：把 Android 适配插件挂进 harness 的 web profile。")
                appendLine("# 每次启动都会重写（路径是运行期绝对路径），删掉会退回引擎默认装配。")
                appendLine("- insert:")
                appendLine("    - id: shell-termux")
                appendLine("      name: '@dsh-android/dsh-shell-termux'")
                appendLine("      config:")
                appendLine("        # bash / 工具链都指向本运行时内置的那一份 Termux 用户态")
                appendLine("        # （与引擎同域、self-contained，不依赖 Termux App）")
                // bash / 工具链指向本运行时内置的那一份 Termux 用户态
                // （与引擎同域、self-contained，不依赖 Termux App）
                appendLine("        bashPath: ${bash.absolutePath}")
                appendLine("        prefix: ${root.absolutePath}")
                appendLine("        home: ${home.absolutePath}")
                appendLine("        cwd: ${home.absolutePath}")
                appendLine("        timeoutMs: 120000")
                appendLine("        maxTimeoutMs: 600000")
                appendLine("        # 写面栅栏：默认只允许工作区与选定共享目录")
                appendLine("        writeMode: workspace-write")
                appendLine("        workspaceRoot: ${workspace.absolutePath}")
                appendLine("        sharedDirs: []")
                appendLine("    - id: host-web-compat")
                appendLine("      name: '@dsh-android/dsh-host-web-compat'")
                appendLine("    - id: ui-responsive")
                appendLine("      name: '@dsh-android/dsh-client-ui-responsive'")
                appendLine("    - id: android-bridge")
                appendLine("      name: '@dsh-android/dsh-android-bridge'")
            }
        )
        return f
    }

    /**
     * 引擎与它的所有子进程共用的环境。
     *
     * 这一组变量不是可有可无的调优项 —— 每一条都对应一个「不设就必坏」的路径：
     *
     *  · `LD_PRELOAD` + `TERMUX_EXEC__*`：node 在 app 域 exec app-data 下的 ELF 会被拒
     *    （Android 15+ / 实测部分 Android 16 上 targetSdk 34 也拒）。Termux 的 execve 钩子会把
     *    被拒的 exec 改走 `/system/bin/linker64`（与 JNI 库同一机制）；钩子只对
     *    `untrusted_app_25/27` 域生效，所以**必须 force**。少了它，agent 的每次子进程调用都 EACCES。
     *  · `SHELL`：上游 subprocess-local 取 `process.env.SHELL ?? os.userInfo().shell`，
     *    而快照的 NSS 会把 userInfo().shell 解成 Termux 编译期前缀（app 域不可达）→ 终端建不起来。
     *  · `OPENSSL_CONF`：node 编译期把 OpenSSL 配置钉死在 Termux 路径，不覆盖则每个 node
     *    子进程启动即 exit（引擎本体不触发该初始化，所以会假装活着）。
     *  · `SSL_CERT_FILE` / `CURL_CA_BUNDLE` / `GIT_SSL_CAINFO`：证书路径同样被钉死，
     *    不覆盖则 curl/wget/git 的 https 校验失败。
     *  · `LD_LIBRARY_PATH`：node 的 9 个动态库与 bash 的依赖库都在私有目录。
     */
    private fun buildEnv(): Map<String, String> {
        val bin = File(root, "bin")
        val cnf = File(root, "etc/tls/openssl.cnf")
        val cert = File(root, "etc/tls/cert.pem")
        val preload = File(libDir, "libtermux-exec-ld-preload.so")
        val bash = File(bin, "bash")
        val m = linkedMapOf<String, String>()
        m.putAll(MoyuExec.baseEnv(context))
        m["PATH"] = bin.absolutePath + ":/system/bin:/system/xbin"
        m["LD_LIBRARY_PATH"] = libDir.absolutePath
        m["HOME"] = home.absolutePath
        m["DSH_HOME"] = home.absolutePath
        m["DSH_AGENTS_HOME"] = File(home, "agents").apply { mkdirs() }.absolutePath
        m["DSH_FILES_DIR"] = context.filesDir.absolutePath
        m["DSH_DOCUMENTS_DIRECTORY"] = documentsDir.absolutePath
        m["DSH_TELEMETRY_DISABLED"] = "1"
        m["NODE_OPTIONS"] = "--max-old-space-size=512"
        m["NO_COLOR"] = "1"
        m["TERM"] = "xterm-256color"
        m["TMPDIR"] = tmpDir.absolutePath
        if (bash.isFile) m["SHELL"] = bash.absolutePath
        if (cnf.isFile) m["OPENSSL_CONF"] = cnf.absolutePath
        if (cert.isFile) {
            m["SSL_CERT_FILE"] = cert.absolutePath
            m["CURL_CA_BUNDLE"] = cert.absolutePath
            m["GIT_SSL_CAINFO"] = cert.absolutePath
        }
        if (preload.isFile) {
            m["LD_PRELOAD"] = preload.absolutePath
            m["TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE"] = "force"
            m["TERMUX_EXEC__EXECVE_CALL__INTERCEPT"] = "1"
        }
        // Termux 用户态的路径口径（bash/termux-exec 据此推导自己的前缀）
        m["TERMUX__PREFIX"] = root.absolutePath
        m["TERMUX__ROOTFS"] = context.filesDir.absolutePath
        m["TERMUX_APP__DATA_DIR"] = context.filesDir.parentFile?.absolutePath
            ?: context.filesDir.absolutePath
        m.putAll(credentialEnv())
        // dir-pick 端点鉴权（fail-closed）：缺了它页面每 500ms 拿一次 403，永远转空
        m["DSH_PICK_TOKEN"] = ensurePickToken()
        return m
    }

    /**
     * 结束本应用遗留的引擎进程（上一轮超时/异常退出时可能没被收掉）。
     *
     * 只动两种情况同时成立的进程：cmdline 指向本应用的 dsh 入口/node/bash，且 Uid 与本进程一致。
     * 不能放宽 —— Android 上没有 `pkill` 可用，而误杀别人的进程是灾难性的。
     *
     * 三条 cmdline 判据覆盖两种 spawn 形态：直接 `execve(node)` 时命令行里是 `bin/node`
     * 与 dsh 入口；走 `/system/bin/linker64 <node>` 时命令行里是 `linker64` + `bin/node`。
     * @return 实际结束的进程数
     */
    private fun killStaleEngines(): Int {
        var killed = 0
        val selfPid = android.os.Process.myPid()
        val myUid = android.os.Process.myUid()
        val procRoot = File("/proc")
        procRoot.listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == selfPid) return@forEach
            val cmdline = runCatching {
                File(dir, "cmdline").readBytes().toString(Charsets.UTF_8).trimEnd('\u0000')
            }.getOrNull() ?: return@forEach
            if (!cmdline.contains("libnode.so") &&
                !cmdline.contains("bin/node") &&
                !cmdline.contains("bin/bash") &&
                !cmdline.contains("dsh/node_modules")
            ) {
                return@forEach
            }
            val sameUid = runCatching {
                File(dir, "status").readLines()
                    .firstOrNull { it.startsWith("Uid:") }
                    ?.removePrefix("Uid:")?.trim()?.split('\t', ' ')?.firstOrNull()?.toIntOrNull() == myUid
            }.getOrDefault(false)
            if (!sameUid) return@forEach
            val done = runCatching { android.os.Process.killProcess(pid) }.isSuccess
            if (done) killed++
            Log.i(TAG, "清理残留引擎进程 pid=$pid ok=$done")
        }
        return killed
    }

    // ─────────────────────────── 一次性任务（headless）───────────────────────────

    /**
     * 把一句话任务交给 harness 自己跑完：它内置的 `headless` profile 是「答一个任务就退出」，
     * 直接跑 core Agent/Session，**没有 Host、没有 HTTP、没有浏览器层**。
     *
     * 这是 AI 驱动 harness 干活的正确通道：
     *  - 答案走 stdout，诊断走 stderr（上游就这样约定）；
     *  - `--json` 输出逐行 NDJSON 事件（想看中间步骤就开）；
     *  - `--session-id` 可以续用已有会话（让多轮任务有上下文）。
     *
     * 依赖：模型凭据（DEEPSEEK_API_KEY 等）必须已配置，否则 harness 会在启动 agent 时失败。
     */
    suspend fun runTask(
        task: String,
        sessionId: String? = null,
        json: Boolean = false,
        timeoutMs: Long = 600_000L,
    ): MoyuExec.Result = withContext(Dispatchers.IO) {
        if (!isInstalled()) {
            return@withContext MoyuExec.Result(-1, "运行时未安装，先安装后再执行任务")
        }
        val args = mutableListOf(
            "--expose-internals",   // 同 start()：绕开缺失的 android-arm64 原生模块
            dshEntry.absolutePath,
            "--profile", "headless",
        )
        // headless 同样能吃到桥接层：这样「ZorvAI 把活交给 harness，harness 回头又能用
        // ZorvAI 的工具」这条双向链在一次性任务里也是通的。
        val bridge = File(root, "dsh-bridge.patch.yml")
        if (bridge.isFile) {
            args.clear()
            args += "--expose-internals"
            args += dshEntry.absolutePath
            args += "--patch"
            args += bridge.absolutePath
            args += "--profile"
            args += "headless"
        }
        if (json) args += "--json"
        if (!sessionId.isNullOrBlank()) {
            args += "--session-id"
            args += sessionId.trim()
        }
        args += task

        File(documentsDir, "deepseek-harness/default-workspace").mkdirs()
        workspace.mkdirs()
        val env = buildEnv()

        val r = MoyuExec.run(
            context = context,
            binary = nodeBin,
            args = args,
            workDir = workspace,
            env = env,
            timeoutMs = timeoutMs,
        )
        if (r.output.contains("API key", ignoreCase = true) ||
            r.output.contains("apiKey", ignoreCase = true) ||
            r.output.contains("unauthorized", ignoreCase = true) ||
            r.output.contains("401", ignoreCase = false)
        ) {
            return@withContext r.copy(
                output = r.output + "\n\n[提示] 看起来是模型凭据问题：请先设置 " +
                    "DEEPSEEK_API_KEY（或 OpenAI / Anthropic 的 key），" +
                    "也可以在 harness 自己的界面里配置。"
            )
        }
        r
    }

    /** 停止 harness（先礼后兵：TERM 给 3 秒，不退则 KILL）。 */
    fun stop() {
        val p = proc ?: return
        try {
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        } catch (e: Exception) {
            Log.w(TAG, "停止失败", e)
        } finally {
            proc = null
        }
    }

    /** 重启：换个端口重来，避免上个实例残留占用。 */
    suspend fun restart(port: Int = activePort): Result<Int> {
        stop()
        delay(600)
        return start(port)
    }

    /**
     * 端口是否被占用。
     *
     * 只回答「有没有东西在 listen」，**不回答「是不是我们的引擎、页面能不能服务」**——
     * 后者由 [probeEngine] 的 HTTP 探活负责（上游 `EngineAvailability` 的
     * OUR_HTTP / PORT_FOREIGN 之分就是为这个存在的）。
     */
    private fun portOpen(port: Int): Boolean = try {
        Socket(Proxy.NO_PROXY).use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 400)
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * HTTP 探活（移植上游 `EngineProbe` 的判据）。
     *
     * 三条判据必须分清，否则"启动了但白屏"会被误判成没启动：
     *  · **listening** —— TCP 握得上手；
     *  · **serving** —— 拿到 200（已认证）/ 303（等令牌交换）/ 401（活着但缺 cookie）。
     *    401 是**就绪**而不是故障：它证明 HTTP 层与鉴权中间件都在正常工作。
     *    上游 `running = 200/401/303` 与 `auth = ok/required/unknown` 正是正交的两件事，
     *    绝不能让看门狗因为 401 去杀一个健康的引擎。
     *
     * `Proxy.NO_PROXY` 同样不能省：`URL.openConnection()` 默认走系统 ProxySelector，
     * WiFi 上配了代理时 loopback 请求会被送去代理网关，表现为「浏览器能打开、
     * App 说引擎没起来」（上游 issue #118 就是这条）。
     */
    private fun probeEngine(port: Int): Probe = try {
        val conn = URL("http://127.0.0.1:$port/")
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            Probe(code = conn.responseCode, listening = true)
        } finally {
            runCatching { conn.disconnect() }
        }
    } catch (e: Exception) {
        // 连不上：区分「拒连」（没进程）与「超时」（僵死）对定位很有用
        Probe(code = 0, listening = false, error = "${e.javaClass.simpleName}: ${e.message}")
    }

    /** 探活结果的人类可读描述 —— 直接进日志与诊断页，不让人猜。 */
    private fun describeProbe(p: Probe): String = when {
        p.error != null -> "无法连接（${p.error}）"
        p.code == 200 -> "HTTP 200，已认证可访问"
        p.code == 303 -> "HTTP 303，等待令牌交换（引擎活着）"
        p.code == 401 -> "HTTP 401，引擎活着但缺鉴权 cookie"
        p.code == 404 -> "HTTP 404，HTTP 层活着但路由未命中（前端 dist 可能没装全）"
        else -> "HTTP ${p.code}（端口 ${if (p.listening) "在监听" else "不在监听"}）"
    }

    /** index 正文的结构体检结果。 */
    data class IndexProbe(
        val code: Int,
        val length: Int,
        val hasBootstrap: Boolean,
        val hasBaseHref: Boolean,
        val hasReadyHook: Boolean,
        val head: String,
        val error: String? = null,
    ) {
        /** 三条全中才算「前端树装配完」——少任何一条，打开就是白屏。 */
        val ready: Boolean get() = code == 200 && hasBootstrap && hasBaseHref
    }

    /**
     * 取一次 index 正文并做结构校验（移植上游「启动前先验 index」的思路）。
     *
     * 为什么要读正文而不是只看状态码：见 [BOOTSTRAP_MARKER] 的注释 ——
     * HTTP 层就绪与 `dsh-client-modules` 装配完之间有窗口期，窗口期里的 index
     * **不带 bootstrap 脚本**，WebView 打开就是一个脚本都不加载的纯白屏。
     *
     * 带上 cookie 取：不带会拿到 401 的 text/plain（那同样「能连上」），
     * 而我们要检的正是 200 正文里的那一行。
     */
    private fun fetchIndex(port: Int, cookie: String?): IndexProbe {
        val conn = try {
            URL("http://127.0.0.1:$port/").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        } catch (e: Exception) {
            return IndexProbe(0, 0, false, false, false, "", "${e.javaClass.simpleName}: ${e.message}")
        }
        return try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 4000
            conn.readTimeout = 10_000
            if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = runCatching {
                stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
            }.getOrNull().orEmpty()
            IndexProbe(
                code = code,
                length = text.length,
                hasBootstrap = text.contains(BOOTSTRAP_MARKER),
                hasBaseHref = text.contains(BASE_HREF_MARKER, ignoreCase = true),
                hasReadyHook = text.contains(READY_HOOK_MARKER),
                head = text.take(600).ifBlank { "（正文为空）" },
            )
        } catch (e: Exception) {
            IndexProbe(0, 0, false, false, false, "", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** index 体检的人类可读描述 —— 直接进日志与诊断页。 */
    private fun describeIndex(i: IndexProbe): String = when {
        i.error != null -> "取 index 失败（${i.error}）"
        i.code == 401 -> "HTTP 401，还没带上可用 cookie"
        i.code == 303 -> "HTTP 303，等着换 cookie"
        i.code != 200 -> "HTTP ${i.code}，非 200"
        i.ready -> "HTTP 200，${i.length}B，bootstrap 脚本与 <base> 都在 ✓"
        !i.hasBootstrap && !i.hasBaseHref ->
            "HTTP 200，${i.length}B，但既无 bootstrap 脚本也无 <base> —— 插件装配层没生效"
        !i.hasBootstrap ->
            "HTTP 200，${i.length}B，有 <base> 但无 bootstrap 脚本 —— dsh-client-modules 还没装配完"
        else -> "HTTP 200，${i.length}B，有 bootstrap 脚本但无 <base> —— 文档相对 URL 解析不了"
    }

    /**
     * 等 index 真的带着 bootstrap 脚本。
     *
     * @param isAlive 引擎进程是否还活着（死了就别等了）
     * @return 就绪返回 null；超时返回最后一次体检描述
     */
    private suspend fun awaitIndexReady(
        port: Int,
        deadline: Long,
        isAlive: () -> Boolean,
    ): String? {
        var last = "（尚未取到 index）"
        var cookieRefreshed = false
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive()) return last
            var cookie = currentCookie()
            if (cookie == null && !cookieRefreshed) {
                cookie = ensureAuthCookie()
                cookieRefreshed = true
            }
            val idx = fetchIndex(port, cookie)
            last = describeIndex(idx)
            if (idx.ready) return null
            // 401 = cookie 过期/没取到：重取一轮再试（只重取一次，避免坏 token 打成死循环）
            if (idx.code == 401 && !cookieRefreshed) {
                cookieRefreshed = true
                continue
            }
            delay(500)
        }
        return last
    }

    /**
     * engine.log 三代轮转。
     *
     * [ProcessBuilder.redirectOutput] 用文件重定向时以**截断**模式打开目标文件 ——
     * 不轮转，上一次启动的崩溃现场（也是唯一写着 launch token 的那份日志）就被抹掉了。
     * 上游 `EngineManager` 同样保留 3 代。
     */
    private fun rotateEngineLog() {
        runCatching {
            val cur = engineLog
            if (!cur.isFile) return@runCatching
            val dir = cur.parentFile ?: return@runCatching
            // 从最老一代开始挪，避免 src → dst 覆盖时踩到还没读的
            for (i in ENGINE_LOG_GENERATIONS - 1 downTo 1) {
                val src = File(dir, "${cur.name}.$i")
                if (!src.isFile) continue
                val dst = File(dir, "${cur.name}.${i + 1}")
                if (dst.isFile) dst.delete()
                src.renameTo(dst)
            }
            File(dir, "${cur.name}.1").takeIf { it.isFile }?.delete()
            cur.renameTo(File(dir, "${cur.name}.1"))
        }.onFailure { Log.w(TAG, "engine.log 轮转失败", it) }
    }

    /**
     * 尾随 engine.log，把新行搬进 ring buffer（给界面与诊断看）。
     *
     * 只跟本代进程 [p]：它一退出就收尾停跟，避免每次重启都漏一个线程和一份 fd。
     * 行边界按 `\n` 切，尾巴不完整的一段留在缓冲里等下一次读 —— 直接按 chunk 切会把
     * 一行日志劈成两条。
     */
    private fun tailEngineLog(p: Process) {
        try {
            val f = engineLog
            val deadline = System.currentTimeMillis() + 10_000
            while (!f.isFile && System.currentTimeMillis() < deadline) Thread.sleep(100)
            if (!f.isFile) return
            RandomAccessFile(f, "r").use { raf ->
                // 只跟本次启动之后的增量（轮转保证文件开头就是本代引擎的第一行）
                var pos = f.length()
                val buf = StringBuilder()
                while (true) {
                    val len = runCatching { f.length() }.getOrDefault(pos)
                    if (len < pos) {
                        pos = 0 // 被外部截断/轮转，回到文件头
                        buf.setLength(0)
                    }
                    if (len > pos) {
                        raf.seek(pos)
                        val want = (len - pos).coerceAtMost(64L * 1024).toInt()
                        val chunk = ByteArray(want)
                        val n = raf.read(chunk)
                        if (n > 0) {
                            buf.append(String(chunk, 0, n, StandardCharsets.UTF_8))
                            pos += n
                            while (true) {
                                val idx = buf.indexOf("\n")
                                if (idx < 0) break
                                val line = buf.substring(0, idx).trimEnd('\r')
                                buf.delete(0, idx + 1)
                                if (line.isNotBlank()) appendLog(line)
                            }
                            // 只有 \r 的进度条之类：攒太长就当一行吐掉，别让缓冲无限长
                            if (buf.length > 8192) {
                                appendLog(buf.toString())
                                buf.setLength(0)
                            }
                        }
                    } else if (!p.isAlive) {
                        // 进程已退出：最后收一次尾，没有新内容就停
                        Thread.sleep(300)
                        if (runCatching { f.length() }.getOrDefault(pos) <= pos) return
                    } else {
                        Thread.sleep(250)
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "尾随 engine.log 失败", e)
        }
    }

    // ─────────────────────────── 日志与诊断 ───────────────────────────

    private fun appendLog(line: String) {
        synchronized(logRing) {
            logRing.addLast(line)
            while (logRing.size > 800) logRing.removeFirst()
        }
    }

    fun logs(): String = synchronized(logRing) { logRing.joinToString("\n") }

    fun clearLogs() = synchronized(logRing) { logRing.clear() }

    /** 逐环自检：缺哪一环就直接说，不让人对着"启动失败"猜。 */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val busybox = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
        val bash = File(root, "bin/bash")
        val spawn = File(
            root,
            "dsh/node_modules/node-pty/prebuilds/linux-arm64/spawn-helper"
        )
        val profilePatch = File(home, "profiles/web/cordis.patch.yml")
        val bridgePatch = File(root, "dsh-bridge.patch.yml")
        buildString {
            appendLine("=== DeepSeek Harness 运行时诊断 ===")
            appendLine("设备 ABI      : " + android.os.Build.SUPPORTED_ABIS.joinToString(","))
            appendLine("版本          : " + android.os.Build.VERSION.RELEASE +
                " (API " + android.os.Build.VERSION.SDK_INT + ")")
            appendLine()
            appendLine("运行时目录    : " + root.absolutePath +
                (if (root.isDirectory) " ✓" else " ✗"))
            appendLine("安装标记      : " +
                (markFile.takeIf { it.isFile }?.readText()?.trim() ?: "（无）") +
                if (isInstalled()) " ✓ 判定已装齐" else " ✗ 判定未装齐")
            appendLine("node          : " + nodeBin.absolutePath +
                if (nodeBin.isFile) {
                    " ✓ ${nodeBin.length() / 1048576}MB" +
                        (if (nodeBin.canExecute()) " 可执行" else " ✗ 缺执行位")
                } else " ✗ 缺失")
            appendLine("bash          : " +
                if (bash.isFile) {
                    "✓" + (if (bash.canExecute()) " 可执行" else " ✗ 缺执行位")
                } else "✗ 缺失（shell 服务起不来）")
            appendLine("dsh 入口      : " +
                (if (dshEntry.isFile) "✓" else "✗ 缺失 " + dshEntry.absolutePath))
            appendLine("前端 dist     : " +
                if (distIndex.isFile) "✓ ${distIndex.length()}B"
                else "✗ 缺失 → WebView 拿到空正文 = 纯白屏（无任何文字）")
            appendLine("动态库目录    : " + libDir.absolutePath + " (" +
                (libDir.listFiles()?.size ?: 0) + " 个)")
            appendLine("DSH_HOME      : " + home.absolutePath)
            appendLine("工作区        : " + workspace.absolutePath)
            appendLine("memfd 启动器  : " +
                (if (MoyuExec.hasLauncher(context)) "✓" else "✗ 缺 libmoexec.so"))
            appendLine("busybox(xz)   : " +
                (if (busybox.isFile) "✓" else "✗ 缺 libbusybox.so"))
            appendLine("pty 原生模块  : " +
                (if (File(root, "dsh/node_modules/node-pty/prebuilds/linux-arm64/pty.node").isFile)
                    "✓ bionic 版" else "✗ 缺失"))
            appendLine("spawn-helper  : " +
                (if (spawn.isFile) "✓" + (if (spawn.canExecute()) " 可执行" else " 缺执行位")
                else "✗ 缺失"))
            appendLine()
            appendLine("profile patch : " +
                if (profilePatch.isFile) "✓ 已写入（Android 适配插件装配层）"
                else "✗ 缺失（下次启动自动补写）")
            appendLine("bridge patch  : " +
                if (bridgePatch.isFile) "✓ MCP 能力桥已挂"
                else "（未挂，AI 工具不会进 harness）")
            appendLine()
            appendLine("engine.log    : " + engineLog.absolutePath +
                if (engineLog.isFile) {
                    " ✓ ${engineLog.length() / 1024}KB，修改于 " +
                        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date(engineLog.lastModified()))
                } else " （尚无）"
            )
            appendLine("token 可读性  : " +
                if (launchToken() != null) "✓ 能从日志取到 launch token"
                else "✗ 取不到（靠 P1 自铸兑底）")
            appendLine("鉴权 cookie   : " +
                if (currentCookie() != null) "✓ 有可用 cookie" else "✗ 无（打开即 401）")
            appendLine("pick token    : " +
                if (currentPickToken() != null) "✓ 已生成（dir-pick 轮询可鉴权）"
                else "✗ 未生成（dir-pick 每 500ms 拿 403）")
            appendLine("构建戳        : $BUILD_STAMP（版本号锁死，区分包装只能靠它）")
            appendLine("凭证文件      : " +
                if (credentialsFile.isFile) "✓（可自铸 cookie）" else "✗（自铸兜底不可用）")
            appendLine()
            appendLine("进程状态      : " + if (isRunning()) "运行中（端口 $activePort）" else "未运行")
            if (!isRunning() && lastExit != null) appendLine("上次退出      : $lastExit")
            appendLine()
            if (isRunning()) {
                appendLine("=== HTTP 探活 ===")
                appendLine(describeProbe(probeEngine(activePort)))
                appendLine(describeIndex(fetchIndex(activePort, currentCookie())))
                appendLine()
            }
            appendLine("=== 最近日志（尾部）===")
            appendLine(logs().takeLast(1500).ifBlank { "（暂无输出）" })
        }
    }
}

/**
 * 快照文件可执行性判据（移植上游 `SnapshotFileMode`）。
 *
 * **为什么按内容判而不按 tar mode**：实测内置运行时里 `bin/node`(50,993,616 B) 与
 * `bin/bash`(880,416 B) 的 tar mode 都是 `0644`，执行位在打包链路上就丢了。Windows /
 * WSL 挂载下的 chmod 只有只读位，Unix 执行位根本存不下来。
 *
 * 判据：ELF 魔数（`7f 45 4c 46`）或 `#!` shebang ⇒ 应当可执行。其余（`.node` 插件是
 * dlopen 的、`.md` / JSON / wasm）一律不动。
 */
internal object SnapshotFileMode {
    fun isDirectlyExecutable(prefix: ByteArray, length: Int): Boolean {
        if (length >= 4 &&
            prefix[0] == 0x7f.toByte() &&
            prefix[1] == 'E'.code.toByte() &&
            prefix[2] == 'L'.code.toByte() &&
            prefix[3] == 'F'.code.toByte()
        ) {
            return true
        }
        return length >= 2 && prefix[0] == '#'.code.toByte() && prefix[1] == '!'.code.toByte()
    }
}

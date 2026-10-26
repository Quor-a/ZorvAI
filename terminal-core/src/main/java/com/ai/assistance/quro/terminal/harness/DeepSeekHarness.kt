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
         * 16 = 引入「按内容嗅探补执行位」与 engine.log 持久化。
         * 17 = 浏览器语法下限降级（对齐参考实现 kelai141/dsh-mobile-apk 的
         *      check-browser-syntax-floor 门禁）：dist 与全部 lib/client.js 在构建期用
         *      esbuild@0.25.12 降到 chrome87，消除入口 chunk 的 `class static{}`
         *      （Chromium 94+）—— 老 WebView 解析期 SyntaxError ⇒ 白屏无报错。
         *      **必须 +1**：老设备已按 v=16 解压过未降级字节，不升版本不会重解。
         * 18 = 直接移植 kelai141/dsh-mobile-apk 全量插件栈（12 插件 + marketplace +
         *      canvas + pnpm + 两个 shim，删 usr/ 瘦身）—— 该包误打了 xz，真机解压报
         *      "Not in GZIP format" 安装失败。
         * 19 = 同一暂存树改回 gz 重打（App 侧纯 Java GZIPInputStream，不认 xz）。
         *      **必须 +1**：v=18 安装全部失败，但个别设备可能留下半解压树，不升版本
         * 20 = profile package.json 补 `dsh.profile.bundles`（dsh-base + dsh-web-app）——
         *      缺它整棵引擎条目树不装配：webserver/tools/subprocess/commands 等服务方
         *      一个都不在树里，10 个 @dsh-android 插件 + dsh-mcp-client 全部
         *      `pending (waiting for service: …)`（2316 包真机实测 11 entries did not
         *      activate）。pack 字节已变，**必须 +1** 触发全量重解压覆盖旧 package.json；
         *      Kotlin 侧另加 ensureProfileBundles() 每次启动自愈（仿参考实现的
         *      ensureHardBundlesSelected），双保险。
     * 21 = 白屏根因三连修（#389 / #389b / #390）：
     *      #389 host-webserver READY_MARKUP 的 `??=`（Chrome 85+ SyntaxError，解析期
     *      整段死）重写为 ES5 手工 deferred；注入行模板 globalThis→self。
     *      #389b 9 文件 130 处动态 `import()` 改写为 `__dshImp` 三路径桥（原生 /
     *      CSP 外链 module / <63 inline module）+ 共享注册表 `self.__dshReg`。
     *      #390 Kotlin 侧：WebView 底色钉深色、注入 JS 的 globalThis→self、
     *      UA 直读兜底种子。pack 字节已变，**必须 +1** 触发全量重解压覆盖旧 dist。
     * 22 = 完整内置 kelai141/dsh-mobile-apk 的 rc.2 适配集（pack 字节已变，**必须 +1**）：
     *      engine 侧 8 条（SPJ F5 补 rename import + materialize 站走 helper；
     *      F7/F8/H1/F6/N1/B2 语义复核；pi-ai 官方 pi-upstream-streaming-020 六文件）、
     *      marketplace 侧 3 条（E 仓库折叠 / F 分页条 / J 全宽面板）+ 16 条配套 CSS。
     *      另加 Kotlin 侧 applyRuntimePatches 覆盖式自愈（assets/patched 三件套，
     *      与本 pack 内字节同源；树健康时 no-op）。
     */
        private const val RUNTIME_VERSION = 22
        private const val MARK = ".dsh_v"

        /** 运行时补丁资产目录（assets/patched）：覆盖式自愈的补丁版字节。 */
        private const val ASSET_PATCH_DIR = "patched"

        /** 补丁应用记录目录（应用私有，随卸载清除；**不**进运行时树，避免被快照整体换掉）。 */
        private const val ASSET_PATCH_RECORD_DIR = "asset-patch-records"

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
         * 就等于装错包，这一条是出包自证链的一部分（同 Moyu 的做法）。
         */
        const val BUILD_STAMP = "B20261027-0226"

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

        // 工厂 bundle 清单自愈（缺了它整棵引擎条目树不装配，见 ensureProfileBundles）
        runCatching { ensureProfileBundles() }
            .onFailure { Log.w(TAG, "profile bundle 自愈失败", it) }

        // PTC node wrapper + pnpm shim（幂等烧写，路径按运行期实际值）——同样必须在启动前
        runCatching { ensureRuntimeShims() }
            .onFailure { Log.w(TAG, "烧写运行时 shim 失败", it) }

        // 运行时补丁覆盖（幂等自愈）：把 assets/patched 下我们自己的补丁版写回运行时树。
        // 树健康时是纯 no-op；快照被旧 pack 污染/解压写坏时靠它每次 spawn 前纠回来。
        runCatching { applyRuntimePatches() }
            .onFailure { Log.w(TAG, "应用运行时补丁失败", it) }

        // 挂上宿主的手机能力：把 ZorvAI 的全部工具经互控桥注册给引擎，
        // harness 的 agent 于是能直接用 read_screen / tap_screen / swipe_screen /
        // launch_app / shizuku_exec / screenshot 等 —— 两条通道：MCP（patch 启动期读）
        // + 内置插件（端点清单零配置发现）；ensureBridge 幂等，每次 spawn 前重写端点。
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
     * 运行时生成 profile 的 `cordis.patch.yml` —— web profile 的完整 Android 装配层。
     *
     * **移植面**：dsh-mobile-apk 0.14.8 的 `scripts/profile-web.cordis.patch.yml`（权威装配），
     * 路径全部换成运行期绝对路径（Termux 前缀在我的树里就是 root 本身：bin/bash、lib/、etc/）。
     *
     * 为什么要运行时生成而不是随资产写死：配置里是**绝对路径**（bash 在哪、prefix 在哪、
     * home 在哪、workspace 根在哪），这些只有运行期才知道。
     *
     * 装配内容（与参考一一对应）：
     *  · insert 11 块 / 12 行：
     *    - `shell-termux`（带 config）—— dsh 的 shell 服务：bash/prefix/home/cwd/写面栅栏/超时；
     *    - `host-web-compat` —— 浏览器兼容层：给旧内核 WebView 注入缺失的 polyfill
     *      （AbortSignal.any / Promise.withResolvers / structuredClone / 全局 Iterator），
     *      没有它，客户端包在 import 期就抛 "Iterator is not defined"，整棵前端树加载失败 → 白屏；
     *    - `ui-responsive` —— 移动端 UI 适配层：只注入 CSS/DOM 与移动外壳（不改布局服务中枢）；
     *    - `dshmarketplace` —— 插件市场（会话 /store 命令、设置页市场、agent 工具与技能文件）；
     *    - `android-bridge` —— 手机管理授权桥：授权状态机、失败关闭、审计；
     *    - `android-manage` —— 手机管理工具面：观察/动作/等待闭环，全部失败关闭；
     *    - `android-linux-env` —— 工具链与环境设置面板（同时是 shell-termux 写面栅栏的设置维护面）；
     *    - `android-file-open` —— 外部文件直达会话；
     *    - `picker-native-surface` —— 原生目录选择面（禁掉 auto 后由它独占能力）；
     *    - `android-browser` —— 侧栏 AI 浏览器宿主半；
     *    - `android-vdisplay` —— 虚拟屏能力状态面 + vd* op 声明；
     *    - `session-delete` —— 会话行菜单「删除对话」+ 受鉴权 host 路由。
     *  · disabled 8 行：bash-sandbox（Android 上无对应沙盒实现，写面栅栏改由 shell-termux 承担）、
     *    open-in-app / ui-open-in-app（桌面编排，Android 无等价候选）、
     *    client-hmr（`/plugins/events` SSE 无鉴权，移动侧无 live reload 收益）、
     *    directory-picker（auto 在 Android 上解析成 browse，与 host-web-compat 的原生单例冲突）、
     *    office-to-pdf（libreoffice-kit 的 platformTarget 不认 android，无条件抛，禁行是上游既定降级面）、
     *    desktop-product-telemetry / product-analytics（桌面态产品遥测，非 Android 服务）。
     *  · config 就地覆盖 2 行（**整体替换不是深合并**，键要给全）：
     *    - `agent-default-model` → deepseek-official / deepseek-v4-flash（零额外配置、国内可达）；
     *    - `ptc-runtime` → launch: node-script，executable 钉死到 `libexec/dsh-node` 包装器
     *      （process.execPath 在 linker64 回退时被污染成 linker64 自身，PTC 子进程必死）。
     *
     * 插件放在 profile 的 node_modules 下；它们的 peer 依赖（cordis / dsh-tools / schemastery /
     * dsh-client-ui-settings …）由引擎 profile launcher 的 two-anchor 解析路由器从安装锚点
     * （dsh/node_modules）供给（dsh-app-boot 的 installProfileResolution，ESM+CJS 双包装）。
     */
    private fun writeProfilePatch(): File {
        val f = File(home, "profiles/web/cordis.patch.yml")
        f.parentFile?.mkdirs()
        val bash = File(root, "bin/bash")
        val ptcWrapper = File(root, "libexec/dsh-node")
        val yaml = buildString {
                appendLine("# 由 ZorvAI 生成：web profile 的完整 Android 装配层。")
                appendLine("# 移植自 dsh-mobile-apk 0.14.8 profile-web.cordis.patch.yml（路径换运行期绝对路径）。")
                appendLine("# 每次启动都会重写，删掉会退回引擎默认装配。")
                appendLine("# bash-sandbox：Android 无对应沙盒实现，写面栅栏由 shell-termux 的 writeMode 承担")
                appendLine("- id: bash-sandbox")
                appendLine("  disabled: true")
                appendLine("- insert:")
                appendLine("    - id: shell-termux")
                appendLine("      name: '@dsh-android/dsh-shell-termux'")
                appendLine("      config:")
                appendLine("        # bash / 工具链都指向本运行时内置的那一份 Termux 用户态")
                appendLine("        # （与引擎同域、self-contained，不依赖 Termux App）")
                appendLine("        bashPath: ${bash.absolutePath}")
                appendLine("        prefix: ${root.absolutePath}")
                appendLine("        home: ${home.absolutePath}")
                appendLine("        cwd: ${home.absolutePath}")
                appendLine("        timeoutMs: 120000")
                appendLine("        maxTimeoutMs: 600000")
                appendLine("        # 写面栅栏：默认只允许工作区与选定共享目录")
                appendLine("        # （workspaceRoot/sharedDirs 由 dsh-android-linux-env 在设置页动态维护）")
                appendLine("        writeMode: workspace-write")
                appendLine("        workspaceRoot: ${workspace.absolutePath}")
                appendLine("        sharedDirs: []")
                appendLine("    - id: host-web-compat")
                appendLine("      name: '@dsh-android/dsh-host-web-compat'")
                appendLine("# ui-responsive：只注入 CSS/DOM 与移动外壳；ui-layout 是布局服务中枢，不禁用")
                appendLine("- insert:")
                appendLine("    - id: ui-responsive")
                appendLine("      name: '@dsh-android/dsh-client-ui-responsive'")
                appendLine("# 插件市场：/store 命令、设置页市场、agent 工具与技能文件")
                appendLine("- insert:")
                appendLine("    - id: dshmarketplace")
                appendLine("      name: dshmarketplace-plugin")
                appendLine("# open-in-app / ui-open-in-app：桌面编排（Finder/Terminal/编辑器），Android 无等价候选")
                appendLine("- id: open-in-app")
                appendLine("  disabled: true")
                appendLine("- id: ui-open-in-app")
                appendLine("  disabled: true")
                appendLine("# client-hmr：/plugins/events SSE 无鉴权，移动侧无 live reload 收益，禁行收口端口面")
                appendLine("- id: client-hmr")
                appendLine("  disabled: true")
                appendLine("# 手机管理授权桥：门控限死为完全访问档位（自动审批模式不构成开放条件）")
                appendLine("- insert:")
                appendLine("    - id: android-bridge")
                appendLine("      name: '@dsh-android/dsh-android-bridge'")
                appendLine("# 手机管理工具面：观察/动作/等待闭环，全部失败关闭")
                appendLine("- insert:")
                appendLine("    - id: android-manage")
                appendLine("      name: '@dsh-android/dsh-android-manage'")
                appendLine("# 工具链与环境设置面板：shell-termux 写面栅栏的设置维护面，不装载则共享目录设置无处维护")
                appendLine("- insert:")
                appendLine("    - id: android-linux-env")
                appendLine("      name: '@dsh-android/dsh-android-linux-env'")
                appendLine("# 外部文件强制新会话请求/状态/清理")
                appendLine("- insert:")
                appendLine("    - id: android-file-open")
                appendLine("      name: '@dsh-android/dsh-android-file-open'")
                appendLine("# 默认模型：deepseek-official 零额外配置、国内可达、开箱即用")
                appendLine("# （就地按上游 id 覆盖 config，不再 disable + 换 id insert——后者会引入服务供给单点）")
                appendLine("- id: agent-default-model")
                appendLine("  config:")
                appendLine("    provider: deepseek-official")
                appendLine("    model: deepseek-v4-flash")
                appendLine("# directory-picker：auto 在 Android 上解析成 browse，与 host-web-compat 的原生单例")
                appendLine("# 冲突（后注册者抛 duplicate service，整个 auto mount 失败）→ 禁 auto，显式挂原生面")
                appendLine("- id: directory-picker")
                appendLine("  disabled: true")
                appendLine("- insert:")
                appendLine("    - id: picker-native-surface")
                appendLine("      name: '@deepseek-ai/dsh-client-ui-directory-picker-native'")
                appendLine("# 侧栏 AI 浏览器（宿主半：tier 判定、面板数据契约、壳桥 op 名）")
                appendLine("- insert:")
                appendLine("    - id: android-browser")
                appendLine("      name: '@dsh-android/dsh-android-browser'")
                appendLine("# 虚拟屏：Shizuku 特权通道的能力状态面 + 只读状态端点 + vd* op 声明")
                appendLine("- insert:")
                appendLine("    - id: android-vdisplay")
                appendLine("      name: '@dsh-android/dsh-android-vdisplay'")
                appendLine("# PTC 代码执行：executable 必须钉死到 wrapper，不得依赖 process.execPath")
                appendLine("# （linker64 回退时 execPath 被污染成 linker64 自身，PTC 子进程必死；")
                appendLine("#  且 PTC 子进程 env 被 tombstone，LD_LIBRARY_PATH 继承不到，由 wrapper 自己 export）")
                appendLine("- id: ptc-runtime")
                appendLine("  config:")
                appendLine("    launch:")
                appendLine("      kind: node-script")
                appendLine("      executable: ${ptcWrapper.absolutePath}")
                appendLine("# office-to-pdf：libreoffice-kit 的 platformTarget 不认 android ⇒ 无条件抛，")
                appendLine("#  不禁该行会在 boot 阶段崩；禁行 = 用上游自己的 unavailable 降级面")
                appendLine("- id: office-to-pdf")
                appendLine("  disabled: true")
                appendLine("# 桌面态产品遥测/分析不是 Android 服务（保留共享 otel 与官方 session-log 上传控制）")
                appendLine("- id: desktop-product-telemetry")
                appendLine("  disabled: true")
                appendLine("- id: product-analytics")
                appendLine("  disabled: true")
                appendLine("# 删除对话：会话行菜单「删除对话」+ 受鉴权 host 路由 /api/session-delete")
                appendLine("- insert:")
                appendLine("    - id: session-delete")
                appendLine("      name: '@dsh-android/dsh-session-delete'")
        }
        // 结构自检（2026-10-24 真机教训）：每一非空行必须是「# 注释 / - 序列项 /
        // key: 键」之一。多行注释的续行一旦漏了 # 前缀，js-yaml 就报
        // "bad indentation of a sequence entry" → 整个 profile 解析失败 →
        // 引擎启动即退出（2247 包实测，报错 65:2）。**写盘前拦下**：
        // 宁可退回上一份好 YAML（引擎用旧装配也能起），也绝不让坏 YAML 落盘。
        val badLine = yaml.lines().withIndex().firstOrNull { (_, line) ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("-") &&
                !Regex("^[A-Za-z_][\\w.-]*:").containsMatchIn(t)
        }
        require(badLine == null) {
            "profile patch 第 ${badLine?.index?.plus(1)} 行既非注释也非键（拒绝写盘）: " +
                badLine?.value?.take(60)
        }
        f.writeText(yaml)
        return f
    }

    /**
     * 确保 profile 的工厂 bundle 清单就位（幂等，仿参考实现的 ensureHardBundlesSelected）。
     *
     * **为什么必须有**：profile 的条目树 = `package.json` 里 `dsh.profile.bundles` 的
     * bundle 层（dsh-base + dsh-web-app 的 cordis.patch.yml）+ 本 profile 的
     * cordis.patch.yml + --patch 覆盖层。缺了 bundles 字段，引擎自带的 ~190 个条目
     * （webserver / tools / subprocess / commands / sessions… 全部服务提供方）一个都不进树，
     * 我们 insert 的 10 个 @dsh-android 插件就全部 `pending (waiting for service: …)`
     * ——2316 包真机实测「11 entries did not activate」，引擎等于空转。
     *
     * pack 里已修好（v=20 重解压覆盖），这里是运行时自愈的第二道保险：
     * 解压被跳过 / 老设备残树 / 用户手改坏了的场景，每次启动补回缺失的工厂项。
     * 只追加缺失项，用户自装插件（soft 选择）与其它字段原样保留；base 必须排在
     * web-app 前（web-app 的 patch 层叠在 base 之上）。
     *
     * @param profile profile 名（web / headless）
     * @param factory 该 profile 的工厂 bundle 清单（顺序即 patch 层叠顺序）
     * @param canvasDeps 文件缺失重建时是否登记 @napi-rs canvas 依赖（仅 web 需要）
     */
    private fun ensureProfileBundles(
        profile: String = "web",
        factory: List<String> = listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app"),
        canvasDeps: Boolean = true,
    ) {
        val f = File(home, "profiles/$profile/package.json")
        val json = if (f.isFile) {
            runCatching { JSONObject(f.readText()) }.getOrNull()
                ?: run {
                    appendLog("[profile] $profile/package.json 不是合法 JSON，跳过 bundle 自愈（交给引擎报错）")
                    return
                }
        } else {
            // 文件缺失时引擎虽会按模板自建，但会丢依赖登记（canvas 会被 pnpm 当孤儿清掉）
            // ——这里补一份完整的。
            JSONObject().put("name", profile).put("private", true).put("version", "0.0.0")
                .put("dependencies", if (canvasDeps) JSONObject()
                    .put("@napi-rs/canvas", "^1.0.8")
                    .put("@napi-rs/canvas-android-arm64", "1.0.8") else JSONObject())
        }
        val profileSection = json.optJSONObject("dsh")?.optJSONObject("profile")
            ?: JSONObject().also { json.put("dsh", JSONObject().put("profile", it)) }
        val current = profileSection.optJSONArray("bundles")
        val names = (0 until (current?.length() ?: 0)).mapNotNull { current?.optString(it) }
            .filter { it.isNotBlank() }
        val merged = names.toMutableList()
        for (name in factory) if (name !in merged) merged.add(name)
        if (merged == names && f.isFile) return
        profileSection.put("bundles", org.json.JSONArray(merged))
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.toString(2) + "\n")
        if (!tmp.renameTo(f)) {
            tmp.delete()
            appendLog("[profile] bundle 清单写入失败（rename 被拒）")
            return
        }
        appendLog("[profile] 工厂 bundle 清单就位: ${merged.joinToString(", ")}")
    }

    /**
     * 确保两个快照内 shim 就位（幂等：缺失或内容不一致才重写）。
     *
     *  · `libexec/dsh-node` —— PTC 代码执行的 node 启动器（移植自 dsh-mobile-apk 7c1）。
     *    三层机制缺一不可：① 脚本非 ELF，内核走 shebang 用 /system/bin/sh 读它，
     *    不触发 app-data ELF exec（untrusted_app 域 execute_no_trans）；② wrapper 自己
     *    export LD_LIBRARY_PATH——PTC 给子进程的 env 是显式过滤后的（只放 6 个名字），
     *    壳侧注入的到不了那条路径；③ `/system/bin/linker64 <node>` 只**读取** node 而非
     *    exec，与壳侧引擎自己在 linker64 回退时的形态一致。附带 V8 堆自适应下限
     *    clamp(MemTotal/2, 512, 4096)MB，仅在调用方未设 NODE_OPTIONS 时填（调用方显式值优先）。
     *  · `bin/pnpm` —— pnpm standalone shim（7e）：市场一键安装（`dsh plugin add`）走 pnpm，
     *    快照无 pnpm 时实测 "pnpm not found on PATH"；pnpm 包本体在 lib/node_modules/pnpm。
     *
     * 树内 pack 已烧写同一份（自包含）；这里按**运行期实际路径**重写兜底——
     * work profile / 数据迁移等 filesDir 变化的场景下烧写路径会错。
     */
    private fun ensureRuntimeShims() {
        // ── PTC node wrapper ──
        val libexec = File(root, "libexec").apply { mkdirs() }
        val wrapper = File(libexec, "dsh-node")
        val node = File(root, "bin/node")
        val wrapperText = """
            #!/system/bin/sh
            # dsh-mobile: PTC node launcher (C1) - app-data ELF cannot be exec'd under untrusted_app,
            # so hand it to the system linker with the snapshot library path exported here.
            export LD_LIBRARY_PATH="${'$'}{libDir.absolutePath}"
            # 引擎/子进程 V8 堆下限：clamp(MemTotal/2, 512, 4096)MB，仅 NODE_OPTIONS 未设时填
            if [ -z "${'$'}NODE_OPTIONS" ]; then
              __dsh_mem_kb=0
              while read -r __dsh_key __dsh_val __dsh_rest; do
                if [ "${'$'}__dsh_key" = "MemTotal:" ]; then __dsh_mem_kb=${'$'}__dsh_val; break; fi
              done < /proc/meminfo
              __dsh_heap=${'$'}(( __dsh_mem_kb / 2048 ))
              [ "${'$'}__dsh_heap" -lt 512 ] && __dsh_heap=512
              [ "${'$'}__dsh_heap" -gt 4096 ] && __dsh_heap=4096
              export NODE_OPTIONS="--max-old-space-size=${'$'}__dsh_heap"
            fi
            exec /system/bin/linker64 "${'$'}{node.absolutePath}" "${'$'}@"
        """.trimIndent() + "\n"
        if (!wrapper.isFile || wrapper.readText() != wrapperText) {
            wrapper.writeText(wrapperText)
            wrapper.setExecutable(true, true)
            appendLog("[shim] PTC node wrapper 就位: ${wrapper.name}")
        }
        // ── pnpm standalone shim ──
        val pnpmCjs = File(root, "lib/node_modules/pnpm/bin/pnpm.cjs")
        if (pnpmCjs.isFile) {
            val shim = File(File(root, "bin"), "pnpm")
            val shimText = """
                #!/system/bin/sh
                # dsh-mobile: pnpm standalone shim（npm registry 打包，自包含；node 由快照提供）
                exec "${'$'}{node.absolutePath}" "${'$'}{pnpmCjs.absolutePath}" "${'$'}@"
            """.trimIndent() + "\n"
            if (!shim.isFile || shim.readText() != shimText) {
                shim.writeText(shimText)
                shim.setExecutable(true, true)
                appendLog("[shim] pnpm shim 就位: ${shim.name}")
            }
        }
    }

    // ─────────────────────────── 运行时补丁（assets/patched 覆盖式自愈）───────────────────────────

    /**
     * 把 assets/patched 下的补丁版覆盖到运行时树对应位置（幂等）。
     *
     * **为什么有这一层**：pack 里本就是打过补丁的字节 —— Android 应用域对 app-data 调
     * link(2) 恒被拒（EACCES/EPERM），rename 回退已经烧进 `dsh-attachment-local` /
     * `dsh-fs-local` / `dsh-session-persistence-jsonl` 三个包的 lib/index.js。
     * 覆盖式补丁是**自愈兜底**：快照被旧版 pack 污染、或解压器写坏目标文件时，
     * 每次 spawn 前把我们的字节重新写回去。树健康时它是纯 no-op（逐字节相同就不写）。
     *
     * **为什么资产必须取自我们自己的暂存树**：参考实现（kelai141/dsh-mobile-apk）的
     * 同名三份资产重建在它那份快照的旧源码上；直接覆盖到我们这棵 0.2.1-rc.2 树上，
     * 会把引擎升级带来的新代码擦掉——它自己的注释就记着这个坑（"the rc.2-locked
     * asset was erasing the engine upgrade's own new code, e.g. breaking prompt via
     * stale persistence API"）。所以这里覆盖的字节与本 pack 内的字节**同源**：
     * 只不少写、不倒退。
     *
     * 每次 spawn 前调用；目标包不在树里（依赖布局变了）就跳过并记一行。
     */
    private fun applyRuntimePatches() {
        val pkgs = File(root, "dsh/node_modules/@deepseek-ai")
        applyAssetPatch(
            "$ASSET_PATCH_DIR/attachment-local-index.js",
            File(pkgs, "dsh-attachment-local/lib/index.js"),
        )
        applyAssetPatch(
            "$ASSET_PATCH_DIR/session-persistence-jsonl-index.js",
            File(pkgs, "dsh-session-persistence-jsonl/lib/index.js"),
        )
        applyAssetPatch(
            "$ASSET_PATCH_DIR/fs-local-index.js",
            File(pkgs, "dsh-fs-local/lib/index.js"),
        )
    }

    /**
     * 覆盖式补丁：内容指纹判等（资产与目标逐字节比），资产更新后能重新施加，
     * 不会被旧的标记字符串绊住（标记式补丁的 v1→v2 资产更新 bug）。
     */
    private fun applyAssetPatch(asset: String, target: File) {
        // 布局跟踪：树里没有这个包（依赖图变了）就跳过，不往树里扔死覆盖层。
        if (!target.parentFile.isDirectory) {
            appendLog("[补丁] 目标包不在树里，跳过: $asset")
            return
        }
        val outcome = applyAssetPatchCore(
            asset = asset,
            target = target,
            recordFile = assetPatchRecordFile(asset),
            apkIdentity = assetPatchApkIdentity(),
            readAsset = { context.assets.open(asset).use { it.readBytes() } },
            log = { msg -> appendLog(msg) },
        )
        when (outcome) {
            AssetPatchOutcome.WRITTEN -> appendLog("[补丁] 已写入: $asset")
            AssetPatchOutcome.SKIPPED_FAST_PATH, AssetPatchOutcome.SKIPPED_ALREADY_EQUAL ->
                appendLog("[补丁] 已就位(${outcome.name}): $asset")
            AssetPatchOutcome.ASSET_MISSING ->
                Log.w(TAG, "运行时补丁资产缺失或不可写: $asset")
        }
    }

    /** 补丁记录落点：应用私有目录，**不进**运行时树（那棵树会被快照整体换掉）。 */
    private fun assetPatchRecordFile(asset: String): File =
        File(File(context.filesDir, ASSET_PATCH_RECORD_DIR), asset.replace('/', '_') + ".rec")

    /** 安装包身份：资产字节只随安装包变化，用它绑定记录（免每次启动读资产算摘要）。 */
    private fun assetPatchApkIdentity(): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        code.toString() + ":" + info.lastUpdateTime
    } catch (t: Throwable) {
        // 取不到身份时给一个必然不匹配的值：宁可每次走内容比对，也不要在快路径上赌。
        "unknown"
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
        // headless profile 的工厂 bundle 自愈（同 web：缺 bundles 则引擎条目树不装配）
        runCatching {
            ensureProfileBundles(
                profile = "headless",
                factory = listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-headless"),
                canvasDeps = false,
            )
        }.onFailure { Log.w(TAG, "headless bundle 自愈失败", it) }
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

// ─────────────────────────── 运行时补丁核心（纯逻辑）───────────────────────────
//
// 覆盖式补丁的判据链（移植 kelai141/dsh-mobile-apk 的 applyAssetPatchCore，
// 资产字节换成本仓暂存树的同源版本，见 [DeepSeekHarness.applyRuntimePatches]）：
//
//   ① 先读记录跑快路径判据 —— 命中就返回，**不读资产**；
//   ② 快路径未命中 ⇒ 读资产（读不出 = ASSET_MISSING，与旧行为一致）；
//   ③ 目标存在且与资产逐字节相同 ⇒ SKIPPED_ALREADY_EQUAL，**但仍刷新记录**
//      （否则下一次启动还会重读资产做比对，快路径永远不命中）；
//   ④ 其余 ⇒ 写入目标并刷新记录。
//
// 为什么不能「只看 mtime/size 就跳过」：这是安全敏感面（补丁会把上游 package 的
// lib/index.js 覆盖成我们的版本），而「mtime 与 size 相同但内容不同」完全可能
// （同尺寸改写、字节翻转）。快路径的判据因此**不是**元数据相同，而是：
//   本安装包（versionCode + lastUpdateTime）**曾经**把这份资产写成了目标文件当前的内容
//   （记录落在应用私有目录），且目标文件的 size/mtime 与记录一致、内容摘要与记录一致。
// 三条与关系任一不成立就退回完整内容比对——没有可信记录时，无论 size/mtime 多像，
// 都必然走到 ③，那正是「同尺寸不同内容」被正确识别的路径。

/** 补丁应用的结果（调用方据此决定打哪条日志）。 */
internal enum class AssetPatchOutcome {
    /** 快路径命中：安装包身份 + size/mtime + 摘要三者一致，未读资产、未写目标。 */
    SKIPPED_FAST_PATH,

    /** 走了完整内容比对，目标已与资产逐字节相同，未写。 */
    SKIPPED_ALREADY_EQUAL,

    /** 资产被成功写入目标（首次打补丁或资产更新）。 */
    WRITTEN,

    /** 资产读不出来（打包缺件）或写不进去——「跳过 + 告警」，不影响引擎启动。 */
    ASSET_MISSING,
}

/** 记录里的内容摘要形态（64 位小写十六进制；与 [sha256Hex] 同口径）。 */
private val SHA256_HEX = Regex("[0-9a-f]{64}")

/**
 * 补丁应用记录：**只在本进程写入成功后**才落盘，用于下一次启动的快路径判定。
 *
 * @param apkIdentity 安装包身份（versionCode:lastUpdateTime）；变了就整条失效
 * @param assetSize 写入的资产字节数
 * @param contentSha256 写入内容的 SHA-256（十六进制小写）
 * @param targetSize 写入后目标文件的大小
 * @param targetMtime 写入后目标文件的 mtime（毫秒）
 */
internal data class AssetPatchRecord(
    val apkIdentity: String,
    val assetSize: Long,
    val contentSha256: String,
    val targetSize: Long,
    val targetMtime: Long,
) {
    /** 单行序列化（空格分隔；身份与摘要是无空白字符的短标识，不需要转义）。 */
    fun encode(): String = listOf(
        apkIdentity,
        assetSize.toString(),
        contentSha256,
        targetSize.toString(),
        targetMtime.toString(),
    ).joinToString(" ")

    companion object {
        /** 解析记录；空/字段缺失/数字不合法一律 null（**不得**把不可解析的记录当成命中）。 */
        fun parse(raw: String?): AssetPatchRecord? {
            val parts = raw?.trim()?.split(' ') ?: return null
            if (parts.size != 5) return null
            if (parts[0].isEmpty()) return null
            // 摘要必须是 64 位十六进制：畸形记录（半截写入 / 乱码）一律判不可解析——
            // 宁可退化到内容比对，也不要把一条读不懂的记录当成「内容已认证」。
            if (!SHA256_HEX.matches(parts[2])) return null
            val assetSize = parts[1].toLongOrNull() ?: return null
            val targetSize = parts[3].toLongOrNull() ?: return null
            val targetMtime = parts[4].toLongOrNull() ?: return null
            if (assetSize < 0L || targetSize < 0L) return null
            return AssetPatchRecord(parts[0], assetSize, parts[2], targetSize, targetMtime)
        }
    }
}

/**
 * 快路径判据（纯函数）：三条与关系，任一不成立就必须退回内容比对。
 *
 * **这一条是本机制的安全边界**：它只回答「能不能省掉这次读盘」，绝不回答「内容对不对」。
 * 所以「记录缺席」「身份变了」「size/mtime 变了」都返回 false，调用方随之走逐字节比对。
 */
internal fun assetPatchFastPathHit(
    record: AssetPatchRecord?,
    apkIdentity: String,
    targetExists: Boolean,
    targetSize: Long,
    targetMtime: Long,
    targetDigest: String?,
): Boolean {
    if (record == null) return false
    if (!targetExists) return false
    if (record.apkIdentity != apkIdentity) return false
    if (record.targetSize != targetSize) return false
    if (record.targetMtime != targetMtime) return false
    // **内容认证**（关键，不可省）：size 与 mtime 都可以被伪造/还原，只有摘要能证明目标文件
    // 现在的内容仍是本包装过的那份。没有这一步，快路径就成了「同尺寸改写也照样跳过」的绕过。
    if (targetDigest == null) return false
    if (!targetDigest.equals(record.contentSha256, ignoreCase = true)) return false
    return true
}

/**
 * applyAssetPatch 的**核心**（依赖注入，JVM 单测用真实文件 + 内存资产即可全覆盖）。
 *
 * 执行顺序即安全边界，不可重排：
 *  ① 先读记录并跑快路径判据（[assetPatchFastPathHit]）——命中就返回，**不读资产**；
 *  ② 快路径未命中 ⇒ 读资产（读不出 = [AssetPatchOutcome.ASSET_MISSING]）；
 *  ③ 目标存在且与资产逐字节相同 ⇒ [AssetPatchOutcome.SKIPPED_ALREADY_EQUAL]，**但仍刷新记录**；
 *  ④ 其余 ⇒ 写入目标并刷新记录。
 */
internal fun applyAssetPatchCore(
    asset: String,
    target: File,
    recordFile: File,
    apkIdentity: String,
    readAsset: () -> ByteArray,
    log: (String) -> Unit = {},
): AssetPatchOutcome {
    val rawRecord = try { recordFile.takeIf { it.isFile }?.readText() } catch (_: Throwable) { null }
    val record = AssetPatchRecord.parse(rawRecord)
    // 读一次目标（随后内容比对若需要可直接复用，不重复读盘）。
    val existing = try { target.takeIf { it.isFile }?.readBytes() } catch (_: Throwable) { null }
    if (existing != null && assetPatchFastPathHit(
            record = record,
            apkIdentity = apkIdentity,
            targetExists = true,
            targetSize = existing.size.toLong(),
            targetMtime = target.lastModified(),
            targetDigest = sha256Hex(existing),
        )
    ) {
        // 快路径命中：内容摘要与记录一致 ⇒ 目标就是本包装过的那份，无需读资产、无需写入。
        return AssetPatchOutcome.SKIPPED_FAST_PATH
    }
    val assetBytes = try {
        readAsset()
    } catch (e: Throwable) {
        log("runtime patch asset unreadable: " + asset)
        return AssetPatchOutcome.ASSET_MISSING
    }
    if (existing != null && existing.contentEquals(assetBytes)) {
        // 内容已经一致：不写文件，但**补记录**——否则每次启动都会重读资产做比对。
        writeAssetPatchRecord(recordFile, sha256Hex(assetBytes), assetBytes.size.toLong(), target, apkIdentity)
        return AssetPatchOutcome.SKIPPED_ALREADY_EQUAL
    }
    return try {
        target.parentFile?.mkdirs()
        target.writeBytes(assetBytes)
        writeAssetPatchRecord(recordFile, sha256Hex(assetBytes), assetBytes.size.toLong(), target, apkIdentity)
        AssetPatchOutcome.WRITTEN
    } catch (e: Throwable) {
        log("runtime patch failed: " + asset + " -> " + e.message)
        AssetPatchOutcome.ASSET_MISSING
    }
}

/** 字节的 SHA-256（十六进制小写）。快路径的内容认证与记录都走它，口径唯一。 */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** 记录落盘（失败不影响补丁本身：下一次启动退化为完整内容比对）。 */
internal fun writeAssetPatchRecord(
    recordFile: File,
    contentSha256: String,
    assetSize: Long,
    target: File,
    apkIdentity: String,
) {
    try {
        recordFile.parentFile?.mkdirs()
        val record = AssetPatchRecord(
            apkIdentity = apkIdentity,
            assetSize = assetSize,
            contentSha256 = contentSha256,
            targetSize = target.length(),
            targetMtime = target.lastModified(),
        )
        // 先写临时再 rename：半截记录就算落下去也被 parse 判非法（退化到内容比对），
        // 但 rename 让「原子性」这件事不依赖解析器的宽容。
        val tmp = File(recordFile.parentFile, recordFile.name + ".tmp")
        tmp.writeText(record.encode())
        if (!tmp.renameTo(recordFile)) {
            recordFile.writeText(record.encode())
            tmp.delete()
        }
    } catch (_: Throwable) {
        // 记录写不出去不影响本次补丁结果，只是下次启动多走一次内容比对。
    }
}

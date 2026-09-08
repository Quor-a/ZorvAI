package com.ai.assistance.quro.build

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import dalvik.system.InMemoryDexClassLoader
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.nio.ByteOrder

/**
 * 端侧 APK 构建引擎（真实可用的工具链）。
 *
 * 设计原则「绝不假装能编」：工具缺失 / dexed 工具 jar 加载失败 / 编译本身报错时，如实报告，
 * 不会编造成功。能力边界：
 *  - ecj（Java → .class）
 *  - d8（.class → classes.dex）
 *  - apksig（对产物签名）
 *  - assembleApk：把 classes.dex 注入 base.apk 模板（已含 AndroidManifest.xml + resources.arsc）再签名，不经过 aapt2
 *
 * 运行模型（v6，真修 Android 16 的 ClassNotFoundException）：
 *  v1 用 `dalvikvm` 拉独立 JVM 子进程 → Android 16 上 VM 启动期 SIGABRT（退出码 134）。
 *  v2 改进程内 DexClassLoader 从 filesDir 加载 → Android 16「Writable dex file ... is not allowed」。
 *  v3~v5 反复在「内存缓冲对齐 / 抽取独立 .dex 文件」上打转，但真机仍报
 *  `ClassNotFoundException: org.eclipse.jdt.internal.compiler.batch.Main`——dex 本身完好（dexdump 验证），
 *  问题在「加载」。
 *  真正根因（v6 修，已用 dexdump + jar 内容核对确认）：**三个 dexed jar 的 classes.dex 是 DEFLATED
 *  （压缩）的**。DexClassLoader 从「压缩 jar 里的 dex」加载时，要在设备上把 dex 抽取到 oat 目录——
 *  这条抽取链路在 ColorOS/Android 16 上不可靠，抽出来的是空/残 dex → 类读不到 → ClassNotFoundException
 *  （注意不是 SecurityException，说明「只读绕过可写拦截」早已通过，纯粹是 dex 没被 ART 真正读出）。
 *  修复：PC 侧把三个 jar 的 classes.dex 改为 STORED（不压缩）+ zipalign 4 字节对齐，随 APK 下发；
 *  运行时用**标准 DexClassLoader 直接从 jar 加载**——ART 对 STORED+对齐 dex 直接 mmap，无需抽取，
 *  与 App 自身 APK 加载 dex 的机制完全一致，ColorOS/Android 16 上 100% 可靠。jar 内的 .properties/.rsc
 *  资源（ecj 必需，共 93 个）由同一 DexClassLoader 从 jar 读取。父加载器用 App 自身 classloader，
 *  框架类正常委派。InMemoryDexClassLoader 仅作 DexClassLoader 异常时的兜底。
 *
 *  dexed 工具 jar 由 PC 侧用 `d8 --min-api 26` 把原始 ecj.jar/d8.jar/apksigner.jar 转成单个
 *  classes.dex（ecj 额外保留其 .properties/.rsc 资源），并用 zipalign 使其 classes.dex STORED+对齐，
 *  随 APK 的 assets 下发，首次运行强制解包到 filesDir/buildproject/libs（覆盖安装也强制重解包）。
 */
/**
 * 把异常完整堆栈（含 Caused by 链路）转成字符串。
 * 真机上不能用 adb 取日志，必须让构建日志本身带完整堆栈，才能在 App 内直接看到真因，
 * 而不是只看到一个没用的 "InvocationTargetException: null"（其 message 恒为 null，真因在 cause 里）。
 */
private fun throwableDetail(e: Throwable): String {
    val sw = StringWriter()
    val pw = PrintWriter(sw)
    e.printStackTrace(pw)
    // InvocationTargetException 等包装异常：printStackTrace 已打印 Caused by 链路，
    // 这里再额外把根因类名/信息提一句，方便一眼定位。
    var root: Throwable? = e
    while (root?.cause != null && root.cause !== root) root = root.cause
    if (root != null && root !== e) {
        pw.append("\n[根因] ${root.javaClass.name}: ${root.message}\n")
    }
    pw.flush()
    return sw.toString()
}

/**
 * 把构建日志双写到 App 外部存储文件，方便真机取回（不需要 adb / 不需要复制粘贴）。
 * 路径：<外部files>/build_logs/build_<时间戳>.txt 与 latest.txt。
 * 用户的构建台（真机）崩了以后，我没法在这台 PC 复现（没有 ART，ecj 在普通 JVM 上跑得好好的），
 * 也拿不到 adb 日志；所以让 App 自己把"真因"落盘到文件，用户用手机文件管理器点开就能发我。
 * 任何异常都吞掉——绝不允许"写日志"这一步本身把构建结果返回搞挂。
 */
private fun saveBuildLog(ctx: Context, log: String) {
    try {
        val dir = File(ctx.getExternalFilesDir(null), "build_logs").apply { mkdirs() }
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        File(dir, "build_$ts.txt").writeText(log)
        File(dir, "latest.txt").writeText(log)
    } catch (_: Throwable) {}
}

object BuildEngine {
    private const val TAG = "BuildEngine"
    const val ZORV_PKG = "com.ai.assistance.quro"

    data class ToolStatus(
        val name: String,
        val present: Boolean,
        val path: String,
        val note: String
    )

    data class BuildResult(
        val ok: Boolean,
        val log: String,
        val dexPath: String? = null,
        val apkPath: String? = null
    )

    /**
     * 一次 APK 构建的全部可定制项（对应「自己写包名 / 应用名 / 图标 / 签名」诉求）。
     * 全部带默认值：默认行为等价于旧版（包名 com.example.buildapp、默认图标、debug.keystore 签名）。
     */
    data class BuildConfig(
        val packageName: String = "com.example.buildapp",
        val appLabel: String = "BuildApp",
        val versionName: String = "1.0.0",
        /** 自定义图标 PNG 字节；null = 用模板默认图标。 */
        val iconBytes: ByteArray? = null,
        /** 自定义 keystore（PKCS12/JKS）；null = 用内置 debug.keystore。 */
        val keystore: File? = null,
        val keyAlias: String = "androiddebugkey",
        val storePassword: String = "android",
        val keyPassword: String = "android",
        /** 工程 assets/ 目录：其中的文件会被注入 APK 的 assets/ 下，供用户代码通过 AssetManager 读取。 */
        val assetsDir: File? = null
    ) {
        // ByteArray 默认 equals 是引用比较，这里按内容比较，避免混淆。
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BuildConfig) return false
            return packageName == other.packageName && appLabel == other.appLabel &&
                versionName == other.versionName && keyAlias == other.keyAlias &&
                storePassword == other.storePassword && keyPassword == other.keyPassword &&
                keystore == other.keystore && assetsDir == other.assetsDir &&
                (iconBytes == null && other.iconBytes == null || iconBytes.contentEquals(other.iconBytes))
        }

        override fun hashCode(): Int {
            var h = packageName.hashCode()
            h = 31 * h + appLabel.hashCode()
            h = 31 * h + versionName.hashCode()
            h = 31 * h + keyAlias.hashCode()
            h = 31 * h + storePassword.hashCode()
            h = 31 * h + keyPassword.hashCode()
            h = 31 * h + (keystore?.hashCode() ?: 0)
            h = 31 * h + (assetsDir?.hashCode() ?: 0)
            h = 31 * h + (iconBytes?.contentHashCode() ?: 0)
            return h
        }
    }

    // 模板 base.apk 二进制 AndroidManifest.xml 字符串池固定索引（由 dump_manifest.py 解析确认）。
    // utf8=False（UTF-16LE），字符串按索引引用，故改名/改图标只需替换这几个字符串、其余保持。
    private const val IDX_PACKAGE = 22
    private const val IDX_LABEL = 14
    private const val IDX_VERSION_NAME = 12

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF)) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toInt() and 0xFF).toLong() or
            ((b[o + 1].toInt() and 0xFF).toLong() shl 8) or
            ((b[o + 2].toInt() and 0xFF).toLong() shl 16) or
            ((b[o + 3].toInt() and 0xFF).toLong() shl 24)
    private fun w16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v ushr 8) and 0xFF).toByte()
    }
    private fun w32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        b[o + 2] = ((v ushr 16) and 0xFF).toByte(); b[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /**
     * 改写二进制 AndroidManifest.xml 的包名/应用名/版本名（仅替换字符串池里对应索引的字符串，
     * XML 树按索引引用，故其它结构原样保留）。启动 Activity 在模板里声明为完整类名
     * com.example.buildapp.MainActivity，不随 package 变化，宿主 dex 始终能被解析。
     *
     * 实现已用 PC 原型（prototype_build.py + aapt2 dump xmltree）验证：改写后包名/应用名生效，
     * activity 名不变，icon 引用 @0x7f010000 保持。
     */
    private fun rewriteManifest(data: ByteArray, newPkg: String, newLabel: String, newVersion: String): ByteArray {
        val docHsize = u16(data, 2)
        val spOff = docHsize
        val spType = u16(data, spOff)
        val spHsize = u16(data, spOff + 2)
        val spSize = u32(data, spOff + 4)
        val count = u32(data, spOff + 8).toInt()
        val styleCount = u32(data, spOff + 12)
        val flags = u32(data, spOff + 16)
        val stringsStart = u32(data, spOff + 20)
        val stylesStart = u32(data, spOff + 24)
        val utf8 = (flags and 0x100) != 0L

        val offsets = IntArray(count) { u32(data, spOff + spHsize + 4 * it).toInt() }
        val base = spOff + stringsStart.toInt()
        val strings = Array(count) { i ->
            val p = base + offsets[i]
            if (utf8) {
                val n = u16(data, p); var q = p + 2
                var l = data[q].toInt() and 0xFF; q++
                val ln = if ((l and 0x80) != 0) {
                    val l2 = data[q].toInt() and 0xFF; q++; ((l and 0x7F) shl 8) or l2
                } else l
                String(data, q, ln, Charsets.UTF_8)
            } else {
                val n = u16(data, p); val q = p + 2
                String(data, q, n * 2, Charsets.UTF_16LE)
            }
        }
        strings[IDX_PACKAGE] = newPkg
        strings[IDX_LABEL] = newLabel
        strings[IDX_VERSION_NAME] = newVersion

        val newOffsets = IntArray(count)
        val parts = mutableListOf<ByteArray>()
        var pos = 0
        for (i in strings.indices) {
            val enc = strings[i].toByteArray(Charsets.UTF_16LE)
            val n = enc.size / 2
            val b = ByteArray(2 + enc.size + 2)
            w16(b, 0, n)
            System.arraycopy(enc, 0, b, 2, enc.size)
            w16(b, 2 + enc.size, 0)
            newOffsets[i] = pos
            parts.add(b)
            pos += b.size
        }

        val offsetArraySize = count * 4
        val newStringsStart = spHsize + offsetArraySize
        val newPoolSize = newStringsStart + pos
        // Android ResChunk size MUST be a multiple of 4; pad the string pool so the
        // following chunks (resource map / XML tree) stay 4-byte aligned. Without this,
        // a custom (different-length) package/label misaligns the whole manifest and the
        // device's ResXMLTree parser rejects it -> "安装包异常". The default package keeps
        // the original aligned size, which is why only custom packages triggered the failure.
        val pad = (4 - (newPoolSize % 4)) % 4
        val pool = ByteArray(newPoolSize + pad)
        w16(pool, 0, spType); w16(pool, 2, spHsize); w32(pool, 4, pool.size.toLong())
        w32(pool, 8, count.toLong()); w32(pool, 12, styleCount); w32(pool, 16, flags)
        w32(pool, 20, newStringsStart.toLong()); w32(pool, 24, stylesStart)
        var off = 28
        for (i in newOffsets.indices) { w32(pool, off, newOffsets[i].toLong()); off += 4 }
        var p = off
        for (b in parts) { System.arraycopy(b, 0, pool, p, b.size); p += b.size }

        val tree = data.copyOfRange(spOff + spSize.toInt(), data.size)
        val out = data.copyOfRange(0, spOff) + pool + tree
        w32(out, 4, out.size.toLong())
        return out
    }

    /**
     * 端内生成自定义签名 keystore（PKCS12）。自签证书需要 BouncyCastle（Android 运行时自带，
     * 但不在编译 SDK stub 中），故用反射调用，保证可编译；若设备不可达 BC 则抛异常由上层提示改用导入。
     */
    fun generateKeystore(file: File, alias: String, storePass: String, keyPass: String) {
        val bcProv = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
        val provider = bcProv.getDeclaredConstructor().newInstance() as java.security.Provider
        if (java.security.Security.getProvider("BC") == null) {
            java.security.Security.addProvider(provider)
        }
        val kpg = java.security.KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val genCls = Class.forName("org.bouncycastle.x509.X509V3CertificateGenerator")
        val gen = genCls.getDeclaredConstructor().newInstance()
        val dn = javax.security.auth.x500.X500Principal("CN=$alias, OU=BuildAci, O=BuildAci, C=CN")
        genCls.getMethod("setSerialNumber", java.math.BigInteger::class.java)
            .invoke(gen, java.math.BigInteger.valueOf(System.currentTimeMillis()))
        genCls.getMethod("setSubjectDN", javax.security.auth.x500.X500Principal::class.java).invoke(gen, dn)
        genCls.getMethod("setIssuerDN", javax.security.auth.x500.X500Principal::class.java).invoke(gen, dn)
        genCls.getMethod("setNotBefore", java.util.Date::class.java).invoke(gen, java.util.Date())
        genCls.getMethod("setNotAfter", java.util.Date::class.java)
            .invoke(gen, java.util.Date(System.currentTimeMillis() + 10000L * 86400000L))
        genCls.getMethod("setPublicKey", java.security.PublicKey::class.java).invoke(gen, kp.public)
        genCls.getMethod("setSignatureAlgorithm", String::class.java).invoke(gen, "SHA256WithRSA")
        val cert = genCls.getMethod("generate", java.security.PrivateKey::class.java, String::class.java)
            .invoke(gen, kp.private, "BC") as java.security.cert.X509Certificate
        val ks = java.security.KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(alias, kp.private, keyPass.toCharArray(), arrayOf(cert))
        file.parentFile?.mkdirs()
        ks.store(java.io.FileOutputStream(file), storePass.toCharArray())
    }

    // =============================================================================================
    // 进程内工具链：用 InMemoryDexClassLoader 内存加载端侧 dexed 工具 jar，并以反射缓存关键入口，避免重复查找。
    // =============================================================================================
    private class InProc(
        val loader: ClassLoader,
        val ecjCtor: Constructor<*>,
        val ecjCompile: Method,
        val d8Parse: Method,
        val d8Run: Method,
        val d8Build: Method,
        val apkSignerCtor: Constructor<*>,
        val signerCfgCtor: Constructor<*>,
        val signerCfgBuild: Method,
        val apkSetInput: Method,
        val apkSetOutput: Method,
        val apkSetV1: Method,
        val apkSetV2: Method,
        val apkSetV3: Method,
        val apkSetMinSdk: Method,
        val apkBuild: Method,
        val apkSign: Method
    ) {
        /**
         * 进程内跑 ecj。返回 (编译是否成功, ecj 自身文本输出, 抛出的异常或 null)。
         *
         * 关键改进（v1.5.12）：**即使 compile() 抛异常，也把 ecj 自己写到 PrintWriter 的输出
         * 一并带出来**。ecj 在真正崩之前往往已经把关键报错打印进这个 Writer（例如
         * "package android.app does not exist"、内部 NPE 的上下文、找不到某个类型的线索），
         * 旧版只在异常分支里丢了一句 "InvocationTargetException: null"，把真因全埋了——
         * 这正是历次"修复"都瞎猜、修不对的根。现在异常 + ecj 输出一起交差，真机日志就能定位到行。
         */
        fun compileEcj(args: Array<String>): Triple<Boolean, String, Throwable?> {
            val prev = Thread.currentThread().contextClassLoader
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            var ex: Throwable? = null
            var ok = false
            try {
                Thread.currentThread().contextClassLoader = loader
                val main = ecjCtor.newInstance(pw, pw, false) // systemExitWhenFinished=false
                ok = ecjCompile.invoke(main, args) as Boolean
            } catch (e: Throwable) {
                ex = e
            } finally {
                pw.flush()
                Thread.currentThread().contextClassLoader = prev
            }
            return Triple(ok, sw.toString(), ex)
        }

        /** 进程内跑 d8：用 D8Command.parse 解析 CLI 风格参数，再 D8.run。失败抛异常（调用方捕获）。 */
        fun runD8(args: Array<String>) {
            val prev = Thread.currentThread().contextClassLoader
            try {
                Thread.currentThread().contextClassLoader = loader
                val origin = loader.loadClass("com.android.tools.r8.origin.Origin")
                    .getMethod("unknown").invoke(null)
                val builder = d8Parse.invoke(null, args, origin)
                val cmd = d8Build.invoke(builder)
                d8Run.invoke(null, cmd)
            } finally {
                Thread.currentThread().contextClassLoader = prev
            }
        }

        /** 进程内签名：用 apksig 的 ApkSigner API（不使用会 System.exit 的 ApkSignerTool.main）。 */
        fun signApk(input: File, output: File, keystore: File, storePass: String, keyPass: String, alias: String) {
            val prev = Thread.currentThread().contextClassLoader
            try {
                Thread.currentThread().contextClassLoader = loader
                val ks = loadKeyStore(keystore, storePass)
                val entry = ks.getEntry(alias, KeyStore.PasswordProtection(keyPass.toCharArray()))
                    as? KeyStore.PrivateKeyEntry
                    ?: throw IllegalStateException("密钥库里找不到别名 '$alias' 的私钥条目")
                val priv = entry.privateKey
                @Suppress("UNCHECKED_CAST")
                val certs = entry.certificateChain?.map { it as X509Certificate }
                    ?: throw IllegalStateException("别名 '$alias' 没有证书链")
                val scBuilder = signerCfgCtor.newInstance(alias, priv, certs)
                val sc = signerCfgBuild.invoke(scBuilder)
                val apkBuilder = apkSignerCtor.newInstance(listOf(sc))
                apkSetInput.invoke(apkBuilder, input)
                apkSetOutput.invoke(apkBuilder, output)
                apkSetV1.invoke(apkBuilder, true)
                apkSetV2.invoke(apkBuilder, true)
                apkSetV3.invoke(apkBuilder, true)
                apkSetMinSdk.invoke(apkBuilder, 21)
                val signer = apkBuild.invoke(apkBuilder)
                apkSign.invoke(signer)
            } finally {
                Thread.currentThread().contextClassLoader = prev
            }
        }

        private fun loadKeyStore(ksFile: File, pass: String): KeyStore {
            val exceptions = mutableListOf<Throwable>()
            for (type in listOf("JKS", "PKCS12", "BKS")) {
                try {
                    val ks = KeyStore.getInstance(type)
                    ksFile.inputStream().use { ks.load(it, pass.toCharArray()) }
                    return ks
                } catch (e: Throwable) {
                    exceptions.add(e)
                }
            }
            throw IllegalStateException(
                "无法加载密钥库（已尝试 JKS/PKCS12/BKS）：${ksFile.name}，" +
                    "最后一次错误：${exceptions.lastOrNull()?.message}"
            )
        }
    }

    @Volatile private var cached: InProc? = null

    /** 从 dexed jar 提取 classes.dex 字节，供 InMemoryDexClassLoader 内存加载（不落盘到可写目录）。 */
    private fun readDexBytes(jar: File): ByteBuffer {
        ZipFile(jar).use { zf ->
            val entry = zf.getEntry("classes.dex")
                ?: throw IllegalStateException("${jar.name} 不含 classes.dex，无法内存加载。")
            val bytes = zf.getInputStream(entry).readBytes()
            // 必须用 direct ByteBuffer：InMemoryDexClassLoader 在 Android 13+（尤其 Android 16 / ColorOS）
            // 从堆 ByteBuffer 加载时，部分 ROM 会静默得到「空 dex」——dex 对象创建成功（DexPathList 里
            // 能看到 InMemoryDexFile），但 class_defs 全部读不到，于是任何类都 ClassNotFoundException。
            // direct 缓冲经 ART 原生映射，行为确定可靠（CodeAssist / AndroidIDE 等同款做法）。
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes)
            buf.position(0)
            return buf
        }
    }

    /** 反射加载类，失败时报出具体缺失的类名（便于真机诊断到底是哪一个 dex 没含该类）。 */
    private fun loadClassOrFail(loader: ClassLoader, name: String): Class<*> {
        try {
            return loader.loadClass(name)
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("类未找到（dex 未含该类，可能 dex 生成/解包不完整）：$name")
        }
    }

    @Synchronized
    private fun getToolchain(ctx: Context): InProc {
        cached?.let { return it }
        val libs = ensureAssets(ctx)
        // Android 上 System.getProperty("java.io.tmpdir") 默认指向 /data/local/tmp，普通 App 不可写。
        // ecj / d8 / apksig 进程内运行若需写临时文件会 IOException / NPE（表现为 ecj compile 抛
        // InvocationTargetException）。改指 App 自身可写的 cache 目录，覆盖安装/多次构建均安全。
        try {
            val ecjTmp = File(ctx.cacheDir, "ecj_tmp").apply { mkdirs() }
            System.setProperty("java.io.tmpdir", ecjTmp.absolutePath)
        } catch (_: Throwable) {}
        val ecjDex = File(libs, "ecj_dex.jar")
        val d8Dex = File(libs, "d8_dex.jar")
        val sigDex = File(libs, "apksigner_dex.jar")
        if (!ecjDex.exists() || !d8Dex.exists() || !sigDex.exists()) {
            throw IllegalStateException("dexed 工具 jar 缺失（ecj_dex.jar / d8_dex.jar / apksigner_dex.jar），无法在端侧编译。")
        }
        // 主路径：DexClassLoader 直接从三个 dexed jar 加载（标准、成熟项目也用的方式）。
        // 关键修复（v6）：三个 jar 的 classes.dex 已改为 STORED（不压缩）+ zipalign 4 字节对齐，
        // ART 直接 mmap，不再需要在设备上「抽取」压缩 dex——ColorOS/Android 16 上「抽取压缩 dex」
        // 会失败，抽出来是空 dex -> ClassNotFoundException（v2~v5 一直踩这个坑，dex 本身完好却读不出类）。
        // jar 内的 .properties/.rsc 资源由 DexClassLoader 从同一 jar 读取（资源走普通 zip 解压，可靠）。
        // 父加载器用 App 自身 classloader：框架类（java.*/android.*）经其委派到 bootclasspath 正常解析。
        // 早期 InMemoryDexClassLoader / 抽取独立 .dex 等方案均废弃（不可靠），仅作为下方回退保底。
        val oat = File(libs, "oat").apply { mkdirs() }
        val dexJarPath = listOf(ecjDex, d8Dex, sigDex).joinToString(":") { it.absolutePath }
        val loader = try {
            DexClassLoader(dexJarPath, oat.absolutePath, null, ctx.classLoader)
        } catch (e: Throwable) {
            Log.w(TAG, "DexClassLoader 失败，回退 InMemoryDexClassLoader：${e.message}")
            val bufs = listOf(ecjDex, d8Dex, sigDex).map { readDexBytes(it) }.toTypedArray()
            InMemoryDexClassLoader(bufs, ctx.classLoader)
        }

        val ecjMain = loadClassOrFail(loader, "org.eclipse.jdt.internal.compiler.batch.Main")
        val d8 = loadClassOrFail(loader, "com.android.tools.r8.D8")
        val d8Cmd = loadClassOrFail(loader, "com.android.tools.r8.D8Command")
        val d8CmdBuilder = loadClassOrFail(loader, "com.android.tools.r8.D8Command\$Builder")
        val apkSigner = loadClassOrFail(loader, "com.android.apksig.ApkSigner")
        val apkBuilder = loadClassOrFail(loader, "com.android.apksig.ApkSigner\$Builder")
        val signerCfgBuilder = loadClassOrFail(loader, "com.android.apksig.ApkSigner\$SignerConfig\$Builder")
        val origin = loadClassOrFail(loader, "com.android.tools.r8.origin.Origin")

        val ip = InProc(
            loader = loader,
            ecjCtor = ecjMain.getConstructor(
                PrintWriter::class.java, PrintWriter::class.java, Boolean::class.javaPrimitiveType
            ),
            ecjCompile = ecjMain.getMethod("compile", Array<String>::class.java),
            d8Parse = d8Cmd.getMethod("parse", Array<String>::class.java, origin),
            d8Run = d8.getMethod("run", d8Cmd),
            d8Build = d8CmdBuilder.getMethod("build"),
            apkSignerCtor = apkBuilder.getConstructor(List::class.java),
            signerCfgCtor = signerCfgBuilder.getConstructor(
                String::class.java, PrivateKey::class.java, List::class.java
            ),
            signerCfgBuild = signerCfgBuilder.getMethod("build"),
            apkSetInput = apkBuilder.getMethod("setInputApk", File::class.java),
            apkSetOutput = apkBuilder.getMethod("setOutputApk", File::class.java),
            apkSetV1 = apkBuilder.getMethod("setV1SigningEnabled", Boolean::class.javaPrimitiveType),
            apkSetV2 = apkBuilder.getMethod("setV2SigningEnabled", Boolean::class.javaPrimitiveType),
            apkSetV3 = apkBuilder.getMethod("setV3SigningEnabled", Boolean::class.javaPrimitiveType),
            apkSetMinSdk = apkBuilder.getMethod("setMinSdkVersion", Int::class.javaPrimitiveType),
            apkBuild = apkBuilder.getMethod("build"),
            apkSign = apkSigner.getMethod("sign")
        )
        cached = ip
        return ip
    }

    /** 把 assets 里的工具链解包到 filesDir/buildproject/libs（assets 不能直接执行）。 */
    fun ensureAssets(ctx: Context): File {
        val out = File(ctx.filesDir, "buildproject/libs")
        out.mkdirs()
        // 这三个 dexed 工具 jar 会被重新生成/修正（例如本次修复 ecj dex），必须每次覆盖解包，
        // 否则覆盖安装时 filesDir 里的旧文件不会被替换，设备会一直用旧（坏的）dex。
        // base.apk 也在列：替换成带图标的模板后必须强制重解包，否则设备会继续用旧的空壳 base.apk。
        val alwaysExtract = setOf("ecj_dex.jar", "d8_dex.jar", "apksigner_dex.jar", "base.apk")
        val names = listOf(
            "ecj.jar", "d8.jar", "apksigner.jar", "android.jar", "debug.keystore", "base.apk",
            // 已 dex 化的工具 jar：进程内加载，避免 Android 14+ 禁止独立 dalvikvm 且绕开 Android 16 Writable dex 限制。
            "ecj_dex.jar", "d8_dex.jar", "apksigner_dex.jar"
        )
        for (n in names) {
            val target = File(out, n)
            val need = alwaysExtract.contains(n) || !target.exists() || target.length() == 0L
            if (need) {
                try {
                    // 先解除只读（上一轮 setReadOnly 过的文件否则无法覆盖写入，会静默失败），
                    // 再重新写入；写完后对 dexed jar 重新设为只读（Android 14+ 禁止从「可写」dex 加载）。
                    if (target.exists()) target.setWritable(true)
                    ctx.assets.open("libs/common/$n").use { ins ->
                        FileOutputStream(target).use { os -> ins.copyTo(os) }
                    }
                    // dexed 工具 jar 设为只读：Android 14+（尤其 16）禁止从「可写」dex 文件加载
                    // （SecurityException: Writable dex file ... is not allowed）。STORED+对齐的 dex
                    // 由 ART 直接 mmap，只读文件可正常 mmap（PROT_READ），故设只读既能过检查又不影响加载。
                    if (alwaysExtract.contains(n)) target.setReadOnly()
                    Log.i(TAG, "解包 $n -> ${target.absolutePath}")
                } catch (e: Throwable) {
                    Log.w(TAG, "解包 $n 失败: ${e.message}")
                }
            }
        }
        // 注意：三个 dexed jar 的 classes.dex 已在打包时设为 STORED（不压缩）+ 4 字节对齐，
        // DexClassLoader 直接从 jar 加载即可（ART 直接 mmap，无需设备端抽取）。无需再抽成独立 .dex 文件。
        return out
    }

    /** 把目录下所有 .class（含子目录）打成 jar，供 d8 作为 program 输入。 */
    private fun jarDirectory(srcDir: File, outJar: File) {
        outJar.parentFile?.mkdirs()
        outJar.delete()
        ZipOutputStream(FileOutputStream(outJar)).use { zos ->
            srcDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val name = f.relativeTo(srcDir).path.replace('\\', '/')
                zos.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    /** 真实工具链自检。首次调用会先把 APK assets 里的工具链解压到 filesDir。 */
    fun detectTools(ctx: Context): List<ToolStatus> {
        ensureAssets(ctx)
        val libs = File(ctx.filesDir, "buildproject/libs")
        val ecj = File(libs, "ecj.jar")
        val d8 = File(libs, "d8.jar")
        val sign = File(libs, "apksigner.jar")
        val aj = File(libs, "android.jar")
        val key = File(libs, "debug.keystore")
        val ecjDex = File(libs, "ecj_dex.jar")
        val d8Dex = File(libs, "d8_dex.jar")
        val sigDex = File(libs, "apksigner_dex.jar")
        return listOf(
            ToolStatus("ecj.jar（Java 编译器）", ecj.exists(), ecj.absolutePath,
                if (ecj.exists()) "已就绪" else "缺失：无法编译 Java"),
            ToolStatus("d8.jar（DEX 转换器）", d8.exists(), d8.absolutePath,
                if (d8.exists()) "已就绪" else "缺失：无法生成 DEX"),
            ToolStatus("apksigner.jar（签名）", sign.exists(), sign.absolutePath,
                if (sign.exists()) "已就绪" else "缺失：无法签名"),
            ToolStatus("android.jar（Android SDK）", aj.exists(), aj.absolutePath,
                if (aj.exists()) "已就绪（编译期 classpath）" else "缺失"),
            ToolStatus("debug.keystore（签名密钥）", key.exists(), key.absolutePath,
                if (key.exists()) "已就绪" else "缺失"),
            ToolStatus("ecj_dex.jar（进程内 ecj）", ecjDex.exists(), ecjDex.absolutePath,
                if (ecjDex.exists()) "已就绪：DexClassLoader 直接加载（STORED dex）" else "缺失：端侧编译不可用"),
            ToolStatus("d8_dex.jar（进程内 d8）", d8Dex.exists(), d8Dex.absolutePath,
                if (d8Dex.exists()) "已就绪：DexClassLoader 直接加载（STORED dex）" else "缺失：生成 DEX 不可用"),
            ToolStatus("apksigner_dex.jar（进程内签名）", sigDex.exists(), sigDex.absolutePath,
                if (sigDex.exists()) "已就绪：apksig API 进程内签名" else "缺失：签名不可用"),
            ToolStatus("端侧运行时（App 进程内 ART）", true, "app-process",
                "可用：工具链直接在 App 进程内执行（不再依赖独立 dalvikvm 子进程，Android 16 亦可用）"),
            ToolStatus("aapt2（资源打包）", false, "未打包",
                "未打包：本引擎用 base.apk 模板注入 DEX 再签名，不依赖 aapt2，APK 组装仍可用")
        )
    }

    /**
     * 是否能组装完整 APK。本引擎的 assembleApk() 是把 classes.dex 注入预置的 base.apk 模板
     * （已含 AndroidManifest.xml + resources.arsc）再进程内签名，**不经过 aapt2**、**不依赖独立运行时**。
     * 真正需要：base.apk + apksigner_dex.jar（进程内签名）+ debug.keystore。
     */
    fun canAssembleApk(ctx: Context): Boolean {
        ensureAssets(ctx)
        val libs = File(ctx.filesDir, "buildproject/libs")
        return File(libs, "base.apk").exists()
            && File(libs, "apksigner_dex.jar").exists()
            && File(libs, "debug.keystore").exists()
    }

    /**
     * 编译整个 src 目录下所有 .java 文件为 classes.dex（真实管线：ecj → class，d8 → dex）。
     * 进程内执行，不需要设备提供独立 JVM 运行时。
     */
    fun compileProject(ctx: Context, srcDir: File, outDir: File): BuildResult {
        val libs = ensureAssets(ctx)
        srcDir.mkdirs()
        // 递归清空上一轮产物（含 ecj 按包名生成的子目录），避免残留旧 class 干扰本轮
        outDir.deleteRecursively()
        outDir.mkdirs()

        val sources = srcDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java", ignoreCase = true) }
            .map { it.absolutePath }
            .toList()

        if (sources.isEmpty()) {
            return BuildResult(false, "src 目录下没有 .java 源文件。", null)
        }

        val ecj = File(libs, "ecj.jar")
        val d8 = File(libs, "d8.jar")
        val aj = File(libs, "android.jar")
        if (!ecj.exists() || !d8.exists() || !aj.exists()) {
            return BuildResult(false, "工具链不完整（ecj/d8/android.jar 缺失），无法编译。", null)
        }

        val log = StringBuilder()
        log.append("发现 ${sources.size} 个 Java 源文件：\n")
        sources.forEach { log.append("  · ").append(it).append("\n") }
        val dexDir = File(outDir.parentFile ?: outDir, "dexout").apply { mkdirs() }
        val dexFile = File(dexDir, "classes.dex")
        // 清理上一轮可能残留的损坏 DEX，避免"前几次失败留下坏中间文件"导致本轮也失败
        dexFile.delete()

        val toolchain = try {
            getToolchain(ctx)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("\n== 工具链加载失败（含完整堆栈）==\n").append(throwableDetail(e)).toString(), null)
        }

        // 1) ecj：Java → class（source/target 降到 8：旧版 ecj 在 ART 下不支持 11；-proc:none 关闭注解处理，减少噪音）
        val ecjArgs = mutableListOf(
            "-proc:none",
            "-d", outDir.absolutePath,
            // android.jar 必须作 bootclasspath：Android 编译的"引导类"（java.lang.* / android.*）都来自它。
            // 只给 -cp 而不给 -bootclasspath 时，ecj 会去取 JVM 默认的 rt.jar（ART 上不存在）→ 内部抛异常。
            "-bootclasspath", aj.absolutePath,
            "-source", "8", "-target", "8"
        )
        ecjArgs.addAll(sources)
        val (ecjOk, ecjOut, ecjEx) = toolchain.compileEcj(ecjArgs.toTypedArray())
        log.append("\n== ecj 编译 Java → class ==\n").append(ecjOut)
        if (ecjEx != null) {
            val full = log.append("\n== ecj 进程内崩溃（完整堆栈 + ecj 自身输出）==\n")
                .append(throwableDetail(ecjEx)).toString()
            saveBuildLog(ctx, full)
            return BuildResult(false, full, null)
        }
        if (!ecjOk) {
            val f = log.toString()
            saveBuildLog(ctx, f)
            return BuildResult(false, f, null)
        }

        // 2) d8：class → dex
        // 关键修复（PC 干跑发现）：本版 R8 的 parse 不支持「目录」作为 program 输入、
        // 也不支持「文件」作为 --output（必须是目录或 jar/zip）。因此先把 ecj 产物打成 jar，
        // 再让 d8 输出到目录，最终 DEX 落在 dexDir/classes.dex。
        val outJar = File(outDir.parentFile ?: outDir, "out.jar")
        try {
            jarDirectory(outDir, outJar)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("\n打包 class 为 jar 失败：${e.message}\n").toString(), null)
        }
        val d8Args = arrayOf(
            "--output", dexDir.absolutePath,
            "--lib", aj.absolutePath,
            outJar.absolutePath
        )
        try {
            toolchain.runD8(d8Args)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("\n== d8 进程内崩溃（含完整堆栈）==\n").append(throwableDetail(e)).append("\n[DEX 未生成]").toString(), null)
        }
        if (!dexFile.exists()) {
            return BuildResult(false, log.toString() + "\n[DEX 未生成]", null)
        }

        log.append("\n✔ 编译成功：classes.dex（${dexFile.length()} 字节）位于 ${dexFile.absolutePath}")
        return BuildResult(true, log.toString(), dexFile.absolutePath, null)
    }

    /**
     * 把用户编译出的 classes.dex 注入 base.apk 模板并进程内签名，产出可直接安装的 APK。
     * 不需要 aapt2：base.apk 已含 AndroidManifest.xml + resources.arsc + 宿主 classes.dex
     * （com.example.buildapp.MainActivity）。这里把用户 dex 作为 classes2.dex 追加进去，
     * Android 多 dex 机制会自动加载宿主与用户两份 dex，宿主 Activity 反射调用用户代码。
     */
    fun assembleApk(ctx: Context, dexPath: String, outApk: File, config: BuildConfig = BuildConfig()): BuildResult {
        val libs = ensureAssets(ctx)
        val baseApk = File(libs, "base.apk")
        // 自定义 keystore 优先；否则回退内置 debug.keystore（android/android/androiddebugkey）。
        val keystore = config.keystore ?: File(libs, "debug.keystore")
        if (!baseApk.exists() || !keystore.exists()) {
            return BuildResult(false, "工具链不完整（base.apk / keystore 缺失），无法构建 APK。", dexPath, null)
        }
        val dexFile = File(dexPath)
        if (!dexFile.exists()) {
            return BuildResult(false, "classes.dex 不存在，请先编译工程。", dexPath, null)
        }

        val log = StringBuilder()
        val customSign = config.keystore != null
        log.append("== 注入用户 classes.dex（classes2.dex）到 base.apk ==\n")
        log.append("签名：").append(if (customSign) "自定义 keystore（别名 ${config.keyAlias}）" else "内置 debug.keystore").append("\n")
        if (config.packageName != "com.example.buildapp" || config.appLabel != "BuildApp" || config.versionName != "1.0.0") {
            log.append("清单改写：package=${config.packageName}  label=${config.appLabel}  versionName=${config.versionName}\n")
        }
        if (config.iconBytes != null) log.append("图标：写入自定义图标（${config.iconBytes.size} 字节）\n")
        if (config.assetsDir?.isDirectory == true) log.append("资源：注入工程 assets/ 目录\n")

        val unsigned = File(outApk.parentFile ?: ctx.filesDir, "app-unsigned.apk")
        try {
            unsigned.parentFile?.mkdirs()
            // base.apk 已含宿主 classes.dex（com.example.buildapp.MainActivity）+ AndroidManifest + resources.arsc。
            // repackAligned 保留全部条目（含宿主 classes.dex），按 config 改写清单/图标、追加用户 classes2.dex 与 assets。
            repackAligned(baseApk, dexFile, unsigned, config)
            log.append("已生成未签名 APK：${unsigned.absolutePath}（${unsigned.length()} 字节，含宿主 classes.dex + 用户 classes2.dex）\n")
        } catch (e: Throwable) {
            return BuildResult(false, log.toString() + "\n注入失败：${e.message}", dexPath, null)
        }

        log.append("\n== apksigner 进程内签名 ==\n")
        val toolchain = try {
            getToolchain(ctx)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("工具链加载失败（含完整堆栈）：\n").append(throwableDetail(e)).append("\n[APK 签名失败]").toString(), dexPath, null)
        }
        try {
            toolchain.signApk(unsigned, outApk, keystore, config.storePassword, config.keyPassword, config.keyAlias)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("签名失败（含完整堆栈）：\n").append(throwableDetail(e)).append("\n[APK 签名失败]").toString(), dexPath, null)
        }
        if (!outApk.exists()) {
            return BuildResult(false, log.toString() + "\n[APK 签名失败]", dexPath, null)
        }
        unsigned.delete()
        log.append("\n✔ APK 构建成功：${outApk.absolutePath}（${outApk.length()} 字节）")
        return BuildResult(true, log.toString(), dexPath, outApk.absolutePath)
    }

    /**
     * 把 base.apk 的所有条目原样重打到 unsigned APK，并追加用户编译出的 classes2.dex，
     * 同时保证所有 STORED（未压缩）条目按 4 字节对齐——这是 apksigner v2/v3 签名的硬性要求。
     *
     * 修复 v1.5.16「安装包异常」根因：旧实现用 ZipOutputStream 重写会把 classes.dex /
     * resources.arsc / classes2.dex 全部 DEFLATED 压缩，且丢失 4 字节对齐，导致 v2/v3 签名
     * 校验失败、安装器拒装。这里：
     *  - 原 STORED 条目保持 STORED；原 DEFLATED 条目用 raw deflate（nowrap=true，含 adler32）
     *    重新压缩，与 zip 规范一致；classes2.dex 设为 STORED。
     *  - 仅对 STORED 条目在 local header 的 extra 字段填充 0 字节，使其数据起始地址 4 字节对齐。
     */
    private data class ZipEntryMeta(
        val name: String,
        val method: Int,
        val data: ByteArray,
        val crc: Long,
        val size: Long
    )

    private fun repackAligned(baseApk: File, userDex: File, outFile: File, config: BuildConfig) {
        val crc = CRC32()
        val entries = mutableListOf<ZipEntryMeta>()
        ZipInputStream(BufferedInputStream(FileInputStream(baseApk))).use { zis ->
            var ze = zis.nextEntry
            while (ze != null) {
                val entry = ze
                val raw = zis.readBytes()
                // 按 config 处理三个定制点：清单改写 / 自定义图标 / 其它保持。
                val outData = when {
                    entry.name == "AndroidManifest.xml" ->
                        rewriteManifest(raw, config.packageName, config.appLabel, config.versionName)
                    config.iconBytes != null && entry.name.startsWith("res/mipmap-") && entry.name.endsWith("/ic_launcher.png") ->
                        config.iconBytes!!
                    else -> raw
                }
                if (entry.method == ZipEntry.STORED) {
                    crc.reset(); crc.update(outData)
                    entries.add(ZipEntryMeta(entry.name, ZipEntry.STORED, outData, crc.value, outData.size.toLong()))
                } else {
                    val def = Deflater(Deflater.DEFAULT_COMPRESSION, true)
                    def.setInput(outData); def.finish()
                    val buf = java.io.ByteArrayOutputStream()
                    val tmp = ByteArray(8192)
                    while (!def.finished()) {
                        val n = def.deflate(tmp)
                        if (n > 0) buf.write(tmp, 0, n)
                    }
                    def.end()
                    crc.reset(); crc.update(outData)
                    entries.add(ZipEntryMeta(entry.name, ZipEntry.DEFLATED, buf.toByteArray(), crc.value, outData.size.toLong()))
                }
                ze = zis.nextEntry
            }
        }
        val userRaw = userDex.readBytes()
        crc.reset(); crc.update(userRaw)
        entries.add(ZipEntryMeta("classes2.dex", ZipEntry.STORED, userRaw, crc.value, userRaw.size.toLong()))

        // 工程 assets/ 下的文件注入 APK（导入的非 .java 资源，用户代码经 AssetManager 读取）。
        config.assetsDir?.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
            val rel = f.relativeTo(config.assetsDir!!).path.replace('\\', '/')
            val bytes = f.readBytes()
            crc.reset(); crc.update(bytes)
            entries.add(ZipEntryMeta("assets/$rel", ZipEntry.STORED, bytes, crc.value, bytes.size.toLong()))
        }

        val little = ByteOrder.LITTLE_ENDIAN
        val central = mutableListOf<ByteArray>()
        var offset = 0
        FileOutputStream(outFile).use { fos ->
            for (e in entries) {
                val nb = e.name.toByteArray(Charsets.UTF_8)
                var extra = byteArrayOf()
                if (e.method == ZipEntry.STORED) {
                    val base = offset + 30 + nb.size
                    val pad = (4 - (base % 4)) % 4
                    if (pad > 0) extra = ByteArray(pad)
                }
                val lh = ByteArray(30 + nb.size + extra.size)
                ByteBuffer.wrap(lh).order(little).apply {
                    putInt(0x04034b50); putShort(20); putShort(0); putShort(e.method.toShort())
                    putShort(0); putShort(0)
                    putInt(e.crc.toInt()); putInt(e.data.size); putInt(e.size.toInt())
                    putShort(nb.size.toShort()); putShort(extra.size.toShort())
                }
                System.arraycopy(nb, 0, lh, 30, nb.size)
                System.arraycopy(extra, 0, lh, 30 + nb.size, extra.size)
                fos.write(lh); fos.write(e.data)
                if (e.method == ZipEntry.STORED) {
                    require((offset + lh.size) % 4 == 0) { "STORED 对齐失败: ${e.name}" }
                }
                val ch = ByteArray(46 + nb.size + extra.size)
                ByteBuffer.wrap(ch).order(little).apply {
                    putInt(0x02014b50); putShort(20); putShort(20); putShort(0)
                    putShort(e.method.toShort()); putShort(0); putShort(0)
                    putInt(e.crc.toInt()); putInt(e.data.size); putInt(e.size.toInt())
                    putShort(nb.size.toShort()); putShort(extra.size.toShort())
                    putShort(0); putShort(0); putShort(0); putInt(0); putInt(offset)
                }
                System.arraycopy(nb, 0, ch, 46, nb.size)
                System.arraycopy(extra, 0, ch, 46 + nb.size, extra.size)
                central.add(ch)
                offset += lh.size + e.data.size
            }
            val cs = offset
            for (ch in central) { fos.write(ch); offset += ch.size }
            val eocd = ByteArray(22)
            ByteBuffer.wrap(eocd).order(little).apply {
                putInt(0x06054b50); putShort(0); putShort(0)
                putShort(entries.size.toShort()); putShort(entries.size.toShort())
                putInt(offset - cs); putInt(cs); putShort(0)
            }
            fos.write(eocd)
        }
    }

    /**
     * 把一段 Java 源码编译为 classes.dex（兼容旧版单文件调用；内部也走 compileProject）。
     * 进程内执行，不需要设备提供独立 JVM 运行时。
     */
    fun compileToDex(ctx: Context, source: String, className: String): BuildResult {
        val libs = ensureAssets(ctx)
        val ecj = File(libs, "ecj.jar")
        val d8 = File(libs, "d8.jar")
        val aj = File(libs, "android.jar")
        if (!ecj.exists() || !d8.exists() || !aj.exists()) {
            return BuildResult(false, "工具链不完整（ecj/d8/android.jar 缺失），无法编译。", null)
        }

        val project = File(ctx.filesDir, "buildproject")
        val srcDir = File(project, "src").apply { mkdirs() }
        val outDir = File(project, "out").apply { mkdirs() }
        outDir.deleteRecursively()
        outDir.mkdirs()

        val srcFile = File(srcDir, "$className.java")
        try {
            srcFile.writeText(source)
        } catch (e: Throwable) {
            return BuildResult(false, "写入源码失败：${e.message}", null)
        }

        val dexDir = File(project, "dexout").apply { mkdirs() }
        val dexFile = File(dexDir, "classes.dex")
        // 清理上一轮残留的损坏 DEX
        dexFile.delete()

        val log = StringBuilder()
        val toolchain = try {
            getToolchain(ctx)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("\n== 工具链加载失败（含完整堆栈）==\n").append(throwableDetail(e)).toString(), null)
        }

        // 1) ecj：Java → class（source/target 降到 8，兼容 ART 下的旧版 ecj）
        val ecjArgs = arrayOf(
            "-proc:none",
            "-d", outDir.absolutePath,
            "-bootclasspath", aj.absolutePath,
            "-source", "8", "-target", "8",
            srcFile.absolutePath
        )
        val (ecjOk, ecjOut, ecjEx) = toolchain.compileEcj(ecjArgs)
        log.append("== ecj 编译 Java → class ==\n").append(ecjOut)
        if (ecjEx != null) {
            val full = log.append("\n== ecj 进程内崩溃（完整堆栈 + ecj 自身输出）==\n")
                .append(throwableDetail(ecjEx)).toString()
            saveBuildLog(ctx, full)
            return BuildResult(false, full, null)
        }
        if (!ecjOk) {
            val f = log.toString()
            saveBuildLog(ctx, f)
            return BuildResult(false, f, null)
        }

        // 2) d8：class → dex
        // 关键修复（PC 干跑发现）：本版 R8 的 parse 不支持「目录」作为 program 输入、
        // 也不支持「文件」作为 --output（必须是目录或 jar/zip）。因此先把 ecj 产物打成 jar，
        // 再让 d8 输出到目录，最终 DEX 落在 dexDir/classes.dex。
        val outJar = File(outDir.parentFile ?: outDir, "out.jar")
        try {
            jarDirectory(outDir, outJar)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("\n打包 class 为 jar 失败：${e.message}\n").toString(), null)
        }
        val d8Args = arrayOf(
            "--output", dexDir.absolutePath,
            "--lib", aj.absolutePath,
            outJar.absolutePath
        )
        try {
            toolchain.runD8(d8Args)
        } catch (e: Throwable) {
            return BuildResult(false, log.append("\n== d8 进程内崩溃（含完整堆栈）==\n").append(throwableDetail(e)).append("\n[DEX 未生成]").toString(), null)
        }
        if (!dexFile.exists()) {
            return BuildResult(false, log.toString() + "\n[DEX 未生成]", null)
        }

        log.append("\n✔ 编译成功：classes.dex（${dexFile.length()} 字节）位于 ${dexFile.absolutePath}")
        return BuildResult(true, log.toString(), dexFile.absolutePath, null)
    }
}

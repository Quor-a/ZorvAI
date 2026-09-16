package com.ai.assistance.quro.kaleidobox.android

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 端侧插件编译器：把 AI（或用户）写好的 **单个 Java 源文件** 经 ecj → class、d8 → classes.dex，
 * 直接产出可被 [com.ai.assistance.quro.kaleidobox.android.engine.JvmDexEngine] 加载的 dex 字节。
 *
 * 这是"AI 编译插件"能力的落点 —— 不需要 PC、不需要 aapt2、不需要独立 dalvikvm 子进程
 * （Android 14+ 已禁止，Android 16 直接 SIGABRT），全部在 App 进程内 ART 完成。
 *
 * 工具链来源（零重复、复用宿主已下发的资产）：
 *  - ecj_dex.jar / d8_dex.jar / android.jar：宿主 App 共享 assets（libs/common），本模块经 ctx.assets 读取后解包；
 *  - kaleidobox_api.jar：本模块编译期由 Gradle 任务 [kaleidoboxApiJar] 从 kaleidobox 自身类打出，
 *    作为插件编译期的 classpath（插件源码 import 的 KaleidoToolkit / KValue / UiNode 等都在里面）。
 *
 * dexed 工具 jar 采用 BuildEngine 在 Android 16 上验证过的「DexClassLoader 直接加载 STORED+对齐 dex」方案
 * （见 BuildEngine 注释），可靠且无需在设备上抽取压缩 dex。
 */
object PluginCompiler {

    private const val TAG = "KaleidoCompiler"

    data class CompileResult(
        val ok: Boolean,
        val log: String,
        val dex: ByteArray? = null,
    ) {
        fun toKValue() = if (ok) {
            com.ai.assistance.quro.kaleidobox.core.model.KValue.obj(
                "ok" to true, "log" to log, "dexSize" to (dex?.size ?: 0)
            )
        } else {
            com.ai.assistance.quro.kaleidobox.core.model.KValue.obj("ok" to false, "log" to log)
        }
    }

    @Volatile private var cached: InProc? = null

    private class InProc(
        val loader: ClassLoader,
        val ecjCtor: Constructor<*>,
        val ecjCompile: Method,
        val d8Parse: Method,
        val d8Run: Method,
        val d8Build: Method,
    )

    /** 端侧编译器是否可用（资产是否齐备）。UI 据此展示开关状态。 */
    fun available(ctx: Context): Boolean = runCatching {
        val libs = ensureAssets(ctx)
        File(libs, "ecj_dex.jar").exists() && File(libs, "d8_dex.jar").exists()
            && File(libs, "android.jar").exists() && File(libs, "kaleidobox_api.jar").exists()
    }.getOrDefault(false)

    @Synchronized
    private fun getToolchain(ctx: Context): InProc {
        cached?.let { return it }
        val libs = ensureAssets(ctx)
        val ecjDex = File(libs, "ecj_dex.jar")
        val d8Dex = File(libs, "d8_dex.jar")
        if (!ecjDex.exists() || !d8Dex.exists()) {
            throw IllegalStateException("端侧编译器缺失（ecj_dex.jar / d8_dex.jar 未解包）")
        }
        val oat = File(libs, "oat").apply { mkdirs() }
        // 主路径：DexClassLoader 直接从两个 dexed jar 加载（STORED+对齐 dex，ART 直接 mmap，无需抽取）。
        val dexJarPath = listOf(ecjDex, d8Dex).joinToString(File.pathSeparator) { it.absolutePath }
        val loader = DexClassLoader(dexJarPath, oat.absolutePath, null, ctx.classLoader)

        val ecjMain = loader.loadClass("org.eclipse.jdt.internal.compiler.batch.Main")
        val d8 = loader.loadClass("com.android.tools.r8.D8")
        val d8Cmd = loader.loadClass("com.android.tools.r8.D8Command")
        val d8CmdBuilder = loader.loadClass("com.android.tools.r8.D8Command\$Builder")
        val ip = InProc(
            loader = loader,
            ecjCtor = ecjMain.getConstructor(
                PrintWriter::class.java, PrintWriter::class.java, Boolean::class.javaPrimitiveType
            ),
            ecjCompile = ecjMain.getMethod("compile", Array<String>::class.java),
            d8Parse = d8Cmd.getMethod("parse", Array<String>::class.java,
                loader.loadClass("com.android.tools.r8.origin.Origin")),
            d8Run = d8.getMethod("run", d8Cmd),
            d8Build = d8CmdBuilder.getMethod("build"),
        )
        cached = ip
        return ip
    }

    /** 把工具链从 assets 解包到 filesDir/kaleidobox/libs（assets 不能直接被 DexClassLoader 加载）。 */
    fun ensureAssets(ctx: Context): File {
        val out = File(ctx.filesDir, "kaleidobox/libs")
        out.mkdirs()
        // ecj/d8/android.jar 来自宿主 App 共享 assets（libs/common）；kaleidobox_api.jar 来自本模块 assets（libs/kaleido）。
        val fromApp = listOf("ecj_dex.jar", "d8_dex.jar", "android.jar")
        for (n in fromApp) {
            val target = File(out, n)
            if (target.exists() && target.length() > 0L) continue
            try {
                if (target.exists()) target.setWritable(true)
                ctx.assets.open("libs/common/$n").use { ins -> FileOutputStream(target).use { ins.copyTo(it) } }
                // dexed 工具 jar 设为只读：Android 14+ 禁止从「可写」dex 加载。
                if (n.endsWith("_dex.jar")) target.setReadOnly()
            } catch (e: Throwable) {
                Log.w(TAG, "解包 $n 失败: ${e.message}")
            }
        }
        val ownAssets = mapOf("kaleidobox_api.jar" to "libs/kaleido/kaleidobox_api.jar")
        for ((n, assetPath) in ownAssets) {
            val target = File(out, n)
            if (target.exists() && target.length() > 0L) continue
            try {
                if (target.exists()) target.setWritable(true)
                ctx.assets.open(assetPath).use { ins -> FileOutputStream(target).use { ins.copyTo(it) } }
            } catch (e: Throwable) {
                Log.w(TAG, "解包 $n 失败: ${e.message}")
            }
        }
        return out
    }

    /**
     * 编译单个 Java 源文件为 classes.dex。
     *
     * @param source   完整 Java 源码（含 `public class <className 末段>`）。
     * @param className 工具包实现类的全限定名（必须与源码里的 public 类名一致，也是 manifest 的 runtime.entry）。
     */
    fun compile(ctx: Context, source: String, className: String): CompileResult {
        val libs = ensureAssets(ctx)
        val aj = File(libs, "android.jar")
        val api = File(libs, "kaleidobox_api.jar")
        if (!aj.exists()) return CompileResult(false, "android.jar 缺失，无法编译。")
        if (!api.exists()) return CompileResult(false, "kaleidobox_api.jar 缺失，无法编译（请重新构建 kaleidobox 模块以生成 API jar）。")

        val project = File(ctx.filesDir, "kaleidobox/build").apply { mkdirs() }
        val srcDir = File(project, "src").apply { mkdirs() }
        val outDir = File(project, "out").apply { deleteRecursively(); mkdirs() }
        val simpleName = className.substringAfterLast('.')
        val srcFile = File(srcDir, "$simpleName.java")
        runCatching { srcFile.writeText(source) }
            .onFailure { return CompileResult(false, "写入源码失败：${it.message}") }

        val dexDir = File(project, "dexout").apply { mkdirs() }
        val dexFile = File(dexDir, "classes.dex")
        dexFile.delete()

        val log = StringBuilder()
        val ip = try {
            getToolchain(ctx)
        } catch (e: Throwable) {
            return CompileResult(false, log.append("工具链加载失败: ${e.message}").toString())
        }

        // 1) ecj：Java → class（source/target 8 兼容 ART 下旧版 ecj；-proc:none 关注解处理）
        val ecjArgs = arrayOf(
            "-proc:none", "-encoding", "UTF-8",
            "-d", outDir.absolutePath,
            "-bootclasspath", aj.absolutePath,
            "-cp", api.absolutePath,
            "-source", "8", "-target", "8",
            srcFile.absolutePath
        )
        val prev = Thread.currentThread().contextClassLoader
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        var ecjOk = false
        var ecjEx: Throwable? = null
        try {
            Thread.currentThread().contextClassLoader = ip.loader
            val main = ip.ecjCtor.newInstance(pw, pw, false) // systemExitWhenFinished=false
            ecjOk = ip.ecjCompile.invoke(main, ecjArgs) as Boolean
        } catch (e: Throwable) {
            ecjEx = e
        } finally {
            pw.flush()
            Thread.currentThread().contextClassLoader = prev
        }
        log.append(sw.toString())
        if (ecjEx != null) return CompileResult(false, log.append("\necj 进程内崩溃: ${ecjEx.message}").toString())
        if (!ecjOk) return CompileResult(false, log.append("\necj 编译失败（详见上方输出）").toString())

        // 2) d8：class → dex（先把 ecj 产物打成 jar，d8 不接受目录/文件直接作 program 输入）
        val outJar = File(project, "out.jar")
        try {
            jarDirectory(outDir, outJar)
        } catch (e: Throwable) {
            return CompileResult(false, log.append("\n打包 class 失败: ${e.message}").toString())
        }
        val d8Args = arrayOf(
            "--output", dexDir.absolutePath,
            "--lib", aj.absolutePath,
            "--lib", api.absolutePath,
            outJar.absolutePath
        )
        try {
            Thread.currentThread().contextClassLoader = ip.loader
            val origin = ip.loader.loadClass("com.android.tools.r8.origin.Origin")
                .getMethod("unknown").invoke(null)
            val builder = ip.d8Parse.invoke(null, d8Args, origin)
            val cmd = ip.d8Build.invoke(builder)
            ip.d8Run.invoke(null, cmd)
        } catch (e: Throwable) {
            return CompileResult(false, log.append("\nd8 进程内崩溃: ${e.message}").toString())
        } finally {
            Thread.currentThread().contextClassLoader = prev
        }
        if (!dexFile.exists()) return CompileResult(false, log.append("\n[DEX 未生成]").toString())

        log.append("\n✔ 编译成功：classes.dex（${dexFile.length()} 字节）")
        return CompileResult(true, log.toString(), dexFile.readBytes())
    }

    /** 把目录下所有 .class 打成 jar，供 d8 作 program 输入。 */
    private fun jarDirectory(srcDir: File, outJar: File) {
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
}

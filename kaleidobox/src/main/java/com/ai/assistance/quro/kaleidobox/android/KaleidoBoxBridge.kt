package com.ai.assistance.quro.kaleidobox.android

import android.content.Context
import com.ai.assistance.quro.kaleidobox.core.KaleidoRuntime
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.registry.InstallSource
import com.ai.assistance.quro.kaleidobox.core.registry.MemoryPackageSource
import com.ai.assistance.quro.kaleidobox.samples.KaleidoCatalog
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * KaleidoBox 高层桥接：把"目录 / 端侧编译 / 导入 / 卸载"收敛成一组供
 * [com.ai.assistance.quro.core.tools.QuroKaleidoBoxTool] 与工具中心 UI 共用的函数，
 * 避免 AI 工具与界面各写一份装包逻辑。
 */
object KaleidoBoxBridge {

    fun runtime(): KaleidoRuntime = KaleidoBoxHost.get().runtime

    fun catalog() = KaleidoCatalog.entries

    /** 安装目录里的某个条目。BUILTIN 走内存清单直装；SRC 走端侧编译再装。 */
    fun installCatalogEntry(ctx: Context, id: String): KValue {
        val entry = KaleidoCatalog.find(id) ?: return KValue.fail("E_NOT_FOUND", "目录里没有插件: $id")
        return try {
            when (entry.kind) {
                KaleidoCatalog.Kind.BUILTIN -> {
                    val src = MemoryPackageSource(
                        mapOf("kaleido.json" to entry.manifestJson.toByteArray(StandardCharsets.UTF_8))
                    )
                    val rec = runtime().install(
                        src, KaleidoRuntime.InstallOptions(trusted = true, source = InstallSource.MARKET)
                    )
                    KValue.obj("ok" to true, "id" to rec.id, "version" to rec.version.toString(), "kind" to "builtin")
                }
                KaleidoCatalog.Kind.SRC -> {
                    val src = entry.source ?: return KValue.fail("E_NO_SRC", "该条目缺少源码")
                    compileAndInstall(ctx, src, entry.entryClass, entry.manifestJson, entry.id)
                }
            }
        } catch (e: Throwable) {
            KValue.fail("E_INSTALL", e.message ?: e.javaClass.simpleName)
        }
    }

    /** AI/用户写的 Java 源码：端侧编译成 dex 并安装。 */
    fun writeAndInstall(
        ctx: Context,
        source: String,
        className: String,
        manifestJson: String,
        label: String = "",
    ): KValue = compileAndInstall(ctx, source, className, manifestJson, label)

    private fun compileAndInstall(
        ctx: Context,
        source: String,
        className: String,
        manifestJson: String,
        label: String,
    ): KValue {
        if (!PluginCompiler.available(ctx))
            return KValue.fail(
                "E_TOOLCHAIN",
                "端侧编译器不可用（缺少 ecj_dex.jar / d8_dex.jar / android.jar / kaleidobox_api.jar 资产，请重新构建）"
            )
        val compiled = PluginCompiler.compile(ctx, source, className)
        if (!compiled.ok) return KValue.fail("E_COMPILE", compiled.log)
        val dex = compiled.dex ?: return KValue.fail("E_COMPILE", "编译成功但无 dex 输出")
        val src = MemoryPackageSource(
            mapOf(
                "kaleido.json" to manifestJson.toByteArray(StandardCharsets.UTF_8),
                "classes.dex" to dex,
            )
        )
        val rec = runtime().install(
            src, KaleidoRuntime.InstallOptions(trusted = true, source = InstallSource.MARKET)
        )
        return KValue.obj(
            "ok" to true,
            "id" to rec.id,
            "version" to rec.version.toString(),
            "dexSize" to dex.size,
            "log" to compiled.log,
        )
    }

    /** 从本地 zip 文件装入（持久化）。 */
    fun installZip(ctx: Context, file: File): KValue = runCatching {
        val rec = PackageImporter.installZipPersisted(ctx, file)
        KValue.obj("ok" to true, "id" to rec.id, "version" to rec.version.toString())
    }.getOrElse { KValue.fail("E_IMPORT", it.message ?: it.javaClass.simpleName) }

    /** 从网络链接下载 zip 并装入（持久化）。 */
    fun installUrl(ctx: Context, url: String): KValue = runCatching {
        val rec = PackageImporter.installUrlPersisted(ctx, url)
        KValue.obj("ok" to true, "id" to rec.id, "version" to rec.version.toString())
    }.getOrElse { KValue.fail("E_IMPORT", it.message ?: it.javaClass.simpleName) }

    /** 卸载一个包（含其所有版本中的该 id 当前激活版本）。 */
    fun uninstall(id: String): KValue = runCatching {
        runtime().uninstall(id)
        KValue.ok(true)
    }.getOrElse { KValue.fail("E_UNINSTALL", it.message ?: it.javaClass.simpleName) }
}

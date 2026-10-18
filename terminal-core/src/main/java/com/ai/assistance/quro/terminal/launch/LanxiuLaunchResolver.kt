/*
 * LanxiuLaunchResolver.kt -- 配置化启动解析器（移植自 Cateners/tiny_container 思路）
 *
 * 作用：把 LANXIU 的启动参数从 Kotlin 硬编码改为「数据化描述」。
 * 一个容器/桌面 = 一份描述（boot_command 模板 + options 树），
 * 解析器递归收集启用的 option，注入 $EXTRA_ARGS/$EXTRA_PATH/... 变量，
 * 产出最终可在终端 session 里执行的命令/脚本。
 *
 * 与 tiny_container 的差异（铁律约束）：
 *  - PRoot 来源：tiny_container 用 fork 的 proot-termux；LANXIU 用 v1.0.70 基线（禁重编）。
 *  - 存储热插拔 --tiny-storage 仅在该自定义 proot 下有效；本解析器保留参数拼装能力，
 *    但接入前需确认 LANXIU 的 proot 是否支持（当前基线大概率不支持，列为后续）。
 *  - 其余（feature/options 递归收集、变量替换）逻辑完全对齐 tiny_container。
 *
 * 依赖：纯 Kotlin 实现，只吃 Map<String,Any>，不依赖 snakeyaml/gson 等外部库，零新增依赖。
 *       真机端若要从 .tiny.yaml 文件喂入，由调用方自行解析成 Map 后传给 resolve()。
 */

package com.ai.assistance.quro.terminal.launch

/**
 * 递归收集后合并的启动参数。
 * 字段名对齐 tiny_container 的 MergedOptions。
 */
data class MergedOptions(
    val env: MutableList<String> = mutableListOf(),
    val path: MutableList<String> = mutableListOf(),
    val ldLibraryPath: MutableList<String> = mutableListOf(),
    val ldPreload: MutableList<String> = mutableListOf(),
    val args: MutableList<String> = mutableListOf(),
) {
    /** 合并单个启用 option（其 env/path/ld_library_path/ld_preload/args 字段） */
    operator fun plusAssign(o: Map<String, Any>) {
        (o["env"] as? List<*>)?.forEach { env += it.toString() }
        (o["path"] as? List<*>)?.forEach { path += it.toString() }
        (o["ld_library_path"] as? List<*>)?.forEach { ldLibraryPath += it.toString() }
        (o["ld_preload"] as? List<*>)?.forEach { ldPreload += it.toString() }
        (o["args"] as? List<*>)?.forEach { args += it.toString() }
    }

    /** 合并另一组 MergedOptions */
    operator fun plusAssign(other: MergedOptions) {
        env += other.env
        path += other.path
        ldLibraryPath += other.ldLibraryPath
        ldPreload += other.ldPreload
        args += other.args
    }
}

object LanxiuLaunchResolver {

    /**
     * 解析容器描述，产出最终启动命令/脚本。
     *
     * @param config           解析后的描述（Map）
     * @param externalStorage  已挂载外部存储 [(path, label), ...]，用于 --bind=。
     *                         真机端由 TinyStorage 风格 JNI 提供；此处纯逻辑，便于单测。
     * @return 替换完 $EXTRA_* 后的命令字符串
     */
    fun resolve(config: Map<String, Any>, externalStorage: List<Pair<String, String>> = emptyList()): String {
        val merged = collectEnabledOptions(config)
        collectStorageArgs(config, merged.args, externalStorage)

        val bootCmd = config["boot_command"] as? String
            ?: throw IllegalStateException("container config missing boot_command")
        return bootCmd
            .replace("\$EXTRA_ARGS", merged.args.joinToString(" "))
            .replace("\$EXTRA_PATH", merged.path.joinToString(":"))
            .replace("\$EXTRA_LD_LIBRARY_PATH", merged.ldLibraryPath.joinToString(":"))
            .replace("\$EXTRA_LD_PRELOAD", merged.ldPreload.joinToString(" "))
            .replace("\$EXTRA_ENV", merged.env.joinToString(" "))
            .trimIndent()
    }

    /**
     * 仅收集启用的 option 注入的 args（供调用方拼装启动命令，如 Weston 后端选择）。
     * 不需要 boot_command 模板，直接返回 List 更便于嵌入现有脚本结构。
     */
    fun resolveArgs(config: Map<String, Any>): List<String> {
        return collectEnabledOptions(config).args.toList()
    }

    /** 递归收集 options 树里所有 enabled 的 option */
    private fun collectEnabledOptions(config: Map<String, Any>): MergedOptions {
        val raw = config["options"] as? List<*> ?: return MergedOptions()
        return collectRecursive(raw.filterIsInstance<Map<String, Any>>())
    }

    private fun collectRecursive(options: List<Map<String, Any>>): MergedOptions {
        val result = MergedOptions()
        for (o in options) {
            when (o["type"]) {
                "options" -> {
                    val subs = (o["options"] as? List<*>)?.filterIsInstance<Map<String, Any>>() ?: emptyList()
                    result += collectRecursive(subs)
                }
                "option" -> if (o["enabled"] == true) result += o
            }
        }
        return result
    }

    /**
     * 若 feature 含启用中的 storage，则注入 --tiny-storage 与每个外部存储的 --bind=。
     * 注意：依赖自定义 proot 的 socket server，接入前需确认 LANXIU 基线 proot 支持。
     */
    private fun collectStorageArgs(
        config: Map<String, Any>,
        args: MutableList<String>,
        storage: List<Pair<String, String>>,
    ) {
        val features = config["feature"] as? List<*> ?: return
        val hasStorage = features.filterIsInstance<Map<String, Any>>()
            .any { it["type"] == "storage" && it["enabled"] == true }
        if (!hasStorage) return
        args += "--tiny-storage"
        for ((path, name) in storage) {
            args += "--bind=$path:/mnt/$name"
        }
    }
}

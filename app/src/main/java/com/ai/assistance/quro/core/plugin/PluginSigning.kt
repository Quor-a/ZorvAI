package com.ai.assistance.quro.core.plugin

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.build.BuildEngine
import java.io.File

/**
 * 插件导入补签配置 —— 「导入 APK → 先签名 → 再安装」。
 *
 * ## 为什么需要
 * 插件由宿主 DexClassLoader 加载，**拥有宿主同等权限**，所以宿主只接受与自身**同签名**的插件。
 * 而用户手头拿到的插件包未必是宿主密钥签的（自己写的包、第三方包、构建台用别的 keystore 签的包），
 * 于是导入时先用**宿主密钥补签一遍**再交给安装器 —— 这样任何来源的插件都能装上，
 * 且装上的东西一定出自本机这把密钥。
 *
 * ## 签名能力从哪来
 * **完全复用构建台**（[BuildEngine] 的进程内 apksig，V1 / V2 / V3 全开），
 * 不另写一套签名逻辑，也不依赖电脑上的 apksigner。
 *
 * ## 密钥从哪来
 * 导入补签**只使用内置宿主密钥**（APK 自带 `assets/keystore/zorvai_release.bks`），
 * 首次使用时释放到 `filesDir/plugin_sign/`。
 *
 * 为什么不做自定义密钥库：插件必须和宿主**同签名**才能被加载，第三方密钥签出来的包
 * 即使补签成功也过不了同签名闸门，所以 UI 只保留「开 / 关」开关，开启即自动用内置密钥。
 *
 * ### 关于「内置密钥」的取舍
 * 这把密钥**只用于证明「插件出自本机构建」**，是同签名闸门的比对基准，
 * **不承担任何安全鉴权职责**（不是加密、不是身份校验、不是授权）。
 * 开源场景下把它藏起来 = 用户永远签不了第三方插件 = 功能残废，
 * 所以它是**随 APK 分发的宿主公钥材料**，这也是 Android 同签名机制的常规做法
 * （debug.keystore 同样随 AOSP 公开）。私钥部分的敏感性止于「别被拿去冒名分发」，
 * 而插件闸门本来只防**本机**加载非同源代码。
 *
 * ⚠️ **APK 里内置 ≠ 仓库里入库**：这两个文件由 `.gitignore` 排除、不进 git
 * （2026-09-17 用户明确要求「别推送签名」）。新 clone 缺这两个文件**不影响编译**，
 * 只是内置补签不可用（[sign] 会明确报「内置密钥缺失」），用户仍可手动指定自己的密钥库。
 * 从源码构建又想要内置补签的，自己把宿主密钥转成 p12/bks 放回该目录即可。
 *
 * ## 为什么用 BKS 而不是 JKS/PKCS12
 * 仓库里的 `zorvai_release.jks` 实际是 **PKCS12**（文件名骗人）。部分 ROM 的
 * 默认 Provider 读 PKCS12 会抛 "Wrong version of key store"，所以内置密钥同时准备了
 * **PKCS12 与 BKS 两种封装**；运行时由 [BuildEngine.loadKeyStore] 按
 * BKS → PKCS12 → JKS × 默认 → BC 逐个尝试，[sign] 再在多个候选文件间依次回退。
 */
object PluginSigning {

    private const val TAG = "PluginSigning"
    private const val PREFS = "quro_plugin_sign"
    private const val K_ENABLED = "enabled"
    private const val K_PATH = "keystore_path"
    private const val K_ALIAS = "alias"
    private const val K_STORE_PASS = "store_password"
    private const val K_KEY_PASS = "key_password"
    private const val K_SEEDED = "seeded_from_build"

    // ===== 内置宿主密钥 =====
    /**
     * APK 内置密钥库（assets 下路径，**按顺序尝试**）。
     *
     * 为什么要同一把密钥准备两种封装：不同 ROM 的 Provider 组合差异极大 ——
     * 有的读不了 PKCS12（报 "Wrong version of key store"），有的 BC 版本太老读不了 BKS v2。
     * 两个都打包（合计 5KB），运行时哪个能加载就用哪个，把格式抽奖的概率降到 0。
     */
    private val BUILTIN_ASSETS = listOf(
        "keystore/zorvai_release.p12",
        "keystore/zorvai_release.bks",
    )

    /** 主候选名（UI 展示、默认配置用） */
    const val BUILTIN_NAME = "zorvai_release.p12"

    /** 内置密钥库的别名与口令。
     *  ⚠️ 这不是「秘密」：密钥库本身随源码公开（见类注释），口令公开与否不改变安全边界。
     *  写在这里是为了让用户零配置可用 —— 否则每次导入插件都要手输口令，等于没做。 */
    const val BUILTIN_ALIAS = "zorvai"
    const val BUILTIN_PASSWORD = "zorvai123"

    /** 手动选择的密钥库落在这里 */
    private fun keyDir(context: Context) = File(context.filesDir, "plugin_sign").apply { mkdirs() }

    /**
     * 上一次【真正签成功】的那个密钥库文件名。
     *
     * 存在意义：配置指向 A、但 A 在这台机器上读不了、实际由内置 B 兜底签成功的情况是有的
     * （各 ROM 的 Provider 组合差异）。提示里如果照抄配置名，用户会以为用的是 A，
     * 排查方向直接被带偏 —— 所以提示统一用这里记录的实际生效者。
     */
    @Volatile
    var lastKeystoreName: String? = null
        private set

    /** 供 UI 选择密钥库时落盘用 */
    fun keyDirOf(context: Context): File = keyDir(context)

    data class Config(
        val enabled: Boolean = false,
        val keystorePath: String = "",
        val alias: String = "zorvai",
        val storePassword: String = "",
        val keyPassword: String = "",
    ) {
        /** 开关开着、密钥库确实存在，才算"可以补签" */
        val ready: Boolean
            get() = enabled && keystorePath.isNotBlank() && runCatching { File(keystorePath).exists() }
                .getOrDefault(false)

        val keystoreName: String
            get() = keystorePath.takeIf { it.isNotBlank() }?.let { File(it).name } ?: "未选择"
    }

    fun load(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var cfg = Config(
            enabled = p.getBoolean(K_ENABLED, false),
            keystorePath = p.getString(K_PATH, "").orEmpty(),
            alias = p.getString(K_ALIAS, "zorvai").orEmpty().ifBlank { "zorvai" },
            storePassword = p.getString(K_STORE_PASS, "").orEmpty(),
            keyPassword = p.getString(K_KEY_PASS, "").orEmpty(),
        )
        val builtIn = builtInFiles(context).firstOrNull()
        // 首次进入：直接用内置宿主密钥（零配置可用）
        if (!p.getBoolean(K_SEEDED, false)) {
            cfg = if (builtIn != null) {
                cfg.copy(
                    enabled = true,
                    keystorePath = builtIn.absolutePath,
                    alias = BUILTIN_ALIAS,
                    storePassword = BUILTIN_PASSWORD,
                    keyPassword = BUILTIN_PASSWORD,
                )
            } else cfg
            p.edit().putBoolean(K_SEEDED, true).apply()
            save(context, cfg)
        }
        // 强制迁移到内置密钥：旧自定义配置不再生效；插件只接受宿主同签名
        if (builtIn != null && cfg.keystorePath != builtIn.absolutePath) {
            cfg = cfg.copy(
                keystorePath = builtIn.absolutePath,
                alias = BUILTIN_ALIAS,
                storePassword = BUILTIN_PASSWORD,
                keyPassword = BUILTIN_PASSWORD,
            )
            save(context, cfg)
        }
        // 每次加载都自愈：开关开着但密钥库被删/路径失效 → 回落到内置，不让用户卡在无效配置上
        if (cfg.enabled && !cfg.ready) {
            val repaired = useBuiltIn(context, cfg)
            if (repaired.keystorePath != cfg.keystorePath) {
                Log.w(TAG, "签名配置失效（${cfg.keystorePath}），已回落到内置密钥")
                cfg = repaired
                save(context, cfg)
            }
        }
        return cfg
    }

    /**
     * 把 APK 内置的宿主密钥库从 assets 释放到私有目录并作为当前配置返回。
     *
     * 内容一致就跳过拷贝（避免每次都做一次 IO）。
     * @return 配置；assets 缺失时返回原 [cfg]（此时 [Config.ready] 仍为 false，UI 会提示）
     */
    fun useBuiltIn(context: Context, cfg: Config): Config {
        val file = builtInFiles(context).firstOrNull()
        if (file == null) {
            Log.e(TAG, "内置密钥库缺失：assets/${BUILTIN_ASSETS.joinToString()}")
            return cfg
        }
        return cfg.copy(
            enabled = true,
            keystorePath = file.absolutePath,
            alias = BUILTIN_ALIAS,
            storePassword = BUILTIN_PASSWORD,
            keyPassword = BUILTIN_PASSWORD,
        )
    }

    /**
     * 释放全部内置密钥库，**保持 BUILTIN_ASSETS 的顺序**。
     * 每个文件仅 2~3KB，整体读成字节再比对（同时避开压缩 asset 的 available() 口径问题）。
     */
    fun builtInFiles(context: Context): List<File> = BUILTIN_ASSETS.mapNotNull { asset ->
        try {
            val bytes = context.assets.open(asset).use { it.readBytes() }
            if (bytes.isEmpty()) {
                Log.e(TAG, "内置密钥库为空：assets/$asset")
                return@mapNotNull null
            }
            val out = File(keyDir(context), asset.substringAfterLast('/'))
            if (!out.exists() || !out.readBytes().contentEquals(bytes)) {
                out.writeBytes(bytes)
                Log.i(TAG, "已释放内置密钥库 → ${out.absolutePath}（${bytes.size} B）")
            }
            out
        } catch (e: Throwable) {
            Log.e(TAG, "释放内置密钥库失败：assets/$asset", e)
            null
        }
    }

    fun save(context: Context, cfg: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ENABLED, cfg.enabled)
            .putString(K_PATH, cfg.keystorePath)
            .putString(K_ALIAS, cfg.alias)
            .putString(K_STORE_PASS, cfg.storePassword)
            .putString(K_KEY_PASS, cfg.keyPassword)
            .apply()
    }

    /**
     * 按当前配置把 [input] 补签成 [output]。
     *
     * @return null 表示成功（[output] 已就绪）；否则为失败原因，调用方应**回退到原包安装**
     */
    fun sign(context: Context, input: File, output: File): String? {
        val cfg = load(context)
        // 候选顺序：用户配的 → 内置 p12 → 内置 bks。
        // 理由：不补签就一定会撞同签名闸门，用户拿到的只有一句「安装失败」。
        // 宁可逐个试到成功，再把失败原因留到最后一起报，也不要在无解的错误上停住。
        val candidates = ArrayList<Triple<File, String, Pair<String, String>>>(3)
        if (cfg.ready) {
            candidates += Triple(File(cfg.keystorePath), cfg.alias, cfg.storePassword to cfg.keyPassword)
        }
        builtInFiles(context).forEach { f ->
            candidates += Triple(f, BUILTIN_ALIAS, BUILTIN_PASSWORD to BUILTIN_PASSWORD)
        }
        if (candidates.isEmpty()) return "没有任何可用密钥库（用户未配置，且内置密钥缺失）"

        val errors = mutableListOf<String>()
        for ((ks, alias, passes) in candidates) {
            val err = BuildEngine.reSignApk(
                context, input, output, ks, alias, passes.first, passes.second
            )
            if (err == null) {
                lastKeystoreName = ks.name
                if (cfg.ready && ks.absolutePath != File(cfg.keystorePath).absolutePath) {
                    Log.w(TAG, "配置的密钥库不可用，实际用 ${ks.name} 完成补签")
                }
                return null
            }
            Log.w(TAG, "用 ${ks.name} 补签失败：$err")
            errors += "${ks.name}：$err"
            // 上一次可能写出半成品，删掉再试下一个
            runCatching { if (output.exists()) output.delete() }
        }
        return "补签未成功：${errors.joinToString(" ｜ ")}"
    }

}

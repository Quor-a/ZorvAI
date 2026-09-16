package com.ai.assistance.quro.core.plugin

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.build.BuildEngine
import org.json.JSONObject
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
 * 1. 用户在插件页「选择密钥库」手动指定（落盘到 `filesDir/plugin_sign/<原名>`）；
 * 2. 首次加载时若本页没配过，**自动继承构建台** `filesDir/buildproject/project_config.json`
 *    里 `signing` 段已配好的 keystore / 别名 / 密码 —— 所以在构建台里「导入 keystore」
 *    选过宿主密钥之后，这里零配置即可用。
 *
 * ⚠️ 必须用**宿主自身那把密钥**（本仓：`zorvai_release.jks`，别名 `zorvai`）。
 * 用别把密钥（例如构建台内置的 `debug.keystore`）签出来的插件照样会被同签名闸门拒绝 ——
 * 所以本模块**刻意不把内置 debug.keystore 当默认值**，宁可留在「未开启」状态并提示用户去选。
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

    /** 构建台工程根目录（与 ProjectViewModel 保持一致） */
    private const val BUILD_PROJECT = "buildproject"

    /** 手动选择的密钥库落在这里 */
    private fun keyDir(context: Context) = File(context.filesDir, "plugin_sign").apply { mkdirs() }

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
        // 首次进入（本页从未配置过）：尝试继承构建台的签名设置，省掉用户重复填写
        if (!p.getBoolean(K_SEEDED, false)) {
            val inherited = seedFromBuildProject(context)
            if (inherited != null) {
                cfg = cfg.copy(
                    enabled = true,
                    keystorePath = inherited.keystorePath,
                    alias = inherited.alias.ifBlank { cfg.alias },
                    storePassword = inherited.storePassword,
                    keyPassword = inherited.keyPassword,
                )
                Log.i(TAG, "已继承构建台签名配置：${inherited.keystorePath}")
            }
            p.edit().putBoolean(K_SEEDED, true).apply()
            save(context, cfg)
        }
        return cfg
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
        if (!cfg.enabled) return "未开启导入补签"
        val ks = File(cfg.keystorePath)
        if (!ks.exists()) return "密钥库不存在：${cfg.keystorePath}"
        if (cfg.alias.isBlank()) return "未填写 keystore 别名"
        return BuildEngine.reSignApk(
            context, input, output, ks, cfg.alias, cfg.storePassword, cfg.keyPassword
        )
    }

    /** 从构建台 `buildproject/project_config.json` 的 signing 段继承；没配自定义签名则返回 null */
    private fun seedFromBuildProject(context: Context): Config? {
        return try {
            val f = File(File(context.filesDir, BUILD_PROJECT), "project_config.json")
            if (!f.exists()) return null
            val s = JSONObject(f.readText()).optJSONObject("signing") ?: return null
            if (!s.optBoolean("useCustom", false)) return null
            if (s.isNull("keystorePath")) return null
            val path = s.optString("keystorePath", "")
            if (path.isBlank() || !File(path).exists()) return null
            Config(
                enabled = true,
                keystorePath = File(path).absolutePath,
                alias = s.optString("alias", "zorvai"),
                storePassword = s.optString("storePassword", ""),
                keyPassword = s.optString("keyPassword", ""),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "读取构建台签名配置失败", e)
            null
        }
    }
}

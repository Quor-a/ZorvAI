package com.ai.assistance.quro.core.pet

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 随 APK 内置的宠物包目录与安装器。
 *
 * assets/pets/<dir>/ 下每个含 pet.json 的目录都是一个「内置宠物包目录项」：
 * 用户在 ZorvAI 里点一下即可安装并启用，不需要自己准备或放置任何文件。
 * 安装到 filesDir/desktop_pets/<id>/ 后由 [PetPack.scan] 统一发现，
 * 与导入的宠物包走完全相同的渲染链路。
 *
 * 版本标记做幂等：素材有更新时把 [VERSION] 加 1 即可触发重装。
 * 点开头的标记文件不会被扫描器当成宠物目录。
 */
object PetPackAssets {

    const val BUILTIN_ID = "dsh.dafeiyu"
    private const val ASSET_DIR = "pets/dafeiyu"
    private const val ASSET_ROOT = "pets"
    private const val VERSION = 1
    private const val VERSION_FILE = ".builtin_version"

    /** 内置目录里的一个可安装项（只从 assets 的 pet.json 读取，不写磁盘）。 */
    data class BuiltinEntry(
        val assetDir: String,
        val id: String,
        val displayName: String,
        val description: String,
        val animationCount: Int,
        val attribution: PetPack.Attribution?,
    )

    /** 列出 APK 内置的全部宠物包，供设置页直接展示与一键安装。 */
    fun builtinCatalog(context: Context): List<BuiltinEntry> = runCatching {
        val dirs = context.assets.list(ASSET_ROOT)?.filter { !it.startsWith(".") } ?: emptyList()
        dirs.mapNotNull { name ->
            runCatching {
                val text = context.assets.open("$ASSET_ROOT/$name/pet.json")
                    .bufferedReader().use { it.readText() }
                val m = PetPack.parseManifest(JSONObject(text))
                BuiltinEntry(
                    assetDir = "$ASSET_ROOT/$name",
                    id = m.id,
                    displayName = m.displayName,
                    description = m.description,
                    animationCount = m.animations.size,
                    attribution = m.attribution,
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /** 确保默认内置包已落盘；返回可传给 PetPrefs 的目录名，失败返回 null。 */
    @Synchronized
    fun ensure(context: Context): String? = ensurePack(context, ASSET_DIR, BUILTIN_ID)

    /**
     * 把内置包安装到 filesDir/desktop_pets/<targetId>/。
     * force=true 时忽略版本标记强制重装（恢复默认 / 修复被删坏的内置包）。
     */
    @Synchronized
    fun ensurePack(
        context: Context,
        assetDir: String,
        targetId: String,
        force: Boolean = false,
    ): String? = runCatching {
        require(targetId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "内置包 id 不合法" }
        val root = PetPack.packRoot(context)
        if (!root.exists()) root.mkdirs()
        val target = PetPack.packDir(context, targetId)
        val marker = File(target, VERSION_FILE)
        if (!force && target.isDirectory && marker.exists() &&
            marker.readText().trim() == VERSION.toString() &&
            File(target, "pet.json").exists()
        ) {
            return@runCatching targetId
        }

        val staging = File(root, ".builtin-staging-${System.currentTimeMillis()}")
        staging.mkdirs()
        try {
            copyAssetDir(context, assetDir, staging)
            require(File(staging, "pet.json").exists()) { "内置包缺少 pet.json" }
            File(staging, VERSION_FILE).writeText(VERSION.toString())
            if (target.exists()) {
                require(target.deleteRecursively()) { "无法更新已存在的宠物包目录" }
            }
            require(staging.renameTo(target)) { "内置包目录创建失败" }
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        targetId
    }.getOrNull()

    /** 用户尚未显式选过宠物包时，默认启用内置包。 */
    fun defaultPackDir(context: Context, chosen: String?): String? {
        ensure(context)
        return chosen ?: ensure(context)
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val am = context.assets
        val names = am.list(assetPath) ?: return
        for (name in names) {
            if (name.startsWith(".")) continue
            val child = "$assetPath/$name"
            val out = File(dest, name)
            val children = am.list(child) ?: emptyArray()
            if (children.isNotEmpty()) {
                out.mkdirs()
                copyAssetDir(context, child, out)
            } else {
                out.parentFile?.mkdirs()
                am.open(child).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
            }
        }
    }
}

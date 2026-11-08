package com.ai.assistance.quro.core.pet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 宠物包系统：
 * 每个宠物 = 一个目录（filesDir/desktop_pets/<dirName>/），内含 pet.json + spritesheet 图片
 * （+可选音效）。pet.json 字段与校验（schema 1）。
 *
 * animations 13 个标准名（stand/walkRight/walkLeft/dragged/fall/thrown/celebrate/think/
 * useTool/reply/action/climbRightEdge/climbLeftEdge），每行一条动画按行列取帧。
 */
object PetPack {

    const val DIR_NAME = "desktop_pets"
    const val MAX_UNZIP_BYTES = 200L * 1024 * 1024
    private val ALLOWED_EXT = setOf("json", "webp", "png", "jpg", "jpeg", "wav", "ogg", "mp3")

    /** 13 个标准动画名全集。 */
    val REQUIRED_ANIMATIONS = listOf(
        "stand", "think", "walkRight", "walkLeft", "dragged", "useTool", "reply",
        "celebrate", "fall", "thrown", "action", "climbRightEdge", "climbLeftEdge",
    )

    data class AnimDef(val row: Int, val frames: Int, val frameDurationMs: Long = 100, val playback: String = "loop")

    data class Manifest(
        val id: String,
        val displayName: String,
        val description: String = "",
        val cellWidth: Int,
        val cellHeight: Int,
        val sheetPath: String,
        val columns: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val animations: Map<String, AnimDef>,
        val interactions: List<Interaction>,
        val sounds: Map<String, String> = emptyMap(),
        val dirName: String = "",
    ) {
        fun sheetFile(context: Context): File = File(packDir(context, dirName), sheetPath)
    }

    data class Interaction(
        val id: String,
        val label: String,
        val icon: String = "✨",
        val animation: String?,
        val prompt: String = "{input}",
        val enabled: Boolean = true,
        val order: Int = 0,
    )

    data class LoadedPack(val manifest: Manifest, val dirName: String)

    fun packRoot(context: Context): File = File(context.filesDir, DIR_NAME)

    fun packDir(context: Context, dirName: String): File = File(packRoot(context), dirName)

    // ———————————————— 解析与校验 ————————————————

    fun parseManifest(json: JSONObject): Manifest {
        val id = json.optString("id").trim()
        require(id.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"))) { "宠物包 id 不合法：$id" }
        val displayName = json.optString("displayName").trim()
        require(displayName.isNotEmpty()) { "宠物包缺少 displayName" }
        val sheet = json.optJSONObject("spritesheet")
            ?: throw IllegalArgumentException("宠物包缺少 spritesheet")
        val sheetPath = sheet.optString("path").trim()
        require(sheetPath.isNotEmpty()) { "spritesheet.path is required." }
        val cellW = sheet.optInt("cellWidth")
        val cellH = sheet.optInt("cellHeight")
        require(cellW > 0 && cellH > 0) { "cellWidth/cellHeight must be positive." }
        val animsJson = sheet.optJSONObject("animations") ?: JSONObject()
        val anims = mutableMapOf<String, AnimDef>()
        for (name in REQUIRED_ANIMATIONS) {
            val a = animsJson.optJSONObject(name)
                ?: throw IllegalArgumentException("缺少动画定义：$name")
            val row = a.optInt("row", -1)
            val frames = a.optInt("frames")
            require(row >= 0 && frames > 0) { "动画 $name 的 row/frames 不合法" }
            anims[name] = AnimDef(
                row = row, frames = frames,
                frameDurationMs = a.optLong("frameDurationMs", 100L).coerceAtLeast(1L),
                playback = a.optString("playback", "loop"),
            )
        }
        val columns = sheet.optInt("columns", 0)
        require(columns <= 0 || anims.values.maxOf { it.frames } <= columns) { "columns 须 ≥ 最长动画帧数" }
        val interactions = mutableListOf<Interaction>()
        json.optJSONArray("interactions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val iid = o.optString("id").trim()
                val label = o.optString("label").trim()
                if (iid.isEmpty() || label.isEmpty()) continue
                interactions += Interaction(
                    id = iid, label = label.take(20),
                    icon = o.optString("icon", "✨").ifBlank { "✨" },
                    animation = o.optString("animation").takeIf { it.isNotBlank() && anims.containsKey(it) },
                    prompt = o.optString("prompt", "{input}").ifBlank { "{input}" }.take(2000),
                    enabled = o.optBoolean("enabled", true),
                    order = o.optInt("order", i),
                )
            }
        }
        val sounds = mutableMapOf<String, String>()
        json.optJSONObject("sounds")?.let { so ->
            so.keys().asSequence().forEach { k -> so.optString(k).takeIf { it.isNotBlank() }?.let { sounds[k] = it } }
        }
        return Manifest(
            id = id, displayName = displayName,
            description = json.optString("description"),
            cellWidth = cellW, cellHeight = cellH,
            sheetPath = sanitizePath(sheetPath), columns = columns,
            displayWidth = sheet.optInt("defaultDisplayWidth", cellW / 2).coerceAtLeast(16),
            displayHeight = sheet.optInt("defaultDisplayHeight", cellH / 2).coerceAtLeast(16),
            animations = anims, interactions = interactions, sounds = sounds,
        )
    }

    /** 路径净化（反斜杠转斜杠、去 "." 段、禁 ".." 与首尾斜杠）。 */
    fun sanitizePath(raw: String): String {
        val cleaned = raw.replace('\\', '/')
            .split('/').filter { it.isNotBlank() && it != "." }
        require(cleaned.none { it == ".." }) { "路径不得包含 .." }
        return cleaned.joinToString("/")
    }

    // ———————————————— 目录扫描 ————————————————

    /** 扫描全部可用包（spritesheet 文件真实存在才算可用）。 */
    fun scan(context: Context): Pair<List<LoadedPack>, List<Pair<String, String>>> {
        val root = packRoot(context)
        val available = mutableListOf<LoadedPack>()
        val invalid = mutableListOf<Pair<String, String>>()
        if (!root.exists()) root.mkdirs()
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.forEach { dir ->
            runCatching {
                val manifestFile = findManifest(dir)
                    ?: throw IllegalArgumentException("宠物包缺少 pet.json")
                val m = parseManifest(JSONObject(manifestFile.readText()))
                val bitmapFile = File(dir, m.sheetPath)
                require(bitmapFile.exists()) { "spritesheet 图片不存在：${m.sheetPath}" }
                available += LoadedPack(m.copy(dirName = dir.name), dir.name)
            }.onFailure { invalid += dir.name to (it.message ?: it.javaClass.simpleName) }
        }
        return available to invalid
    }

    private fun findManifest(dir: File): File? =
        File(dir, "pet.json").takeIf { it.exists() }
            ?: dir.listFiles { f -> f.isDirectory }?.firstNotNullOfOrNull { sub -> File(sub, "pet.json").takeIf { it.exists() } }

    // ———————————————— zip 导入 ————————————————

    /** zip 导入（200MB 上限/扩展名白名单/路径净化/pet.json 校验）。 */
    fun importZip(context: Context, stream: java.io.InputStream, expectId: String? = null): LoadedPack {
        val root = packRoot(context); root.mkdirs()
        val staging = File(root, ".staging-${System.currentTimeMillis()}")
        staging.mkdirs()
        return try {
            var total = 0L
            ZipInputStream(stream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = sanitizePath(entry.name)
                    if (entry.isDirectory) { entry = zis.nextEntry; continue }
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    require(ext in ALLOWED_EXT) { "宠物包含不允许的文件类型：$name" }
                    val out = File(staging, name)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { out2 -> zis.copyTo(out2) }
                    total += out.length()
                    require(total <= MAX_UNZIP_BYTES) { "宠物包解压后超过 200MB" }
                    entry = zis.nextEntry
                }
            }
            val manifestFile = findManifest(staging)
                ?: throw IllegalArgumentException("宠物包缺少 pet.json")
            val m = parseManifest(JSONObject(manifestFile.readText()))
            if (expectId != null) require(m.id == expectId) { "宠物包 id 与预期不符：${m.id} != $expectId" }
            val taken = root.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
            var dirName = m.id.replace(Regex("[^A-Za-z0-9._-]"), "_").trimEnd('.').ifBlank { "pet" }
            var n = 2
            while (dirName in taken) { dirName = "${m.id}-$n"; n++ }
            val target = File(root, dirName)
            require(staging.renameTo(target)) { "宠物包目录创建失败" }
            LoadedPack(m.copy(dirName = dirName), dirName)
        } finally {
            staging.deleteRecursively()
        }
    }

    fun delete(context: Context, dirName: String): Boolean {
        require(!dirName.startsWith(".") && !dirName.contains('/') && !dirName.contains('\\')) { "非法目录名" }
        val dir = packDir(context, dirName)
        return dir.exists() && dir.deleteRecursively()
    }

    // ———————————————— 内置示例包清单 ————————————————

    /** 生成最小可用的示例 pet.json（spritesheet 图片由用户替换；渲染有 Canvas 矢量兜底）。 */
    fun writeSampleManifest(context: Context): File {
        val dir = packDir(context, "sample.blip")
        dir.mkdirs()
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("id", "sample.blip")
            put("displayName", "示例团子")
            put("description", "内置示例宠物包（spritesheet 可自行替换）")
            put("spritesheet", JSONObject().apply {
                put("path", "sprites.png")
                put("cellWidth", 224); put("cellHeight", 243)
                put("columns", 13)
                put("defaultDisplayWidth", 56); put("defaultDisplayHeight", 61)
                put("animations", JSONObject().apply {
                    REQUIRED_ANIMATIONS.forEachIndexed { row, name ->
                        put(name, JSONObject().apply {
                            put("row", row); put("frames", 2); put("frameDurationMs", 140); put("playback", "loop")
                        })
                    }
                })
            })
            put("interactions", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "say.hello"); put("label", "打招呼"); put("icon", "👋")
                    put("animation", "celebrate"); put("prompt", "请和用户打个招呼，主题：{input}"); put("enabled", true); put("order", 0)
                })
            })
        }
        val f = File(dir, "pet.json")
        f.writeText(json.toString(2))
        return dir
    }
}

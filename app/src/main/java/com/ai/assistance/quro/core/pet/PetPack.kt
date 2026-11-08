package com.ai.assistance.quro.core.pet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 宠物包系统：
 * 每个宠物 = 一个目录（filesDir/desktop_pets/<dirName>/），内含 pet.json + 雪碧图
 * （+可选音效）。schema 1。
 *
 * 动画按「分片雪碧图」组织：pet.json 顶层 animations 里每一项可以带 sheet，
 * 指向顶层 sheets 声明的一张图；未带 sheet 的旧包仍用 spritesheet.path 这张主图。
 * 这样一只能装下上百段动作的宠物不会解码成单张巨型位图，运行时只加载当前动作
 * 所在的那一张分片。
 *
 * 13 个标准动画名（stand/think/walkRight/walkLeft/dragged/useTool/reply/
 * celebrate/fall/thrown/action/climbRightEdge/climbLeftEdge）必须齐全，
 * 其余动作名可以任意扩展（环境动作池、事件动画、招牌动作等）。
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

    data class AnimDef(
        val row: Int,
        val frames: Int,
        val frameDurationMs: Long = 100,
        val playback: String = "loop",
        /** 所属分片 key（对应 Manifest.sheets）；null 表示使用主图 spritesheet.path。 */
        val sheet: String? = null,
    )

    data class AmbientActions(
        val enabledByDefault: Boolean = true,
        val intervalSeconds: Int = 33,
        val actions: List<String> = emptyList(),
    )

    data class ModeProfile(
        val baseAction: String = "stand",
        val moveRight: String? = null,
        val moveLeft: String? = null,
        val ambient: AmbientActions? = null,
    )

    data class Author(val name: String, val role: String = "", val url: String = "")

    data class Attribution(
        val authors: List<Author> = emptyList(),
        val sourceUrl: String = "",
        val licenseName: String = "",
        val licenseUrl: String = "",
        val modifications: String = "",
        val notice: String = "",
    )

    data class PetTheme(
        val bubbleContainer: String = "",
        val bubbleContent: String = "",
        val bubbleStroke: String = "",
    )

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
        /** 分片 key -> 包内相对路径。 */
        val sheets: Map<String, String> = emptyMap(),
        val modeProfiles: Map<String, ModeProfile> = emptyMap(),
        val ambientActions: AmbientActions? = null,
        val theme: PetTheme? = null,
        val tags: List<String> = emptyList(),
        val attribution: Attribution? = null,
    ) {
        fun sheetFile(context: Context): File = File(packDir(context, dirName), sheetPath)

        /** 某个动画所在的分片文件；未声明分片时回退主图。 */
        fun sheetFileFor(context: Context, animName: String): File {
            val key = animations[animName]?.sheet
            val rel = key?.let { sheets[it] } ?: sheetPath
            return File(packDir(context, dirName), rel)
        }

        /** 该动画引用的分片 key；null 表示主图（可用于缓存键）。 */
        fun sheetKeyFor(animName: String): String = animations[animName]?.sheet ?: "__main__"

        /** 按模式取环境动作池，未配置时回退顶层 ambientActions。 */
        fun ambientFor(mode: String): AmbientActions? =
            modeProfiles[mode]?.ambient ?: ambientActions
    }

    data class Interaction(
        val id: String,
        val label: String,
        val icon: String = "spark",
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
        val schema = json.optInt("schemaVersion", 1)
        require(schema == 1) { "宠物包 schemaVersion 必须是 1" }
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

        // 新格式：animations 在顶层；旧格式：嵌在 spritesheet 内。
        val animsJson = json.optJSONObject("animations")
            ?: sheet.optJSONObject("animations")
            ?: JSONObject()
        val anims = mutableMapOf<String, AnimDef>()
        val keys = animsJson.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val a = animsJson.optJSONObject(name) ?: continue
            val row = a.optInt("row", -1)
            val frames = a.optInt("frames")
            require(row >= 0 && frames > 0) { "动画 $name 的 row/frames 不合法" }
            anims[name] = AnimDef(
                row = row,
                frames = frames,
                frameDurationMs = a.optLong("frameDurationMs", 100L).coerceAtLeast(1L),
                playback = a.optString("playback", "loop").ifBlank { "loop" },
                sheet = a.optString("sheet").takeIf { it.isNotBlank() },
            )
        }
        for (name in REQUIRED_ANIMATIONS) {
            require(anims.containsKey(name)) { "缺少动画定义：$name" }
        }
        val columns = sheet.optInt("columns", 0)
        require(columns <= 0 || anims.values.maxOf { it.frames } <= columns) { "columns 须 ≥ 最长动画帧数" }

        // 分片声明
        val sheets = mutableMapOf<String, String>()
        json.optJSONObject("sheets")?.let { so ->
            val sk = so.keys()
            while (sk.hasNext()) {
                val k = sk.next()
                val v = so.optString(k).trim()
                if (v.isNotEmpty()) sheets[k] = sanitizePath(v)
            }
        }
        // 所有动画引用的分片必须已声明
        val usedSheets = anims.values.mapNotNull { it.sheet }.toSet()
        require(usedSheets.all { it in sheets }) {
            "动画引用了未声明的分片：${usedSheets.firstOrNull { it !in sheets }}"
        }

        val interactions = mutableListOf<Interaction>()
        json.optJSONArray("interactions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val iid = o.optString("id").trim()
                val label = o.optString("label").trim()
                if (iid.isEmpty() || label.isEmpty()) continue
                interactions += Interaction(
                    id = iid, label = label.take(20),
                    // icon 是语义矢量 key（wave / spark / task …），不再是 emoji；
                    // 旧包里若仍写 emoji，由 PetGlyph.fromIcon 兜底映射。
                    icon = o.optString("icon", "spark").ifBlank { "spark" },
                    animation = o.optString("animation").takeIf { it.isNotBlank() && anims.containsKey(it) },
                    prompt = o.optString("prompt", "{input}").ifBlank { "{input}" }.take(2000),
                    enabled = o.optBoolean("enabled", true),
                    order = o.optInt("order", i),
                )
            }
        }
        require(interactions.size <= 20) { "宠物包 interactions 不能超过 20 条" }

        val sounds = mutableMapOf<String, String>()
        json.optJSONObject("sounds")?.let { so ->
            so.keys().asSequence().forEach { k -> so.optString(k).takeIf { it.isNotBlank() }?.let { sounds[k] = it } }
        }

        // 模式配置 / 环境动作池
        val modeProfiles = mutableMapOf<String, ModeProfile>()
        json.optJSONObject("modeProfiles")?.let { mo ->
            val mk = mo.keys()
            while (mk.hasNext()) {
                val k = mk.next()
                val p = mo.optJSONObject(k) ?: continue
                modeProfiles[k] = ModeProfile(
                    baseAction = p.optString("baseAction", "stand").ifBlank { "stand" },
                    moveRight = p.optString("moveRight").takeIf { it.isNotBlank() },
                    moveLeft = p.optString("moveLeft").takeIf { it.isNotBlank() },
                    ambient = p.optJSONObject("ambientActions")?.let { parseAmbient(it) },
                )
            }
        }
        val ambientActions = json.optJSONObject("ambientActions")?.let { parseAmbient(it) }

        val theme = json.optJSONObject("theme")?.let {
            PetTheme(
                bubbleContainer = it.optString("bubbleContainer"),
                bubbleContent = it.optString("bubbleContent"),
                bubbleStroke = it.optString("bubbleStroke"),
            )
        }
        val tags = mutableListOf<String>()
        json.optJSONArray("tags")?.let { ta ->
            for (i in 0 until ta.length()) ta.optString(i).takeIf { it.isNotBlank() }?.let { tags += it }
        }
        val attribution = json.optJSONObject("attribution")?.let { ao ->
            val authors = mutableListOf<Author>()
            ao.optJSONArray("authors")?.let { aa ->
                for (i in 0 until aa.length()) {
                    val a = aa.optJSONObject(i) ?: continue
                    val n = a.optString("name").trim()
                    if (n.isNotEmpty()) authors += Author(n, a.optString("role"), a.optString("url"))
                }
            }
            Attribution(
                authors = authors.take(20),
                sourceUrl = ao.optString("sourceUrl"),
                licenseName = ao.optString("licenseName"),
                licenseUrl = ao.optString("licenseUrl"),
                modifications = ao.optString("modifications"),
                notice = ao.optString("notice"),
            )
        }

        return Manifest(
            id = id, displayName = displayName,
            description = json.optString("description"),
            cellWidth = cellW, cellHeight = cellH,
            sheetPath = sanitizePath(sheetPath), columns = columns,
            displayWidth = sheet.optInt("defaultDisplayWidth", cellW / 2).coerceAtLeast(16),
            displayHeight = sheet.optInt("defaultDisplayHeight", cellH / 2).coerceAtLeast(16),
            animations = anims, interactions = interactions, sounds = sounds,
            sheets = sheets, modeProfiles = modeProfiles, ambientActions = ambientActions,
            theme = theme, tags = tags, attribution = attribution,
        )
    }

    private fun parseAmbient(o: JSONObject): AmbientActions {
        val actions = mutableListOf<String>()
        o.optJSONArray("actions")?.let { aa ->
            for (i in 0 until aa.length()) aa.optString(i).takeIf { it.isNotBlank() }?.let { actions += it }
        }
        return AmbientActions(
            enabledByDefault = o.optBoolean("enabledByDefault", true),
            intervalSeconds = o.optInt("intervalSeconds", 33).coerceAtLeast(1),
            actions = actions,
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

    /** 扫描全部可用包（主图与所有分片都真实存在才算可用）。 */
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
                require(File(dir, m.sheetPath).exists()) { "spritesheet 图片不存在：${m.sheetPath}" }
                m.sheets.values.forEach { rel ->
                    require(File(dir, rel).exists()) { "分片图片不存在：$rel" }
                }
                available += LoadedPack(m.copy(dirName = dir.name), dir.name)
            }.onFailure { invalid += dir.name to (it.message ?: it.javaClass.simpleName) }
        }
        return available to invalid
    }

    private fun findManifest(dir: File): File? =
        File(dir, "pet.json").takeIf { it.exists() }
            ?: dir.listFiles { f -> f.isDirectory }?.firstNotNullOfOrNull { sub -> File(sub, "pet.json").takeIf { it.exists() } }

    // ———————————————— zip 导入 ————————————————

    /**
     * zip 导入（200MB 上限/扩展名白名单/路径净化/pet.json 校验）。
     * replaceExisting=true 时，同 id 的已安装包会被替换而不是生成 "<id>-2" 垃圾目录，
     * 供应用内「直接导入 / 覆盖安装」使用。
     */
    fun importZip(
        context: Context,
        stream: java.io.InputStream,
        expectId: String? = null,
        replaceExisting: Boolean = false,
    ): LoadedPack {
        val root = packRoot(context); root.mkdirs()
        val staging = File(root, ".staging-${System.currentTimeMillis()}")
        staging.mkdirs()
        return try {
            var total = 0L
            var entries = 0
            ZipInputStream(stream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = sanitizePath(entry.name)
                    if (entry.isDirectory) { entry = zis.nextEntry; continue }
                    entries++
                    require(entries <= 512) { "宠物包文件数量超过 512" }
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
            val base = m.id.replace(Regex("[^A-Za-z0-9._-]"), "_").trimEnd('.').ifBlank { "pet" }
            var dirName = base
            if (replaceExisting) {
                // 覆盖安装：删掉所有 manifest.id 相同的旧目录（含历史 "<id>-2" 副本）。
                root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.forEach { d ->
                    val old = runCatching {
                        findManifest(d)?.let { parseManifest(JSONObject(it.readText())).id }
                    }.getOrNull()
                    if (old == m.id) d.deleteRecursively()
                }
            } else {
                val taken = root.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
                var n = 2
                while (dirName in taken) { dirName = "$base-$n"; n++ }
            }
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

package com.yuanbao.miniapp.pack

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Mini program package loader.
 *
 * Two sources are supported:
 *  1. an asset directory (assets/miniprograms/<appId>/...)
 *  2. a .mapkg file, which is a plain ZIP archive (read with java.util.zip)
 */
class MiniPackage(
    val appId: String,
    private val files: Map<String, String>
) {
    fun read(path: String): String? = files[normalize(path)]

    fun has(path: String): Boolean = files.containsKey(normalize(path))

    /** 包内文件数量（诊断用：0 = 包没落地/没读到）。 */
    fun fileCount(): Int = files.size

    /** 包内全部相对路径（诊断用）。 */
    fun filePaths(): List<String> = files.keys.sorted()

    private fun normalize(path: String): String = path.trim().trimStart('/')

    val appJson: String get() = read("app.json") ?: "{}"
    val appJs: String? get() = read("app.js")
    val appWxss: String? get() = read("app.wxss")

    fun pageWxml(page: String): String? = read("$page.wxml")
    fun pageWxss(page: String): String? = read("$page.wxss")
    fun pageJs(page: String): String? = read("$page.js")
    fun pageJson(page: String): String? = read("$page.json")

    companion object {
        /** Loads from assets: assets/miniprograms/<appId>/... */
        fun fromAssets(context: Context, appId: String): MiniPackage {
            val assets = context.assets
            val base = "miniprograms/$appId"
            val out = HashMap<String, String>()
            collect(assets, base, "", out)
            return MiniPackage(appId, out)
        }

        private fun collect(
            assets: android.content.res.AssetManager,
            base: String,
            rel: String,
            out: HashMap<String, String>
        ) {
            val dir = if (rel.isEmpty()) base else "$base/$rel"
            val list = assets.list(dir)
            if (list.isNullOrEmpty()) {
                runCatching {
                    assets.open(dir).use { out[rel] = readText(it) }
                }
                return
            }
            for (name in list) {
                collect(assets, base, if (rel.isEmpty()) name else "$rel/$name", out)
            }
        }

        /** Loads from a real directory (e.g. context.filesDir/miniapps/<appId>/) — GenUI AI 生成的小程序落地目录。 */
        fun fromDirectory(root: java.io.File, appId: String): MiniPackage {
            val out = HashMap<String, String>()
            fun walk(dir: java.io.File, rel: String) {
                val list = dir.listFiles() ?: return
                for (f in list) {
                    val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                    if (f.isDirectory) walk(f, r)
                    else runCatching { out[r] = f.readText() }
                }
            }
            walk(root, "")
            return MiniPackage(appId, out)
        }

        /** Loads a .mapkg (ZIP) package. */
        fun fromZip(stream: InputStream, appId: String): MiniPackage {
            val out = HashMap<String, String>()
            ZipInputStream(stream.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    out[entry.name.trimStart('/')] = readText(zis)
                }
            }
            return MiniPackage(appId, out)
        }

        private fun readText(input: InputStream): String {
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
            return String(bos.toByteArray(), Charsets.UTF_8)
        }
    }
}

/** app.json 描述 */
class AppConfig(private val json: Map<String, Any?>) {
    val pages: List<String> get() = (json["pages"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
    val window: Map<String, Any?> get() = (json["window"] as? Map<*, *>)
        ?.mapKeys { it.key.toString() } ?: emptyMap()
    val entry: String get() = pages.firstOrNull() ?: ""

    companion object {
        fun empty(): AppConfig = AppConfig(emptyMap())

        fun parse(text: String): AppConfig {
            val m = runCatching { parseAppJson(text) }.getOrDefault(emptyMap())
            val cfg = AppConfig(m)
            // 严格解析拿不到 pages 时（app.json 用了单引号 / 注释 / 未加引号的键等非严格写法），
            // 再做一次宽松扫描，直接从 "pages" 数组里抠出路径字符串。
            // 缺了这一步：pages=[] → entry="" → MiniAppView.start() 一个页面都不 push
            // → pageStack 空 → 卡片上除了一行"页面未建立"什么都没有（"AI 没写进对话框"的真身）。
            if (cfg.pages.isEmpty()) {
                val loose = loosePages(text)
                if (loose.isNotEmpty()) return AppConfig(m + ("pages" to loose))
            }
            return cfg
        }

        /**
         * 宽松提取 pages（容错解析器的第二道）：兼容单引号、注释、多余逗号。
         * 只在严格解析失败时兜底，不改变正常 JSON 的行为。
         */
        private fun loosePages(text: String): List<String> {
            val at = Regex("[\"']?pages[\"']?\\s*:").find(text) ?: return emptyList()
            val open = text.indexOf('[', at.range.last)
            if (open < 0) return emptyList()
            var depth = 0
            var close = -1
            for (k in open until text.length) {
                when (text[k]) {
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { close = k; break } }
                }
            }
            val body = if (close > open) text.substring(open + 1, close) else text.substring(open + 1)
            val out = ArrayList<String>()
            for (mm in Regex("[\"']([^\"']+)[\"']").findAll(body)) {
                val v = mm.groupValues[1].trim().trimStart('/').removeSuffix(".wxml")
                if (v.isNotEmpty()) out.add(v)
            }
            return out
        }

        /**
         * Tiny JSON reader for app.json (objects, arrays, strings, numbers, bools).
         *
         * ⚠ 必须"永不空转"：这个函数跑在 **UI 线程**上（AndroidView.factory → MiniAppEngine.create →
         * AppConfig.parse）。旧实现在遇到非严格 JSON（注释、单引号、多余符号…）时会原地打转——
         * `value()` 读不出东西又不推进 i，外层循环条件恒真 → **UI 线程死循环，整页白屏/卡死**。
         * 现在两个循环都加了"每轮必须前进"的兜底：读不出内容就跳过一个字符，最坏情况是解析结果为空，
         * 绝不会卡住。
         */
        private fun parseAppJson(text: String): Map<String, Any?> {
            var i = 0
            fun skipWs() { while (i < text.length && text[i].isWhitespace()) i++ }
            fun string(): String {
                if (i >= text.length || text[i] != '"') return ""
                i++
                val sb = StringBuilder()
                while (i < text.length && text[i] != '"') {
                    if (text[i] == '\\') { i++; sb.append(text[i]); i++; continue }
                    sb.append(text[i]); i++
                }
                i++
                return sb.toString()
            }
            fun value(): Any? {
                skipWs()
                if (i >= text.length) return null
                return when (text[i]) {
                    '{' -> {
                        i++
                        val map = LinkedHashMap<String, Any?>()
                        while (i < text.length) {
                            skipWs()
                            if (i >= text.length) break
                            if (text[i] == '}') { i++; break }
                            val mark = i
                            val k = string()
                            skipWs()
                            if (i < text.length && text[i] == ':') i++
                            map[k] = value()
                            skipWs()
                            if (i < text.length && text[i] == ',') i++
                            // 本轮没推进（非法 token / 非严格 JSON）→ 跳过一个字符继续，绝不空转
                            if (i <= mark) i = mark + 1
                        }
                        map
                    }
                    '[' -> {
                        i++
                        val list = ArrayList<Any?>()
                        while (i < text.length) {
                            skipWs()
                            if (i >= text.length) break
                            if (text[i] == ']') { i++; break }
                            val mark = i
                            list.add(value())
                            skipWs()
                            if (i < text.length && text[i] == ',') i++
                            if (i <= mark) i = mark + 1
                        }
                        list
                    }
                    '"' -> string()
                    't' -> { i += 4; true }
                    'f' -> { i += 5; false }
                    'n' -> { i += 4; null }
                    else -> {
                        val start = i
                        while (i < text.length && (text[i].isDigit() || text[i] == '.' || text[i] == '-')) i++
                        if (i == start) i++   // 认不出的字符：吞掉一个，保证前进（旧实现这里会原地打转）
                        text.substring(start, i.coerceAtMost(text.length)).trimEnd(',').toDoubleOrNull()
                    }
                }
            }
            @Suppress("UNCHECKED_CAST")
            return (runCatching { value() as? Map<String, Any?> }.getOrNull()) ?: emptyMap()
        }
    }
}

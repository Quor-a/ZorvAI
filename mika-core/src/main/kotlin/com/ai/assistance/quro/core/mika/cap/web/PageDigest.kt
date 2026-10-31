package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.uistate.ScreenCheck
import com.ai.assistance.quro.core.mika.uistate.ScreenDiagnosis
import com.ai.assistance.quro.core.mika.uistate.ScreenFacts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalTime

/**
 * 一次 web_read_page 要什么:[url] 为 null 读当前标签,给了在临时后台标签里读;[unfold] 为真时收起的列表块也全文给出
 * (分段的 offset 按同一种 unfold 算)。
 */
data class PageRead(
    val maxChars: Int,
    val offset: Int,
    val unfold: Boolean = false,
    val url: String? = null,
    /** 读之前滚一遍触发懒加载、等正文稳定;核查取证时为 false,只读不动页面。 */
    val lazy: Boolean = true,
)

/** 这页读出来怎么样:正文够不够、要不要先登录、被不被拒、内容是不是主要在图里。[wire] 是给模型的写法。 */
enum class ReadVerdict(val wire: String) {
    OK("ok"), THIN("thin"), NEEDS_SIGN_IN("needs_sign_in"), REFUSED("refused"), IMAGE_TEXT("image_text"),
}

/**
 * 读页的质量信号:[chars] 是整页正文字数(按这次的收起方式),[textDensity] 是正文里不是链接文字的比例,
 * [cause] 是判定依据的简短说明;[published]/[updated] 是页面自己声明的时间;[dialogs] 是开着的对话框个数(放在正文最前),
 * [folded] 是收起成一行的列表块个数。
 */
data class ReadQuality(
    val chars: Int,
    val textDensity: Double,
    val verdict: ReadVerdict,
    val cause: String?,
    val published: String? = null,
    val updated: String? = null,
    val dialogs: Int = 0,
    val folded: Int = 0,
)

/**
 * 页面脚本的整页读取结果(page.js 的 digest)交到这里:决定哪些列表块收起、拼出正文、判定质量、挑出声明的时间。
 * 页面脚本只按结构采集(地标角色、链接占比、重复的子结构、图片的位置),判断都在这里,不按网站区分。
 * 页面脚本挑候选块用的门槛也从这里给([scriptOptions]),两边只有一份。
 */
object PageDigest {
    /** 正文短于它算 thin。 */
    const val THIN_CHARS = 200
    /** 候选块:至少这么多个链接,链接文字至少占这么多(页面脚本按它挑候选,这里再细判)。 */
    const val CANDIDATE_MIN_LINKS = 5
    const val CANDIDATE_LINK_SHARE = 0.5
    /** 链接文字占一块文字的比例到这里,就是一串链接列表(推荐、目录)。 */
    const val LINK_LIST_SHARE = 0.7
    /** 子元素重复同一结构(卡片列表)时,链接占比到这里就够。 */
    const val REPEATED_LINK_SHARE = 0.5
    const val MIN_REPEAT = 5
    /** 地标块(导航、页脚、侧栏)链接至少占这么多才收起;以文字为主的侧栏、页脚照读。 */
    const val LANDMARK_LINK_SHARE = 0.3
    /** 一个列表块占地标以外文字的比例到这里,它就是正文本身(目录页、搜索结果页),不收起。 */
    const val DOMINANT_SHARE = 0.4
    /** 收起之后至少要留下这么多比例的文字;不够就从最大的块起放回。 */
    const val MIN_LEFT_SHARE = 0.1
    /** 图片、画布盖住页面开头的比例到这里,而字又少,算 image_text。 */
    const val PICTURE_SHARE = 0.5
    const val PICTURE_MAX_CHARS = THIN_CHARS * 4
    /** 小于这个边长(CSS 像素)的图不算:图标、头像、分隔线。 */
    const val MIN_PICTURE_PX = 48
    /** 登录框(密码框所在的那一块)的文字占整页文字的比例到这里,这页就是登录页。 */
    const val SIGN_IN_BOX_SHARE = 0.5
    private const val COVER_GRID = 64
    private const val PREVIEW_ITEMS = 6
    private const val PREVIEW_CHARS = 120
    private val LANDMARKS = setOf("navigation", "footer", "complementary")

    /** 登录框、标题、对话框里出现这些字,且有密码框,就是要先登录。比对时去掉空白、不分大小写。 */
    private val SIGN_IN_WORDS = listOf("登录", "登入", "登陆", "signin", "login", "logon", "账号", "帐号", "密码", "password")

    /** 页面脚本 digest 的参数(候选块门槛)。 */
    val scriptOptions: String get() = """{"minLinks":$CANDIDATE_MIN_LINKS,"linkShare":$CANDIDATE_LINK_SHARE}"""

    /** 一个链接:[group] 是它所在的列表块(不在块里为 -1)。 */
    data class Link(val text: String, val url: String, val group: Int)

    /** 正文的一段:一行文字,或一个可收起的列表块。 */
    sealed interface Part {
        data class Line(val text: String) : Part
        /**
         * [kind] 为 navigation/footer/complementary(按地标)或 links(按链接占比认出的列表);[lines] 是块里的文字,
         * [chars]/[linkChars] 是块里全部文字和链接文字的字数,[repeat] 是同一结构的子元素个数,[links] 是块里的链接个数。
         */
        data class Group(
            val kind: String, val label: String, val lines: List<String>,
            val chars: Int, val linkChars: Int, val repeat: Int, val links: Int,
        ) : Part {
            val landmark: Boolean get() = kind in LANDMARKS
            val linkShare: Double get() = if (chars > 0) linkChars.toDouble() / chars else 0.0
        }
    }

    /** 一张图在页面上的位置(文档坐标,CSS 像素)。 */
    data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    /** 判定用的页面迹象(都来自页面脚本)。 */
    data class Signals(
        val passwordFields: Int = 0,
        /** 登录框(密码框往上、不含正文的最大那一块,遇到表单或对话框就停)自己的文字占整页文字的比例。 */
        val signInBoxShare: Double = 0.0,
        /** 找登录字样的地方:登录框的文字、页面标题、对话框开头。 */
        val signInText: List<String> = emptyList(),
        /** 醒目处的文字(标题、h1–h3、对话框开头),给拦截判定用。 */
        val prominent: List<String> = emptyList(),
        /** 页面开头的区域(宽, 高)和里面的图片、画布、视频。 */
        val regionW: Int = 0,
        val regionH: Int = 0,
        val pictures: List<Box> = emptyList(),
        val frames: List<String> = emptyList(),
        val textChars: Int = 0,
        val linkChars: Int = 0,
        /** 页面声明的时间:(来源, 原值),来源如 ld:datePublished、meta:article:published_time、itemprop:dateModified、time。 */
        val dates: List<Pair<String, String>> = emptyList(),
    )

    data class Digest(
        val url: String,
        val title: String,
        val dialogs: List<List<String>>,
        val parts: List<Part>,
        val links: List<Link>,
        val images: List<Pair<String, String>>,
        val signals: Signals,
    )

    /** 拼好的结果;[folded] 是这次收起了几块。 */
    data class Rendered(val text: String, val links: List<Pair<String, String>>, val folded: Int)

    // ---------- 解析 ----------

    fun parse(o: JsonObject): Digest {
        fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        fun num(e: JsonElement?) = (e as? JsonPrimitive)?.doubleOrNull ?: 0.0
        fun int(e: JsonElement?) = (e as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        fun strings(e: JsonElement?) = (e as? JsonArray).orEmpty().map(::str).filter { it.isNotEmpty() }
        val parts = (o["parts"] as? JsonArray).orEmpty().mapNotNull { p ->
            when (p) {
                is JsonPrimitive -> str(p).takeIf { it.isNotEmpty() }?.let(Part::Line)
                is JsonObject -> Part.Group(str(p["g"]), str(p["label"]), strings(p["lines"]), int(p["chars"]), int(p["linkChars"]), int(p["repeat"]), int(p["links"]))
                else -> null
            }
        }
        val links = (o["links"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val url = str(m["url"]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Link(str(m["text"]), url, (m["p"] as? JsonPrimitive)?.intOrNull ?: -1)
        }
        val images = (o["images"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val url = str(m["url"]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            str(m["alt"]) to url
        }
        val s = o["signals"] as? JsonObject
        val dates = (s?.get("dates") as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val v = str(m["v"]).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            str(m["k"]) to v
        }
        val region = (s?.get("region") as? JsonArray).orEmpty().map(::int)
        val pictures = (s?.get("pictures") as? JsonArray).orEmpty().mapNotNull { e ->
            val a = (e as? JsonArray)?.map(::int)?.takeIf { it.size == 4 } ?: return@mapNotNull null
            Box(a[0], a[1], a[2], a[3])
        }
        return Digest(
            url = str(o["url"]), title = str(o["title"]),
            dialogs = (o["dialogs"] as? JsonArray).orEmpty().map(::strings).filter { it.isNotEmpty() },
            parts = parts, links = links, images = images,
            signals = Signals(
                passwordFields = int(s?.get("password")),
                signInBoxShare = num(s?.get("signInShare")),
                signInText = strings(s?.get("signInText")),
                prominent = strings(s?.get("prominent")),
                regionW = region.getOrElse(0) { 0 }, regionH = region.getOrElse(1) { 0 },
                pictures = pictures,
                frames = strings(s?.get("frames")),
                textChars = int(s?.get("textChars")),
                linkChars = int(s?.get("linkChars")),
                dates = dates,
            ),
        )
    }

    // ---------- 收起 ----------

    /**
     * 哪些块收起(按块在 [Digest.parts] 里的序号,只数块)。地标块链接够多就收;链接列表要链接占得多,
     * 且不能占了地标以外文字的大头(那是正文本身);最大的块是链接列表、又比块外的文字还多时它就是正文,不收;
     * 收完剩下的文字不到 [MIN_LEFT_SHARE] 时从最大的块起放回。
     */
    fun foldSet(parts: List<Part>): Set<Int> {
        val groups = parts.filterIsInstance<Part.Group>()
        if (groups.isEmpty()) return emptySet()
        fun Part.size() = when (this) { is Part.Line -> text.length + 1; is Part.Group -> lines.sumOf { it.length + 1 } }
        val total = parts.sumOf { it.size() }
        val free = parts.filterIsInstance<Part.Line>().sumOf { it.size() }
        val outsideLandmarks = free + groups.filter { !it.landmark }.sumOf { it.size() }
        val largest = groups.indices.maxBy { groups[it].size() }
        val fold = groups.indices.filter { i ->
            val g = groups[i]
            val wanted = if (g.landmark) g.linkShare >= LANDMARK_LINK_SHARE
            else (g.linkShare >= LINK_LIST_SHARE || (g.repeat >= MIN_REPEAT && g.linkShare >= REPEATED_LINK_SHARE)) &&
                g.size() < outsideLandmarks * DOMINANT_SHARE
            // 最大的块若是链接列表、又比块外的文字还多,它就是正文(地标按页面自己的声明照收)
            wanted && !(i == largest && !g.landmark && g.size() > free)
        }.toMutableSet()
        while (fold.isNotEmpty() && total - fold.sumOf { groups[it].size() } < total * MIN_LEFT_SHARE) {
            fold.remove(fold.maxBy { groups[it].size() })
        }
        return fold
    }

    /** 拼正文:对话框在最前,收起的块各占一行。链接表只要还看得见的,同一地址只留一次。 */
    fun render(d: Digest, unfold: Boolean): Rendered {
        val folded = if (unfold) emptySet() else foldSet(d.parts)
        val lines = ArrayList<String>()
        for (dl in d.dialogs) {
            lines += "<<dialog>>"
            lines += dl
            lines += "<<end of dialog>>"
        }
        var groupIndex = -1
        for (p in d.parts) when (p) {
            is Part.Line -> lines += p.text
            is Part.Group -> {
                groupIndex++
                if (groupIndex in folded) lines += collapsed(p) else lines += p.lines
            }
        }
        val links = d.links.filter { it.group < 0 || it.group !in folded }.distinctBy { it.url }.map { it.text to it.url }
        return Rendered(lines.joinToString("\n"), links, folded.size)
    }

    private fun collapsed(g: Part.Group): String {
        val name = g.kind + (g.label.takeIf { it.isNotBlank() }?.let { " \"${it.take(60)}\"" } ?: "")
        val sample = g.lines.take(PREVIEW_ITEMS).joinToString(" | ").let { if (it.length > PREVIEW_CHARS) it.take(PREVIEW_CHARS) + "…" else it }
        val count = if (g.links > 0) "${g.links} links" else "${g.lines.size} lines"
        return "<<collapsed $name · $count · $sample>>"
    }

    // ---------- 判定 ----------

    /**
     * 判定这页读得怎么样。[check] 是网页障碍判定(用醒目处的文字、跨源框架、HTTP 状态做的,见 [barrierFacts]),
     * [httpStatus] 是主文档的 HTTP 错误状态。先看拒绝,再看登录,再看是不是图,最后看字数。
     */
    fun verdict(chars: Int, s: Signals, check: ScreenCheck?, httpStatus: Int?): Pair<ReadVerdict, String?> {
        val reason = check?.takeoverReason
        if (reason == "captcha" || reason == "access_blocked") return ReadVerdict.REFUSED to (check.cause ?: reason)
        if (reason == ScreenDiagnosis.UNSUPPORTED_BROWSER) return ReadVerdict.REFUSED to "the site refuses this browser"
        if (httpStatus != null && httpStatus >= 400 && chars < PICTURE_MAX_CHARS) return ReadVerdict.REFUSED to "HTTP $httpStatus"
        if (s.passwordFields > 0) {
            val cue = signInCue(s.signInText)
            if (cue != null && (s.signInBoxShare >= SIGN_IN_BOX_SHARE || chars < PICTURE_MAX_CHARS)) return ReadVerdict.NEEDS_SIGN_IN to "password field and \"$cue\""
        } else if (reason == "login" && chars < PICTURE_MAX_CHARS) {
            return ReadVerdict.NEEDS_SIGN_IN to (check.cause ?: "login")
        }
        val cover = pictureShare(s)
        if (cover >= PICTURE_SHARE && chars < PICTURE_MAX_CHARS) return ReadVerdict.IMAGE_TEXT to "pictures cover ${(cover * 100).toInt()}% of the page top"
        if (chars < THIN_CHARS) return ReadVerdict.THIN to "$chars characters of text"
        return ReadVerdict.OK to null
    }

    private fun signInCue(texts: List<String>): String? {
        val text = texts.joinToString("\n").lowercase().filterNot(Char::isWhitespace)
        return SIGN_IN_WORDS.firstOrNull { it in text }
    }

    /**
     * 页面开头区域里被图盖住的比例:在区域上铺一张格子,数格子中心落在哪张图里;重叠的图只算一次,
     * 边长小于 [MIN_PICTURE_PX] 的不算。
     */
    fun pictureShare(s: Signals): Double {
        val w = s.regionW
        val h = s.regionH
        if (w <= 0 || h <= 0) return 0.0
        val boxes = s.pictures.filter { it.w >= MIN_PICTURE_PX && it.h >= MIN_PICTURE_PX }
        if (boxes.isEmpty()) return 0.0
        val cols = COVER_GRID
        val rows = maxOf(1, (COVER_GRID.toLong() * h / w).toInt())
        var covered = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = (c + 0.5) * w / cols
            val y = (r + 0.5) * h / rows
            if (boxes.any { x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h }) covered++
        }
        return covered.toDouble() / (rows * cols)
    }

    /** 给 [WebBarrier.classify] 的事实:只拿醒目处的文字(文章里提到「验证码」不算),密码框另判。 */
    fun barrierFacts(d: Digest, httpStatus: Int?, host: String?): WebFacts = WebFacts(
        base = ScreenFacts(
            currentApp = host, hasWindow = true, readableElements = 1, changing = false, blackFrames = emptyList(),
            texts = d.signals.prominent + d.dialogs.flatten(), passwordField = false,
        ),
        httpStatus = httpStatus, frameUrls = d.signals.frames, host = host,
    )

    /** 正文里不是链接文字的比例,两位小数;没有字为 0。 */
    fun density(s: Signals): Double {
        if (s.textChars <= 0) return 0.0
        val share = 1.0 - s.linkChars.coerceAtMost(s.textChars).toDouble() / s.textChars
        return Math.round(share * 100) / 100.0
    }

    // ---------- 时间 ----------

    /**
     * 页面声明的发布时间与更新时间:先看结构化的声明(JSON-LD,再 meta,再 itemprop),最后才看正文里的 <time>;
     * 同一类里按出现先后。读不懂的值跳过。
     */
    fun dates(s: Signals): Pair<String?, String?> {
        fun rank(src: String) = when {
            src.startsWith("ld:") -> 0
            src.startsWith("meta:") -> 1
            src.startsWith("itemprop:") -> 2
            else -> 3
        }
        fun pick(kind: (String) -> Boolean) = s.dates.withIndex()
            .filter { kind(it.value.first.lowercase()) }
            .sortedWith(compareBy({ rank(it.value.first) }, { it.index }))
            .firstNotNullOfOrNull { DeclaredTime.read(it.value.second) }
        val published = pick { k -> PUBLISHED_HINTS.any { it in k } || k == "time" || k == "time:pubdate" || k in PLAIN_DATE_KEYS }
        val updated = pick { k -> UPDATED_HINTS.any { it in k } }
        return published to updated
    }

    private val PUBLISHED_HINTS = listOf("published", "datecreated", "issued", "created", "pubdate", "publishdate", "publish_date")
    private val UPDATED_HINTS = listOf("modified", "updated", "lastmod", "revised")
    private val PLAIN_DATE_KEYS = setOf("meta:date", "meta:dc.date")

    /** 整页读取的质量。 */
    fun quality(d: Digest, r: Rendered, check: ScreenCheck?, httpStatus: Int?): ReadQuality {
        val (v, cause) = verdict(r.text.length, d.signals, check, httpStatus)
        val (pub, upd) = dates(d.signals)
        return ReadQuality(r.text.length, density(d.signals), v, cause, pub, upd, d.dialogs.size, r.folded)
    }
}

/**
 * 页面上写的时间读成 ISO-8601。按顺序取出数字段:四位的年、月、日,再可能有时、分、秒(小数秒丢掉),
 * 时间后面跟 Z 或 ±时[:分] 就是时区。年月日之间是什么符号都行(-、/、.、年月日)。认不出年月日、数值不成立为 null。
 */
object DeclaredTime {
    private class Scan(val s: String) {
        var i = 0
        fun skipNonDigits() { while (i < s.length && !s[i].isDigit()) i++ }
        fun digits(): String {
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(start, i)
        }
        fun peek(): Char? = s.getOrNull(i)
    }

    fun read(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 60) return null
        val sc = Scan(text)
        sc.skipNonDigits()
        val year = sc.digits().takeIf { it.length == 4 }?.toInt() ?: return null
        val nums = ArrayList<Int>()
        // 月、日:中间隔一个分隔符
        repeat(2) {
            val sep = sc.peek() ?: return null
            if (sep.isDigit() || sep.isWhitespace() && nums.isEmpty()) return null
            sc.i++
            val n = sc.digits().takeIf { it.length in 1..2 } ?: return null
            nums += n.toInt()
        }
        val date = runCatching { LocalDate.of(year, nums[0], nums[1]) }.getOrNull() ?: return null
        if (sc.peek() == '日') sc.i++
        // 时间:T 或空格后的 时:分[:秒]
        val tStart = sc.i
        if (sc.peek() == 'T' || sc.peek() == 't' || sc.peek() == ' ') sc.i++ else return date.toString()
        val hour = sc.digits().takeIf { it.length in 1..2 }?.toInt()
        if (hour == null || sc.peek() != ':') { sc.i = tStart; return date.toString() }
        sc.i++
        val minute = sc.digits().takeIf { it.length == 2 }?.toInt() ?: return date.toString()
        var second = 0
        if (sc.peek() == ':') {
            sc.i++
            second = sc.digits().takeIf { it.length == 2 }?.toInt() ?: return null
        }
        if (sc.peek() == '.' || sc.peek() == ',') { sc.i++; sc.digits() }
        val time = runCatching { LocalTime.of(hour, minute, second) }.getOrNull() ?: return null
        val local = "$date" + "T" + "%02d:%02d:%02d".format(time.hour, time.minute, time.second)
        return local + (zone(sc) ?: "")
    }

    /** Z、+08:00、+0800、-05 这样的时区;没有为 null。 */
    private fun zone(sc: Scan): String? {
        while (sc.peek() == ' ') sc.i++
        val c = sc.peek() ?: return null
        if (c == 'Z' || c == 'z') return "Z"
        if (c != '+' && c != '-') return null
        sc.i++
        val a = sc.digits()
        val (hh, mm) = when {
            a.length == 4 -> a.substring(0, 2) to a.substring(2)
            a.length in 1..2 && sc.peek() == ':' -> { sc.i++; a to sc.digits() }
            a.length in 1..2 -> a to "00"
            else -> return null
        }
        val h = hh.toIntOrNull() ?: return null
        val m = mm.toIntOrNull() ?: return null
        if (h > 14 || m > 59) return null
        if (h == 0 && m == 0) return "Z"
        return "%c%02d:%02d".format(c, h, m)
    }
}

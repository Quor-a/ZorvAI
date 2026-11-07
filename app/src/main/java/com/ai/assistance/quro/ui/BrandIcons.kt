package com.ai.assistance.quro.ui

import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import java.util.Locale

/**
 * 🔴 官方品牌图标（2026-11-07 全量重做）。
 *
 * ## 为什么改成「按 id 精确绑定」
 *
 * 旧实现只用 `TABLE` 的 `contains` 猜品牌，出现两类硬错误：
 * - **错配**：Context7 命中 kagi、DeepWiki 命中 github、智谱命中 qwen —— 用户截图里
 *   DeepWiki 顶着 GitHub Octocat、Context7 顶着 Kagi，属于「拿别的品牌 logo 顶替」；
 * - **缺图**：monday / Semgrep / Microsoft Learn / AWS / GitMCP / Amplitude / Canva /
 *   Slack / Exa / 高德 / 滴滴 / 麦当劳 / 瑞幸 / 飞书等 `slug=null`，只渲染一个灰底首字。
 *
 * 现在连接器与机器人接入页都传 [brandId]（目录里的稳定 id / 平台 id），
 * 走 [EXACT] **精确**映射；只有应用中心等没有稳定 id 的调用方才回退到名称模糊匹配。
 * 每个 id 都保证有对应 SVG 资产，零首字占位。
 *
 * ## 资产来源与渲染优先级（2026-11-07 逐项核对官方图标后重做）
 *
 * 1. **官方彩色 PNG（最高优先）**：`brand_icons/<slug>.png`，来自 BotHub 官方
 *    `mcp-catalog.json` 内嵌的 128×128 品牌图标。这些是各品牌**真实彩色 logo**
 *    （含官方四色 Microsoft、aws 字标、Cloudflare 云、monday 三色条、高德纸飞机、
 *    滴滴橙 D、瑞幸鹿头、DeepWiki 星标、Context7 字标），不是单色近似。
 * 2. **官方单色 SVG**：`brand_icons/<slug>.svg`。36 个与 Simple Icons 15.16.0 的
 *    path 逐字节一致；另有 LobeHub Icons 官方单色矢量（Exa / DeepSeek / Qwen /
 *    智谱 / Tavily / AWS / Microsoft）与 gilbarbara/logos 官方 mark
 *    （Amplitude / monday / Neon）。
 * 3. 兜底示意图（**非官方 logo，如实标注**）：Simple Icons 15.16.0（3364 个）、
 *    旧版 Simple Icons、FontAwesome Brands、SuperTinyIcons、LobeHub Icons（369 个）、
 *    gilbarbara/logos、Iconify logos（2070 个）均**未收录**以下品牌：
 *    `apify` / `gitmcp` / `semgrep` / `feishu`。这四个是品牌色示意图形，
 *    不是官方 mark；`context7` / `deepwiki` / `amap` / `didi` / `luckin`
 *    的 SVG 同样是示意图，但有同名官方 PNG，运行时不会用到。
 *
 * 之前那批「凭印象画的几何图形」（AWS 画成三角形 A、瑞幸画成咖啡杯、
 * Amplitude 画成山峰、Exa 画成 E）已被官方资产替换，不再冒充官方 logo。
 *
 * 运行时 SVG 由 AndroidSVG 解析，nativeCanvas.drawPicture(Picture, RectF)
 * 按目标尺寸绘制（旧实现 PictureDrawable 未设 bounds → 零尺寸 → 空白，已修）。
 */
object BrandIcons {

    /** 官方品牌底色（各品牌官网当前主色）。slug=null = 无矢量（不应再出现）。 */
    data class Brand(val slug: String?, val bg: Color)

    /**
     * 稳定 id → 官方品牌。与 QuroConnectorCatalog.connectors 的 id、机器人平台 id 一一对应。
     * 这里的顺序无关紧要：查表是精确 key 匹配，不会像 contains 那样被别的品牌截胡。
     */
    private val EXACT: Map<String, Brand> = mapOf(
        // 文档与知识
        "deepwiki" to Brand("deepwiki", Color(0xFF0EA5A4)),
        "context7" to Brand("context7", Color(0xFF16A34A)),
        "huggingface" to Brand("huggingface", Color(0xFFFFD21E)),
        "microsoft-learn" to Brand("microsoft", Color(0xFF0067B8)),
        "aws-knowledge" to Brand("amazonaws", Color(0xFF232F3E)),
        "gitmcp" to Brand("gitmcp", Color(0xFF0F9D58)),
        // 效率
        "notion" to Brand("notion", Color(0xFF000000)),
        "linear" to Brand("linear", Color(0xFF5E6AD2)),
        "atlassian" to Brand("atlassian", Color(0xFF0052CC)),
        "monday" to Brand("monday", Color(0xFFFF3D57)),
        "asana" to Brand("asana", Color(0xFFF06A6A)),
        // 代码
        "github" to Brand("github", Color(0xFF181717)),
        "gitlab" to Brand("gitlab", Color(0xFFFC6D26)),
        "sentry" to Brand("sentry", Color(0xFF362D59)),
        "vercel" to Brand("vercel", Color(0xFF000000)),
        "cloudflare" to Brand("cloudflareworkers", Color(0xFFF38020)),
        "semgrep" to Brand("semgrep", Color(0xFFE3132C)),
        // 数据
        "supabase" to Brand("supabase", Color(0xFF3ECF8E)),
        "neon" to Brand("neon", Color(0xFF00E599)),
        "mongodb" to Brand("mongodb", Color(0xFF47A248)),
        "airtable" to Brand("airtable", Color(0xFF18BFFF)),
        "posthog" to Brand("posthog", Color(0xFFF54E00)),
        "amplitude" to Brand("amplitude", Color(0xFF1E61F0)),
        // 设计
        "canva" to Brand("canva", Color(0xFF00C4CC)),
        "figma" to Brand("figma", Color(0xFFF24E1E)),
        "webflow" to Brand("webflow", Color(0xFF146EF5)),
        "wix" to Brand("wix", Color(0xFF116BF2)),
        // 通信
        "slack" to Brand("slack", Color(0xFF4A154B)),
        "intercom" to Brand("intercom", Color(0xFF286EFA)),
        // 自动化
        "zapier" to Brand("zapier", Color(0xFFFF4F00)),
        "apify" to Brand("apify", Color(0xFF37C1D4)),
        // 支付
        "stripe" to Brand("stripe", Color(0xFF635BFF)),
        "paypal" to Brand("paypal", Color(0xFF003087)),
        "square" to Brand("square", Color(0xFF000000)),
        // 生活与出行
        "exa" to Brand("exa", Color(0xFF1F2937)),
        "amap" to Brand("amap", Color(0xFF00A9E0)),
        "didi" to Brand("didi", Color(0xFFFF7E33)),
        "mcdonalds" to Brand("mcdonalds", Color(0xFFDA291C)),
        "luckin" to Brand("luckin", Color(0xFF1C4DB2)),
        // 机器人接入平台
        "qq" to Brand("qq", Color(0xFF12B7F5)),
        "feishu" to Brand("feishu", Color(0xFF3370FF)),
        "wechat" to Brand("wechat", Color(0xFF07C160)),
    )

    /** 名称/URL 模糊匹配表：仅服务没有稳定 id 的调用方（应用中心等）。 */
    private val TABLE: List<Pair<Array<String>, Brand>> = listOf(
        arrayOf("notion") to Brand("notion", Color(0xFF000000)),
        arrayOf("github") to Brand("github", Color(0xFF181717)),
        arrayOf("linear") to Brand("linear", Color(0xFF5E6AD2)),
        arrayOf("sentry") to Brand("sentry", Color(0xFF362D59)),
        arrayOf("vercel") to Brand("vercel", Color(0xFF000000)),
        arrayOf("google") to Brand("google", Color(0xFF4285F4)),
        arrayOf("duckduckgo", "duck") to Brand("duckduckgo", Color(0xFFDE5833)),
        arrayOf("supabase") to Brand("supabase", Color(0xFF3ECF8E)),
        arrayOf("zapier") to Brand("zapier", Color(0xFFFF4F00)),
        arrayOf("stripe") to Brand("stripe", Color(0xFF635BFF)),
        arrayOf("figma") to Brand("figma", Color(0xFFF24E1E)),
        arrayOf("openai") to Brand("openai", Color(0xFF10A37F)),
        arrayOf("anthropic", "claude") to Brand("anthropic", Color(0xFF191919)),
        arrayOf("naver") to Brand("naver", Color(0xFF03C75A)),
        arrayOf("deepwiki") to Brand("deepwiki", Color(0xFF0EA5A4)),
        arrayOf("context7") to Brand("context7", Color(0xFF16A34A)),
        arrayOf("huggingface", "hugging face") to Brand("huggingface", Color(0xFFFFD21E)),
        arrayOf("gitlab") to Brand("gitlab", Color(0xFFFC6D26)),
        arrayOf("atlassian") to Brand("atlassian", Color(0xFF0052CC)),
        arrayOf("asana") to Brand("asana", Color(0xFFF06A6A)),
        arrayOf("cloudflare", "workers") to Brand("cloudflareworkers", Color(0xFFF38020)),
        arrayOf("neon") to Brand("neon", Color(0xFF00E599)),
        arrayOf("mongodb", "mongo") to Brand("mongodb", Color(0xFF47A248)),
        arrayOf("airtable") to Brand("airtable", Color(0xFF18BFFF)),
        arrayOf("posthog") to Brand("posthog", Color(0xFFF54E00)),
        arrayOf("webflow") to Brand("webflow", Color(0xFF146EF5)),
        arrayOf("wix") to Brand("wix", Color(0xFF116BF2)),
        arrayOf("intercom") to Brand("intercom", Color(0xFF286EFA)),
        arrayOf("paypal") to Brand("paypal", Color(0xFF003087)),
        arrayOf("square") to Brand("square", Color(0xFF000000)),
        arrayOf("qq") to Brand("qq", Color(0xFF12B7F5)),
        arrayOf("wechat", "微信") to Brand("wechat", Color(0xFF07C160)),
        arrayOf("feishu", "飞书", "lark") to Brand("feishu", Color(0xFF3370FF)),
        arrayOf("deepseek") to Brand("deepseek", Color(0xFF4D6BFE)),
        arrayOf("qwen", "通义") to Brand("qwen", Color(0xFF615CED)),
        arrayOf("perplexity") to Brand("perplexity", Color(0xFF20808D)),
        arrayOf("kagi") to Brand("kagi", Color(0xFFFFB513)),
        arrayOf("amap", "高德", "gaode") to Brand("amap", Color(0xFF00A9E0)),
        arrayOf("zhipu", "智谱", "bigmodel") to Brand("zhipu", Color(0xFF3859FF)),
        arrayOf("tavily") to Brand("tavily", Color(0xFF12325C)),
        arrayOf("exa") to Brand("exa", Color(0xFF1F2937)),
        arrayOf("semgrep") to Brand("semgrep", Color(0xFFE3132C)),
        arrayOf("amplitude") to Brand("amplitude", Color(0xFF1E61F0)),
        arrayOf("canva") to Brand("canva", Color(0xFF00C4CC)),
        arrayOf("apify") to Brand("apify", Color(0xFF37C1D4)),
        arrayOf("slack") to Brand("slack", Color(0xFF4A154B)),
        arrayOf("monday") to Brand("monday", Color(0xFFFF3D57)),
        arrayOf("microsoft") to Brand("microsoft", Color(0xFF0067B8)),
        arrayOf("aws", "amazon") to Brand("amazonaws", Color(0xFF232F3E)),
        arrayOf("gitmcp") to Brand("gitmcp", Color(0xFF0F9D58)),
        arrayOf("didi", "滴滴") to Brand("didi", Color(0xFFFF7E33)),
        arrayOf("mcdonald", "麦当劳") to Brand("mcdonalds", Color(0xFFDA291C)),
        arrayOf("luckin", "瑞幸") to Brand("luckin", Color(0xFF1C4DB2)),
    )

    /** 稳定 id 精确查表（连接器 / 机器人接入走这里）。 */
    fun matchId(id: String?): Brand? {
        if (id.isNullOrBlank()) return null
        val key = id.trim().lowercase(Locale.ROOT)
        EXACT[key]?.let { return it }
        for ((keys, brand) in TABLE) {
            if (keys.any { it.lowercase(Locale.ROOT) == key }) return brand
        }
        return null
    }

    /** 名称/URL 模糊匹配（应用中心等无稳定 id 的调用方）。 */
    fun match(vararg texts: String?): Brand? {
        val joined = texts.filterNotNull().joinToString(" ").lowercase(Locale.ROOT)
        if (joined.isBlank()) return null
        for ((keys, brand) in TABLE) {
            if (keys.any { joined.contains(it.lowercase(Locale.ROOT)) }) return brand
        }
        return null
    }

    /** 优先精确 id，其次名称兜底。 */
    fun resolve(brandId: String?, vararg texts: String?): Brand? =
        matchId(brandId) ?: match(*texts)

    fun slugAsset(slug: String): String = "brand_icons/$slug.svg"

    private val cache = HashMap<String, SVG>()
    private val pngCache = HashMap<String, ImageBitmap>()
    private val noPng = HashSet<String>()

    /**
     * 官方彩色品牌图标（BotHub 官方 mcp-catalog 内嵌 PNG），按 slug 同名存放。
     * 与 SVG 同名不同扩展名，所以调用方无需知道走哪条路径 —— 有 PNG 就用 PNG。
     */
    fun loadOfficialPng(context: android.content.Context, slug: String): ImageBitmap? =
        synchronized(pngCache) {
            if (slug in noPng) return@synchronized null
            pngCache[slug]?.let { return@synchronized it }
            val bmp = runCatching {
                context.assets.open("brand_icons/$slug.png").use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
            if (bmp == null) noPng.add(slug) else pngCache[slug] = bmp
            bmp
        }

    /** 解析并缓存官方 SVG（白字形，24×24 viewBox）。资产缺失时返回 null。 */
    fun load(context: android.content.Context, slug: String): SVG? =
        synchronized(cache) {
            cache[slug] ?: runCatching {
                SVG.getFromAsset(context.assets, slugAsset(slug)).apply {
                    documentWidth = 24f
                    documentHeight = 24f
                }
            }.getOrNull()?.also { cache[slug] = it }
        }
}

/**
 * 品牌头像：已知品牌 → 官方底色圆角块 + 官方 logo 矢量；完全未知 → 官方色首字兜底。
 *
 * [brandId] 是连接器/平台的稳定 id，优先于名称模糊匹配，避免错配。
 */
@Composable
fun BrandAvatar(
    name: String,
    urlOrId: String? = null,
    size: Dp = 40.dp,
    corner: Dp = 10.dp,
    fallbackBg: Color = Color(0xFFE7D9CF),
    fallbackFg: Color = Color(0xFF5A4634),
    brandId: String? = null,
) {
    val ctx = LocalContext.current
    val brand = BrandIcons.resolve(brandId, name, urlOrId)
    val slug = brand?.slug
    // 官方彩色 PNG 优先（自带品牌底色与官方配色），没有才回退单色官方 SVG。
    val png = remember(slug) { slug?.let { BrandIcons.loadOfficialPng(ctx, it) } }
    val svg = remember(slug, brand?.bg, png) {
        if (png == null) slug?.let { BrandIcons.load(ctx, it) } else null
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(brand?.bg ?: fallbackBg),
        contentAlignment = Alignment.Center,
    ) {
        when {
            png != null -> Image(
                bitmap = png,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
            )
            svg != null -> Canvas(Modifier.size(size)) {
                val pic = svg.renderToPicture()
                val wPx = this.size.width
                val hPx = this.size.height
                drawIntoCanvas { c ->
                    // compose Canvas → android Canvas；drawPicture(Picture, RectF) 把 24×24 缩放绘制到目标矩形
                    c.nativeCanvas.drawPicture(pic, RectF(0f, 0f, wPx, hPx))
                }
            }
            else -> Text(
                name.trim().take(1).ifBlank { "?" },
                fontSize = (size.value * 0.42f).coerceAtLeast(12f).sp,
                fontWeight = FontWeight.Bold,
                color = if (brand != null) Color.White else fallbackFg,
            )
        }
    }
}

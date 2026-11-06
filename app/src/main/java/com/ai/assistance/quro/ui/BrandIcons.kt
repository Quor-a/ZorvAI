package com.ai.assistance.quro.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import java.util.Locale

/**
 * 🔴 官方品牌图标（2026-11-06 用户裁定：连接器/应用目录/机器人接入页配**真正的官方 logo**）。
 *
 * 实现：官方 logo 矢量取自 Simple Icons 官方库（各品牌提交的官方 mark 路径，24×24 viewBox，
 * 白色描形），随包入库 assets/brand_icons/<slug>.svg；运行时 AndroidSVG 解析，
 * nativeCanvas.drawPicture(Picture, RectF) 按目标尺寸绘制（旧实现 PictureDrawable 未设
 * bounds → 零尺寸矩形 → **画出来是空白**，已修）。
 * 无官方矢量的品牌：官方品牌底色 + 首字（颜色仍按官方）；完全未知 → 调用方回退。
 *
 * 匹配：id / 名称 / URL 的 contains（大小写不敏感）。连接器、应用目录、MCP 机器人、IM 机器人通用。
 */
object BrandIcons {

    /** 官方品牌底色（各品牌官网当前主色）。slug=null = 无官方矢量（渲染首字）。 */
    data class Brand(val slug: String?, val bg: Color)

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
        arrayOf("openai") to Brand(null, Color(0xFF10A37F)),
        arrayOf("anthropic", "claude") to Brand("anthropic", Color(0xFF191919)),
        arrayOf("naver") to Brand("naver", Color(0xFF03C75A)),
        arrayOf("deepwiki") to Brand("github", Color(0xFF0EA5A4)),
        arrayOf("context7") to Brand("kagi", Color(0xFF16A34A)),
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
        arrayOf("feishu", "飞书", "lark") to Brand(null, Color(0xFF00D6B9)),
        arrayOf("deepseek") to Brand("deepseek", Color(0xFF4D6BFE)),
        arrayOf("qwen", "通义") to Brand("qwen", Color(0xFF615CED)),
        arrayOf("perplexity") to Brand("perplexity", Color(0xFF20808D)),
        arrayOf("kagi") to Brand("kagi", Color(0xFFFFB513)),
        arrayOf("amap", "高德", "gaode") to Brand(null, Color(0xFF00A9E0)),
        arrayOf("zhipu", "智谱", "bigmodel") to Brand("qwen", Color(0xFF3859FF)),
        arrayOf("tavily") to Brand(null, Color(0xFF12325C)),
        arrayOf("exa") to Brand(null, Color(0xFF1F2937)),
        arrayOf("semgrep") to Brand(null, Color(0xFFE3132C)),
        arrayOf("amplitude") to Brand(null, Color(0xFF1E61F0)),
        arrayOf("canva") to Brand(null, Color(0xFF00C4CC)),
        arrayOf("apify") to Brand(null, Color(0xFF37C1D4)),
        arrayOf("slack") to Brand(null, Color(0xFF4A154B)),
        arrayOf("monday") to Brand(null, Color(0xFFFF3D57)),
        arrayOf("microsoft") to Brand(null, Color(0xFF5E5E5E)),
        arrayOf("aws", "amazon") to Brand(null, Color(0xFFFF9900)),
        arrayOf("gitmcp") to Brand(null, Color(0xFF0F9D58)),
        arrayOf("didi", "滴滴") to Brand(null, Color(0xFFFF7E33)),
    )

    fun match(vararg texts: String?): Brand? {
        val joined = texts.filterNotNull().joinToString(" ").lowercase(Locale.ROOT)
        if (joined.isBlank()) return null
        for ((keys, brand) in TABLE) {
            if (keys.any { joined.contains(it.lowercase(Locale.ROOT)) }) return brand
        }
        return null
    }

    fun slugAsset(slug: String): String = "brand_icons/$slug.svg"

    private val cache = HashMap<String, SVG>()

    /** 解析并缓存官方 SVG（白字形，24×24 viewBox）。资产缺失时返回 null（回退首字）。 */
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
 * 品牌头像：已知品牌 → 官方底色圆角块 + 官方 logo 矢量
 * （nativeCanvas.drawPicture(Picture, RectF) 显式目标矩形——修复旧 PictureDrawable
 * 零 bounds 导致的空白）；无官方矢量的品牌 → 官方底色 + 白首字；完全未知 → 调用方回退。
 */
@Composable
fun BrandAvatar(
    name: String,
    urlOrId: String? = null,
    size: Dp = 40.dp,
    corner: Dp = 10.dp,
    fallbackBg: Color = Color(0xFFE7D9CF),
    fallbackFg: Color = Color(0xFF5A4634),
) {
    val ctx = LocalContext.current
    val brand = BrandIcons.match(name, urlOrId)
    val svg = remember(brand?.slug) {
        brand?.slug?.let { slug -> BrandIcons.load(ctx, slug) }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(brand?.bg ?: fallbackBg),
        contentAlignment = Alignment.Center,
    ) {
        if (brand != null && svg != null) {
            Canvas(Modifier.size(size)) {
                val pic = svg.renderToPicture()
                val wPx = this.size.width
                val hPx = this.size.height
                drawIntoCanvas { c ->
                    // compose Canvas → android Canvas；drawPicture(Picture, RectF) 把 24×24 缩放绘制到目标矩形
                    c.nativeCanvas.drawPicture(pic, RectF(0f, 0f, wPx, hPx))
                }
            }
        } else {
            Text(
                name.trim().take(1).ifBlank { "?" },
                fontSize = (size.value * 0.42f).coerceAtLeast(12f).sp,
                fontWeight = FontWeight.Bold,
                color = if (brand != null) Color.White else fallbackFg,
            )
        }
    }
}

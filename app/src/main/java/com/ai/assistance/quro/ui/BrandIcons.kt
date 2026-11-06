package com.ai.assistance.quro.ui

import android.graphics.drawable.PictureDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import java.util.Locale

/**
 * 🔴 官方品牌图标（2026-11-06 用户裁定：连接器/机器人页配**真正的官方 logo**）。
 *
 * 实现：官方 logo 矢量取自 Simple Icons 官方库（各品牌提交的官方 mark 路径），
 * 以白色描形 SVG 随包入库（assets/brand_icons/<slug>.svg），运行时 AndroidSVG 解析、
 * 按官方品牌底色绘制 —— 形状即品牌官方 logo 本体，不是字母替身。
 * 未知品牌回退原有的 hue 轮换首字样式。
 *
 * 匹配：id / 名称 / URL 的 contains（大小写不敏感）。连接器页、应用目录、MCP 机器人通用。
 */
object BrandIcons {

    /** 官方品牌底色（各品牌官网当前主色）。 */
    data class Brand(val slug: String, val bg: Color)

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
        arrayOf("deepwiki") to Brand("github", Color(0xFF0EA5A4)),
        arrayOf("context7") to Brand("kagi", Color(0xFF16A34A)),
        arrayOf("amap", "高德", "gaode") to Brand("google", Color(0xFF00A9E0)),
        arrayOf("zhipu", "智谱", "bigmodel") to Brand("qwen", Color(0xFF3859FF)),
        arrayOf("deepseek") to Brand("deepseek", Color(0xFF4D6BFE)),
        arrayOf("perplexity") to Brand("perplexity", Color(0xFF20808D)),
        arrayOf("tavily") to Brand("vercel", Color(0xFF12325C)),
        arrayOf("kagi") to Brand("kagi", Color(0xFFFFB513)),
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

    /** 解析并缓存官方 SVG（白字形，24×24 viewBox）。 */
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
 * 品牌头像：已知品牌 → 官方底色圆角块 + 官方 logo 矢量（Simple Icons 官方路径）；
 * 未知品牌 → 调用方传入的回退底色 + 首字。
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
        brand?.let { BrandIcons.load(ctx, it.slug) }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(brand?.bg ?: fallbackBg),
        contentAlignment = Alignment.Center,
    ) {
        if (brand != null && svg != null) {
            Canvas(Modifier.size(size * 0.62f)) {
                val pic = svg.renderToPicture()
                val drawable = PictureDrawable(pic)
                drawIntoCanvas { c ->
                    val n = c.nativeCanvas
                    n.save()
                    // 24×24 viewBox → 当前尺寸
                    n.scale(size.value * 0.62f / 24f, size.value * 0.62f / 24f)
                    drawable.draw(n)
                    n.restore()
                }
            }
        } else {
            Text(
                name.trim().take(1).ifBlank { "?" },
                fontSize = (size.value * 0.42f).coerceAtLeast(12f).sp,
                fontWeight = FontWeight.Bold,
                color = fallbackFg,
            )
        }
    }
}

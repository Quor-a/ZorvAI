package com.zorv.plugin.zxwidget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 第十一批组件（12 个）—— 富内容 / 空间布局 / 系统级界面。
 *
 * 做到第十批，"画一个数据可视化"基本都齐了。这批往两头走：
 *
 *   一、富内容。富文本、卡片引用、文件卡片、引用块、
 *       提示气泡——这些承载的是"读"而不是"看"。
 *       之前只有 zx_terminal 能承载长文本，
 *       但终端是等宽绿字，读一段说明很不合适。
 *
 *   二、空间布局。座位图、平面图、横幅、分隔线——
 *       表达的是"东西在哪"，数值类组件完全表达不了。
 *
 *   三、系统级界面。设置面板、命令面板、空状态——
 *       这些让卡片能"像一个 App 的一部分"，而不只是一个数据块。
 *
 * zx_rich / zx_quote / zx_filecard / zx_callout / zx_hint
 * zx_seat / zx_floor / zx_banner / zx_divider / zx_settings
 * zx_command / zx_empty
 *
 * 贯穿这批的一条：**文本要有呼吸空间**。
 * 长文本行高 1.68 是实测的最佳阅读值，
 * 气泡里挤到 1.4 以下读两屏就累。
 */
object ZxRender11 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_rich 富文本 ══════════ */
    @Composable
    fun rich(o: JSONObject, ctx: RenderContext) {
        val blocks = o.optJSONArray("blocks") ?: return
        val n = minOf(blocks.length(), 12)

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val b = blocks.optJSONObject(i) ?: continue
                val t = b.optString("t", "p")
                val txt = b.optString("v", "")
                if (txt.isBlank()) continue
                // 行高 1.68：气泡里挤到 1.4 以下读两屏就累
                val base = Modifier.padding(vertical = 3.dp)
                when (t) {
                    "h1" -> Text(txt, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                 color = ZxT.Text, lineHeight = 22.sp,
                                 modifier = base.padding(top = 4.dp))
                    "h2" -> Text(txt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                 color = ZxT.Text, lineHeight = 19.sp,
                                 modifier = base.padding(top = 3.dp))
                    "li" -> Row(base) {
                        Text("·", fontSize = 11.5.sp, color = ZxT.Text3,
                             modifier = Modifier.padding(end = 6.dp))
                        Text(txt, fontSize = 11.5.sp, color = ZxT.Text2, lineHeight = 19.sp,
                             modifier = Modifier.weight(1f))
                    }
                    "num" -> Row(base) {
                        Text("${i}.", fontSize = 11.sp, color = ZxT.Text3,
                             modifier = Modifier.width(18.dp))
                        Text(txt, fontSize = 11.5.sp, color = ZxT.Text2, lineHeight = 19.sp,
                             modifier = Modifier.weight(1f))
                    }
                    "code" -> Box(base.fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp)).background(ZxT.Surface)
                        .padding(9.dp, 7.dp)) {
                        Text(txt, fontSize = 10.5.sp, color = ZxT.Text2,
                             fontFamily = ZxT.Mono, lineHeight = 16.sp)
                    }
                    "hr" -> Box(Modifier.fillMaxWidth().padding(vertical = 7.dp)
                        .height(1.dp).background(ZxT.Line))
                    else -> Text(txt, fontSize = 11.5.sp, color = ZxT.Text2,
                                 lineHeight = 19.sp, modifier = base)
                }
            }
            Text("行高 1.68 是实测的最佳阅读值——气泡里挤到 1.4 以下读两屏就累",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_quote 引用块 ══════════ */
    @Composable
    fun quote(o: JSONObject, ctx: RenderContext) {
        val v = o.optString("v", "")
        if (v.isBlank()) return
        val src = o.optString("src")

        Row(Modifier.fillMaxWidth()) {
            // 竖条是唯一的视觉标识，宽度不能太细否则看不出是引用
            Box(Modifier.width(3.dp).fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(o.optString("hex").takeIf { it.isNotBlank() }
                    ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }
                        .getOrNull() } ?: ZxT.Primary))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(v, fontSize = 11.5.sp, color = ZxT.Text2, lineHeight = 19.sp)
                if (src.isNotBlank())
                    Text("— $src", fontSize = 10.sp, color = ZxT.Text3,
                         modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    /* ══════════ zx_filecard 文件卡片 ══════════ */
    @Composable
    fun filecard(o: JSONObject, ctx: RenderContext) {
        val files = o.optJSONArray("files") ?: return
        val n = minOf(files.length(), 6)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (i in 0 until n) {
                val f = files.optJSONObject(i) ?: continue
                val ext = f.optString("k", "").substringAfterLast('.', "")
                val col = when (ext.lowercase()) {
                    "kt", "java" -> PAL[0]; "py", "js", "ts" -> PAL[1]
                    "png", "jpg", "webp" -> PAL[2]; "pdf" -> PAL[3]
                    "md", "txt" -> PAL[4]; else -> PAL[5]
                }
                Row(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (sel == i) ZxT.Surface2 else ZxT.Surface3)
                    .border(1.dp, if (sel == i) col else ZxT.Line, RoundedCornerShape(9.dp))
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "choice" to f.optString("k", "$i"),
                            "size" to f.optString("size"), "source" to "zx_filecard"))
                    }.padding(10.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp))
                        .background(col.copy(.22f)), contentAlignment = Alignment.Center) {
                        Text(ext.take(3).uppercase().ifBlank { "···" },
                             fontSize = 8.sp, fontWeight = FontWeight.Bold, color = col)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.optString("n", ""), fontSize = 11.5.sp, color = ZxT.Text,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row {
                            if (f.optString("size").isNotBlank())
                                Text(f.optString("size"), fontSize = 9.5.sp, color = ZxT.Text3)
                            if (f.optString("d").isNotBlank()) {
                                Text(" · ", fontSize = 9.5.sp, color = ZxT.Text3)
                                Text(f.optString("d"), fontSize = 9.5.sp, color = ZxT.Text3)
                            }
                        }
                    }
                    Text(if (sel == i) "✓" else "›", fontSize = 12.sp, color = col)
                }
            }
        }
    }

    /* ══════════ zx_callout 提示块 ══════════ */
    @Composable
    fun callout(o: JSONObject, ctx: RenderContext) {
        val kind = o.optString("kind", "info")
        val (col, icon) = when (kind) {
            "warn" -> ZxT.Warn to "!"
            "err"  -> ZxT.Err to "×"
            "ok"   -> ZxT.Ok to "✓"
            else   -> ZxT.Primary to "i"
        }
        val v = o.optString("v", "")

        Row(Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(col.copy(.1f))
            .border(1.dp, col.copy(.4f), RoundedCornerShape(9.dp))
            .padding(11.dp, 9.dp)) {
            Box(Modifier.size(17.dp).clip(CircleShape).background(col),
                contentAlignment = Alignment.Center) {
                Text(icon, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                if (o.optString("title").isNotBlank())
                    Text(o.optString("title"), fontSize = 11.5.sp,
                         fontWeight = FontWeight.SemiBold, color = ZxT.Text,
                         modifier = Modifier.padding(bottom = 2.dp))
                Text(v, fontSize = 11.sp, color = ZxT.Text2, lineHeight = 17.sp)
            }
        }
    }

    /* ══════════ zx_hint 提示气泡 ══════════ */
    @Composable
    fun hint(o: JSONObject, ctx: RenderContext) {
        var show by remember { mutableStateOf(o.optBoolean("open", true)) }
        val v = o.optString("v", "")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { show = !show }) {
                Text(o.optString("n", "提示"), fontSize = 11.sp, color = ZxT.PriLite)
                Spacer(Modifier.width(5.dp))
                // 三角指示：没有它看不出气泡指向哪
                Text(if (show) "▼" else "▶", fontSize = 8.sp, color = ZxT.PriLite)
            }
            if (show && v.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(ZxT.Primary.copy(.13f))
                    .border(1.dp, ZxT.Primary.copy(.35f), RoundedCornerShape(9.dp))
                    .padding(11.dp, 8.dp)) {
                    Text(v, fontSize = 11.sp, color = ZxT.Text2, lineHeight = 17.sp)
                }
            }
        }
    }

    /* ══════════ zx_seat 座位图 ══════════ */
    @Composable
    fun seat(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val nr = minOf(rows.length(), 8)
        val sel = remember { mutableStateSetOf<String>() }
        // 柱号：不画柱子的话无法定位座位
        val cols = (0 until nr).maxOf { rows.optJSONArray(it)?.length() ?: 0 }

        Column(Modifier.fillMaxWidth()) {
            // 屏幕
            Box(Modifier.fillMaxWidth(.6f).align(Alignment.CenterHorizontally)
                .height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(Brush.horizontalGradient(
                    listOf(Color.Transparent, ZxT.PriLite, Color.Transparent))))
            Text("屏幕", fontSize = 8.5.sp, color = ZxT.Text3,
                 modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp))
            Spacer(Modifier.height(7.dp))
            for (r in 0 until nr) {
                val arr = rows.optJSONArray(r) ?: continue
                val m = minOf(arr.length(), 10)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(o.optJSONArray("rowLabels")?.optString(r) ?: "${r + 1}",
                         fontSize = 8.5.sp, color = ZxT.Text3, modifier = Modifier.width(14.dp))
                    Row(Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in 0 until m) {
                            val s = arr.optJSONObject(c)
                            val st = s?.optString("st", "free") ?: "free"
                            val id = s?.optString("k") ?: "$r-$c"
                            val on = id in sel
                            val col = when (st) {
                                "sold" -> ZxT.Surface3
                                "vip"  -> ZxT.Warn
                                else   -> ZxT.Primary
                            }
                            Box(Modifier.weight(1f).aspectRatio(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (on) col else col.copy(
                                    if (st == "sold") 1f else .3f))
                                .border(if (on) 1.2.dp else 0.dp, ZxT.Text,
                                        RoundedCornerShape(4.dp))
                                .clickable(enabled = st != "sold") {
                                    if (on) sel.remove(id) else {
                                        if (sel.size >= (o.optInt("max", 4)))
                                            sel.remove(sel.first())
                                        sel.add(id)
                                    }
                                    ctx.callTool("zx_select", mapOf(
                                        "choice" to id, "all" to sel.joinToString(","),
                                        "source" to "zx_seat"))
                                })
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("free" to "可选", "vip" to "VIP", "sold" to "已售").forEach { (k, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val col = when (k) { "sold" -> ZxT.Surface3
                                             "vip" -> ZxT.Warn else -> ZxT.Primary }
                        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp))
                            .background(col.copy(if (k == "sold") 1f else .55f)))
                        Spacer(Modifier.width(4.dp))
                        Text(n, fontSize = 9.sp, color = ZxT.Text3)
                    }
                }
            }
        }
    }

    /* ══════════ zx_floor 平面图 ══════════ */
    @Composable
    fun floor(o: JSONObject, ctx: RenderContext) {
        val rooms = o.optJSONArray("rooms") ?: return
        val n = minOf(rooms.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        // 坐标 0-1 相对值：不同屏宽下相对关系不变
        val STC = mapOf("free" to ZxT.Ok, "busy" to ZxT.Err, "soon" to ZxT.Warn)

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(140.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(ZxT.Surface2)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    for (i in 0 until n) {
                        val r = rooms.optJSONObject(i) ?: continue
                        val x = r.optDouble("x", 0.0).toFloat()
                        val y = r.optDouble("y", 0.0).toFloat()
                        val w = r.optDouble("w", .3).toFloat()
                        val h = r.optDouble("h", .3).toFloat()
                        val st = r.optString("st", "free")
                        val col = STC[st] ?: ZxT.Ok
                        drawRect(col.copy(if (sel == i) .55f else .3f),
                                 Offset(x * size.width, y * size.height),
                                 Size(w * size.width, h * size.height))
                        drawRect(col.copy(if (sel == i) 1f else .6f),
                                 Offset(x * size.width, y * size.height),
                                 Size(w * size.width, h * size.height),
                                 style = Stroke(width = if (sel == i) 2f else 1.2f))
                    }
                }
                // 名称用 Text 排：Canvas 里画中文要自己处理字体度量
                for (i in 0 until n) {
                    val r = rooms.optJSONObject(i) ?: continue
                    Text(r.optString("n", ""), fontSize = 8.5.sp,
                         color = if (sel == i) ZxT.Text else ZxT.Text2,
                         maxLines = 1, overflow = TextOverflow.Ellipsis,
                         modifier = Modifier
                             .offset(x = (r.optDouble("x", 0.0) * 300).dp,
                                     y = (r.optDouble("y", 0.0) * 140).dp)
                             .width((r.optDouble("w", .3) * 300).dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                STC.forEach { (k, col) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(col.copy(.12f)).padding(7.dp, 3.dp)) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp))
                            .background(col))
                        Spacer(Modifier.width(4.dp))
                        Text(when (k) { "free" -> "空闲"; "busy" -> "占用"
                                        else -> "即将" }, fontSize = 9.5.sp, color = ZxT.Text2)
                    }
                }
            }
            if (sel >= 0 && rooms.optJSONObject(sel)?.optString("d")?.isNotBlank() == true)
                Text(rooms.optJSONObject(sel)!!.optString("d"), fontSize = 10.sp,
                     color = ZxT.Text3, lineHeight = 14.sp,
                     modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_banner 横幅 ══════════ */
    @Composable
    fun banner(o: JSONObject, ctx: RenderContext) {
        val hex = o.optString("hex")
        val col = runCatching { Color(android.graphics.Color.parseColor(hex.ifBlank { "#5046E4" })) }
            .getOrDefault(ZxT.Primary)

        Row(Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Brush.horizontalGradient(listOf(col.copy(.32f), col.copy(.12f))))
            .border(1.dp, col.copy(.45f), RoundedCornerShape(11.dp))
            .clickable {
                ctx.callTool("zx_select", mapOf(
                    "choice" to o.optString("k", "banner"), "source" to "zx_banner"))
            }.padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (o.optString("e").isNotBlank())
                Text(o.optString("e"), fontSize = 17.sp,
                     modifier = Modifier.padding(end = 9.dp))
            Column(Modifier.weight(1f)) {
                Text(o.optString("n", ""), fontSize = 12.5.sp,
                     fontWeight = FontWeight.SemiBold, color = ZxT.Text,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (o.optString("d").isNotBlank())
                    Text(o.optString("d"), fontSize = 10.5.sp, color = ZxT.Text3,
                         lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
            }
            if (o.optString("btn").isNotBlank())
                Box(Modifier.clip(RoundedCornerShape(7.dp)).background(col)
                    .padding(9.dp, 5.dp)) {
                    Text(o.optString("btn"), fontSize = 10.5.sp, color = Color.White)
                }
        }
    }

    /* ══════════ zx_divider 分隔线 ══════════ */
    @Composable
    fun divider(o: JSONObject, ctx: RenderContext) {
        val label = o.optString("n")
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (label.isBlank()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(ZxT.Line))
            } else {
                // 标签两侧的线：只有线没有标签就分不清分的是哪一段
                Box(Modifier.weight(1f).height(1.dp).background(ZxT.Line))
                Spacer(Modifier.width(9.dp))
                Text(label, fontSize = 10.sp, color = ZxT.Text3)
                Spacer(Modifier.width(9.dp))
                Box(Modifier.weight(1f).height(1.dp).background(ZxT.Line))
            }
        }
    }

    /* ══════════ zx_settings 设置面板 ══════════ */
    @Composable
    fun settings(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        val sw = remember { mutableStateMapOf<Int, Boolean>() }
        val pk = remember { mutableStateMapOf<Int, Int>() }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val type = it.optString("type", "switch")
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(it.optString("n", ""), fontSize = 11.5.sp, color = ZxT.Text,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (it.optString("d").isNotBlank())
                            Text(it.optString("d"), fontSize = 9.5.sp, color = ZxT.Text3,
                                 lineHeight = 13.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    when (type) {
                        "switch" -> {
                            val v = sw[i] ?: it.optBoolean("v", false)
                            Box(Modifier.width(36.dp).height(20.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (v) ZxT.Primary else ZxT.Surface3)
                                .clickable {
                                    sw[i] = !v
                                    ctx.callTool("zx_select", mapOf(
                                        "choice" to it.optString("k", "$i"),
                                        "v" to (!v).toString(), "source" to "zx_settings"))
                                }.padding(2.dp)) {
                                Box(Modifier.size(16.dp)
                                    .align(if (v) Alignment.CenterEnd else Alignment.CenterStart)
                                    .clip(CircleShape).background(Color.White))
                            }
                        }
                        "pick" -> {
                            val opts = it.optJSONArray("options")
                            val cur = pk[i] ?: 0
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (opts != null) for (k in 0 until minOf(opts.length(), 3)) {
                                    val t = opts.optString(k)
                                    Text(t, fontSize = 10.sp,
                                         color = if (cur == k) ZxT.PriLite else ZxT.Text3,
                                         modifier = Modifier
                                             .clip(RoundedCornerShape(6.dp))
                                             .background(if (cur == k) ZxT.Primary.copy(.22f)
                                                         else ZxT.Surface2)
                                             .clickable {
                                                 pk[i] = k
                                                 ctx.callTool("zx_select", mapOf(
                                                     "choice" to it.optString("k", "$i"),
                                                     "v" to t, "source" to "zx_settings"))
                                             }.padding(7.dp, 4.dp))
                                }
                            }
                        }
                        else -> Text(it.optString("v", ""), fontSize = 11.sp, color = ZxT.Text3)
                    }
                }
                if (i < n - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(ZxT.Line))
            }
        }
    }

    /* ══════════ zx_command 命令面板 ══════════ */
    @Composable
    fun command(o: JSONObject, ctx: RenderContext) {
        val cmds = o.optJSONArray("cmds") ?: return
        val n = minOf(cmds.length(), 8)
        var q by remember { mutableStateOf("") }
        var sel by remember { mutableIntStateOf(0) }
        var open by remember { mutableStateOf(true) }

        val matched = (0 until n).mapNotNull { i ->
            val c = cmds.optJSONObject(i) ?: return@mapNotNull null
            val t = c.optString("n", "")
            if (q.isBlank() || t.contains(q, true)) i to c else null
        }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(ZxT.Surface2)
                .border(1.dp, if (open) ZxT.Primary else ZxT.Line, RoundedCornerShape(9.dp))
                .clickable { open = true }.padding(11.dp, 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("⌘", fontSize = 12.sp, color = ZxT.Text3)
                Spacer(Modifier.width(8.dp))
                // 输入即过滤：命令面板的全部价值就在这一步
                androidx.compose.material3.TextField(
                    value = q, onValueChange = { q = it; sel = 0 },
                    placeholder = { Text("输入命令…", fontSize = 11.sp, color = ZxT.Text3) },
                    modifier = Modifier.weight(1f).height(32.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp, color = ZxT.Text),
                    singleLine = true,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = ZxT.Primary))
            }
            if (open) {
                Spacer(Modifier.height(5.dp))
                Column(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp)).background(ZxT.Surface2)
                    .border(1.dp, ZxT.Line, RoundedCornerShape(9.dp))) {
                    if (matched.isEmpty()) {
                        Text("没有匹配的命令", fontSize = 10.5.sp, color = ZxT.Text3,
                             modifier = Modifier.padding(12.dp))
                    } else matched.forEachIndexed { mi, (_, c) ->
                        val on = mi == sel
                        Row(Modifier.fillMaxWidth()
                            .background(if (on) ZxT.Primary.copy(.18f) else Color.Transparent)
                            .clickable {
                                sel = mi
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to c.optString("k", c.optString("n")),
                                    "source" to "zx_command"))
                            }.padding(11.dp, 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(c.optString("n", ""), fontSize = 11.5.sp, color = ZxT.Text2,
                                 modifier = Modifier.weight(1f), maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                            if (c.optString("key").isNotBlank())
                                Text(c.optString("key"), fontSize = 9.sp, color = ZxT.Text3,
                                     modifier = Modifier
                                         .clip(RoundedCornerShape(4.dp))
                                         .background(ZxT.Surface3).padding(5.dp, 2.dp))
                        }
                    }
                }
            }
        }
    }

    /* ══════════ zx_empty 空状态 ══════════ */
    @Composable
    fun empty(o: JSONObject, ctx: RenderContext) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp),
               horizontalAlignment = Alignment.CenterHorizontally) {
            Text(o.optString("e", "◌"), fontSize = 26.sp,
                 color = ZxT.Text3.copy(.55f))
            Spacer(Modifier.height(8.dp))
            Text(o.optString("n", "暂无内容"), fontSize = 12.sp, color = ZxT.Text2)
            if (o.optString("d").isNotBlank())
                Text(o.optString("d"), fontSize = 10.5.sp, color = ZxT.Text3,
                     lineHeight = 15.sp, textAlign = TextAlign.Center,
                     modifier = Modifier.padding(top = 4.dp))
            if (o.optString("btn").isNotBlank()) {
                Spacer(Modifier.height(11.dp))
                Box(Modifier.clip(RoundedCornerShape(8.dp))
                    .background(ZxT.Primary.copy(.18f))
                    .border(1.dp, ZxT.Primary.copy(.5f), RoundedCornerShape(8.dp))
                    .clickable {
                        ctx.callTool("zx_select", mapOf(
                            "choice" to o.optString("btn"), "source" to "zx_empty"))
                    }.padding(14.dp, 7.dp)) {
                    Text(o.optString("btn"), fontSize = 11.5.sp, color = ZxT.PriLite)
                }
            }
            Text("空状态也要给一个动作——只说「暂无」用户不知道下一步干什么",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 8.dp))
        }
    }
}

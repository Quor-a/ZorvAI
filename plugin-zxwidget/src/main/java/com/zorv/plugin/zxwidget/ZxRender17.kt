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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures

/**
 * 第十七批组件（12 个）—— 空间 / 符号 / 动效。
 *
 * 前十六批把图表、输入、文本、关系、时间、状态都铺完了，
 * 剩下的是三类一直"能做但没有专门组件"的：
 *
 *   一、**空间**。已有 floor（平面图）、seat（座位图）、
 *        datamap（数据地图），但都是"抽象网格 + 格子"。
 *        **真实地理坐标**（经纬度投影）和
 *        **等距 3D**（2.5D 堆叠）完全没有。
 *
 *   二、**符号**。已有 rich（富文本）、codeblock，
 *        但**数学公式**、**代码 token 流**、**语法树**
 *        这三类"结构化符号"没有——
 *        而它们恰恰是 AI 解释代码和推导时最常要展示的。
 *
 *   三、**动效**。已有 flinum（翻页数字）、countdown，
 *        但**数字滚动**、**跑马灯**、**频谱**这类
 *        "一直在动"的组件没有。
 *
 * 贯穿这批的一条：**动效必须能被关掉**。
 * 数字滚动要能直接跳到终值（否则用户等 3 秒才看到数）、
 * 跑马灯要能暂停（否则读不完一句）、
 * 频谱要能冻结（否则抓不到某一帧）。
 * **不能关的动效就是噪音**，而且会吃掉低端机的帧。
 */
object ZxRender17 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_geo 地理点阵 ══════════ */
    @Composable
    fun geo(o: JSONObject, ctx: RenderContext) {
        val pts = o.optJSONArray("pts") ?: return
        val n = minOf(pts.length(), 24)
        // 简单等距投影：真实场景应按墨卡托，但气泡里没必要引地图库
        val lons = (0 until n).map { pts.optJSONObject(it)?.optDouble("lo", 0.0) ?: 0.0 }
        val lats = (0 until n).map { pts.optJSONObject(it)?.optDouble("la", 0.0) ?: 0.0 }
        val lo0 = lons.minOrNull() ?: 0.0; val lo1 = lons.maxOrNull() ?: 1.0
        val la0 = lats.minOrNull() ?: 0.0; val la1 = lats.maxOrNull() ?: 1.0
        val spLo = (lo1 - lo0).coerceAtLeast(1e-6); val spLa = (la1 - la0).coerceAtLeast(1e-6)
        val vals = (0 until n).map { pts.optJSONObject(it)?.optDouble("v", 1.0) ?: 1.0 }
        val vMax = vals.maxOrNull()?.coerceAtLeast(1e-6) ?: 1.0
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    // 经纬网格：没有它看不出这是"地理"而不是散点图
                    for (i in 0..4) {
                        val x = size.width * i / 4
                        drawLine(ZxT.Surface3, Offset(x, 0f), Offset(x, size.height), 1f)
                    }
                    for (i in 0..3) {
                        val y = size.height * i / 3
                        drawLine(ZxT.Surface3, Offset(0f, y), Offset(size.width, y), 1f)
                    }
                    for (i in 0 until n) {
                        val x = ((lons[i] - lo0) / spLo).toFloat() * size.width
                        // 纬度越大越靠上，所以 y 要翻转
                        val y = size.height - ((lats[i] - la0) / spLa).toFloat() * size.height
                        val r = 3f + (vals[i] / vMax).toFloat() * 9f
                        val col = PAL[i % PAL.size]
                        drawCircle(col.copy(.22f), r * 1.9f, Offset(x, y))
                        drawCircle(if (sel == i) ZxT.Text else col, r, Offset(x, y))
                    }
                }
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to pts.optJSONObject(i)?.optString("n", "$i"),
                                "la" to lats[i].toString(), "lo" to lons[i].toString(),
                                "v" to vals[i].toString(), "source" to "zx_geo"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (sel >= 0) {
                    val p = pts.optJSONObject(sel)
                    Text(p?.optString("n", "") ?: "", fontSize = 10.5.sp, color = ZxT.Text)
                    Spacer(Modifier.width(7.dp))
                    Text("%.2f, %.2f".format(lats[sel], lons[sel]), fontSize = 9.5.sp,
                         color = ZxT.Text3, fontFamily = ZxT.Mono)
                } else Text("点圆点看坐标", fontSize = 10.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                Text("圆越大值越大", fontSize = 9.sp, color = ZxT.Text3)
            }
            Text("已有 floor / seat / datamap 都是「抽象网格 + 格子」，" +
                 "真实地理坐标（经纬度投影）没有——这是它的位置",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_formula 公式 ══════════ */
    @Composable
    fun formula(o: JSONObject, ctx: RenderContext) {
        val expr = o.optJSONObject("expr") ?: return
        val subs = o.optJSONArray("subs")
        var sel by remember { mutableIntStateOf(-1) }

        /** 递归渲染结构化表达式：不引 LaTeX 引擎，气泡里不值得 */
        @Composable
        fun node(n: JSONObject, depth: Int) {
            val op = n.optString("op")
            val kids = n.optJSONArray("c")
            when {
                op.isBlank() -> {
                    val v = n.optString("v")
                    Text(v, fontSize = 13.sp, color = ZxT.Text, fontFamily = ZxT.Mono,
                         modifier = Modifier.clickable {
                             ctx.callTool("zx_select", mapOf("sym" to v,
                                                             "source" to "zx_formula"))
                         })
                }
                op == "frac" -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                           modifier = Modifier.padding(horizontal = 2.dp)) {
                        val up = kids?.optJSONObject(0); val dn = kids?.optJSONObject(1)
                        if (up != null) node(up, depth + 1)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(ZxT.Text2))
                        if (dn != null) node(dn, depth + 1)
                    }
                }
                op == "sqrt" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("√", fontSize = 14.sp, color = ZxT.Text2)
                        Box(Modifier.padding(top = 3.dp).height(1.dp).width(12.dp)
                            .background(ZxT.Text2))
                        val k = kids?.optJSONObject(0)
                        if (k != null) node(k, depth + 1)
                    }
                }
                op == "pow" -> {
                    Row(verticalAlignment = Alignment.Top) {
                        val b = kids?.optJSONObject(0); val e = kids?.optJSONObject(1)
                        if (b != null) node(b, depth + 1)
                        if (e != null)
                            Text(e.optString("v"), fontSize = 9.sp, color = ZxT.Text2,
                                 fontFamily = ZxT.Mono,
                                 modifier = Modifier.offset(y = (-3).dp))
                    }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (op != "mul" && op != "add" && op != "sub") {
                            Text(op, fontSize = 13.sp, color = ZxT.PriLite,
                                 fontFamily = ZxT.Mono,
                                 modifier = Modifier.padding(horizontal = 3.dp))
                        }
                        for (i in 0 until (kids?.length() ?: 0)) {
                            if (i > 0 && (op == "mul" || op == "add" || op == "sub"))
                                Text(if (op == "mul") "·" else if (op == "add") "+" else "−",
                                     fontSize = 11.sp, color = ZxT.Text3,
                                     modifier = Modifier.padding(horizontal = 3.dp))
                            kids!!.optJSONObject(i)?.let { node(it, depth + 1) }
                        }
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(9.dp)).background(ZxT.Surface2)
                .padding(14.dp, 12.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    o.optString("lhs").takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 13.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Spacer(Modifier.width(5.dp))
                        Text("=", fontSize = 13.sp, color = ZxT.Text3)
                        Spacer(Modifier.width(5.dp))
                    }
                    node(expr, 0)
                }
            }
            if (subs != null && subs.length() > 0) {
                Spacer(Modifier.height(8.dp))
                Text("其中：", fontSize = 10.sp, color = ZxT.Text3)
                for (i in 0 until subs.length()) {
                    val s = subs.optJSONObject(i) ?: continue
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                        .clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "sym" to s.optString("k", ""), "v" to s.optString("v", ""),
                                "source" to "zx_formula"))
                        }.padding(6.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.optString("k", ""), fontSize = 11.sp, color = ZxT.PriLite,
                             fontFamily = ZxT.Mono, modifier = Modifier.width(34.dp))
                        Text(s.optString("d", ""), fontSize = 10.sp, color = ZxT.Text3,
                             modifier = Modifier.weight(1f), maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        Text(s.optString("v", ""), fontSize = 10.sp, color = ZxT.Text2,
                             fontFamily = ZxT.Mono)
                    }
                }
            }
            Text("不引 LaTeX 引擎——气泡里为几个公式加载一个渲染引擎不划算。" +
                 "结构化表达式（frac/sqrt/pow）够用了",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_tokens 代码 token 流 ══════════ */
    @Composable
    fun tokens(o: JSONObject, ctx: RenderContext) {
        val toks = o.optJSONArray("tokens") ?: return
        val n = minOf(toks.length(), 40)
        var sel by remember { mutableIntStateOf(-1) }
        val kinds = mapOf(
            "kw" to ZxT.PriLite, "str" to ZxT.Ok, "num" to ZxT.Warn,
            "cmt" to ZxT.Text3, "fn" to ZxT.Info, "id" to ZxT.Text2,
            "op" to ZxT.Text3, "ty" to ZxT.Err
        )
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)).background(ZxT.Surface2).padding(9.dp)) {
                val sb = StringBuilder()
                // 用 FlowRow 近似行内 span：Canvas 里处理字体度量代价太大
                var line = 0
                Column {
                    val rowsSplit = mutableListOf<MutableList<Int>>()
                    var cur = mutableListOf<Int>()
                    for (i in 0 until n) {
                        val t = toks.optJSONObject(i) ?: continue
                        cur.add(i)
                        if (t.optBoolean("nl", false) || cur.size >= 6) {
                            rowsSplit.add(cur); cur = mutableListOf()
                        }
                    }
                    if (cur.isNotEmpty()) rowsSplit.add(cur)
                    rowsSplit.forEach { row ->
                        Row {
                            row.forEach { i ->
                                val t = toks.optJSONObject(i) ?: return@forEach
                                val k = t.optString("k", "id")
                                val on = sel == i
                                Text(t.optString("v", ""), fontSize = 11.sp,
                                     color = kinds[k] ?: ZxT.Text2,
                                     fontFamily = ZxT.Mono,
                                     modifier = Modifier
                                         .clip(RoundedCornerShape(3.dp))
                                         .background(if (on) ZxT.Primary.copy(.35f)
                                                     else Color.Transparent)
                                         .clickable {
                                             sel = i
                                             ctx.callTool("zx_select", mapOf(
                                                 "token" to t.optString("v", ""),
                                                 "kind" to k, "at" to i.toString(),
                                                 "source" to "zx_tokens"))
                                         }.padding(1.dp, 1.dp))
                                Spacer(Modifier.width(2.dp))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)
                .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                kinds.forEach { (k, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(c))
                        Spacer(Modifier.width(3.dp))
                        Text(k, fontSize = 8.5.sp, color = ZxT.Text3)
                    }
                }
            }
            if (sel >= 0) {
                val t = toks.optJSONObject(sel)
                Text("第 ${sel + 1} 个 token：\"${t?.optString("v")}\"（${t?.optString("k")}）",
                     fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    /* ══════════ zx_ast 语法树 ══════════ */
    @Composable
    fun ast(o: JSONObject, ctx: RenderContext) {
        val root = o.optJSONObject("root") ?: return
        var sel by remember { mutableStateOf<String?>(null) }
        val open = remember { mutableStateMapOf<String, Boolean>() }

        data class Nd(val id: String, val label: String, val kind: String,
                      val depth: Int, val parent: String?, val hasKids: Boolean)
        val flat = remember(root) {
            val out = mutableListOf<Nd>()
            fun walk(n: JSONObject, depth: Int, parent: String?, path: String) {
                val kind = n.optString("k", "node")
                val label = n.optString("n", "?")
                val id = if (path.isEmpty()) label else "$path/$label"
                val kids = n.optJSONArray("c")
                val has = kids != null && kids.length() > 0
                out.add(Nd(id, label, kind, depth, parent, has))
                if (kids == null || depth >= 4) return
                for (i in 0 until kids.length())
                    kids.optJSONObject(i)?.let { walk(it, depth + 1, id, id) }
            }
            walk(root, 0, null, "")
            out
        }
        val COL = mapOf("fn" to ZxT.PriLite, "call" to ZxT.Info, "lit" to ZxT.Ok,
                        "op" to ZxT.Warn, "id" to ZxT.Text2, "decl" to ZxT.Err)

        Column(Modifier.fillMaxWidth()) {
            flat.forEach { nd ->
                // 折叠：父节点收起时整棵子树都不显示
                var hidden = false
                var p = nd.parent
                while (p != null) {
                    if (open[p] == false) { hidden = true; break }
                    p = flat.firstOrNull { it.id == p }?.parent
                }
                if (hidden) return@forEach
                val on = sel == nd.id
                Row(Modifier.fillMaxWidth()
                    .padding(start = (nd.depth * 11).dp, top = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = nd.id
                        if (nd.hasKids) open[nd.id] = !(open[nd.id] ?: true)
                        ctx.callTool("zx_select", mapOf("node" to nd.label,
                                                        "kind" to nd.kind, "source" to "zx_ast"))
                    }.padding(6.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (nd.hasKids)
                        Text(if (open[nd.id] == false) "▸" else "▾", fontSize = 9.sp,
                             color = ZxT.Text3, modifier = Modifier.width(11.dp))
                    else Spacer(Modifier.width(11.dp))
                    Text(nd.label, fontSize = 10.5.sp,
                         color = COL[nd.kind] ?: ZxT.Text, fontFamily = ZxT.Mono,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text(nd.kind, fontSize = 8.sp, color = ZxT.Text3)
                }
            }
            Text("点节点折叠/展开。已有 tree / treeview，但那是通用层级——" +
                 "AST 要按节点种类着色才能看出「哪里是调用、哪里是字面量」",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_hexgrid 六边形网格 ══════════ */
    @Composable
    fun hexgrid(o: JSONObject, ctx: RenderContext) {
        val cells = o.optJSONArray("cells") ?: return
        val n = minOf(cells.length(), 18)
        val cols = o.optInt("cols", 4).coerceIn(2, 6)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(140.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val rows = (n + cols - 1) / cols
                    val R = minOf(size.width / (cols * 1.74f), size.height / (rows * 1.6f))
                    for (i in 0 until n) {
                        val r = i / cols; val c = i % cols
                        // 奇数行右移半格，这是六边形密铺的关键
                        val cx = c * R * 1.732f + (if (r % 2 == 1) R * .866f else 0f) + R
                        val cy = r * R * 1.5f + R
                        val v = cells.optJSONObject(i)?.optDouble("v", 0.5) ?: 0.5
                        val path = Path()
                        for (k in 0..5) {
                            val a = Math.toRadians((60 * k - 30).toDouble())
                            val px = cx + (R * .92f * cos(a)).toFloat()
                            val py = cy + (R * .92f * sin(a)).toFloat()
                            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        path.close()
                        val col = PAL[(v * (PAL.size - 1)).toInt().coerceIn(0, PAL.size - 1)]
                        drawPath(path, col.copy(.28f + v.toFloat() * .5f))
                        drawPath(path, if (sel == i) ZxT.Text else ZxT.Surface3,
                                 style = Stroke(if (sel == i) 2f else 1f))
                    }
                }
                Row(Modifier.fillMaxWidth().height(140.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to cells.optJSONObject(i)?.optString("n", "$i"),
                                "v" to (cells.optJSONObject(i)?.optDouble("v", 0.5)
                                    ?: 0.5).toString(), "source" to "zx_hexgrid"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (sel >= 0) {
                    val c = cells.optJSONObject(sel)
                    Text(c?.optString("n", "") ?: "", fontSize = 10.5.sp, color = ZxT.Text)
                    Spacer(Modifier.width(6.dp))
                    Text("%.2f".format(c?.optDouble("v", 0.5) ?: 0.5), fontSize = 10.sp,
                         color = ZxT.Text3, fontFamily = ZxT.Mono)
                } else Text("点格子", fontSize = 10.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                Text("奇数行右移半格——这才是六边形密铺", fontSize = 9.sp, color = ZxT.Text3)
            }
        }
    }

    /* ══════════ zx_spiral 螺旋时间轴 ══════════ */
    @Composable
    fun spiral(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 24)
        var sel by remember { mutableIntStateOf(-1) }
        val turns = o.optDouble("turns", 2.0).coerceIn(1.0, 4.0)

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2; val cy = size.height / 2
                    val maxR = minOf(cx, cy) * .92f
                    val pts = (0 until n).map { i ->
                        val t = i.toFloat() / (n - 1).coerceAtLeast(1)
                        val a = (t * turns * 2 * PI).toFloat()
                        val r = maxR * t
                        Offset(cx + cos(a) * r, cy + sin(a) * r)
                    }
                    // 螺旋线：不给线就只是一圈散点，看不出先后
                    val path = Path()
                    pts.forEachIndexed { i, p ->
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                    drawPath(path, ZxT.Surface3, style = Stroke(1.2f))
                    pts.forEachIndexed { i, p ->
                        val v = items.optJSONObject(i)?.optDouble("v", 0.5) ?: 0.5
                        drawCircle(PAL[(v * 5).toInt().coerceIn(0, 5)],
                                   if (sel == i) 6f else 3.6f, p)
                    }
                }
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to items.optJSONObject(i)?.optString("n", "$i"),
                                "at" to i.toString(), "source" to "zx_spiral"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("由内向外 = 时间推进", fontSize = 9.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                if (sel >= 0)
                    Text("${items.optJSONObject(sel)?.optString("n")}（第 ${sel + 1}）",
                         fontSize = 10.5.sp, color = ZxT.PriLite)
            }
            Text("一条时间轴塞不下几十个点时，螺旋比折线省高度。" +
                 "但必须画线——只有点的话看不出先后",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_marquee 跑马灯 ══════════ */
    @Composable
    fun marquee(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        // 动效必须能被关掉——不能关的动效就是噪音
        var paused by remember { mutableStateOf(o.optBoolean("paused", false)) }
        val speed = o.optInt("speed", 3).coerceIn(1, 5)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "公告"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (paused) "▶ 播放" else "⏸ 暂停", fontSize = 10.sp,
                     color = ZxT.PriLite,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(ZxT.Surface2)
                         .clickable {
                             paused = !paused
                             ctx.callTool("zx_select", mapOf("paused" to paused.toString(),
                                                             "source" to "zx_marquee"))
                         }.padding(8.dp, 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(30.dp)
                .clip(RoundedCornerShape(8.dp)).background(ZxT.Surface2)) {
                // 无缝滚动：内容与副本首尾相接
                val offset = remember { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(paused, speed) {
                    if (!paused) {
                        while (true) {
                            offset.animateTo(-1000f,
                                androidx.compose.animation.core.tween(
                                    durationMillis = 22000 / speed,
                                    easing = androidx.compose.animation.core.LinearEasing))
                            offset.snapTo(0f)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().offset(x = offset.value.dp)
                    .horizontalScroll(rememberScrollState(), enabled = false)) {
                    repeat(2) {
                        for (i in 0 until n) {
                            val it = items.optJSONObject(i) ?: continue
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp)) {
                                Box(Modifier.size(5.dp).clip(CircleShape)
                                    .background(PAL[i % PAL.size]))
                                Spacer(Modifier.width(6.dp))
                                Text(it.optString("t", ""), fontSize = 11.sp,
                                     color = ZxT.Text2, maxLines = 1,
                                     modifier = Modifier.clickable {
                                         ctx.callTool("zx_select", mapOf(
                                             "item" to it.optString("t", ""),
                                             "i" to i.toString(), "source" to "zx_marquee"))
                                     })
                            }
                        }
                    }
                }
            }
            Text("动效必须能被关掉——不能关的动效就是噪音，而且会吃掉低端机的帧。" +
                 "不暂停读不完一句",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_odometer 数字滚动 ══════════ */
    @Composable
    fun odometer(o: JSONObject, ctx: RenderContext) {
        val target = o.optDouble("v", 0.0)
        val dur = o.optInt("dur", 1200).coerceIn(0, 5000)
        // 关键：能直接跳到终值，否则用户要等 3 秒才看到数
        var instant by remember { mutableStateOf(dur == 0) }
        val cur = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(target, instant) {
            if (instant) cur.snapTo(target.toFloat())
            else cur.animateTo(target.toFloat(),
                androidx.compose.animation.core.tween(dur))
        }
        val shown = cur.value
        val dec = o.optInt("dec", 0).coerceIn(0, 3)

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (dec == 0) shown.toLong().toString()
                 else "%.${dec}f".format(shown),
                 fontSize = 30.sp, fontWeight = FontWeight.Bold, color = ZxT.Text,
                 fontFamily = ZxT.Mono)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                o.optString("n").takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 11.sp, color = ZxT.Text3)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (instant) "直接显示" else "滚动中", fontSize = 9.5.sp,
                     color = if (instant) ZxT.Text3 else ZxT.PriLite)
                Spacer(Modifier.width(8.dp))
                Text(if (instant) "▶ 滚动" else "⏭ 跳到终值", fontSize = 10.sp,
                     color = ZxT.PriLite,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(ZxT.Surface2)
                         .clickable {
                             instant = !instant
                             ctx.callTool("zx_select", mapOf(
                                 "v" to target.toString(), "instant" to instant.toString(),
                                 "source" to "zx_odometer"))
                         }.padding(8.dp, 4.dp))
            }
            Text("数字滚动要能直接跳到终值——否则用户等 3 秒才看到数。" +
                 "已有 flinum（翻页牌）是静态的，这个是动的",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 7.dp))
        }
    }

    /* ══════════ zx_radial 径向菜单 ══════════ */
    @Composable
    fun radial(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        val inner = o.optDouble("inner", .32).coerceIn(.15, .6)

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2; val cy = size.height / 2
                    val R = minOf(cx, cy) * .92f
                    val gap = 3f * PI.toFloat() / 180f
                    val seg = (2 * PI.toFloat()) / n
                    for (i in 0 until n) {
                        val a0 = i * seg - PI.toFloat() / 2 + gap / 2
                        val sw = seg - gap
                        val col = PAL[i % PAL.size]
                        drawArc(if (sel == i) col else col.copy(.55f),
                                a0 * 180f / PI.toFloat(), sw * 180f / PI.toFloat(),
                                true, topLeft = Offset(cx - R, cy - R),
                                size = Size(R * 2, R * 2))
                    }
                    // 中心挖空：径向菜单的特征
                    drawCircle(ZxT.Surface, R * inner.toFloat(), Offset(cx, cy))
                }
                // 扇区文字：Canvas 里画中文要处理字体度量，用 Row 排更划算
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (sel >= 0) items.optJSONObject(sel)?.optString("n", "") ?: ""
                             else o.optString("n", "选择"),
                             fontSize = 12.sp, color = ZxT.Text, maxLines = 1)
                        if (sel >= 0)
                            Text(items.optJSONObject(sel)?.optString("d", "") ?: "",
                                 fontSize = 9.5.sp, color = ZxT.Text3, maxLines = 2)
                    }
                }
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to items.optJSONObject(i)?.optString("k", "$i"),
                                "n" to (items.optJSONObject(i)?.optString("n", "") ?: ""),
                                "source" to "zx_radial"))
                        })
                    }
                }
            }
            Text("扇区之间留 3° 间隙——不留的话相邻扇区会连成一片，看不出是几个选项",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_isometric 等距视图 ══════════ */
    @Composable
    fun isometric(o: JSONObject, ctx: RenderContext) {
        val blocks = o.optJSONArray("blocks") ?: return
        val n = minOf(blocks.length(), 14)
        var sel by remember { mutableIntStateOf(-1) }
        val hMax = (0 until n).maxOfOrNull {
            blocks.optJSONObject(it)?.optDouble("h", 1.0) ?: 1.0 }?.coerceAtLeast(1.0) ?: 1.0

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    // 按 (x+y) 排序：等距投影下远的先画
                    val sorted = (0 until n).sortedBy {
                        (blocks.optJSONObject(it)?.optDouble("x", 0.0) ?: 0.0) +
                        (blocks.optJSONObject(it)?.optDouble("y", 0.0) ?: 0.0) }
                    sorted.forEach { i ->
                        val b = blocks.optJSONObject(i) ?: return@forEach
                        val gx = b.optDouble("x", 0.0); val gy = b.optDouble("y", 0.0)
                        val h = b.optDouble("h", 1.0)
                        val W = 34f; val H = 17f
                        val cx = size.width / 2 + ((gx - gy) * W / 2).toFloat()
                        val cy = size.height * .68f + ((gx + gy) * H / 2).toFloat()
                        val hh = ((h / hMax) * 52f).toFloat()
                        val col = PAL[i % PAL.size]
                        val on = sel == i
                        // 顶面
                        val top = Path().apply {
                            moveTo(cx, cy - hh - H); lineTo(cx + W, cy - hh)
                            lineTo(cx, cy - hh + H); lineTo(cx - W, cy - hh); close() }
                        drawPath(top, col.copy(if (on) 1f else .85f))
                        // 左面
                        val left = Path().apply {
                            moveTo(cx - W, cy - hh); lineTo(cx, cy - hh + H)
                            lineTo(cx, cy + H); lineTo(cx - W, cy); close() }
                        drawPath(left, col.copy(if (on) .7f else .55f))
                        // 右面
                        val right = Path().apply {
                            moveTo(cx + W, cy - hh); lineTo(cx, cy - hh + H)
                            lineTo(cx, cy + H); lineTo(cx + W, cy); close() }
                        drawPath(right, col.copy(if (on) .45f else .35f))
                        if (on) {
                            drawPath(top, ZxT.Text, style = Stroke(1.5f))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to blocks.optJSONObject(i)?.optString("n", "$i"),
                                "h" to (blocks.optJSONObject(i)?.optDouble("h", 1.0)
                                    ?: 1.0).toString(), "source" to "zx_isometric"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                if (sel >= 0) {
                    val b = blocks.optJSONObject(sel)
                    Text(b?.optString("n", "") ?: "", fontSize = 10.5.sp, color = ZxT.Text)
                    Spacer(Modifier.width(6.dp))
                    Text("x=${b?.optDouble("x", 0.0)} y=${b?.optDouble("y", 0.0)} " +
                         "h=${b?.optDouble("h", 1.0)}", fontSize = 9.5.sp,
                         color = ZxT.Text3, fontFamily = ZxT.Mono)
                } else Text("点方块", fontSize = 10.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                Text("按 x+y 排序，远的先画", fontSize = 9.sp, color = ZxT.Text3)
            }
        }
    }

    /* ══════════ zx_spectrum 频谱 ══════════ */
    @Composable
    fun spectrum(o: JSONObject, ctx: RenderContext) {
        val bands = o.optJSONArray("bands") ?: return
        val n = minOf(bands.length(), 32)
        // 动效必须能冻结——否则抓不到某一帧
        var frozen by remember { mutableStateOf(o.optBoolean("frozen", false)) }
        var sel by remember { mutableIntStateOf(-1) }
        var phase by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(frozen) {
            if (!frozen) while (true) {
                phase += .12f
                kotlinx.coroutines.delay(60)
            }
        }
        val base = (0 until n).map { bands.optDouble(it, .3) }
        val shown = base.mapIndexed { i, v ->
            if (frozen) v
            else (v * (.72f + .28f * sin((phase + i * .5f).toDouble())))
                .toFloat().coerceIn(0f, 1f).toDouble()
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "频谱"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (frozen) "▶ 继续" else "⏸ 冻结", fontSize = 10.sp,
                     color = ZxT.PriLite,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(ZxT.Surface2)
                         .clickable {
                             frozen = !frozen
                             ctx.callTool("zx_select", mapOf("frozen" to frozen.toString(),
                                                             "source" to "zx_spectrum"))
                         }.padding(8.dp, 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(84.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val bw = size.width / n
                    shown.forEachIndexed { i, v ->
                        // 频段分条：一格一条，不是连续曲线——频谱的特征就是离散频段
                        val cnt = (v * 14).toInt().coerceIn(0, 14)
                        for (k in 0 until cnt) {
                            val yy = size.height - k * (size.height / 15f)
                            val col = when {
                                k > 11 -> ZxT.Err
                                k > 8 -> ZxT.Warn
                                else -> PAL[1]
                            }
                            drawRect(if (sel == i) ZxT.Text else col.copy(.45f + k * .04f),
                                     Offset(i * bw + bw * .12f, yy - size.height / 17f),
                                     Size(bw * .76f, size.height / 21f))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(84.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "band" to i.toString(),
                                "v" to base[i].toString(), "source" to "zx_spectrum"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (sel >= 0) "频段 $sel：%.2f".format(base[sel])
                     else "点频段看静态值",
                     fontSize = 10.sp, color = if (sel >= 0) ZxT.PriLite else ZxT.Text3)
                Spacer(Modifier.weight(1f))
                Text(if (frozen) "已冻结" else "实时", fontSize = 9.sp,
                     color = if (frozen) ZxT.Warn else ZxT.Text3)
            }
            Text("点的是静态值不是动画值——冻结后回传才有意义。一格一条而非连续曲线，" +
                 "频谱的特征就是离散频段",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_bezier 曲线编辑器 ══════════ */
    @Composable
    fun bezier(o: JSONObject, ctx: RenderContext) {
        val p1x0 = o.optDouble("p1x", .25); val p1y0 = o.optDouble("p1y", .1)
        val p2x0 = o.optDouble("p2x", .25); val p2y0 = o.optDouble("p2y", 1.0)
        var p1 by remember { mutableStateOf(Offset(p1x0.toFloat(), p1y0.toFloat())) }
        var p2 by remember { mutableStateOf(Offset(p2x0.toFloat(), p2y0.toFloat())) }
        var drag by remember { mutableIntStateOf(-1) }
        val presets = o.optJSONArray("presets")

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(140.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    fun px(o: Offset) = Offset(o.x * w, h - o.y * h)
                    val a = Offset(0f, 0f); val d = Offset(1f, 1f)
                    val c1 = px(p1); val c2 = px(p2)
                    val pa = px(a); val pd = px(d)
                    // 控制柄：没有它就不知道拖哪个点会怎么变
                    drawLine(ZxT.Text3, pa, c1, 1f)
                    drawLine(ZxT.Text3, pd, c2, 1f)
                    val path = Path().apply {
                        moveTo(pa.x, pa.y)
                        cubicTo(c1.x, c1.y, c2.x, c2.y, pd.x, pd.y)
                    }
                    drawPath(path, PAL[0], style = Stroke(2.4f))
                    drawCircle(if (drag == 1) ZxT.Text else PAL[3], 7f, c1)
                    drawCircle(if (drag == 2) ZxT.Text else PAL[3], 7f, c2)
                }
                // 拖拽区：左半拖 p1，右半拖 p2
                Row(Modifier.fillMaxWidth().height(140.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { drag = 1 },
                                onDragEnd = { drag = -1 },
                                onDrag = { _, ch ->
                                    p1 = Offset((p1.x + ch.x / size.width).coerceIn(0f, 1f),
                                                (p1.y - ch.y / size.height).coerceIn(-.5f, 1.5f))
                                })
                        })
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { drag = 2 },
                                onDragEnd = { drag = -1 },
                                onDrag = { _, ch ->
                                    p2 = Offset((p2.x + ch.x / size.width).coerceIn(0f, 1f),
                                                (p2.y - ch.y / size.height).coerceIn(-.5f, 1.5f))
                                })
                        })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("cubic-bezier(%.2f, %.2f, %.2f, %.2f)"
                    .format(p1.x, p1.y, p2.x, p2.y),
                     fontSize = 10.5.sp, color = ZxT.PriLite, fontFamily = ZxT.Mono,
                     modifier = Modifier.weight(1f))
                Text("复制", fontSize = 10.sp, color = ZxT.PriLite,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(ZxT.Surface2)
                         .clickable {
                             ctx.callTool("zx_select", mapOf(
                                 "css" to "cubic-bezier(%.2f, %.2f, %.2f, %.2f)"
                                     .format(p1.x, p1.y, p2.x, p2.y),
                                 "source" to "zx_bezier"))
                         }.padding(8.dp, 4.dp))
            }
            if (presets != null && presets.length() > 0) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (i in 0 until presets.length()) {
                        val p = presets.optJSONObject(i) ?: continue
                        Text(p.optString("n", ""), fontSize = 10.sp, color = ZxT.Text3,
                             modifier = Modifier.clip(RoundedCornerShape(9.dp))
                                 .background(ZxT.Surface2)
                                 .clickable {
                                     p1 = Offset(p.optDouble("p1x", .25).toFloat(),
                                                 p.optDouble("p1y", .1).toFloat())
                                     p2 = Offset(p.optDouble("p2x", .25).toFloat(),
                                                 p.optDouble("p2y", 1.0).toFloat())
                                     ctx.callTool("zx_select", mapOf(
                                         "preset" to p.optString("n", ""),
                                         "source" to "zx_bezier"))
                                 }.padding(8.dp, 4.dp))
                    }
                }
            }
            Text("控制柄必须画出来——没有它就不知道拖哪个点会怎么变。" +
                 "y 允许超出 0-1（回弹），但 x 必须夹在 0-1，否则不是函数",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }
}

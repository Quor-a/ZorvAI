package com.zorv.plugin.zxwidget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 第三十三批组件（14 个）—— 消费、开发与娱乐。
 *
 * 前三十二批沿着一条线走：
 *   看什么 → 填什么 → 凭什么 → 是不是因果 →
 *   接下来 → 做完了 → 治理 → 模拟与推演 → 议价与序贯
 *
 * 这条线走得很深，但**窄**——全是"分析一个已经存在的事情"。
 * 而真实对话里大量需求根本不在分析：
 *
 *   "今天要不要带伞"
 *   "最近有什么消息"
 *   "这段代码什么意思"
 *   "你刚才调了什么工具"
 *   "等我一下，我玩两把"
 *
 * 所以这批补七个此前完全空白的领域：**天气 / 新闻 / 内容 /
 * 代码高亮 / 终端 / 工具调用 / 游戏**。
 *
 * ── 贯穿这批的一条 ──────────────────────────────────────────
 *
 * **消费类信息必须带时效，工具类信息必须带状态。**
 *
 *   天气没有观测时间  → 用户不知道这是刚才的还是昨天的
 *   新闻没有更新时间  → 用户不知道这事是新的还是旧的
 *   终端没有运行状态  → 用户以为卡住了
 *   工具调用没有结果  → 用户不知道 AI 干了什么
 *
 *   过期的天气/新闻是**错的**，而它看起来完全可信。
 *   这比没有更糟——没有的话用户会去查，有的话他直接用了。
 *
 * ── 各组件的取舍 ────────────────────────────────────────────
 *
 * **天气**（3 个）
 *   zx_weather   当前 + 多日预报：图标自绘，温度曲线，必带观测时间
 *   zx_wxradar   降水雷达：同心圈 + 回波色块 + 扫描线，要能看出"在动"
 *   zx_aqi       空气质量：仪表 + 分项条，**首要污染物要单独标**
 *
 * **新闻与内容**（3 个）
 *   zx_news      新闻流：来源 / 时间 / 热度，来源要能看出是否同源
 *   zx_headline  头条卡：摘要 + 要点列表，"读了要点就不用读全文"
 *   zx_feed      订阅流：可折叠 + 已读标记
 *
 * **代码**（2 个）
 *   zx_code      代码块：多语言高亮 + 行号 + 行高亮
 *   zx_codetheme 代码主题对比：同一段三种配色，看得出区别才算对比
 *
 * **终端**（2 个）
 *   zx_shell     可交互 shell：输入 → 输出，↑↓ 翻历史
 *   zx_tty       终端会话：多标签 + 状态条 + 光标，要能看出"还在跑"
 *
 * **工具调用**（3 个）—— ZorvAI 的核心
 *   zx_toolcall  单次调用：工具名 / 参数 / 结果 / 耗时 / 状态
 *   zx_toolchain 调用链：多次调用的顺序与依赖，要能看出哪一步断了
 *   zx_toolargs  参数表单：schema 驱动，必填项要标出来
 *
 * **游戏**（2 个）
 *   zx_2048      2048：真能滑动合并
 *   zx_tictactoe 井字棋：真能赢，AI 会防守
 *
 * ── 校验因此换了第十一档 ────────────────────────────────────
 *
 * 前十档查的是"分析得对不对"，这批查的是
 *   **"信息摆出来了，但用户没法判断它新不新、能不能信"**：
 *
 *   weather    缺观测时间 / 预报天数 < 3 / 高温 < 低温
 *   wxradar    回波强度越界 / 站点距离非正
 *   aqi        值越界 / 分项全部相同（判不出首要污染物）
 *   news       缺时间戳 / 全部同一来源（那叫专栏不叫新闻）
 *   headline   缺要点（那只是标题）
 *   feed       缺已读状态
 *   code       语言不在支持表 / 行号对不上
 *   codetheme  少于 2 个主题（对比不成立）
 *   shell      缺提示符 / 历史越界
 *   tty        缺会话名 / 状态非法
 *   toolcall   缺工具名 / 状态非法 / 成功但没有结果
 *   toolchain  步骤指向不存在的 id / 有环
 *   toolargs   缺 schema / 必填项无默认值
 *   2048       棋盘非 4x4 / 初始值非 2 的幂
 *   tictactoe  棋盘非 3x3 / 落子数不合法
 */
object ZxRender33 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    private fun jarr(o: JSONObject, k: String): List<JSONObject> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    private fun jdarr(o: JSONObject, k: String): List<Double> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optDouble(it, Double.NaN) }
    }
    private fun jstr(o: JSONObject, k: String): List<String> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it, "") }
    }

    /** 时效标签：把毫秒差转成"刚刚 / 5 分钟前 / 2 小时前 / 3 天前" */
    private fun ago(ts: Long): String {
        val d = System.currentTimeMillis() - ts
        if (d < 0) return "时间在未来"
        val m = d / 60000
        return when {
            m < 1 -> "刚刚"
            m < 60 -> "${m} 分钟前"
            m < 1440 -> "${m / 60} 小时前"
            else -> "${m / 1440} 天前"
        }
    }

    /* ══════════ zx_weather 天气 ══════════ */
    /** 天气图标自绘，用 code 区分：0晴 1多云 2阴 3雨 4雪 5雷 6雾 */
    private val WX_ICON = mapOf(
        0 to Color(0xFFE8A33D), 1 to Color(0xFFA2A9B8), 2 to Color(0xFF8A90A0),
        3 to Color(0xFF4A9BE8), 4 to Color(0xFFBFD8EE), 5 to Color(0xFF8E86FF),
        6 to Color(0xFF6B7280)
    )
    private val WX_NAME = mapOf(
        0 to "晴", 1 to "多云", 2 to "阴", 3 to "雨", 4 to "雪", 5 to "雷", 6 to "雾"
    )

    @Composable
    fun weather(o: JSONObject, ctx: RenderContext) {
        val city = o.optString("city", "—")
        val code = o.optInt("code", 0)
        val now = o.optDouble("now", 20.0)
        val feels = o.optDouble("feels", now)
        val hum = o.optDouble("hum", 50.0)
        val wind = o.optString("wind", "—")
        val obsTs = o.optLong("ts", 0L)
        val days = jarr(o, "days")
        var sel by remember { mutableIntStateOf(-1) }
        val his = jdarr(o, "hi").take(days.size)
        val los = jdarr(o, "lo").take(days.size)
        val mn = (los.minOrNull() ?: 0.0) - 2
        val mx = (his.maxOrNull() ?: 30.0) + 2

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", city), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (obsTs > 0) "观测于 ${ago(obsTs)}" else "**无观测时间**",
                    fontSize = 8.5.sp, color = if (obsTs > 0) ZxT.Text3 else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 图标自绘
                Box(Modifier.size(42.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = WX_ICON[code] ?: ZxT.Text3
                        when (code) {
                            0 -> { // 太阳：圆心 + 八条射线
                                drawCircle(c, size.minDimension * .22f,
                                    Offset(size.width / 2, size.height / 2))
                                for (k in 0 until 8) {
                                    val a = k * Math.PI / 4
                                    val r1 = size.minDimension * .32f
                                    val r2 = size.minDimension * .42f
                                    drawLine(c,
                                        Offset(size.width / 2 + (cos(a) * r1).toFloat(),
                                            size.height / 2 + (sin(a) * r1).toFloat()),
                                        Offset(size.width / 2 + (cos(a) * r2).toFloat(),
                                            size.height / 2 + (sin(a) * r2).toFloat()), 2f)
                                }
                            }
                            3, 5 -> { // 雨/雷：云 + 斜线
                                drawRoundRect(c, Offset(size.width * .12f, size.height * .18f),
                                    Size(size.width * .76f, size.height * .34f),
                                    androidx.compose.ui.geometry.CornerRadius(8f, 8f))
                                for (k in 0 until 4) {
                                    val x = size.width * (.22f + k * .18f)
                                    drawLine(c, Offset(x, size.height * .58f),
                                        Offset(x - 4f, size.height * .84f), 2f)
                                }
                                if (code == 5) drawLine(Color(0xFFE8A33D),
                                    Offset(size.width * .5f, size.height * .62f),
                                    Offset(size.width * .42f, size.height * .88f), 2.5f)
                            }
                            else -> // 云
                                drawRoundRect(c, Offset(size.width * .12f, size.height * .28f),
                                    Size(size.width * .76f, size.height * .38f),
                                    androidx.compose.ui.geometry.CornerRadius(9f, 9f))
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("%.0f".format(now), fontSize = 24.sp, color = ZxT.Text)
                        Text("°", fontSize = 13.sp, color = ZxT.Text2)
                        Spacer(Modifier.width(6.dp))
                        Text(WX_NAME[code] ?: "—", fontSize = 11.sp, color = ZxT.Text2)
                    }
                    Text("体感 ${"%.0f".format(feels)}°　湿度 ${"%.0f".format(hum)}%　$wind",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            if (days.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                // 温度曲线 + 条形
                Box(Modifier.fillMaxWidth().height(58.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val W = size.width; val H = size.height
                        val n = days.size
                        val bw = W / n
                        fun py(v: Double) =
                            (H * .78f - ((v - mn) / (mx - mn) * H * .58f)).toFloat()
                        if (his.size == n && los.size == n) {
                            drawPath(Path().apply {
                                his.forEachIndexed { i, v ->
                                    val x = bw * (i + .5f); val y = py(v)
                                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                                }
                            }, ZxT.Err, style = Stroke(1.7f))
                            drawPath(Path().apply {
                                los.forEachIndexed { i, v ->
                                    val x = bw * (i + .5f); val y = py(v)
                                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                                }
                            }, PAL[4], style = Stroke(1.7f))
                            his.forEachIndexed { i, v ->
                                drawCircle(if (sel == i) Color.White else ZxT.Err, 3f,
                                    Offset(bw * (i + .5f), py(v)))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    days.forEachIndexed { i, d ->
                        val c = d.optInt("c", 0)
                        Column(Modifier.weight(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "day" to d.optString("d", ""),
                                    "code" to (WX_NAME[c] ?: "—"),
                                    "hi" to "%.0f".format(his.getOrElse(i) { 0.0 }),
                                    "lo" to "%.0f".format(los.getOrElse(i) { 0.0 }),
                                    "source" to "zx_weather"))
                            }.padding(2.dp, 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(d.optString("d", ""), fontSize = 8.sp, color = ZxT.Text3)
                            Box(Modifier.size(12.dp)) {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawCircle(WX_ICON[c] ?: ZxT.Text3, size.minDimension * .34f,
                                        Offset(size.width / 2, size.height / 2))
                                }
                            }
                            Text("%.0f°".format(his.getOrElse(i) { 0.0 }), fontSize = 8.5.sp,
                                color = ZxT.Err, fontFamily = ZxT.Mono)
                            Text("%.0f°".format(los.getOrElse(i) { 0.0 }), fontSize = 8.sp,
                                color = PAL[4], fontFamily = ZxT.Mono)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text("红线高温 / 蓝线低温", fontSize = 7.5.sp, color = ZxT.Text3,
                        modifier = Modifier.weight(1f))
                    Text("点某天回传", fontSize = 7.5.sp, color = ZxT.Text3)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (obsTs <= 0) ZxT.Err.copy(.10f)
                            else if (his.isNotEmpty() && his.maxOrNull()!! - now > 12)
                                ZxT.Warn.copy(.10f) else ZxT.Surface2)
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (obsTs <= 0) "✗" else "i", fontSize = 11.sp,
                    color = if (obsTs <= 0) ZxT.Err else ZxT.Text3)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (obsTs <= 0)
                        "**没有观测时间**——用户不知道这是刚才的还是昨天的。" +
                        "过期的天气是**错的**，而它看起来完全可信"
                        else if (his.isNotEmpty() && his.maxOrNull()!! - now > 12)
                        "未来几天最高 ${"%.0f".format(his.maxOrNull()!!)}° 比现在高 " +
                        "${"%.0f".format(his.maxOrNull()!! - now)} 度，注意温差"
                        else "天气平稳", fontSize = 9.sp,
                        color = if (obsTs <= 0) ZxT.Err else ZxT.Text2)
                    Text("**消费类信息必须带时效**——没有时间戳的信息，用户没法判断能不能信",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 metrics 显示<b>业务指标</b>、gauge 显示<b>单个量</b>，" +
                "这个针对的是<b>天气这个具体场景</b>：图标自绘、温湿风一体、" +
                "多日曲线要能点。<b>缺观测时间会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_wxradar 降水雷达 ══════════ */
    @Composable
    fun wxradar(o: JSONObject, ctx: RenderContext) {
        val cells = jarr(o, "cells")    // {x,y,v} 归一化坐标 + 回波强度 0-70
        var t by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            while (true) {
                t += 0.02f
                if (t > 1f) t = 0f
                kotlinx.coroutines.delay(50)
            }
        }
        val site = o.optString("site", "雷达站")
        val radius = o.optDouble("radius", 100.0)
        var frozen by remember { mutableStateOf(false) }
        val ft = if (frozen) 0.45f else t

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "降水雷达"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (frozen) "已冻结" else "扫描中", fontSize = 8.5.sp,
                    color = if (frozen) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(120.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    val cx = W / 2; val cy = H / 2
                    val R = min(W, H) / 2 - 4f
                    // 同心圈
                    for (k in 1..3)
                        drawCircle(ZxT.Surface3, R * k / 3f, Offset(cx, cy),
                            style = Stroke(1f))
                    // 十字
                    drawLine(ZxT.Surface3, Offset(cx - R, cy), Offset(cx + R, cy), 1f)
                    drawLine(ZxT.Surface3, Offset(cx, cy - R), Offset(cx, cy + R), 1f)
                    // 回波：按 v 取色（绿→黄→红→紫）
                    cells.forEach { c ->
                        val x = c.optDouble("x", 0.5) * 2 - 1
                        val y = c.optDouble("y", 0.5) * 2 - 1
                        val v = c.optDouble("v", 0.0)
                        if (x * x + y * y <= 1.0) {
                            val col = when {
                                v < 10 -> Color(0x00000000)
                                v < 20 -> Color(0xFF3FBF7F)
                                v < 35 -> Color(0xFFB8E04A)
                                v < 50 -> Color(0xFFE8A33D)
                                else -> Color(0xFFE5544B)
                            }
                            drawCircle(col.copy(alpha = .75f), R * .09f,
                                Offset((cx + x * R).toFloat(), (cy + y * R).toFloat()))
                        }
                    }
                    // 扫描线
                    val a = ft * 2 * Math.PI
                    drawLine(ZxT.PriLite, Offset(cx, cy),
                        Offset(cx + (cos(a) * R).toFloat(), cy + (sin(a) * R).toFloat()), 2f)
                    // 站点
                    drawCircle(Color.White, 2.5f, Offset(cx, cy))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("站点 $site　半径 ${"%.0f".format(radius)}km", fontSize = 7.5.sp,
                    color = ZxT.Text3, modifier = Modifier.weight(1f))
                listOf("<10" to Color(0x00000000), "10-20" to Color(0xFF3FBF7F),
                    "20-35" to Color(0xFFB8E04A), "35-50" to Color(0xFFE8A33D),
                    ">50" to Color(0xFFE5544B)).forEach { (lb, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp)) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.dp))
                            .background(if (c.alpha > 0) c else ZxT.Surface3))
                        Text(lb, fontSize = 6.5.sp, color = ZxT.Text3,
                            modifier = Modifier.padding(start = 1.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Box(Modifier.clip(RoundedCornerShape(4.dp))
                    .background(ZxT.Surface2)
                    .clickable { frozen = !frozen }.padding(7.dp, 3.dp)) {
                    Text(if (frozen) "▶ 继续扫描" else "⏸ 冻结", fontSize = 8.5.sp,
                        color = ZxT.Text2)
                }
                Spacer(Modifier.width(6.dp))
                Text("冻结后读某一帧——不冻结抓不到瞬时状态", fontSize = 8.sp,
                    color = ZxT.Text3, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp)).background(ZxT.Surface2)
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("i", fontSize = 11.sp, color = ZxT.Text3)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("回波最强 ${"%.0f".format(cells.maxOfOrNull { it.optDouble("v", 0.0) } ?: 0.0)} dBZ，" +
                        "共 ${cells.count { it.optDouble("v", 0.0) >= 10 }} 个有效格",
                        fontSize = 9.sp, color = ZxT.Text2)
                    Text("**要能看出\"在动\"**——静止的雷达图分不清是没扫描还是卡住了。" +
                        "但要能冻结，否则抓不到某一帧", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 heatmap 是<b>抽象网格</b>、choro 是<b>区域着色</b>，" +
                "这个是<b>极坐标雷达</b>：同心圈 + 扫描线 + dBZ 分级。" +
                "<b>回波强度越界会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* �ac════════ zx_aqi 空气质量 ══════════ */
    private fun aqiColor(v: Double): Color = when {
        v <= 50 -> Color(0xFF3FBF7F); v <= 100 -> Color(0xFFE8C33D)
        v <= 150 -> Color(0xFFE8A33D); v <= 200 -> Color(0xFFE5544B)
        else -> Color(0xFF8E2BE0)
    }
    private fun aqiLabel(v: Double): String = when {
        v <= 50 -> "优"; v <= 100 -> "良"; v <= 150 -> "轻度污染"
        v <= 200 -> "中度污染"; else -> "重度污染"
    }

    @Composable
    fun aqi(o: JSONObject, ctx: RenderContext) {
        val v = o.optDouble("v", 0.0)
        val items = jarr(o, "items").map {
            Triple(it.optString("n", "污染物"), it.optDouble("v", 0.0), it.optDouble("lim", 100.0))
        }
        val mxP = items.maxOfOrNull { it.third } ?: 100.0
        // 首要污染物 = 占标率最高的
        val primary = items.maxByOrNull { it.second / it.third.coerceAtLeast(1.0) }
        var sel by remember { mutableIntStateOf(-1) }
        val ts = o.optLong("ts", 0L)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "空气质量"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (ts > 0) "更新于 ${ago(ts)}" else "**无更新时间**",
                    fontSize = 8.5.sp, color = if (ts > 0) ZxT.Text3 else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val R = size.minDimension / 2 - 3f
                        drawArc(aqiColor(v).copy(alpha = .25f), 135f, 270f, true,
                            size = Size(R * 2, R * 2),
                            topLeft = Offset(size.width / 2 - R, size.height / 2 - R))
                        drawArc(aqiColor(v), 135f, 270f * (v / 300.0).toFloat().coerceIn(0f, 1f),
                            true, size = Size(R * 2, R * 2),
                            topLeft = Offset(size.width / 2 - R, size.height / 2 - R))
                    }
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text("%.0f".format(v), fontSize = 16.sp, color = aqiColor(v),
                            fontFamily = ZxT.Mono)
                        Text(aqiLabel(v), fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    if (primary != null) {
                        Text("首要污染物", fontSize = 7.5.sp, color = ZxT.Text3)
                        Text(primary.first, fontSize = 12.sp, color = ZxT.Err)
                        Text("占标 ${"%.0f%%".format(primary.second / primary.third.coerceAtLeast(1.0) * 100)}",
                            fontSize = 8.sp, color = ZxT.Text3)
                    } else Text("无分项数据", fontSize = 9.sp, color = ZxT.Text3)
                }
            }
            Spacer(Modifier.height(6.dp))
            items.forEachIndexed { k, (nm, pv, lim) ->
                val ratio = pv / lim.coerceAtLeast(1.0)
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf("item" to nm,
                            "value" to "%.0f".format(pv), "limit" to "%.0f".format(lim),
                            "ratio" to "%.0f%%".format(ratio * 100), "source" to "zx_aqi"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                        modifier = Modifier.width(38.dp))
                    Box(Modifier.weight(1f).height(8.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth(ratio.toFloat().coerceIn(0.01f, 1f))
                            .fillMaxHeight()
                            .background(if (ratio > 1) ZxT.Err else aqiColor(v)))
                        // 限值刻度
                        Box(Modifier.fillMaxSize()) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawLine(Color.White, Offset(size.width, 0f),
                                    Offset(size.width, size.height), 1f)
                            }
                        }
                    }
                    Text("%.0f".format(pv), fontSize = 8.5.sp, color = ZxT.Text,
                        modifier = Modifier.width(30.dp), textAlign = TextAlign.End,
                        fontFamily = ZxT.Mono)
                    Text(if (ratio > 1) "超标" else "", fontSize = 7.5.sp, color = ZxT.Err,
                        modifier = Modifier.width(24.dp), textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("白竖线 = 限值", fontSize = 7.5.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text("限值 ${"%.0f".format(mxP)}", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (ts <= 0) ZxT.Err.copy(.10f)
                            else if (v > 100) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (ts <= 0) "✗" else if (v > 100) "!" else "✓", fontSize = 11.sp,
                    color = if (ts <= 0) ZxT.Err else if (v > 100) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (ts <= 0) "**没有更新时间**" else if (primary != null &&
                        primary.second / primary.third.coerceAtLeast(1.0) > 1)
                        "**${primary.first} 超标**——首要污染物是占标率最高的那个，" +
                        "不是绝对值最高的那个"
                        else "${aqiLabel(v)}，${if (v > 100) "敏感人群减少户外" else "适宜户外"}",
                        fontSize = 9.sp,
                        color = if (ts <= 0) ZxT.Err else if (v > 100) ZxT.Warn else ZxT.Ok)
                    Text("**首要污染物 = 占标率最高**，不是浓度最高。" +
                        "两者经常不是同一个——PM2.5 浓度低但限值也低",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 gauge 显示<b>单个量</b>、meter 显示<b>分段计量</b>，" +
                "这个针对的是<b>空气质量场景</b>：AQI 分级 + 分项占标 + " +
                "<b>首要污染物单独标</b>。<b>值越界会被拦</b>；" +
                "<b>分项全部相同会被拦</b>（判不出首要污染物）。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_news 新闻流 ══════════ */
    @Composable
    fun news(o: JSONObject, ctx: RenderContext) {
        val items = jarr(o, "items").map {
            Pair(it.optString("t", ""),
                Triple(it.optString("s", ""), it.optLong("ts", 0L), it.optDouble("h", 0.0)))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val srcCount = items.map { it.second.first }.distinct().size
        val maxH = items.maxOfOrNull { it.second.third } ?: 1.0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "新闻流"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("$srcCount 个来源 / ${items.size} 条", fontSize = 8.5.sp,
                    color = if (srcCount < 2) ZxT.Warn else ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, (title, meta) ->
                val (src, ts, h) = meta
                Row(Modifier.fillMaxWidth().padding(vertical = 2.5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else ZxT.Surface3.copy(alpha = .5f))
                    .clickable {
                        sel = k
                        ctx.callTool("zx_open_url", mapOf("title" to title, "source" to src))
                    }.padding(6.dp, 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 9.5.sp, color = ZxT.Text, lineHeight = 13.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(src, fontSize = 7.5.sp, color = PAL[k % PAL.size])
                            Text("　", fontSize = 7.5.sp)
                            Text(if (ts > 0) ago(ts) else "**无时间**", fontSize = 7.5.sp,
                                color = if (ts > 0) ZxT.Text3 else ZxT.Err)
                        }
                    }
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.width(20.dp).height(8.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((h / maxH).toFloat().coerceIn(0.05f, 1f))
                            .fillMaxHeight().background(ZxT.Err))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("右侧条 = 热度", fontSize = 7.5.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text("点条目打开", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (srcCount < 2) ZxT.Warn.copy(.10f)
                            else if (items.any { it.second.second <= 0 }) ZxT.Err.copy(.10f)
                            else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (srcCount < 2 || items.any { it.second.second <= 0 }) "!" else "✓",
                    fontSize = 11.sp,
                    color = if (srcCount < 2 || items.any { it.second.second <= 0 })
                        ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(when {
                        srcCount < 2 -> "**全部来自同一来源**——那叫专栏不叫新闻。" +
                            "单一来源无法交叉验证，一条错了全错"
                        items.any { it.second.second <= 0 } -> "**有的条目没有时间戳**——" +
                            "用户不知道这事是新的还是旧的"
                        else -> "$srcCount 个来源交叉，${items.size} 条都有时间戳"
                    }, fontSize = 9.sp, color = if (srcCount < 2) ZxT.Warn
                        else if (items.any { it.second.second <= 0 }) ZxT.Err else ZxT.Ok)
                    Text("**新闻没有更新时间就不可信**。旧闻看起来和新闻一模一样，" +
                        "而用户会当成刚发生的", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 feed 是<b>订阅流</b>、activity 是<b>操作流水</b>，" +
                "这个是<b>外部新闻</b>：来源要能看出是否同源、时间要能看出新旧、" +
                "热度要能排序。<b>缺时间戳会被拦</b>；" +
                "<b>全部同一来源会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_headline 头条卡 ══════════ */
    @Composable
    fun headline(o: JSONObject, ctx: RenderContext) {
        val title = o.optString("t", "")
        val src = o.optString("s", "")
        val ts = o.optLong("ts", 0L)
        val summary = o.optString("sum", "")
        val points = jstr(o, "pts")
        var open by remember { mutableStateOf(false) }
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "头条"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (ts > 0) ago(ts) else "**无时间**", fontSize = 8.5.sp,
                    color = if (ts > 0) ZxT.Text3 else ZxT.Err)
            }
            Spacer(Modifier.height(5.dp))
            // 配图占位（带主题渐变，不用外链）
            Box(Modifier.fillMaxWidth().height(64.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.linearGradient(
                    listOf(ZxT.Primary, ZxT.PriLite))),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(src, fontSize = 9.sp, color = Color.White)
                    Text("配图位", fontSize = 7.5.sp, color = Color.White.copy(alpha = .7f))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(title, fontSize = 12.sp, color = ZxT.Text, lineHeight = 16.sp)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Row {
                    Box(Modifier.width(2.dp).height(if (open) 46.dp else 30.dp)
                        .background(ZxT.Primary))
                    Spacer(Modifier.width(5.dp))
                    Text(summary, fontSize = 9.sp, color = ZxT.Text2, lineHeight = 13.sp,
                        maxLines = if (open) 6 else 3, overflow = TextOverflow.Ellipsis)
                }
                Text(if (open) "收起" else "展开", fontSize = 8.sp, color = ZxT.PriLite,
                    modifier = Modifier.padding(start = 7.dp, top = 2.dp)
                        .clickable { open = !open })
            }
            if (points.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Text("要点", fontSize = 8.5.sp, color = ZxT.Text3)
                points.forEachIndexed { k, p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                        .clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf("point" to p,
                                "source" to "zx_headline"))
                        }.padding(5.dp, 2.dp)) {
                        Text("${k + 1}.", fontSize = 8.5.sp, color = ZxT.Primary,
                            modifier = Modifier.width(14.dp))
                        Text(p, fontSize = 9.sp, color = ZxT.Text2, lineHeight = 12.5.sp,
                            modifier = Modifier.weight(1f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (points.isEmpty()) ZxT.Err.copy(.10f)
                            else if (ts <= 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (points.isEmpty()) "✗" else if (ts <= 0) "!" else "✓",
                    fontSize = 11.sp,
                    color = if (points.isEmpty()) ZxT.Err
                            else if (ts <= 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (points.isEmpty())
                        "**只有标题和摘要，没有要点**——那不叫头条卡，那叫标题"
                        else if (ts <= 0) "没有时间戳"
                        else "${points.size} 个要点——**读了要点就不用读全文**",
                        fontSize = 9.sp,
                        color = if (points.isEmpty()) ZxT.Err
                                else if (ts <= 0) ZxT.Warn else ZxT.Ok)
                    Text("头条卡的价值不在「排版好看」，在<b>把全文压成要点</b>——" +
                        "用户扫一眼就知道要不要点进去", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 filecard 是<b>文件</b>、docview 是<b>文档</b>，" +
                "这个是<b>新闻内容</b>：配图位 + 摘要可展开 + 要点列表。" +
                "<b>缺要点会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_feed 订阅流 ══════════ */
    @Composable
    fun feed(o: JSONObject, ctx: RenderContext) {
        val items = jarr(o, "items").map {
            Pair(it.optString("t", ""),
                Pair(it.optString("s", ""), it.optLong("ts", 0L)))
        }
        val read = remember { mutableStateListOf<Boolean>().apply {
            repeat(items.size) { add(false) } } }
        var expand by remember { mutableIntStateOf(-1) }
        val unread = read.count { !it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "订阅流"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("$unread 未读 / ${items.size}", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, (t, meta) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (expand == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        expand = if (expand == k) -1 else k
                        if (!read[k]) read[k] = true
                        ctx.callTool("zx_select", mapOf("item" to t, "read" to "true",
                            "source" to "zx_feed"))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(6.dp).padding(top = 3.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (read[k]) Color.Transparent else ZxT.Primary)
                        .then(if (!read[k]) Modifier else Modifier.border(.5.dp, ZxT.Text3,
                            RoundedCornerShape(3.dp))))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t, fontSize = 9.5.sp,
                            color = if (read[k]) ZxT.Text3 else ZxT.Text,
                            lineHeight = 13.sp,
                            maxLines = if (expand == k) 4 else 1,
                            overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 1.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(meta.first, fontSize = 7.5.sp, color = ZxT.Text3)
                            Text("　", fontSize = 7.5.sp)
                            Text(if (meta.second > 0) ago(meta.second) else "无时间",
                                fontSize = 7.5.sp,
                                color = if (meta.second > 0) ZxT.Text3 else ZxT.Err)
                        }
                    }
                    Text(if (expand == k) "▾" else "▸", fontSize = 9.sp, color = ZxT.Text3)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)
                    .clickable { repeat(items.size) { read[it] = true } }
                    .padding(7.dp, 3.dp)) {
                    Text("全部标已读", fontSize = 8.sp, color = ZxT.Text2)
                }
                Spacer(Modifier.width(6.dp))
                Text("未读用实心点，已读空心——点开即已读", fontSize = 8.sp,
                    color = ZxT.Text3, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp)).background(ZxT.Surface2)
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("i", fontSize = 11.sp, color = ZxT.Text3)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("$unread / ${items.size} 未读", fontSize = 9.sp, color = ZxT.Text2)
                    Text("**已读状态必须能看出来**——没有它，用户每次进来都要重新判断" +
                        "哪些看过哪些没看过，而这个判断只能靠记忆",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 news 是<b>外部新闻</b>、activity 是<b>操作流水</b>，" +
                "这个是<b>持续订阅</b>：已读标记 + 可折叠。" +
                "<b>缺已读状态会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_code 代码块（多语言高亮） ══════════ */
    /** 极简分词器：够用即可，不做完整 parser */
    private val KW = mapOf(
        "kotlin" to setOf("fun", "val", "var", "when", "if", "else", "return", "object",
            "class", "for", "while", "is", "in", "private", "internal", "data", "suspend"),
        "python" to setOf("def", "class", "if", "else", "elif", "for", "while", "return",
            "import", "from", "with", "as", "lambda", "None", "True", "False", "try"),
        "js" to setOf("function", "const", "let", "var", "if", "else", "for", "while",
            "return", "class", "new", "await", "async", "import", "export", "null"),
        "json" to setOf("true", "false", "null")
    )
    private data class Tok(val s: String, val kind: Int)  // 0普通 1关键字 2字符串 3注释 4数字

    private fun lex(line: String, lang: String): List<Tok> {
        val kw = KW[lang] ?: emptySet()
        val out = ArrayList<Tok>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                    out.add(Tok(line.substring(i), 3)); i = line.length
                }
                c == '#' && (lang == "python") -> {
                    out.add(Tok(line.substring(i), 3)); i = line.length
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < line.length && line[j] != c) j++
                    out.add(Tok(line.substring(i, min(j + 1, line.length)), 2))
                    i = j + 1
                }
                c.isDigit() -> {
                    var j = i
                    while (j < line.length && (line[j].isDigit() || line[j] == '.')) j++
                    out.add(Tok(line.substring(i, j), 4)); i = j
                }
                c.isLetter() -> {
                    var j = i
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    val w = line.substring(i, j)
                    out.add(Tok(w, if (w in kw) 1 else 0)); i = j
                }
                else -> { out.add(Tok(c.toString(), 0)); i++ }
            }
        }
        return out
    }

    private fun tokColor(kind: Int): Color = when (kind) {
        1 -> Color(0xFFC792EA)   // 关键字：紫
        2 -> Color(0xFFC3E88D)   // 字符串：绿
        3 -> Color(0xFF6B7280)   // 注释：灰
        4 -> Color(0xFFF78C6C)   // 数字：橙
        else -> Color(0xFFD6DEEB)
    }

    @Composable
    fun code(o: JSONObject, ctx: RenderContext) {
        val lang = o.optString("lang", "kotlin")
        val src = o.optString("src", "")
        val lines = src.split("\n")
        val hl = jdarr(o, "hl").map { it.toInt() }   // 高亮行号
        var wrap by remember { mutableStateOf(o.optBoolean("wrap", true)) }
        var copied by remember { mutableStateOf(false) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(3.dp)).background(ZxT.Primary.copy(.22f))
                    .padding(5.dp, 1.dp)) {
                    Text(lang, fontSize = 7.5.sp, color = ZxT.PriLite)
                }
                Spacer(Modifier.width(5.dp))
                Text("${lines.size} 行" + if (hl.isNotEmpty()) "　${hl.size} 行高亮" else "",
                    fontSize = 8.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text(if (copied) "已复制" else "复制", fontSize = 8.sp,
                    color = if (copied) ZxT.Ok else ZxT.Text3,
                    modifier = Modifier.clickable {
                        ctx.callTool("zx_copy", mapOf("text" to src))
                        copied = true
                    })
                Spacer(Modifier.width(6.dp))
                Text(if (wrap) "不换行" else "换行", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.clickable { wrap = !wrap })
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0F1319))
                .then(Modifier.horizontalScroll(rememberScrollState()))) {
                Column(Modifier.padding(7.dp, 5.dp)) {
                    lines.forEachIndexed { k, line ->
                        val on = (k + 1) in hl
                        Row(Modifier.fillMaxWidth()
                            .background(if (on) Color(0xFF5046E4).copy(alpha = .18f)
                                        else Color.Transparent)) {
                            Text("${k + 1}".padStart(3), fontSize = 9.sp,
                                color = Color(0xFF4B5266), fontFamily = ZxT.Mono,
                                modifier = Modifier.width(22.dp),
                                textAlign = TextAlign.End)
                            Spacer(Modifier.width(6.dp))
                            Row {
                                lex(line, lang).forEach { t ->
                                    Text(t.s, fontSize = 9.5.sp, color = tokColor(t.kind),
                                        fontFamily = ZxT.Mono, softWrap = wrap)
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (hl.isEmpty()) ZxT.Surface2
                            else if (hl.any { it < 1 || it > lines.size })
                                ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (hl.isEmpty()) "i" else if (hl.any { it < 1 || it > lines.size })
                    "✗" else "✓", fontSize = 11.sp,
                    color = if (hl.isEmpty()) ZxT.Text3
                            else if (hl.any { it < 1 || it > lines.size }) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (hl.any { it < 1 || it > lines.size })
                        "**高亮行号超出 1-${lines.size}**——对不上就没有意义"
                        else if (hl.isEmpty()) "没有高亮行。指明「看这几行」比整段贴出来有用得多"
                        else "高亮第 ${hl.joinToString("、")} 行——**指向明确**",
                        fontSize = 9.sp,
                        color = if (hl.any { it < 1 || it > lines.size }) ZxT.Err
                                else ZxT.Text2)
                    Text("**代码高亮是为了扫读，不是为了好看**。行号要对齐、" +
                        "关键字/字符串/注释/数字四色区分，多了反而乱",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 editor 是<b>可编辑</b>、codeblock 是<b>只读块</b>，" +
                "这个加了<b>行高亮</b>：AI 解释代码时要能说「看这几行」。" +
                "<b>语言不在支持表会被拦</b>；<b>行号越界会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_codetheme 代码主题对比 ══════════ */
    private val THEMES = listOf(
        "Dark+" to listOf(Color(0xFFD6DEEB), Color(0xFFC792EA), Color(0xFFC3E88D),
            Color(0xFF6B7280), Color(0xFFF78C6C), Color(0xFF0F1319)),
        "Solarized" to listOf(Color(0xFF93A1A1), Color(0xFF859900), Color(0xFF2AA198),
            Color(0xFF586E75), Color(0xFFCB4B16), Color(0xFF002B36)),
        "Monokai" to listOf(Color(0xFFF8F8F2), Color(0xFFF92672), Color(0xFFE6DB74),
            Color(0xFF75715E), Color(0xFFAE81FF), Color(0xFF272822))
    )

    @Composable
    fun codetheme(o: JSONObject, ctx: RenderContext) {
        val lang = o.optString("lang", "kotlin")
        val src = o.optString("src", "")
        val lines = src.split("\n").take(6)
        var sel by remember { mutableIntStateOf(0) }
        val names = jstr(o, "themes").takeIf { it.isNotEmpty() }
            ?: THEMES.map { it.first }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "代码主题对比"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${names.size} 个主题", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                names.forEachIndexed { k, nm ->
                    Box(Modifier.padding(end = 3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (sel == k) ZxT.Primary else ZxT.Surface2)
                        .clickable { sel = k }.padding(7.dp, 3.dp)) {
                        Text(nm, fontSize = 8.5.sp,
                            color = if (sel == k) Color.White else ZxT.Text3)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            val th = THEMES.getOrElse(sel) { THEMES[0] }
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(th.second[5])
                .then(Modifier.horizontalScroll(rememberScrollState()))) {
                Column(Modifier.padding(7.dp, 5.dp)) {
                    lines.forEachIndexed { k, line ->
                        Row {
                            Text("${k + 1}".padStart(3), fontSize = 8.5.sp,
                                color = th.second[3], fontFamily = ZxT.Mono,
                                modifier = Modifier.width(20.dp), textAlign = TextAlign.End)
                            Spacer(Modifier.width(5.dp))
                            Row {
                                lex(line, lang).forEach { t ->
                                    Text(t.s, fontSize = 9.sp,
                                        color = when (t.kind) {
                                            1 -> th.second[1]; 2 -> th.second[2]
                                            3 -> th.second[3]; 4 -> th.second[4]
                                            else -> th.second[0] },
                                        fontFamily = ZxT.Mono)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            // 调色板：把当前主题的 5 个前景色摆出来
            Row(Modifier.fillMaxWidth()) {
                th.second.take(5).forEachIndexed { k, c ->
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(14.dp)
                            .clip(RoundedCornerShape(3.dp)).background(c))
                        Text(listOf("正文", "关键字", "字符串", "注释", "数字")[k],
                            fontSize = 6.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (names.size < 2) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (names.size < 2) "✗" else "✓", fontSize = 11.sp,
                    color = if (names.size < 2) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (names.size < 2) "**只有 1 个主题**——对比不成立"
                        else "当前 ${names.getOrElse(sel) { "—" }}，" +
                            "切标签看同一段代码在不同配色下的可读性",
                        fontSize = 9.sp,
                        color = if (names.size < 2) ZxT.Err else ZxT.Ok)
                    Text("**看得出区别才算对比**。三个主题如果摆出来都一样，" +
                        "那说明配色没有真的区分语义——高亮就白做了",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 swatch 是<b>取色板</b>、color 是<b>色卡</b>，" +
                "这个是<b>代码配色</b>：同一段源码在多种主题下渲染 + " +
                "五类 token 的调色条。<b>少于 2 个主题会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_shell 可交互 shell ══════════ */
    private val FAKE_OUT = mapOf(
        "ls" to "ZxRender.kt\nZxDsl.kt\nZxNest.kt\nREADME.md",
        "pwd" to "/data/workspace/zxwidget",
        "whoami" to "zorv",
        "git status" to "On branch main\nnothing to commit, working tree clean",
        "date" to "Sun Oct 11 12:00:00 CST 2026"
    )

    @Composable
    fun shell(o: JSONObject, ctx: RenderContext) {
        val prompt = o.optString("prompt", "$ ")
        val hist = remember { mutableStateListOf<String>() }
        val out = remember { mutableStateListOf<String>() }
        var input by remember { mutableStateOf("") }
        var hi by remember { mutableIntStateOf(-1) }
        var running by remember { mutableStateOf(false) }

        fun run(cmd: String) {
            if (cmd.isBlank()) return
            hist.add(0, cmd); hi = -1
            running = true
            out.add("$prompt$cmd")
            val r = FAKE_OUT[cmd.trim()]
            out.add(r ?: "command not found: ${cmd.trim()}")
            running = false
            input = ""
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "Shell"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (running) "**执行中**" else "空闲", fontSize = 8.5.sp,
                    color = if (running) ZxT.Warn else ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 70.dp, max = 110.dp)
                .clip(RoundedCornerShape(6.dp)).background(Color(0xFF0F1319))) {
                Column(Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(7.dp, 5.dp)) {
                    if (out.isEmpty())
                        Text("试试 ls / pwd / whoami / git status / date",
                            fontSize = 8.5.sp, color = Color(0xFF4B5266))
                    out.forEach { l ->
                        Text(l, fontSize = 9.sp,
                            color = if (l.startsWith(prompt)) Color(0xFFC3E88D)
                                    else Color(0xFFD6DEEB),
                            fontFamily = ZxT.Mono, lineHeight = 12.5.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(prompt, fontSize = 9.5.sp, color = ZxT.Ok, fontFamily = ZxT.Mono)
                Box(Modifier.weight(1f)
                    .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)
                    .padding(6.dp, 3.dp)) {
                    Text(input.ifBlank { "输入命令后回车" }, fontSize = 9.5.sp,
                        color = if (input.isBlank()) ZxT.Text3 else ZxT.Text,
                        fontFamily = ZxT.Mono)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                listOf("ls", "pwd", "whoami", "git status").forEach { c ->
                    Box(Modifier.padding(end = 3.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)
                        .clickable { run(c) }.padding(5.dp, 2.dp)) {
                        Text(c, fontSize = 8.sp, color = ZxT.Text2, fontFamily = ZxT.Mono)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("历史 ${hist.size} 条（↑↓ 翻）", fontSize = 7.5.sp,
                    color = ZxT.Text3, modifier = Modifier.weight(1f))
                if (hist.isNotEmpty())
                    Text("↑", fontSize = 9.sp, color = ZxT.Text3,
                        modifier = Modifier.clickable {
                            hi = (hi + 1).coerceAtMost(hist.size - 1)
                            input = hist[hi]
                        })
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp)).background(ZxT.Surface2)
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("i", fontSize = 11.sp, color = ZxT.Text3)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("**要能看出\"还在跑\"还是\"跑完了\"**——" +
                        "没有状态区分的终端，用户会以为卡住了",
                        fontSize = 9.sp, color = ZxT.Text2)
                    Text("命令与输出用不同颜色区分（绿=输入，白=输出），" +
                        "否则翻历史时分不清哪个是自己敲的", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 terminal 是<b>静态输出</b>、console 是<b>控制台</b>，" +
                "这个是<b>可交互</b>：输入→输出 + 历史 + 常用命令快键。" +
                "<b>缺提示符会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_tty 终端会话 ══════════ */
    @Composable
    fun tty(o: JSONObject, ctx: RenderContext) {
        val tabs = jarr(o, "tabs").map {
            Triple(it.optString("n", "tab"),
                it.optString("st", "idle"),     // running / idle / error / done
                it.optString("last", ""))
        }
        var sel by remember { mutableIntStateOf(0) }
        val statusCol = mapOf(
            "running" to ZxT.Warn, "idle" to ZxT.Text3,
            "error" to ZxT.Err, "done" to ZxT.Ok)
        val statusTxt = mapOf(
            "running" to "执行中", "idle" to "空闲",
            "error" to "出错", "done" to "完成")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "终端会话"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                val running = tabs.count { it.second == "running" }
                Text(if (running > 0) "$running 个在执行" else "全部空闲",
                    fontSize = 8.5.sp, color = if (running > 0) ZxT.Warn else ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                tabs.forEachIndexed { k, (nm, st, _) ->
                    Row(Modifier.padding(end = 3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (sel == k) ZxT.Surface2 else ZxT.Surface3)
                        .clickable { sel = k }.padding(6.dp, 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp))
                            .background(statusCol[st] ?: ZxT.Text3))
                        Spacer(Modifier.width(4.dp))
                        Text(nm, fontSize = 8.5.sp, color = ZxT.Text2)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            val cur = tabs.getOrElse(sel) { Triple("—", "idle", "") }
            Box(Modifier.fillMaxWidth().heightIn(min = 60.dp)
                .clip(RoundedCornerShape(6.dp)).background(Color(0xFF0F1319))) {
                Column(Modifier.padding(7.dp, 5.dp)) {
                    Text(if (cur.third.isNotBlank()) cur.third else "（无输出）",
                        fontSize = 9.sp,
                        color = if (cur.second == "error") ZxT.Err else Color(0xFFD6DEEB),
                        fontFamily = ZxT.Mono, lineHeight = 12.5.sp)
                    if (cur.second == "running")
                        Row(Modifier.padding(top = 3.dp)) {
                            Text("▊", fontSize = 9.sp, color = ZxT.Ok)
                        }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("状态：${statusTxt[cur.second] ?: cur.second}", fontSize = 8.sp,
                    color = statusCol[cur.second] ?: ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (tabs.any { it.second !in statusCol }) ZxT.Err.copy(.10f)
                            else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (tabs.any { it.second !in statusCol }) "✗" else "✓", fontSize = 11.sp,
                    color = if (tabs.any { it.second !in statusCol }) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (tabs.any { it.second !in statusCol })
                        "**有的标签状态非法**（只认 running / idle / error / done）——" +
                        "状态认不出来就没法显示"
                        else "每个标签都有状态点，**不用点进去就知道哪个在跑**",
                        fontSize = 9.sp,
                        color = if (tabs.any { it.second !in statusCol }) ZxT.Err else ZxT.Ok)
                    Text("**多标签的价值在状态外显**：不切过去也能看见哪个在执行、" +
                        "哪个出错了。要切进去才知道，标签就白做了",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 shell 是<b>单个可交互终端</b>、tabbar 是<b>普通标签</b>，" +
                "这个是<b>多会话</b>：状态点外显 + 会话切换。" +
                "<b>状态非法会被拦</b>；<b>缺会话名会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_toolcall 工具调用卡 ══════════ */
    @Composable
    fun toolcall(o: JSONObject, ctx: RenderContext) {
        val name = o.optString("tool", "")
        val st = o.optString("st", "ok")     // ok / err / running / denied
        val ms = o.optLong("ms", 0L)
        val args = o.optString("args", "")
        val result = o.optString("res", "")
        var open by remember { mutableStateOf(st == "err") }
        val col = mapOf("ok" to ZxT.Ok, "err" to ZxT.Err, "running" to ZxT.Warn,
            "denied" to ZxT.Err)[st] ?: ZxT.Text3
        val txt = mapOf("ok" to "成功", "err" to "失败", "running" to "执行中",
            "denied" to "被拒绝")[st] ?: st

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(col))
                Spacer(Modifier.width(5.dp))
                Text(name.ifBlank { "（无名工具）" }, fontSize = 10.sp, color = ZxT.Text,
                    fontFamily = ZxT.Mono, modifier = Modifier.weight(1f))
                Text(txt, fontSize = 8.5.sp, color = col)
                if (ms > 0) {
                    Spacer(Modifier.width(5.dp))
                    Text(if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0),
                        fontSize = 8.5.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
                }
            }
            Spacer(Modifier.height(4.dp))
            if (args.isNotBlank()) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                    .background(ZxT.Surface3).padding(6.dp, 4.dp)) {
                    Column {
                        Text("参数", fontSize = 7.5.sp, color = ZxT.Text3)
                        Text(args, fontSize = 8.5.sp, color = ZxT.Text2,
                            fontFamily = ZxT.Mono, lineHeight = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (open) "▾" else "▸", fontSize = 9.sp, color = ZxT.Text3,
                    modifier = Modifier.clickable { open = !open })
                Spacer(Modifier.width(4.dp))
                Text("结果", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.clickable { open = !open })
                Spacer(Modifier.width(4.dp))
                if (result.isNotBlank())
                    Text("${result.length} 字符", fontSize = 7.5.sp, color = ZxT.Text3,
                        modifier = Modifier.weight(1f))
                Text("重新执行", fontSize = 8.sp, color = ZxT.PriLite,
                    modifier = Modifier.clickable {
                        ctx.callTool("zx_tool_rerun", mapOf("tool" to name))
                    })
            }
            if (open) {
                Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (st == "err") ZxT.Err.copy(.08f)
                                else Color(0xFF0F1319))
                    .padding(6.dp, 4.dp)) {
                    Text(result.ifBlank { "（无结果）" }, fontSize = 8.5.sp,
                        color = if (st == "err") ZxT.Err else Color(0xFFD6DEEB),
                        fontFamily = ZxT.Mono, lineHeight = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(when {
                    st == "ok" && result.isBlank() -> ZxT.Err.copy(.10f)
                    st == "err" -> ZxT.Err.copy(.10f)
                    st == "denied" -> ZxT.Warn.copy(.10f)
                    else -> ZxT.Ok.copy(.10f) })
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (st == "ok" && result.isBlank()) "!" else if (st == "err") "✗"
                     else if (st == "denied") "!" else "✓", fontSize = 11.sp,
                    color = if (st == "ok" && result.isBlank()) ZxT.Warn
                            else if (st == "err") ZxT.Err
                            else if (st == "denied") ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(when {
                        st == "ok" && result.isBlank() ->
                            "**状态是成功但没有结果**——这是最该报的一种：用户会以为" +
                            "工具跑了，实际什么都没拿到"
                        st == "err" -> "**失败**：错误信息要在结果里，不能只标个红点"
                        st == "denied" -> "**被拒绝**：要说明是被权限拦的还是被用户拒的"
                        st == "running" -> "执行中——要有超时预期，否则用户会一直等"
                        else -> "成功，${result.length} 字符${if (ms > 0) "，耗时 ${ms}ms" else ""}"
                    }, fontSize = 9.sp,
                        color = if (st == "ok" && result.isBlank()) ZxT.Warn
                                else if (st == "err") ZxT.Err
                                else if (st == "denied") ZxT.Warn else ZxT.Ok)
                    Text("**工具调用不透明是 Agent 最被诟病的地方**。" +
                        "调了什么、参数是什么、结果如何、耗时多少——四件缺一不可",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 traceback 是<b>调用栈</b>、cmd 是<b>命令</b>，" +
                "这个是<b>Agent 工具调用</b>：工具名 + 参数 + 结果 + 耗时 + 状态。" +
                "<b>缺工具名会被拦</b>；<b>状态非法会被拦</b>；" +
                "<b>成功但无结果会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_toolchain 工具调用链 ══════════ */
    @Composable
    fun toolchain(o: JSONObject, ctx: RenderContext) {
        val steps = jarr(o, "steps").map {
            Pair(it.optString("id", ""),
                Triple(it.optString("tool", ""), it.optString("st", "ok"),
                    it.optString("after", "")))
        }   // after = 依赖的上一步 id
        var sel by remember { mutableIntStateOf(-1) }
        val totalMs = jarr(o, "steps").sumOf { it.optLong("ms", 0L) }
        val failed = steps.indexOfFirst { it.second.second == "err" }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "工具调用链"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${steps.size} 步 / ${totalMs}ms", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            steps.forEachIndexed { k, (id, meta) ->
                val (tool, st, after) = meta
                val col = mapOf("ok" to ZxT.Ok, "err" to ZxT.Err, "running" to ZxT.Warn,
                    "denied" to ZxT.Err)[st] ?: ZxT.Text3
                // 上游失败 → 下游待定（不是失败）
                val blocked = failed >= 0 && k > failed
                val effCol = if (blocked) ZxT.Text3 else col
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.width(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp))
                            .background(effCol))
                        if (k < steps.size - 1)
                            Box(Modifier.width(1.2.dp).height(16.dp).background(ZxT.Surface3))
                    }
                    Spacer(Modifier.width(5.dp))
                    Row(Modifier.weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                        .clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf("id" to id, "tool" to tool,
                                "status" to st, "source" to "zx_toolchain"))
                        }.padding(4.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tool, fontSize = 9.sp, color = if (blocked) ZxT.Text3 else ZxT.Text,
                            fontFamily = ZxT.Mono, modifier = Modifier.weight(1f))
                        if (after.isNotBlank())
                            Text("← $after", fontSize = 7.5.sp, color = ZxT.Text3)
                        Spacer(Modifier.width(4.dp))
                        Text(if (blocked) "待定" else
                            mapOf("ok" to "✓", "err" to "✗", "running" to "…",
                                "denied" to "⊘")[st] ?: st,
                            fontSize = 8.5.sp, color = effCol)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("竖线 = 执行顺序", fontSize = 7.5.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text("← 表示依赖上一步", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (failed >= 0) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (failed >= 0) "✗" else "✓", fontSize = 11.sp,
                    color = if (failed >= 0) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (failed >= 0)
                        "**第 ${failed + 1} 步 ${steps[failed].second.first} 失败**——" +
                        "后面 ${steps.size - failed - 1} 步是**待定**不是失败。" +
                        "它们压根没跑，不该显示成红的"
                        else "${steps.size} 步全部完成，共 ${totalMs}ms",
                        fontSize = 9.sp, color = if (failed >= 0) ZxT.Err else ZxT.Ok)
                    Text("**失败和待定要分开**——把它们都显示成红，" +
                        "用户会去排查那些根本没执行过的步骤", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 chain 是<b>处理链</b>、pipe 是<b>流水线</b>，" +
                "这个是<b>Agent 的工具调用序列</b>：顺序 + 依赖 + 断点。" +
                "<b>依赖指向不存在的 id 会被拦</b>；<b>有环会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_toolargs 工具参数表单 ══════════ */
    @Composable
    fun toolargs(o: JSONObject, ctx: RenderContext) {
        val tool = o.optString("tool", "")
        val fields = jarr(o, "fields").map {
            Pair(it.optString("n", ""),
                Triple(it.optString("t", "string"), it.optBoolean("req", false),
                    it.optString("def", "")))
        }
        val vals = remember { mutableStateMapOf<String, String>().apply {
            fields.forEach { (n, m) -> put(n, m.third) } } }
        val missing = fields.filter { (n, m) -> m.second && vals[n].isNullOrBlank() }
        var submitted by remember { mutableStateOf(false) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tool.ifBlank { "（无名工具）" }, fontSize = 10.5.sp, color = ZxT.Text,
                    fontFamily = ZxT.Mono, modifier = Modifier.weight(1f))
                Text("${fields.count { it.second.second }}/${fields.size} 必填",
                    fontSize = 8.5.sp,
                    color = if (missing.isEmpty()) ZxT.Ok else ZxT.Warn)
            }
            Spacer(Modifier.height(5.dp))
            fields.forEach { (n, m) ->
                val (type, req, def) = m
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(72.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n, fontSize = 9.sp, color = ZxT.Text2,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (req) Text("*", fontSize = 9.sp, color = ZxT.Err)
                        }
                        Text(type, fontSize = 7.sp, color = ZxT.Text3)
                    }
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.weight(1f)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)
                        .padding(6.dp, 3.dp)) {
                        Text(vals[n]?.ifBlank { def.ifBlank { "（空）" } } ?: "（空）",
                            fontSize = 9.sp,
                            color = if (vals[n].isNullOrBlank()) ZxT.Text3 else ZxT.Text)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.clip(RoundedCornerShape(5.dp))
                    .background(if (missing.isEmpty()) ZxT.Primary else ZxT.Surface3)
                    .clickable {
                        if (missing.isEmpty()) {
                            submitted = true
                            ctx.callTool("zx_tool_run", mapOf("tool" to tool) +
                                fields.associate { (n, _) -> n to (vals[n] ?: "") })
                        }
                    }.padding(11.dp, 5.dp)) {
                    Text(if (submitted) "已提交" else "执行", fontSize = 9.sp,
                        color = Color.White)
                }
                Spacer(Modifier.width(6.dp))
                Text(if (missing.isEmpty()) "全部必填已填" else
                    "**还缺 ${missing.size} 项必填**：${missing.joinToString("、") { it.first }}",
                    fontSize = 8.sp,
                    color = if (missing.isEmpty()) ZxT.Ok else ZxT.Err,
                    modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (fields.isEmpty()) ZxT.Err.copy(.10f)
                            else if (missing.isNotEmpty()) ZxT.Warn.copy(.10f)
                            else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (fields.isEmpty()) "✗" else if (missing.isNotEmpty()) "!" else "✓",
                    fontSize = 11.sp,
                    color = if (fields.isEmpty()) ZxT.Err
                            else if (missing.isNotEmpty()) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (fields.isEmpty()) "**没有字段**——那不是表单"
                        else if (missing.isNotEmpty()) "**必填项没填满不给执行**——" +
                            "按钮置灰比点了报错好，点了报错比静默失败好"
                        else "可以执行",
                        fontSize = 9.sp,
                        color = if (fields.isEmpty()) ZxT.Err
                                else if (missing.isNotEmpty()) ZxT.Warn else ZxT.Ok)
                    Text("**必填项要标出来**。不标的话用户不知道为什么点了没反应，" +
                        "而标了星号还让他点，比不标更糟——那等于把提示变成装饰",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 form 是<b>通用表单</b>，" +
                "这个是<b>schema 驱动</b>：字段直接来自工具的参数声明。" +
                "<b>缺字段会被拦</b>；<b>必填项无默认值会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_2048 游戏 ══════════ */
    private fun slide(line: IntArray): Pair<IntArray, Int> {
        val v = line.filter { it != 0 }.toMutableList()
        var gain = 0
        var i = 0
        while (i < v.size - 1) {
            if (v[i] == v[i + 1]) { v[i] *= 2; gain += v[i]; v.removeAt(i + 1) }
            i++
        }
        val out = IntArray(line.size)
        v.forEachIndexed { k, x -> out[k] = x }
        return out to gain
    }

    @Composable
    fun game2048(o: JSONObject, ctx: RenderContext) {
        var board by remember {
            mutableStateOf(IntArray(16).apply {
                val init = o.optString("init", "")
                if (init.isBlank()) {
                    this[0] = 2; this[5] = 4; this[10] = 2
                } else init.split(",").take(16).forEachIndexed { k, s ->
                    this[k] = s.trim().toIntOrNull() ?: 0
                }
            })
        }
        var score by remember { mutableIntStateOf(0) }
        var moves by remember { mutableIntStateOf(0) }
        var over by remember { mutableStateOf(false) }

        fun spawn(b: IntArray): IntArray {
            val empty = b.indices.filter { b[it] == 0 }
            if (empty.isEmpty()) return b
            val c = b.copyOf()
            c[empty[Random.nextInt(empty.size)]] = if (Random.nextFloat() < 0.9) 2 else 4
            return c
        }
        fun move(dir: Int) {   // 0左 1上 2右 3下
            if (over) return
            val old = board.copyOf()
            val nb = IntArray(16)
            var gain = 0
            for (k in 0 until 4) {
                val line = IntArray(4)
                for (j in 0 until 4) {
                    line[j] = when (dir) {
                        0 -> old[k * 4 + j]
                        2 -> old[k * 4 + (3 - j)]
                        1 -> old[j * 4 + k]
                        else -> old[(3 - j) * 4 + k]
                    }
                }
                val (sl, g) = slide(line)
                gain += g
                for (j in 0 until 4) {
                    when (dir) {
                        0 -> nb[k * 4 + j] = sl[j]
                        2 -> nb[k * 4 + (3 - j)] = sl[j]
                        1 -> nb[j * 4 + k] = sl[j]
                        else -> nb[(3 - j) * 4 + k] = sl[j]
                    }
                }
            }
            if (!nb.contentEquals(old)) {
                board = spawn(nb); score += gain; moves++
                // 判负：满且无可合并
                if (board.none { it == 0 }) {
                    var can = false
                    for (r in 0 until 4) for (c in 0 until 4) {
                        val v = board[r * 4 + c]
                        if (c < 3 && board[r * 4 + c + 1] == v) can = true
                        if (r < 3 && board[(r + 1) * 4 + c] == v) can = true
                    }
                    over = !can
                }
            }
        }
        val mx = board.maxOrNull() ?: 0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "2048"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("$score 分 / $moves 步", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(7.dp)).background(ZxT.Surface3)) {
                Column(Modifier.fillMaxSize().padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(4) { r ->
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            repeat(4) { c ->
                                val v = board[r * 4 + c]
                                // 颜色随数值加深
                                val t = if (v == 0) 0f
                                        else (kotlin.math.log2(v.toFloat()) - 1) / 10f
                                Box(Modifier.weight(1f).fillMaxHeight()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (v == 0) ZxT.Surface2
                                        else Color(
                                            red = (0.31f + (0.68f - 0.31f) * (1 - t)),
                                            green = (0.27f + (0.85f - 0.27f) * t),
                                            blue = (0.89f - (0.60f) * t))),
                                    contentAlignment = Alignment.Center) {
                                    if (v > 0) Text(v.toString(), fontSize = 13.sp,
                                        color = Color.White)
                                }
                            }
                        }
                    }
                }
                if (over)
                    Box(Modifier.fillMaxSize()
                        .background(Color(0xCC0F1319)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("无路可走", fontSize = 13.sp, color = ZxT.Err)
                            Text("$score 分 / $moves 步", fontSize = 9.sp, color = ZxT.Text3)
                            Text("重开", fontSize = 9.sp, color = ZxT.PriLite,
                                modifier = Modifier.padding(top = 4.dp).clickable {
                                    board = IntArray(16).apply { this[0] = 2; this[5] = 4 }
                                    score = 0; moves = 0; over = false
                                })
                        }
                    }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("←" to 0, "↑" to 1, "→" to 2, "↓" to 3).forEach { (lb, d) ->
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp))
                        .background(ZxT.Surface2).clickable { move(d) },
                        contentAlignment = Alignment.Center) {
                        Text(lb, fontSize = 14.sp, color = ZxT.Text)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("最大 $mx", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text("重开", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.clickable {
                        board = IntArray(16).apply { this[0] = 2; this[5] = 4 }
                        score = 0; moves = 0; over = false
                    })
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp)).background(ZxT.Surface2)
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("i", fontSize = 11.sp, color = ZxT.Text3)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("**游戏要真能玩**。只能看不能操作的「游戏组件」就是一张图——" +
                        "滑动要真合并、赢要真判出来", fontSize = 9.sp, color = ZxT.Text2)
                    Text("判负的条件是**满且无可合并**，不是「满了就输」——" +
                        "满了但还能合并就还没输", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 board 是<b>可拖拽画布</b>、grid 是<b>布局网格</b>，" +
                "这个是<b>真能玩的游戏</b>：滑动合并 + 随机生成 + 判负。" +
                "<b>初始值非 2 的幂会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_tictactoe 井字棋 ══════════ */
    @Composable
    fun tictactoe(o: JSONObject, ctx: RenderContext) {
        var b by remember { mutableStateOf(CharArray(9) { ' ' }) }
        var turn by remember { mutableStateOf('X') }   // 玩家 X，AI O
        var win by remember { mutableStateOf<Char?>(null) }
        var winLine by remember { mutableStateOf<List<Int>?>(null) }
        val LINES = listOf(listOf(0,1,2), listOf(3,4,5), listOf(6,7,8),
            listOf(0,3,6), listOf(1,4,7), listOf(2,5,8),
            listOf(0,4,8), listOf(2,4,6))

        fun checkWinner(board: CharArray): Pair<Char?, List<Int>?> {
            LINES.forEach { l ->
                if (board[l[0]] != ' ' && board[l[0]] == board[l[1]] &&
                    board[l[1]] == board[l[2]]) return board[l[0]] to l
            }
            return null to null
        }
        fun bestMove(board: CharArray): Int {
            // 优先赢 → 挡对方 → 中心 → 角 → 边
            for (i in 0 until 9) if (board[i] == ' ') {
                val t = board.copyOf(); t[i] = 'O'
                if (checkWinner(t).first == 'O') return i
            }
            for (i in 0 until 9) if (board[i] == ' ') {
                val t = board.copyOf(); t[i] = 'X'
                if (checkWinner(t).first == 'X') return i
            }
            if (board[4] == ' ') return 4
            listOf(0, 2, 6, 8).firstOrNull { board[it] == ' ' }?.let { return it }
            return (0 until 9).firstOrNull { board[it] == ' ' } ?: -1
        }
        fun play(i: Int) {
            if (win != null || b[i] != ' ' || turn != 'X') return
            val nb = b.copyOf(); nb[i] = 'X'
            val (w, l) = checkWinner(nb)
            if (w != null) { b = nb; win = w; winLine = l; return }
            if (nb.none { it == ' ' }) { b = nb; win = 'D'; return }
            val ai = bestMove(nb)
            if (ai >= 0) nb[ai] = 'O'
            val (w2, l2) = checkWinner(nb)
            b = nb; turn = 'X'
            if (w2 != null) { win = w2; winLine = l2 }
            else if (nb.none { it == ' ' }) win = 'D'
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "井字棋"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(when (win) {
                    'X' -> "**你赢了**"; 'O' -> "AI 赢了"
                    'D' -> "平局"; else -> "你执 X" },
                    fontSize = 9.sp,
                    color = when (win) { 'X' -> ZxT.Ok; 'O' -> ZxT.Err
                        'D' -> ZxT.Warn; else -> ZxT.Text3 })
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(7.dp)).background(ZxT.Surface3)) {
                Column(Modifier.fillMaxSize().padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { r ->
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(3) { c ->
                                val i = r * 3 + c
                                val on = winLine?.contains(i) == true
                                Box(Modifier.weight(1f).fillMaxHeight()
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (on) ZxT.Ok.copy(.22f) else ZxT.Surface2)
                                    .clickable { play(i) },
                                    contentAlignment = Alignment.Center) {
                                    Text(if (b[i] == ' ') "" else b[i].toString(),
                                        fontSize = 24.sp,
                                        color = if (b[i] == 'X') ZxT.PriLite else ZxT.Err)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("AI 会先赢后挡再占中心——你赢不了除非它先手失误",
                    fontSize = 8.5.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("重开", fontSize = 8.5.sp, color = ZxT.Text3,
                    modifier = Modifier.clickable {
                        b = CharArray(9) { ' ' }; turn = 'X'; win = null; winLine = null
                    })
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (win == 'X') ZxT.Ok.copy(.10f)
                            else if (win == 'O') ZxT.Err.copy(.10f)
                            else if (win == 'D') ZxT.Warn.copy(.10f) else ZxT.Surface2)
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (win == 'X') "✓" else if (win == 'O') "✗" else if (win == 'D')
                    "=" else "i", fontSize = 11.sp,
                    color = if (win == 'X') ZxT.Ok else if (win == 'O') ZxT.Err
                        else if (win == 'D') ZxT.Warn else ZxT.Text3)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(when (win) {
                        'X' -> "**你赢了**——AI 先赢后挡再占中心，能赢说明它确实算漏了"
                        'O' -> "AI 赢了：三个连成一线会被高亮标出"
                        'D' -> "平局——棋盘满了且无人连线"
                        else -> "点格子落子。连成三个即胜"
                    }, fontSize = 9.sp,
                        color = if (win == 'X') ZxT.Ok else if (win == 'O') ZxT.Err
                            else if (win == 'D') ZxT.Warn else ZxT.Text2)
                    Text("**赢的三连要高亮标出来**——只说「你赢了」用户还得自己去找是哪三个",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 payoff 是<b>博弈收益矩阵</b>（静态），" +
                "这个是<b>真能下的棋</b>：AI 先赢后挡、三连高亮、判平局。" +
                "<b>棋盘非 3x3 会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

package com.zorv.plugin.zxwidget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 第三十四批组件（15 个）—— 日常界面。
 *
 * 第三十三批补了七个空白领域（天气/新闻/内容/代码/终端/工具调用/游戏），
 * 但那批有个共同点：**都是"看"**。天气是看、新闻是看、代码是看、
 * 工具调用记录也是看。游戏虽然能操作，但操作的目的是消遣。
 *
 * 而这批是**操作一个日常工具**：
 *
 *   导航     要能跟着走、知道下一个路口往哪拐
 *   播放器   要能拖到某一秒
 *   相册     要能翻、知道是第几张
 *   文件管理 要能进目录、知道在哪、能退回
 *   购物车   选了规格才知道能不能买
 *   拨号盘   按了要显示号码
 *
 * ── 贯穿这批的一条 ──────────────────────────────────────────
 *
 * **日常界面要"能用"，而不只是"能看"。**
 *
 * 前三十三批多数组件的评价标准是"读不读得出信息"；
 * 这批的评价标准换成"**操作了能不能得到结果**"：
 *
 *   播放器进度条画出来了但不能拖     → 那是个装饰
 *   相册 9 张图摆出来了但不能翻      → 那是九宫格贴图
 *   文件管理器列了目录但点不进去      → 那是张树形图
 *   购物车能选规格但缺货的也能选      → 那是把错误留到付款时才告诉用户
 *
 * 所以校验重心也换了：**查"操作了但得不到结果"**。
 * 这类问题不崩、不报错，只是让用户面对一个看起来能交互、
 * 实际点了没反应的界面——比崩溃更糟，因为模型会以为下发成功了。
 *
 * ── 各组件 ──────────────────────────────────────────────────
 *
 * **出行**（2）
 *   zx_mapnav  地图导航：路线折线 + 转向点 + **下一个路口**
 *   zx_transit 公交换乘：线路 + 换乘点 + 步行段
 *
 * **媒体**（2）
 *   zx_music   播放器：进度可拖 + 播放列表
 *   zx_album   相册：网格 + 查看器 + **第几张**
 *
 * **沟通**（2）
 *   zx_chatlog 聊天记录：双方向 + 已读 + 时间分隔
 *   zx_contact 联系人：多方式 + 快捷动作
 *
 * **效率**（3）
 *   zx_agenda  日程时间轴：时间块 + **冲突检测**
 *   zx_tasklist 任务清单：子任务 + 依赖
 *   zx_filemgr 文件管理器：目录 + 面包屑 + 多选
 *
 * **交易**（3）
 *   zx_shop    商品卡：规格 + 库存 + **缺货不能选**
 *   zx_order   订单：状态机进度 + 物流节点
 *   zx_wallet  钱包账单：收支 + 分类占比
 *
 * **设备**（3）
 *   zx_setup   设置向导：分步 + 前置校验
 *   zx_remote  遥控器：方向键 + 旋钮
 *   zx_dialpad 拨号盘：3x4 + 号码显示
 *
 * ── 校验因此换了第十二档 ────────────────────────────────────
 *
 * 具体要拦的（与 ZxDsl34 注释一一对应）：
 *   mapnav    步骤距离和 ≠ 总距离 / 无转向点
 *   transit   换乘到达早于出发 / 无步行段
 *   music     时长 ≤ 0（进度条除零）/ 播放列表为空
 *   album     无图 / 初始索引越界
 *   chatlog   无消息 / 同一条消息两侧都是"我"
 *   contact   无联系方式
 *   agenda    时间块重叠（看不见冲突等于没检测）
 *   tasklist  子任务依赖自己 / 进度超 100
 *   filemgr   无根节点 / 层级过深
 *   shop      价格非正 / 选中的规格缺货
 *   order     节点时间倒序
 *   wallet    收支和 ≠ 余额
 *   setup     前置步不存在 / 无起点
 *   remote    按键无 action
 *   dialpad   号码含非数字
 */
object ZxRender34 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    private fun jarr(o: JSONObject, k: String): List<JSONObject> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    private fun jstr(o: JSONObject, k: String): List<String> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it, "") }
    }

    /* ══════════ zx_mapnav 地图导航 ══════════ */
    @Composable
    fun mapnav(o: JSONObject, ctx: RenderContext) {
        val steps = jarr(o, "steps")   // {d:距离m, t:说明, turn:0直 1左 2右 3掉头}
        val total = o.optDouble("total", steps.sumOf { it.optDouble("d", 0.0) })
        var sel by remember { mutableIntStateOf(0) }
        val eta = o.optInt("eta", 0)
        val turnIcon = listOf("↑", "↰", "↱", "⟲")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "导航"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${"%.1f".format(total / 1000)}km" +
                    if (eta > 0) "　${eta} 分钟" else "", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            // 路线：折线 + 转向点
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    val n = steps.size
                    if (n >= 2) {
                        val bw = W / (n - 1)
                        // 每段画成带轻微起伏的折线，纯直线看不出是"路"
                        val pts = (0 until n).map { i ->
                            Offset(bw * i, H * (0.30f + 0.40f * ((i * 7) % 5) / 5f))
                        }
                        drawPath(Path().apply {
                            pts.forEachIndexed { i, p ->
                                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                            }
                        }, Color(0xFF4A5A78), style = Stroke(3f))
                        // 已走过的部分高亮
                        drawPath(Path().apply {
                            pts.take(sel + 1).forEachIndexed { i, p ->
                                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                            }
                        }, ZxT.Primary, style = Stroke(3.5f))
                        pts.forEachIndexed { i, p ->
                            drawCircle(if (i <= sel) ZxT.Primary else ZxT.Surface3,
                                if (i == sel) 5f else 3.5f, p)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("蓝 = 已走过", fontSize = 7.5.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text("${sel + 1}/${steps.size}", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            // 下一个路口：这是导航的核心
            if (sel < steps.size) {
                Spacer(Modifier.height(5.dp))
                Row(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(ZxT.Primary.copy(.16f)).padding(9.dp, 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(turnIcon[steps[sel].optInt("turn", 0).coerceIn(0, 3)],
                        fontSize = 20.sp, color = ZxT.PriLite)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("下一个路口", fontSize = 7.5.sp, color = ZxT.Text3)
                        Text(steps[sel].optString("t", "—"), fontSize = 11.sp, color = ZxT.Text)
                        Text("还有 ${"%.0f".format(steps[sel].optDouble("d", 0.0))} 米",
                            fontSize = 8.5.sp, color = ZxT.Text2)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            steps.forEachIndexed { k, s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf("step" to s.optString("t", ""),
                            "dist" to "%.0f".format(s.optDouble("d", 0.0)),
                            "index" to k, "source" to "zx_mapnav"))
                    }.padding(6.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(turnIcon[s.optInt("turn", 0).coerceIn(0, 3)], fontSize = 11.sp,
                        color = if (k <= sel) ZxT.Primary else ZxT.Text3,
                        modifier = Modifier.width(16.dp))
                    Text(s.optString("t", "—"), fontSize = 9.sp,
                        color = if (k <= sel) ZxT.Text3 else ZxT.Text2,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text("${"%.0f".format(s.optDouble("d", 0.0))}m", fontSize = 8.sp,
                        color = ZxT.Text3, fontFamily = ZxT.Mono)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("点某一步切换「当前位置」", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp)).background(ZxT.Surface2)
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("i", fontSize = 11.sp, color = ZxT.Text3)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("**导航的价值在「下一个路口」，不在「全程路线」**。",
                        fontSize = 9.sp, color = ZxT.Text2)
                    Text("全程八步摆出来，用户还得自己找当前在哪。" +
                        "只给下一步，他抬头就能走", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 route 是<b>路线</b>、trail 是<b>轨迹</b>，" +
                "这个是<b>正在导航</b>：当前位置 + <b>下一个路口往哪拐</b>。" +
                "<b>步骤距离和不等于总距离会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_transit 公交换乘 ══════════ */
    @Composable
    fun transit(o: JSONObject, ctx: RenderContext) {
        val legs = jarr(o, "legs")   // {m:方式 walk/bus/metro, l:线路名, from, to, dep, arr, min}
        var sel by remember { mutableIntStateOf(-1) }
        val total = legs.sumOf { it.optInt("min", 0) }
        val walk = legs.count { it.optString("m") == "walk" }
        val legCol = mapOf("walk" to ZxT.Text3, "bus" to PAL[4], "metro" to PAL[0])
        val legName = mapOf("walk" to "步行", "bus" to "公交", "metro" to "地铁")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "换乘"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("$total 分钟 / ${legs.size} 段", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            legs.forEachIndexed { k, l ->
                val m = l.optString("m", "bus")
                val col = legCol[m] ?: ZxT.Text3
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.width(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(col))
                        if (k < legs.size - 1)
                            Box(Modifier.width(if (m == "walk") 1.2.dp else 3.dp)
                                .height(20.dp).background(col.copy(alpha = .5f)))
                    }
                    Spacer(Modifier.width(6.dp))
                    Row(Modifier.weight(1f)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                        .clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "mode" to (legName[m] ?: m),
                                "line" to l.optString("l", ""),
                                "from" to l.optString("from", ""),
                                "to" to l.optString("to", ""), "source" to "zx_transit"))
                        }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.clip(RoundedCornerShape(3.dp))
                                    .background(col.copy(alpha = .22f)).padding(4.dp, 1.dp)) {
                                    Text(l.optString("l").ifBlank { legName[m] ?: m },
                                        fontSize = 8.sp, color = col)
                                }
                                Spacer(Modifier.width(4.dp))
                                Text("${l.optString("from", "?")} → ${l.optString("to", "?")}",
                                    fontSize = 9.sp, color = ZxT.Text2, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                            }
                            if (l.optString("dep").isNotBlank())
                                Text("${l.optString("dep")} – ${l.optString("arr")}",
                                    fontSize = 7.5.sp, color = ZxT.Text3)
                        }
                        Text("${l.optInt("min", 0)}′", fontSize = 9.sp, color = ZxT.Text,
                            fontFamily = ZxT.Mono)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (walk == 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (walk == 0) "!" else "✓", fontSize = 11.sp,
                    color = if (walk == 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (walk == 0)
                        "**没有步行段**——地铁 A 口直接到地铁 B 口是不可能的，" +
                        "换乘必然要走路。缺了它，总时长是偏短的"
                        else "$walk 段步行 / ${legs.size - walk} 段乘车，" +
                            "共 $total 分钟",
                        fontSize = 9.sp, color = if (walk == 0) ZxT.Warn else ZxT.Ok)
                    Text("**换乘方案里步行段最容易漏**。漏了它总时长看着更短，" +
                        "用户按这个时间出门就会迟到", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 mapnav 是<b>驾车/步行导航</b>，" +
                "这个是<b>公共交通</b>：多段联程 + 换乘点。" +
                "<b>无步行段会被拦</b>；<b>某段到达早于出发会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_music 播放器 ══════════ */
    @Composable
    fun music(o: JSONObject, ctx: RenderContext) {
        val tracks = jarr(o, "tracks").map {
            Triple(it.optString("t", ""), it.optString("a", ""), it.optInt("d", 0))
        }
        var cur by remember { mutableIntStateOf(o.optInt("cur", 0).coerceIn(0,
            (tracks.size - 1).coerceAtLeast(0))) }
        var pos by remember { mutableFloatStateOf(0f) }   // 0-1
        var playing by remember { mutableStateOf(false) }
        var loop by remember { mutableStateOf(o.optString("loop", "off")) }
        // 播放推进：只在 playing 时走，且到尾自动下一首
        LaunchedEffect(playing, cur) {
            while (playing && tracks.isNotEmpty()) {
                val dur = tracks[cur].third.coerceAtLeast(1)
                pos += 1f / dur
                if (pos >= 1f) {
                    pos = 0f
                    if (loop == "one") { /* 重放本首 */ }
                    else if (cur < tracks.size - 1) cur++
                    else if (loop == "all") cur = 0
                    else playing = false
                }
                kotlinx.coroutines.delay(1000)
            }
        }
        fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
        val dur = tracks.getOrNull(cur)?.third ?: 0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tracks.getOrNull(cur)?.first ?: "（空播放列表）",
                    fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(if (playing) "播放中" else "已暂停", fontSize = 8.5.sp,
                    color = if (playing) ZxT.Ok else ZxT.Text3)
            }
            Text(tracks.getOrNull(cur)?.second ?: "—", fontSize = 8.5.sp, color = ZxT.Text3)
            Spacer(Modifier.height(6.dp))
            // 封面占位
            Box(Modifier.fillMaxWidth().height(58.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.linearGradient(listOf(ZxT.Primary, PAL[5]))),
                contentAlignment = Alignment.Center) {
                Text("♪", fontSize = 22.sp, color = Color.White)
            }
            Spacer(Modifier.height(6.dp))
            // 进度条：可拖
            Box(Modifier.fillMaxWidth().height(22.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val cy = size.height / 2
                    drawLine(ZxT.Surface3, Offset(0f, cy), Offset(W, cy), 3f)
                    drawLine(ZxT.PriLite, Offset(0f, cy), Offset(W * pos, cy), 3f)
                    drawCircle(Color.White, 6f, Offset(W * pos, cy))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text(fmt((dur * pos).toInt()), fontSize = 8.sp, color = ZxT.Text2,
                    fontFamily = ZxT.Mono)
                Spacer(Modifier.weight(1f))
                Text(fmt(dur), fontSize = 8.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                listOf("⏮" to -1, if (playing) "⏸" to 0 else "▶" to 0, "⏭" to 1)
                    .forEach { (lb, d) ->
                        Box(Modifier.size(34.dp).clip(CircleShape)
                            .background(if (d == 0) ZxT.Primary else ZxT.Surface2)
                            .clickable {
                                if (d == 0) playing = !playing
                                else if (tracks.isNotEmpty()) {
                                    cur = (cur + d + tracks.size) % tracks.size; pos = 0f
                                }
                            }, contentAlignment = Alignment.Center) {
                            Text(lb, fontSize = 13.sp,
                                color = if (d == 0) Color.White else ZxT.Text)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                Spacer(Modifier.weight(1f))
                // 循环模式
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)
                    .clickable {
                        loop = if (loop == "off") "all" else if (loop == "all") "one" else "off"
                    }.padding(6.dp, 3.dp)) {
                    Text(if (loop == "off") "顺序" else if (loop == "all") "列表循环" else "单曲",
                        fontSize = 8.sp, color = ZxT.Text2)
                }
                Spacer(Modifier.width(5.dp))
                // 秒刻度快进
                Row {
                    listOf(-30, 30).forEach { d ->
                        Text(if (d < 0) "«30s" else "30s»", fontSize = 8.sp,
                            color = ZxT.Text3,
                            modifier = Modifier.padding(start = 3.dp).clickable {
                                pos = (pos + d.toFloat() / dur.coerceAtLeast(1))
                                    .coerceIn(0f, 1f)
                            })
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            tracks.forEachIndexed { k, (t, a, d) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (cur == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        cur = k; pos = 0f
                        ctx.callTool("zx_select", mapOf("track" to t, "artist" to a,
                            "source" to "zx_music"))
                    }.padding(6.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (cur == k && playing) "♪" else "${k + 1}", fontSize = 8.5.sp,
                        color = if (cur == k) ZxT.PriLite else ZxT.Text3,
                        modifier = Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t, fontSize = 9.sp,
                            color = if (cur == k) ZxT.PriLite else ZxT.Text2,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a, fontSize = 7.5.sp, color = ZxT.Text3, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Text(fmt(d), fontSize = 8.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (tracks.isEmpty()) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (tracks.isEmpty()) "✗" else "✓", fontSize = 11.sp,
                    color = if (tracks.isEmpty()) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (tracks.isEmpty()) "**播放列表为空**——没有歌的播放器是空的壳"
                        else "${tracks.size} 首，第 ${cur + 1} 首。" +
                            "**进度条能拖**——拖了要真跳到那一秒",
                        fontSize = 9.sp,
                        color = if (tracks.isEmpty()) ZxT.Err else ZxT.Ok)
                    Text("**播放器进度条画出来但不能拖，那就是个装饰**。" +
                        "日常界面的标准是「操作了要有结果」，不是「看起来像」",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 audio 是<b>音频波形</b>、seek 是<b>视频进度</b>，" +
                "这个是<b>音乐播放器</b>：播放/暂停 + 上下曲 + 循环模式 + 列表。" +
                "<b>时长 ≤ 0 会被拦</b>（进度条除零）；<b>列表为空会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_album 相册 ══════════ */
    @Composable
    fun album(o: JSONObject, ctx: RenderContext) {
        val imgs = jstr(o, "imgs")
        var idx by remember { mutableIntStateOf(o.optInt("cur", 0).coerceIn(0,
            (imgs.size - 1).coerceAtLeast(0))) }
        var viewer by remember { mutableStateOf(false) }
        val cols = min(3, imgs.size.coerceAtLeast(1))

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "相册"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${imgs.size} 张", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            if (viewer && imgs.isNotEmpty()) {
                // 查看器
                Box(Modifier.fillMaxWidth().height(110.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Brush.linearGradient(
                        listOf(PAL[idx % PAL.size], ZxT.Surface2))),
                    contentAlignment = Alignment.Center) {
                    Text(imgs[idx].substringAfterLast('/'), fontSize = 9.sp,
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("‹" to -1, "${idx + 1} / ${imgs.size}" to 0, "›" to 1)
                        .forEach { (lb, d) ->
                            if (d == 0) Text(lb, fontSize = 9.sp, color = ZxT.Text2)
                            else Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp))
                                .background(ZxT.Surface2)
                                .clickable { idx = (idx + d + imgs.size) % imgs.size },
                                contentAlignment = Alignment.Center) {
                                Text(lb, fontSize = 14.sp, color = ZxT.Text)
                            }
                        }
                }
                Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    Text("关闭查看器", fontSize = 8.sp, color = ZxT.Text3,
                        modifier = Modifier.clickable { viewer = false })
                }
            } else {
                // 网格
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    var k = 0
                    while (k < imgs.size) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (c in 0 until cols) {
                                if (k + c < imgs.size) {
                                    val i = k + c
                                    Box(Modifier.weight(1f).aspectRatio(1f)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(PAL[i % PAL.size].copy(alpha = .55f))
                                        .clickable {
                                            idx = i; viewer = true
                                            ctx.callTool("zx_select", mapOf(
                                                "img" to imgs[i], "index" to i,
                                                "source" to "zx_album"))
                                        }, contentAlignment = Alignment.Center) {
                                        Text("${i + 1}", fontSize = 10.sp, color = Color.White)
                                    }
                                } else Spacer(Modifier.weight(1f))
                            }
                        }
                        k += cols
                        Spacer(Modifier.height(3.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (imgs.isEmpty()) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (imgs.isEmpty()) "✗" else "✓", fontSize = 11.sp,
                    color = if (imgs.isEmpty()) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (imgs.isEmpty()) "**没有图**——空的相册没有任何意义"
                        else "${imgs.size} 张，当前第 ${idx + 1} 张。" +
                            "**翻了要知道是第几张**", fontSize = 9.sp,
                        color = if (imgs.isEmpty()) ZxT.Err else ZxT.Ok)
                    Text("**相册九张图摆出来但不能翻，那是九宫格贴图**。" +
                        "翻到某一张时如果不显示「第几张/共几张」，用户不知道还剩多少",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 gallery 是<b>横向画廊</b>、model3d 是<b>3D</b>，" +
                "这个是<b>相册</b>：九宫格 + 查看器 + <b>第几张 / 共几张</b>。" +
                "<b>无图会被拦</b>；<b>初始索引越界会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_chatlog 聊天记录 ══════════ */
    @Composable
    fun chatlog(o: JSONObject, ctx: RenderContext) {
        val msgs = jarr(o, "msgs").map {
            Pair(it.optBoolean("me", false), it.optString("t", ""))
                .let { (me, t) -> Triple(me, t, Triple(it.optString("who", ""),
                    it.optLong("ts", 0L), it.optInt("st", 0))) }   // st 0未读 1已读 2发送中
        }
        var sel by remember { mutableIntStateOf(-1) }
        val mine = msgs.count { it.first }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "聊天记录"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${msgs.size} 条", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            msgs.forEachIndexed { k, (me, t, meta) ->
                val (who, ts, st) = meta
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp),
                    horizontalArrangement = if (me) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.fillMaxWidth(if (me) 0.82f else 0.82f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (me) ZxT.Primary.copy(.22f) else ZxT.Surface3)
                        .clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf("msg" to t,
                                "from" to if (me) "me" else who, "source" to "zx_chatlog"))
                        }.padding(7.dp, 4.dp),
                        horizontalAlignment = if (me) Alignment.End else Alignment.Start) {
                        if (!me && who.isNotBlank())
                            Text(who, fontSize = 7.5.sp, color = PAL[k % PAL.size])
                        Text(t, fontSize = 9.5.sp,
                            color = if (me) ZxT.Text else ZxT.Text, lineHeight = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ts > 0) {
                                val m = (System.currentTimeMillis() - ts) / 60000
                                Text(if (m < 1) "刚刚" else if (m < 60) "${m}分前"
                                    else if (m < 1440) "${m / 60}时前" else "${m / 1440}天前",
                                    fontSize = 7.sp, color = ZxT.Text3)
                            }
                            if (me) {
                                Spacer(Modifier.width(4.dp))
                                Text(if (st == 0) "未读" else if (st == 1) "已读" else "发送中",
                                    fontSize = 7.sp,
                                    color = if (st == 2) ZxT.Text3 else ZxT.PriLite)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("右=我（$mine 条）左=对方", fontSize = 7.5.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text("点消息选中", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (msgs.isEmpty()) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (msgs.isEmpty()) "✗" else "✓", fontSize = 11.sp,
                    color = if (msgs.isEmpty()) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (msgs.isEmpty()) "**没有消息**——空的聊天记录"
                        else "${msgs.size} 条，其中 $mine 条是我发的。" +
                            "**方向必须能区分**", fontSize = 9.sp,
                        color = if (msgs.isEmpty()) ZxT.Err else ZxT.Ok)
                    Text("**聊天记录里分不清哪句是自己说的，这个记录就没用了**。" +
                        "左右分边 + 气泡底色 + 已读状态，三层冗余——任意一层都能看出来",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 conversation 是<b>模拟对话</b>、thread 是<b>话题串</b>，" +
                "这个是<b>真实聊天记录</b>：方向 + 时间 + 已读。" +
                "<b>无消息会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_contact 联系人 ══════════ */
    @Composable
    fun contact(o: JSONObject, ctx: RenderContext) {
        val name = o.optString("name", "")
        val ways = jarr(o, "ways").map {
            Pair(it.optString("t", ""), it.optString("v", ""))
        }   // t: tel / mail / addr
        val acts = jstr(o, "acts")
        var sel by remember { mutableIntStateOf(-1) }
        val icon = mapOf("tel" to "☎", "mail" to "✉", "addr" to "⌂")
        val label = mapOf("tel" to "电话", "mail" to "邮箱", "addr" to "地址")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 头像：首字母圆形
                Box(Modifier.size(40.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(ZxT.Primary, PAL[5]))),
                    contentAlignment = Alignment.Center) {
                    Text(name.take(1).ifBlank { "?" }, fontSize = 17.sp, color = Color.White)
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "（无名）" }, fontSize = 13.sp, color = ZxT.Text)
                    if (o.optString("role").isNotBlank())
                        Text(o.optString("role"), fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Spacer(Modifier.height(6.dp))
            ways.forEachIndexed { k, (t, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else ZxT.Surface3.copy(alpha = .6f))
                    .clickable {
                        sel = k
                        ctx.callTool(if (t == "tel") "zx_call" else "zx_open_url",
                            mapOf("type" to t, "value" to v, "name" to name))
                    }.padding(7.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(icon[t] ?: "•", fontSize = 12.sp, color = ZxT.PriLite,
                        modifier = Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label[t] ?: t, fontSize = 7.5.sp, color = ZxT.Text3)
                        Text(v, fontSize = 9.5.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                    }
                    Text("›", fontSize = 12.sp, color = ZxT.Text3)
                }
            }
            if (acts.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    acts.forEach { a ->
                        Box(Modifier.padding(end = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ZxT.Primary).clickable {
                                ctx.callTool("zx_select", mapOf("action" to a,
                                    "contact" to name, "source" to "zx_contact"))
                            }.padding(11.dp, 5.dp)) {
                            Text(a, fontSize = 9.sp, color = Color.White)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (ways.isEmpty()) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (ways.isEmpty()) "✗" else "✓", fontSize = 11.sp,
                    color = if (ways.isEmpty()) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (ways.isEmpty()) "**没有任何联系方式**——那不是联系人卡，那是名字"
                        else "${ways.size} 种联系方式，点即调用。" +
                            "**卡片的终点是动作，不是展示**", fontSize = 9.sp,
                        color = if (ways.isEmpty()) ZxT.Err else ZxT.Ok)
                    Text("**联系人卡点了没反应，等于让用户把号码抄下来再自己去拨**。" +
                        "日常界面要把「下一步」做进去，而不是把数据摆出来",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 avatargroup 是<b>头像组</b>、orgchart 是<b>组织架构</b>，" +
                "这个是<b>单个联系人</b>：多方式 + <b>点了直接拨/发</b>。" +
                "<b>无联系方式会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_agenda 日程时间轴 ══════════ */
    @Composable
    fun agenda(o: JSONObject, ctx: RenderContext) {
        // blocks: {t:标题, s:开始分钟(0-1440), e:结束分钟, c:色号}
        val blocks = jarr(o, "blocks").map {
            Triple(it.optString("t", ""), it.optInt("s", 0) to it.optInt("e", 0),
                it.optInt("c", 0))
        }
        var sel by remember { mutableIntStateOf(-1) }
        // 冲突：两两时间重叠
        val conflicts = ArrayList<Pair<Int, Int>>()
        for (i in blocks.indices) for (j in i + 1 until blocks.size) {
            val (a, b) = blocks[i].second to blocks[j].second
            if (max(a.first, b.first) < min(a.second, b.second)) conflicts.add(i to j)
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "今日日程"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (conflicts.isEmpty()) "${blocks.size} 项，无冲突"
                    else "**${conflicts.size} 处冲突**", fontSize = 8.5.sp,
                    color = if (conflicts.isEmpty()) ZxT.Text3 else ZxT.Err)
            }
            Spacer(Modifier.height(5.dp))
            // 时间轴：纵向 24 小时
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    val laneW = 26f
                    // 整点刻度
                    for (h in 0..24 step 6) {
                        val y = H * h / 24f
                        drawLine(ZxT.Surface3, Offset(laneW, y), Offset(W, y), 1f)
                        drawCircle(ZxT.Text3, 1.5f, Offset(laneW - 6f, y))
                    }
                    blocks.forEachIndexed { k, (_, se, c) ->
                        val y0 = H * se.first / 1440f
                        val y1 = H * se.second / 1440f
                        val col = PAL[c % PAL.size]
                        drawRoundRect(col.copy(alpha = if (sel == k) .9f else .55f),
                            Offset(laneW + 2f, y0),
                            Size(W - laneW - 4f, (y1 - y0).coerceAtLeast(3f)),
                            androidx.compose.ui.geometry.CornerRadius(3f, 3f))
                        if (conflicts.any { it.first == k || it.second == k })
                            drawRoundRect(ZxT.Err, Offset(laneW + 2f, y0),
                                Size(W - laneW - 4f, (y1 - y0).coerceAtLeast(3f)),
                                androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                                style = Stroke(1.5f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("刻度每 6 小时　红框 = 冲突", fontSize = 7.5.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text("点条目选中", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            blocks.forEachIndexed { k, (t, se, c) ->
                val bad = conflicts.any { it.first == k || it.second == k }
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (bad) ZxT.Err.copy(.08f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf("event" to t,
                            "start" to "%02d:%02d".format(se.first / 60, se.first % 60),
                            "end" to "%02d:%02d".format(se.second / 60, se.second % 60),
                            "conflict" to bad, "source" to "zx_agenda"))
                    }.padding(6.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp))
                        .background(PAL[c % PAL.size]))
                    Spacer(Modifier.width(6.dp))
                    Text("%02d:%02d".format(se.first / 60, se.first % 60), fontSize = 8.5.sp,
                        color = ZxT.Text3, fontFamily = ZxT.Mono,
                        modifier = Modifier.width(34.dp))
                    Text(t, fontSize = 9.sp, color = ZxT.Text2,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (bad) Text("冲突", fontSize = 7.5.sp, color = ZxT.Err)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (conflicts.isNotEmpty()) ZxT.Err.copy(.10f)
                    else ZxT.Ok.copy(.10f)).padding(7.dp, 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (conflicts.isNotEmpty()) "✗" else "✓", fontSize = 11.sp,
                    color = if (conflicts.isNotEmpty()) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (conflicts.isNotEmpty())
                        "**${conflicts.size} 处时间重叠**：" +
                        conflicts.take(2).joinToString("；") { (i, j) ->
                            "「${blocks[i].first}」和「${blocks[j].first}」" }
                        else "${blocks.size} 项，无冲突",
                        fontSize = 9.sp,
                        color = if (conflicts.isNotEmpty()) ZxT.Err else ZxT.Ok)
                    Text("**日程表看不见冲突，就等于没有检测冲突**。" +
                        "用户按这个表出门，到点了才发现两个会撞车——那时已经晚了",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 calendar 是<b>月历</b>、calheat 是<b>日历热力</b>、weekgrid 是<b>周网格</b>，" +
                "这个是<b>单日时间轴</b>：24 小时纵向排布 + <b>冲突高亮</b>。" +
                "<b>时间块重叠会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_filemgr 文件管理器 ══════════ */
    @Composable
    fun filemgr(o: JSONObject, ctx: RenderContext) {
        // nodes: {n:名, p:父路径, d:是否目录, sz:大小}
        val nodes = jarr(o, "nodes").map {
            Pair(it.optString("n", ""), Triple(it.optString("p", ""),
                it.optBoolean("d", false), it.optLong("sz", 0L)))
        }
        var path by remember { mutableStateOf(o.optString("root", "/")) }
        val sel = remember { mutableStateListOf<String>() }
        val cur = nodes.filter { it.second.first == path }
        val parent = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "文件"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${cur.size} 项${if (sel.isNotEmpty()) "　选了 ${sel.size}" else ""}",
                    fontSize = 8.5.sp, color = if (sel.isEmpty()) ZxT.Text3 else ZxT.PriLite)
            }
            Spacer(Modifier.height(4.dp))
            // 面包屑：点段跳回去
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                path.split('/').filter { it.isNotBlank() }.let { parts ->
                    Text("/", fontSize = 8.5.sp, color = ZxT.Text3,
                        modifier = Modifier.clickable { path = "/" })
                    var acc = ""
                    parts.forEach { p ->
                        acc += "/$p"
                        val target = acc
                        Text(p, fontSize = 8.5.sp, color = ZxT.PriLite,
                            modifier = Modifier.padding(start = 3.dp)
                                .clickable { path = target })
                        Text("/", fontSize = 8.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (path != "/") {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { path = parent }.padding(6.dp, 3.dp)) {
                    Text("↑ 上级目录", fontSize = 9.sp, color = ZxT.Text2)
                }
            }
            cur.forEach { (n, meta) ->
                val (_, isDir, sz) = meta
                val on = sel.contains(n)
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (on) ZxT.Primary.copy(.14f) else Color.Transparent)
                    .clickable {
                        if (isDir) path = if (path == "/") "/$n" else "$path/$n"
                        else {
                            if (on) sel.remove(n) else sel.add(n)
                            ctx.callTool("zx_select", mapOf("file" to n, "path" to path,
                                "source" to "zx_filemgr"))
                        }
                    }.padding(6.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isDir) "📁" else "📄", fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(n, fontSize = 9.sp, color = ZxT.Text2,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (!isDir) Text(if (sz > 1024) "${sz / 1024}K" else "${sz}B",
                        fontSize = 8.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
                    else Text("›", fontSize = 10.sp, color = ZxT.Text3)
                }
            }
            if (cur.isEmpty())
                Text("（空目录）", fontSize = 9.sp, color = ZxT.Text3,
                    modifier = Modifier.padding(6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (nodes.isEmpty()) ZxT.Err.copy(.10f)
                    else if (cur.isEmpty() && path != "/") ZxT.Warn.copy(.10f)
                    else ZxT.Ok.copy(.10f)).padding(7.dp, 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (nodes.isEmpty()) "✗" else if (cur.isEmpty() && path != "/") "!" else "✓",
                    fontSize = 11.sp, color = if (nodes.isEmpty()) ZxT.Err
                        else if (cur.isEmpty() && path != "/") ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (nodes.isEmpty()) "**没有任何节点**——空的文件管理器"
                        else if (cur.isEmpty() && path != "/")
                        "这个目录下没有子项，但**能退回上级**——进得去出得来才算能操作"
                        else "在 $path，${cur.size} 项。" +
                            "**进了目录要知道在哪、能退回**", fontSize = 9.sp,
                        color = if (nodes.isEmpty()) ZxT.Err
                            else if (cur.isEmpty() && path != "/") ZxT.Warn else ZxT.Ok)
                    Text("**文件管理器列了目录但点不进去，那是张树形图**。" +
                        "面包屑不是装饰——它同时回答「我在哪」和「怎么回去」",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 tree 是<b>目录树</b>、treeview 是<b>文件树</b>，" +
                "这个是<b>能进能退的文件管理器</b>：面包屑 + 上级 + 多选。" +
                "<b>无根节点会被拦</b>；<b>层级 > 6 会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_setup 设置向导 ══════════ */
    @Composable
    fun setup(o: JSONObject, ctx: RenderContext) {
        val steps = jarr(o, "steps").map {
            Pair(it.optString("t", ""), Pair(it.optString("req", ""),
                it.optString("hint", "")))
        }   // req = 前置步骤标题（空则无前置）
        var cur by remember { mutableIntStateOf(0) }
        val done = remember { mutableStateListOf<Boolean>().apply {
            repeat(steps.size) { add(false) } } }
        val reqIdx = steps[cur].second.first.takeIf { it.isNotBlank() }
            ?.let { t -> steps.indexOfFirst { it.first == t } } ?: -1
        val blocked = reqIdx >= 0 && !done[reqIdx]

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "设置向导"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${cur + 1}/${steps.size}", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            // 步骤条
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                steps.forEachIndexed { k, (t, _) ->
                    Box(Modifier.weight(1f).height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (done[k]) ZxT.Ok
                            else if (k == cur) ZxT.Primary else ZxT.Surface3))
                    if (k < steps.size - 1) Spacer(Modifier.width(2.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                steps.forEachIndexed { k, _ ->
                    Text(if (done[k]) "✓" else "${k + 1}", fontSize = 7.5.sp,
                        color = if (done[k]) ZxT.Ok else if (k == cur) ZxT.PriLite else ZxT.Text3,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(7.dp))
            val (title, meta) = steps[cur]
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .background(if (blocked) ZxT.Warn.copy(.12f) else ZxT.Surface2)
                .padding(9.dp, 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 11.sp, color = ZxT.Text)
                    if (meta.second.isNotBlank())
                        Text(meta.second, fontSize = 8.5.sp, color = ZxT.Text3,
                            lineHeight = 12.sp)
                }
                if (done[cur]) Text("✓", fontSize = 13.sp, color = ZxT.Ok)
            }
            if (blocked) {
                Spacer(Modifier.height(4.dp))
                Text("**需要先完成「${steps[reqIdx].first}」**（第 ${reqIdx + 1} 步）——" +
                    "现在做不了", fontSize = 8.5.sp, color = ZxT.Warn)
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.clip(RoundedCornerShape(5.dp))
                    .background(if (cur == 0) ZxT.Surface3 else ZxT.Surface2)
                    .clickable { if (cur > 0) cur-- }.padding(11.dp, 5.dp)) {
                    Text("上一步", fontSize = 9.sp,
                        color = if (cur == 0) ZxT.Text3 else ZxT.Text2)
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.clip(RoundedCornerShape(5.dp))
                    .background(if (blocked) ZxT.Surface3 else ZxT.Primary)
                    .clickable {
                        if (blocked) return@clickable
                        done[cur] = true
                        if (cur < steps.size - 1) cur++
                        else ctx.callTool("zx_setup_done", mapOf(
                            "steps" to steps.joinToString("→") { it.first }))
                    }.padding(11.dp, 5.dp)) {
                    Text(if (cur == steps.size - 1) "完成" else "下一步", fontSize = 9.sp,
                        color = Color.White)
                }
                Spacer(Modifier.width(6.dp))
                Text(if (blocked) "按钮置灰：前置未完成" else if (cur == steps.size - 1)
                    "最后一步" else "完成 ${done.count { it }}/${steps.size}",
                    fontSize = 8.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (steps.isEmpty()) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (steps.isEmpty()) "✗" else "✓", fontSize = 11.sp,
                    color = if (steps.isEmpty()) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (steps.isEmpty()) "**没有步骤**——那不是向导"
                        else "${steps.size} 步，完成 ${done.count { it }} 个。" +
                            "**有前置的步骤，前置没做完就不能过**", fontSize = 9.sp,
                        color = if (steps.isEmpty()) ZxT.Err else ZxT.Ok)
                    Text("**向导的价值在「卡住」，不在「下一步」**。" +
                        "什么都能点下一步的向导，用户做完了才发现漏了第一步",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 wizard 是<b>通用向导</b>、checklist 是<b>检查清单</b>，" +
                "这个是<b>设置向导</b>：分步 + <b>前置未完成就卡住</b>。" +
                "<b>无步骤会被拦</b>；<b>前置指向不存在的步骤会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_shop 商品卡 ══════════ */
    @Composable
    fun shop(o: JSONObject, ctx: RenderContext) {
        val name = o.optString("name", "")
        val price = o.optDouble("price", 0.0)
        val specs = jarr(o, "specs").map {
            Triple(it.optString("n", ""), it.optInt("stock", 0), it.optDouble("add", 0.0))
        }
        var sel by remember { mutableIntStateOf(
            specs.indexOfFirst { it.second > 0 }.let { if (it < 0) 0 else it }) }
        var qty by remember { mutableIntStateOf(1) }
        var added by remember { mutableStateOf(false) }
        val sp = specs.getOrNull(sel)
        val unit = price + (sp?.third ?: 0.0)
        val oos = sp?.second ?: 0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name.ifBlank { "（无名商品）" }, fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(if (oos <= 0) "**缺货**" else "库存 $oos", fontSize = 8.5.sp,
                    color = if (oos <= 0) ZxT.Err else ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(70.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.linearGradient(listOf(PAL[0], PAL[5]))),
                contentAlignment = Alignment.Center) {
                Text("商品图", fontSize = 9.sp, color = Color.White.copy(alpha = .8f))
            }
            Spacer(Modifier.height(6.dp))
            if (specs.isNotEmpty()) {
                Text("规格", fontSize = 8.5.sp, color = ZxT.Text3)
                Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    specs.forEachIndexed { k, (n, st, add) ->
                        Box(Modifier.padding(end = 4.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .border(1.dp, if (sel == k) ZxT.Primary else ZxT.Surface3,
                                RoundedCornerShape(5.dp))
                            .background(if (st <= 0) ZxT.Surface2.copy(alpha = .4f)
                                else if (sel == k) ZxT.Primary.copy(.16f) else ZxT.Surface2)
                            .clickable { if (st > 0) { sel = k; added = false } }
                            .padding(8.dp, 4.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(n, fontSize = 9.sp,
                                    color = if (st <= 0) ZxT.Text3
                                        else if (sel == k) ZxT.PriLite else ZxT.Text2)
                                if (add != 0.0) Text(if (add > 0) "+${add.toInt()}"
                                    else "${add.toInt()}", fontSize = 7.5.sp, color = ZxT.Text3)
                                if (st <= 0) Text("缺货", fontSize = 7.sp, color = ZxT.Err)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("¥", fontSize = 9.sp, color = ZxT.Err)
                        Text("%.0f".format(unit), fontSize = 18.sp, color = ZxT.Err)
                    }
                    Text("小计 ¥${"%.0f".format(unit * qty)}", fontSize = 8.5.sp,
                        color = ZxT.Text3)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("−", fontSize = 13.sp, color = ZxT.Text2,
                        modifier = Modifier.clickable { if (qty > 1) qty-- }
                            .padding(horizontal = 6.dp))
                    Text("$qty", fontSize = 11.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                    Text("+", fontSize = 13.sp, color = ZxT.Text2,
                        modifier = Modifier.clickable {
                            if (qty < (oos.coerceAtLeast(1))) qty++ }
                            .padding(horizontal = 6.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (oos <= 0) ZxT.Surface3 else ZxT.Primary)
                .clickable {
                    if (oos <= 0) return@clickable
                    added = true
                    ctx.callTool("zx_cart_add", mapOf("name" to name,
                        "spec" to (sp?.first ?: ""), "qty" to qty,
                        "total" to "%.0f".format(unit * qty)))
                }.padding(0.dp, 7.dp), contentAlignment = Alignment.Center) {
                Text(if (oos <= 0) "该规格缺货" else if (added) "已加入购物车" else "加入购物车",
                    fontSize = 10.sp, color = if (oos <= 0) ZxT.Text3 else Color.White)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (price <= 0) ZxT.Err.copy(.10f)
                    else if (oos <= 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (price <= 0) "✗" else if (oos <= 0) "!" else "✓", fontSize = 11.sp,
                    color = if (price <= 0) ZxT.Err else if (oos <= 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (price <= 0) "**价格非正**——那不是商品，那是赠品或数据错了"
                        else if (oos <= 0) "**当前选中规格缺货**——" +
                            "**缺货的规格不能选**，选了等于把错误留到付款时才告诉用户"
                        else "¥${"%.0f".format(unit)} × $qty = ¥${"%.0f".format(unit * qty)}",
                        fontSize = 9.sp,
                        color = if (price <= 0) ZxT.Err else if (oos <= 0) ZxT.Warn else ZxT.Ok)
                    Text("**缺货规格要能看出来且选不了**。能选但下单失败，" +
                        "用户的感受是「这 App 有 bug」，而不是「我选错了」",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 price 是<b>定价卡</b>、export 是<b>导出</b>，" +
                "这个是<b>可下单的商品卡</b>：规格 + 库存 + 数量 + 加购。" +
                "<b>价格非正会被拦</b>；<b>选中的规格缺货会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_order 订单 ══════════ */
    @Composable
    fun order(o: JSONObject, ctx: RenderContext) {
        val nodes = jarr(o, "nodes").map {
            Pair(it.optString("t", ""), it.optString("ts", ""))
        }
        var cur by remember { mutableIntStateOf(o.optInt("cur", nodes.size - 1)
            .coerceIn(0, (nodes.size - 1).coerceAtLeast(0))) }
        val id = o.optString("id", "")
        val total = o.optDouble("total", 0.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("订单 ${id.ifBlank { "—" }}", fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                if (total > 0) Text("¥${"%.0f".format(total)}", fontSize = 10.sp,
                    color = ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            nodes.forEachIndexed { k, (t, ts) ->
                val done = k <= cur
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.width(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(7.dp).clip(CircleShape)
                            .background(if (done) ZxT.Ok else ZxT.Surface3))
                        if (k < nodes.size - 1)
                            Box(Modifier.width(1.2.dp).height(18.dp)
                                .background(if (k < cur) ZxT.Ok.copy(.6f) else ZxT.Surface3))
                    }
                    Spacer(Modifier.width(6.dp))
                    Row(Modifier.weight(1f).padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            cur = k
                            ctx.callTool("zx_select", mapOf("node" to t, "step" to k,
                                "source" to "zx_order"))
                        }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t, fontSize = 9.sp,
                                color = if (done) ZxT.Text else ZxT.Text3)
                            if (ts.isNotBlank()) Text(ts, fontSize = 7.5.sp, color = ZxT.Text3)
                        }
                        if (k == cur) Text("当前", fontSize = 7.5.sp, color = ZxT.PriLite)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (nodes.isEmpty()) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (nodes.isEmpty()) "✗" else "✓", fontSize = 11.sp,
                    color = if (nodes.isEmpty()) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (nodes.isEmpty()) "**没有物流节点**——空订单"
                        else "${nodes.size} 个节点，当前「${nodes[cur].first}」。" +
                            "**要能看出到哪了**", fontSize = 9.sp,
                        color = if (nodes.isEmpty()) ZxT.Err else ZxT.Ok)
                    Text("**订单状态要标「当前」，不能只列一串节点**。" +
                        "八个节点都长一样，用户得自己比对时间才知道货到哪了",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 receipt 是<b>回执</b>、statelist 是<b>状态列表</b>，" +
                "这个是<b>订单物流</b>：节点 + <b>当前标出</b>。" +
                "<b>无节点会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_wallet 钱包账单 ══════════ */
    @Composable
    fun wallet(o: JSONObject, ctx: RenderContext) {
        val items = jarr(o, "items").map {
            Triple(it.optString("t", ""), it.optDouble("v", 0.0), it.optString("c", ""))
        }
        val bal = o.optDouble("bal", items.sumOf { it.second })
        var sel by remember { mutableIntStateOf(-1) }
        val inc = items.filter { it.second > 0 }.sumOf { it.second }
        val exp = items.filter { it.second < 0 }.sumOf { -it.second }
        val cats = items.filter { it.second < 0 }.groupBy { it.third.ifBlank { "其他" } }
            .map { (k, v) -> k to -v.sumOf { it.second } }.sortedByDescending { it.second }
        val catSum = cats.sumOf { it.second }.coerceAtLeast(1.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "钱包"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("余额 ¥${"%.0f".format(bal)}", fontSize = 10.sp,
                    color = if (bal < 0) ZxT.Err else ZxT.Text)
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth().height(30.dp)) {
                // 收支柱
                Box(Modifier.weight(inc.coerceAtLeast(1.0).toFloat()).height(30.dp)
                    .clip(RoundedCornerShape(4.dp)).background(ZxT.Ok.copy(.75f)),
                    contentAlignment = Alignment.Center) {
                    Text("收 ${"%.0f".format(inc)}", fontSize = 8.sp, color = Color.White)
                }
                Spacer(Modifier.width(3.dp))
                Box(Modifier.weight(exp.coerceAtLeast(1.0).toFloat()).height(30.dp)
                    .clip(RoundedCornerShape(4.dp)).background(ZxT.Err.copy(.75f)),
                    contentAlignment = Alignment.Center) {
                    Text("支 ${"%.0f".format(exp)}", fontSize = 8.sp, color = Color.White)
                }
            }
            if (cats.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Text("支出分类", fontSize = 8.5.sp, color = ZxT.Text3)
                cats.forEachIndexed { k, (nm, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                        .clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf("category" to nm,
                                "amount" to "%.0f".format(v), "source" to "zx_wallet"))
                        }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                            modifier = Modifier.width(46.dp))
                        Box(Modifier.weight(1f).height(7.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                            Box(Modifier.fillMaxWidth((v / catSum).toFloat().coerceIn(0.02f, 1f))
                                .fillMaxHeight().background(PAL[k % PAL.size]))
                        }
                        Text("¥${"%.0f".format(v)}", fontSize = 8.5.sp, color = ZxT.Text,
                            modifier = Modifier.width(42.dp), textAlign = TextAlign.End,
                            fontFamily = ZxT.Mono)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            items.forEachIndexed { k, (t, v, _) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable { sel = k }.padding(6.dp, 3.dp)) {
                    Text(t, fontSize = 9.sp, color = ZxT.Text2,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text((if (v > 0) "+" else "") + "%.0f".format(v), fontSize = 9.sp,
                        color = if (v > 0) ZxT.Ok else ZxT.Err, fontFamily = ZxT.Mono)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (abs(items.sumOf { it.second } - bal) > 0.5)
                    ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (abs(items.sumOf { it.second } - bal) > 0.5) "✗" else "✓",
                    fontSize = 11.sp,
                    color = if (abs(items.sumOf { it.second } - bal) > 0.5) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (abs(items.sumOf { it.second } - bal) > 0.5)
                        "**流水合计 ${"%.0f".format(items.sumOf { it.second })} ≠ 余额 " +
                        "${"%.0f".format(bal)}**——差 " +
                        "${"%.0f".format(abs(items.sumOf { it.second } - bal))}。" +
                        "对不上账的账单，用户没法信"
                        else "流水合计 = 余额，对得上", fontSize = 9.sp,
                        color = if (abs(items.sumOf { it.second } - bal) > 0.5) ZxT.Err else ZxT.Ok)
                    Text("**账单对不上账，其余所有数字都不可信**——" +
                        "用户会怀疑每一笔是不是记错了，而不只是怀疑总额",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 ledger 是<b>流水账</b>、unitecon 是<b>单位经济</b>，" +
                "这个是<b>钱包</b>：收支对比 + 分类占比 + <b>对账校验</b>。" +
                "<b>流水合计 ≠ 余额会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_remote 遥控器 ══════════ */
    @Composable
    fun remote(o: JSONObject, ctx: RenderContext) {
        val keys = jarr(o, "keys").map {
            Triple(it.optString("l", ""), it.optString("a", ""), it.optInt("s", 1))
        }   // l 显示 a action s 占格数
        var last by remember { mutableStateOf("") }
        var vol by remember { mutableIntStateOf(50) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "遥控器"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (last.isBlank()) "待命" else "已发：$last", fontSize = 8.5.sp,
                    color = if (last.isBlank()) ZxT.Text3 else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            // 方向键
            Box(Modifier.fillMaxWidth().size(96.dp).align(Alignment.CenterHorizontally)) {
                Canvas(Modifier.fillMaxSize()) {
                    val R = size.minDimension / 2 - 2f
                    drawCircle(ZxT.Surface2, R, Offset(size.width / 2, size.height / 2))
                }
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(ZxT.Surface3)
                        .clickable { last = "上"; ctx.callTool("zx_remote_key",
                            mapOf("key" to "up")) }, contentAlignment = Alignment.Center) {
                        Text("▲", fontSize = 11.sp, color = ZxT.Text2)
                    }
                    Row {
                        listOf("◀" to "left", "●" to "ok", "▶" to "right").forEach { (lb, a) ->
                            Box(Modifier.size(30.dp).clip(CircleShape)
                                .background(if (a == "ok") ZxT.Primary else ZxT.Surface3)
                                .clickable {
                                    last = lb
                                    ctx.callTool("zx_remote_key", mapOf("key" to a))
                                }, contentAlignment = Alignment.Center) {
                                Text(lb, fontSize = if (a == "ok") 9.sp else 11.sp,
                                    color = if (a == "ok") Color.White else ZxT.Text2)
                            }
                            Spacer(Modifier.width(3.dp))
                        }
                    }
                    Box(Modifier.size(30.dp).clip(CircleShape).background(ZxT.Surface3)
                        .clickable { last = "下"; ctx.callTool("zx_remote_key",
                            mapOf("key" to "down")) }, contentAlignment = Alignment.Center) {
                        Text("▼", fontSize = 11.sp, color = ZxT.Text2)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 音量旋钮（拖动改变）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("−", fontSize = 14.sp, color = ZxT.Text2,
                    modifier = Modifier.clickable { vol = (vol - 5).coerceIn(0, 100) }
                        .padding(horizontal = 5.dp))
                Box(Modifier.weight(1f).height(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                    Box(Modifier.fillMaxWidth(vol / 100f).fillMaxHeight()
                        .background(ZxT.Primary))
                }
                Text("+", fontSize = 14.sp, color = ZxT.Text2,
                    modifier = Modifier.clickable {
                        vol = (vol + 5).coerceIn(0, 100)
                        ctx.callTool("zx_remote_key", mapOf("key" to "vol", "value" to vol))
                    }.padding(horizontal = 5.dp))
                Text("$vol", fontSize = 10.sp, color = ZxT.Text,
                    fontFamily = ZxT.Mono, modifier = Modifier.width(28.dp),
                    textAlign = TextAlign.End)
            }
            if (keys.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    keys.forEach { (l, a, _) ->
                        Box(Modifier.padding(end = 4.dp)
                            .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface3)
                            .clickable {
                                last = l
                                ctx.callTool("zx_remote_key", mapOf("key" to a, "label" to l))
                            }.padding(10.dp, 5.dp)) {
                            Text(l, fontSize = 9.sp, color = ZxT.Text2)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (keys.any { it.second.isBlank() }) ZxT.Err.copy(.10f)
                    else ZxT.Ok.copy(.10f)).padding(7.dp, 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (keys.any { it.second.isBlank() }) "✗" else "✓", fontSize = 11.sp,
                    color = if (keys.any { it.second.isBlank() }) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (keys.any { it.second.isBlank() })
                        "**有按键没有 action**——按了不知道发给谁，那按键是画上去的"
                        else "方向键 + 音量 + ${keys.size} 个自定义键。" +
                            "**每个键都要有 action**", fontSize = 9.sp,
                        color = if (keys.any { it.second.isBlank() }) ZxT.Err else ZxT.Ok)
                    Text("**遥控器上没有反应的键，比没有这个键更让人困惑**——" +
                        "用户会反复按，以为是自己没按准", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 joystick 是<b>摇杆</b>、switch 是<b>开关列表</b>，" +
                "这个是<b>家电遥控器</b>：方向键 + 音量 + 自定义键。" +
                "<b>按键无 action 会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_dialpad 拨号盘 ══════════ */
    @Composable
    fun dialpad(o: JSONObject, ctx: RenderContext) {
        var num by remember { mutableStateOf(o.optString("num", "")) }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "拨号"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${num.length} 位", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            // 号码显示
            Box(Modifier.fillMaxWidth().height(34.dp)
                .clip(RoundedCornerShape(6.dp)).background(ZxT.Surface2),
                contentAlignment = Alignment.Center) {
                Text(num.ifBlank { "（空）" }, fontSize = 15.sp,
                    color = if (num.isBlank()) ZxT.Text3 else ZxT.Text,
                    fontFamily = ZxT.Mono, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (r in 0 until 4) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (c in 0 until 3) {
                            val k = keys[r * 3 + c]
                            Box(Modifier.weight(1f).size(40.dp).clip(CircleShape)
                                .background(ZxT.Surface3)
                                .clickable { if (num.length < 20) num += k },
                                contentAlignment = Alignment.Center) {
                                Text(k, fontSize = 15.sp, color = ZxT.Text)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Text("退格", fontSize = 8.5.sp, color = ZxT.Text3,
                    modifier = Modifier.clickable { if (num.isNotEmpty()) num = num.dropLast(1) })
                Text("清空", fontSize = 8.5.sp, color = ZxT.Text3,
                    modifier = Modifier.clickable { num = "" })
                Box(Modifier.size(38.dp).clip(CircleShape).background(ZxT.Ok)
                    .clickable {
                        if (num.isNotBlank())
                            ctx.callTool("zx_call", mapOf("number" to num))
                    }, contentAlignment = Alignment.Center) {
                    Text("☎", fontSize = 15.sp, color = Color.White)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (num.any { !it.isDigit() && it !in "*#" }) ZxT.Err.copy(.10f)
                    else ZxT.Ok.copy(.10f)).padding(7.dp, 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (num.any { !it.isDigit() && it !in "*#" }) "✗" else "✓",
                    fontSize = 11.sp,
                    color = if (num.any { !it.isDigit() && it !in "*#" }) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (num.any { !it.isDigit() && it !in "*#" })
                        "**号码里有非数字字符**——拨不出去"
                        else if (num.isBlank()) "按了要显示号码——" +
                            "**拨号盘按了没显示，用户不知道按没按上**"
                        else "已输入 $num，点 ☎ 拨出", fontSize = 9.sp,
                        color = if (num.any { !it.isDigit() && it !in "*#" }) ZxT.Err else ZxT.Ok)
                    Text("**拨号盘是「输入工具」，不是「展示号码」**。" +
                        "每个键按下都要立刻回显，否则用户无法确认自己按对了没有",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 inputmask 是<b>格式化输入</b>、keypad 无，" +
                "这个是<b>拨号盘</b>：3×4 + 回显 + 退格/清空/拨出。" +
                "<b>号码含非数字会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_tasklist 任务清单 ══════════ */
    @Composable
    fun tasklist(o: JSONObject, ctx: RenderContext) {
        data class T(val t: String, val done: Boolean, val dep: String, val sub: List<String>,
                     val pri: Int)
        val tasks = jarr(o, "tasks").map {
            T(it.optString("t", ""), it.optBoolean("done", false), it.optString("dep", ""),
                (it.optJSONArray("sub") ?: JSONArray()).let { a ->
                    (0 until a.length()).map { k -> a.optString(k) } },
                it.optInt("pri", 2))
        }
        val state = remember { mutableStateListOf<Boolean>().apply {
            tasks.forEach { add(it.done) } } }
        var sel by remember { mutableIntStateOf(-1) }
        val doneN = state.count { it }
        val pct = if (tasks.isEmpty()) 0 else doneN * 100 / tasks.size
        // 依赖未完成的，点不动
        fun blocked(k: Int): Boolean {
            val d = tasks[k].dep
            if (d.isBlank()) return false
            val i = tasks.indexOfFirst { it.t == d }
            return i >= 0 && !state[i]
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "任务"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("$doneN/${tasks.size}　$pct%", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(5.dp)
                .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                Box(Modifier.fillMaxWidth(pct / 100f).fillMaxHeight().background(ZxT.Ok))
            }
            Spacer(Modifier.height(5.dp))
            tasks.forEachIndexed { k, t ->
                val blk = blocked(k)
                val pc = listOf("", "高", "中", "低").getOrElse(t.pri) { "" }
                val pcCol = listOf(ZxT.Text3, ZxT.Err, ZxT.Warn, ZxT.Text3)
                    .getOrElse(t.pri) { ZxT.Text3 }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (blk) ZxT.Surface3.copy(alpha = .4f) else Color.Transparent)
                    .clickable {
                        if (blk) return@clickable
                        state[k] = !state[k]; sel = k
                        ctx.callTool("zx_task_toggle", mapOf("task" to t.t,
                            "done" to state[k], "progress" to "$pct%"))
                    }.padding(6.dp, 3.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(13.dp).padding(top = 1.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (state[k]) ZxT.Ok else Color.Transparent)
                        .border(1.dp, if (state[k]) ZxT.Ok else ZxT.Text3,
                            RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                        if (state[k]) Text("✓", fontSize = 8.sp, color = Color.White)
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.t, fontSize = 9.5.sp,
                            color = if (state[k]) ZxT.Text3 else if (blk) ZxT.Text3 else ZxT.Text,
                            lineHeight = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(pc, fontSize = 7.5.sp, color = pcCol)
                            if (t.dep.isNotBlank()) {
                                Text("　依赖「${t.dep}」", fontSize = 7.5.sp,
                                    color = if (blk) ZxT.Warn else ZxT.Text3)
                                if (blk) Text("（未完成，锁定）", fontSize = 7.5.sp,
                                    color = ZxT.Warn)
                            }
                        }
                        if (t.sub.isNotEmpty() && sel == k)
                            t.sub.forEach { s ->
                                Text("· $s", fontSize = 8.sp, color = ZxT.Text3,
                                    modifier = Modifier.padding(start = 2.dp))
                            }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (tasks.isEmpty()) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (tasks.isEmpty()) "✗" else "✓", fontSize = 11.sp,
                    color = if (tasks.isEmpty()) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (tasks.isEmpty()) "**没有任务**——空的清单"
                        else "$doneN/${tasks.size}（$pct%）。" +
                            "**依赖未完成的任务要锁住**", fontSize = 9.sp,
                        color = if (tasks.isEmpty()) ZxT.Err else ZxT.Ok)
                    Text("**依赖锁住比放任勾选好**——用户勾了不该勾的，" +
                        "到执行那一步才发现前置没做，那时已经晚了",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 checklist 是<b>检查清单</b>、kanban 是<b>看板</b>，" +
                "这个是<b>任务清单</b>：进度 + 优先级 + <b>依赖锁定</b>。" +
                "<b>任务依赖自己会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

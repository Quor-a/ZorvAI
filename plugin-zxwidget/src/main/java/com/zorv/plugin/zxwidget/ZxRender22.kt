package com.zorv.plugin.zxwidget

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 第二十二批组件（12 个）—— 追溯 / 关系强度 / 定位。
 *
 * 前二十一批把"看什么、填什么、改什么、凭什么、多密、\n* 怎么对齐、怎么撤回"铺完了。剩下的是三类
 * **一直靠通用组件硬凑**的：
 *
 *   一、**追溯**。"刚才那一步到底发生了什么"，
 *       现在只能用 logview（日志）或 console（控制台）凑。
 *       但日志是流式的、控制台是输出式的，
 *       都回答不了"**什么时候、耗时多久、能不能重跑**"。
 *       `zx_cmdhist`（命令历史）、`zx_callstack`（调用栈）、
 *       `zx_uptime`（可用性条）、`zx_playback`（回放控制器）、
 *       `zx_pipe`（流水线吞吐）。
 *
 *   二、**关系强度**。已有 matrix / heat / chord / network，
 *       但"两个量之间**有多强**"没有专门组件——
 *       相关系数矩阵被当成普通热力图用，
 *       而热力图不区分正负、不显示变量名。
 *       `zx_corr`、`zx_radar2`、`zx_choro`。
 *
 *   三、**定位与采集**。已有 breadcrumb / minmap / pager / form，
 *       但"在一长篇里找到某一节"（目录）、
 *       "一个词是什么意思"（术语表）、
 *       "一次收多道题"（问卷）没有。
 *       `zx_toc`、`zx_glossary`、`zx_survey`、`zx_punch`。
 *
 * 贯穿这批的一条：**能定位到具体时刻 / 具体位置**。
 *
 *   · 命令历史没有时间戳和执行时长 → 只知道"跑过"，不知道"什么时候、跑多久"
 *   · 调用栈没有行号 → 只知道"到了这层"，不知道"卡在第几行"
 *   · 可用性条每格不能点 → 只知道"挂过"，不知道"哪一小时挂的"
 *   · 回放没有帧号 → 只知道"能播"，不知道"现在在第几帧"
 *   · 目录没有当前项 → 那只是列表，不是导航
 *
 * 只有"发生过"、没有"什么时候发生"的追溯是没有用的。
 *
 * 另一个共同点：这批有一半要**在窄气泡里塞下高密度信息**，
 * 所以每个都定死上限——看不清比渲染失败更糟，
 * 因为模型会以为成功了。
 */
object ZxRender22 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_cmdhist 命令历史 ══════════ */
    @Composable
    fun cmdhist(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 12)
        var sel by remember { mutableIntStateOf(-1) }
        var onlyFav by remember { mutableStateOf(o.optBoolean("fav", false)) }
        // 光标位置：↑↓ 翻阅，命中的那条高亮
        var cursor by remember { mutableIntStateOf(n - 1) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "命令历史"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (onlyFav) "仅收藏" else "全部", fontSize = 9.sp,
                         color = if (onlyFav) Color.White else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(8.dp))
                             .background(if (onlyFav) PAL[0] else ZxT.Surface2)
                             .clickable { onlyFav = !onlyFav }.padding(7.dp, 2.dp))
                    Text("↑↓", fontSize = 9.sp, color = ZxT.Text3,
                         fontFamily = ZxT.Mono)
                }
            }
            Spacer(Modifier.height(6.dp))
            val shown = (0 until n).filter {
                !onlyFav || items.optJSONObject(it)?.optBoolean("fav", false) == true }
            if (shown.isEmpty())
                Text("还没有收藏的命令", fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(vertical = 10.dp))
            shown.forEach { i ->
                val it = items.optJSONObject(i) ?: return@forEach
                val ok = it.optInt("rc", 0) == 0
                val on = sel == i
                val cur = cursor == i
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .border(if (cur) 1.dp else 0.dp,
                        if (cur) ZxT.PriLite else Color.Transparent,
                        RoundedCornerShape(5.dp))
                    .clickable {
                        sel = i; cursor = i
                        ctx.callTool("zx_select", mapOf(
                            "cmd" to it.optString("c", ""),
                            "rc" to it.optInt("rc", 0).toString(),
                            "source" to "zx_cmdhist"))
                    }.padding(6.dp, 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    // 退出码：不画出来就分不清成功和失败
                    Box(Modifier.size(6.dp).clip(CircleShape)
                        .background(if (ok) ZxT.Ok else ZxT.Err))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(it.optString("c", ""), fontSize = 9.5.sp,
                             color = if (ok) ZxT.Text else ZxT.Err,
                             fontFamily = ZxT.Mono, maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        // 时间戳 + 耗时：只有"跑过"没有"何时/多久"的追溯没用
                        Row {
                            Text(it.optString("t", ""), fontSize = 8.5.sp,
                                 color = ZxT.Text3, fontFamily = ZxT.Mono)
                            Spacer(Modifier.width(6.dp))
                            Text(it.optString("d", ""), fontSize = 8.5.sp,
                                 color = if (ok) ZxT.Text3 else ZxT.Warn,
                                 fontFamily = ZxT.Mono)
                        }
                    }
                    if (it.optBoolean("fav", false))
                        Text("★", fontSize = 10.sp, color = ZxT.Warn)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("重跑", fontSize = 9.5.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(9.dp))
                         .background(ZxT.Surface2).clickable {
                             val it = items.optJSONObject(cursor)
                             ctx.callTool("zx_select", mapOf(
                                 "rerun" to (it?.optString("c", "") ?: ""),
                                 "source" to "zx_cmdhist"))
                         }.padding(9.dp, 3.dp))
                Text("收藏本条", fontSize = 9.5.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(9.dp))
                         .background(ZxT.Surface2).clickable {
                             ctx.callTool("zx_select", mapOf(
                                 "fav" to (items.optJSONObject(cursor)
                                     ?.optString("c", "") ?: ""),
                                 "source" to "zx_cmdhist"))
                         }.padding(9.dp, 3.dp))
                Spacer(Modifier.weight(1f))
                Text("第 ${cursor + 1}/$n 条", fontSize = 8.5.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Text("已有 logview 是流式日志、console 是程序输出，" +
                 "都回答不了「**什么时候、耗时多久、能不能重跑**」——" +
                 "这个每条都带时间戳和耗时，失败标红且能直接重跑",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_callstack 调用栈 ══════════ */
    @Composable
    fun callstack(o: JSONObject, ctx: RenderContext) {
        val frames = o.optJSONArray("frames") ?: return
        val n = minOf(frames.length(), 10)
        var sel by remember { mutableIntStateOf(o.optInt("top", 0).coerceIn(0, n - 1)) }
        val err = o.optString("err", "")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "调用栈"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 层", fontSize = 9.sp, color = ZxT.Text3)
            }
            if (err.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(err, fontSize = 10.sp, color = ZxT.Err, fontFamily = ZxT.Mono,
                     lineHeight = 14.sp,
                     modifier = Modifier.fillMaxWidth()
                         .clip(RoundedCornerShape(5.dp))
                         .background(ZxT.Err.copy(.12f)).padding(7.dp, 5.dp))
            }
            Spacer(Modifier.height(6.dp))
            // 栈顶在上：栈是"最近调用的在最上面"
            for (i in 0 until n) {
                val f = frames.optJSONObject(i) ?: continue
                val on = sel == i
                val isTop = i == 0
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else
                        (if (isTop) ZxT.Err.copy(.08f) else Color.Transparent))
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "fn" to f.optString("fn", ""),
                            "at" to f.optString("at", ""),
                            "depth" to i.toString(), "source" to "zx_callstack"))
                    }.padding(6.dp, 4.dp)) {
                    Text(if (isTop) "▸" else "·", fontSize = 9.sp,
                         color = if (isTop) ZxT.Err else ZxT.Text3,
                         modifier = Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.optString("fn", ""), fontSize = 9.5.sp,
                             color = if (isTop) ZxT.Err else ZxT.Text2,
                             fontFamily = ZxT.Mono, maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        // 行号：不知道"卡在第几行"就得自己翻源码
                        Text(f.optString("at", ""), fontSize = 8.5.sp,
                             color = ZxT.Text3, fontFamily = ZxT.Mono, maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                    }
                    if (f.optBoolean("ext", false))
                        Text("外部", fontSize = 8.sp, color = ZxT.Text3,
                             modifier = Modifier.clip(RoundedCornerShape(7.dp))
                                 .background(ZxT.Surface3).padding(6.dp, 1.dp))
                }
            }
            Text("已有 traceback 是**路径分叉**（能走哪几条）、" +
                 "ast 是语法结构，这个是**运行时栈帧**——" +
                 "栈顶标红、每层带文件行号、外部库单列。" +
                 "没有行号就只知道「到了这层」，不知道卡在第几行",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_uptime 可用性时间条 ══════════ */
    @Composable
    fun uptime(o: JSONObject, ctx: RenderContext) {
        val slots = o.optJSONArray("slots") ?: return
        val n = minOf(slots.length(), 48)
        var sel by remember { mutableIntStateOf(-1) }
        // 0 正常 / 1 降级 / 2 中断 / 3 无数据
        val cols = listOf(ZxT.Ok, ZxT.Warn, ZxT.Err, ZxT.Surface3)
        val bad = (0 until n).count { slots.optInt(it, 0) == 2 }
        val total = o.optDouble("total", 100.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "可用性"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (bad > 0) "$bad 段中断" else "%.2f%%".format(total),
                     fontSize = 9.5.sp,
                     color = if (bad > 0) ZxT.Err else ZxT.Ok,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
                for (k in 0 until n) {
                    val st = slots.optInt(k, 0).coerceIn(0, 3)
                    val on = sel == k
                    Box(Modifier.weight(1f).height(26.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(cols[st].copy(if (st == 3) 1f else .8f))
                        .border(if (on) 1.6.dp else 0.dp,
                            if (on) ZxT.Text else Color.Transparent,
                            RoundedCornerShape(2.dp))
                        .clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "slot" to k.toString(),
                                "state" to st.toString(),
                                "at" to (o.optJSONArray("at")?.optString(k, "") ?: ""),
                                "source" to "zx_uptime"))
                        })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(sel.takeIf { it >= 0 }?.let {
                    "${o.optJSONArray("at")?.optString(it, "")} · " +
                    listOf("正常", "降级", "中断", "无数据")[slots.optInt(it, 0).coerceIn(0, 3)]
                } ?: "点格子看那一刻", fontSize = 9.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                listOf("正常" to cols[0], "降级" to cols[1],
                       "中断" to cols[2], "无数据" to cols[3]).forEach { (lab, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 6.dp)) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.dp))
                            .background(c))
                        Spacer(Modifier.width(3.dp))
                        Text(lab, fontSize = 8.sp, color = ZxT.Text3)
                    }
                }
            }
            Text("已有 sla 是**指标达成**（百分比、剩余额度），" +
                 "这个是**时间轴本身**——每一格是一个时间片且能点开看那一刻。" +
                 "只知道「挂过」、不知道「哪一小时挂的」，就没法定位",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_playback 回放控制器 ══════════ */
    @Composable
    fun playback(o: JSONObject, ctx: RenderContext) {
        val total = o.optInt("frames", 60).coerceIn(2, 600)
        var cur by remember { mutableIntStateOf(o.optInt("cur", 0).coerceIn(0, total - 1)) }
        var playing by remember { mutableStateOf(false) }
        var speed by remember { mutableFloatStateOf(o.optDouble("speed", 1.0).toFloat()) }
        val marks = o.optJSONArray("marks")

        // 帧推进：不做定时器（插件不该自己起协程循环），交给宿主驱动
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "回放"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("帧 ${cur + 1}/$total", fontSize = 9.5.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(28.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    drawRoundRect(ZxT.Surface3, Offset(0f, H * .35f),
                                  Size(W, H * .3f), cornerRadius =
                                  androidx.compose.ui.geometry.CornerRadius(3f, 3f))
                    // 已播放段
                    val w = (cur + 1f) / total * W
                    drawRoundRect(PAL[0], Offset(0f, H * .35f), Size(w, H * .3f),
                                  cornerRadius =
                                  androidx.compose.ui.geometry.CornerRadius(3f, 3f))
                    // 标记点：回放里"第几帧发生了什么"全靠它
                    for (k in 0 until (marks?.length() ?: 0)) {
                        val f = marks!!.optJSONObject(k)?.optInt("f", -1) ?: -1
                        if (f < 0 || f >= total) continue
                        val x = (f + .5f) / total * W
                        val col = if (marks.optJSONObject(k)?.optBoolean("err", false) == true)
                            ZxT.Err else ZxT.Warn
                        drawRoundRect(col, Offset(x - 1f, H * .1f), Size(2.2f, H * .8f),
                                      cornerRadius =
                                      androidx.compose.ui.geometry.CornerRadius(1f, 1f))
                    }
                    // 游标
                    val cx = (cur + .5f) / total * W
                    drawCircle(ZxT.Text, 5f, Offset(cx, H * .5f))
                }
                Row(Modifier.fillMaxWidth().height(28.dp)) {
                    for (k in 0 until total) {
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            cur = k
                            ctx.callTool("zx_select", mapOf(
                                "frame" to k.toString(), "source" to "zx_playback"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (playing) "❚❚" else "▶", fontSize = 10.sp,
                     color = if (playing) ZxT.Text else ZxT.PriLite,
                     modifier = Modifier.clip(RoundedCornerShape(9.dp))
                         .background(ZxT.Surface2).clickable {
                             playing = !playing
                             ctx.callTool("zx_select", mapOf(
                                 "play" to playing.toString(), "at" to cur.toString(),
                                 "speed" to speed.toString(), "source" to "zx_playback"))
                         }.padding(10.dp, 3.dp))
                Text("◀", fontSize = 9.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(7.dp))
                         .background(ZxT.Surface2).clickable {
                             cur = (cur - 1).coerceAtLeast(0)
                             ctx.callTool("zx_select", mapOf(
                                 "frame" to cur.toString(), "source" to "zx_playback"))
                         }.padding(7.dp, 2.dp))
                Text("▶", fontSize = 9.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(7.dp))
                         .background(ZxT.Surface2).clickable {
                             cur = (cur + 1).coerceAtMost(total - 1)
                             ctx.callTool("zx_select", mapOf(
                                 "frame" to cur.toString(), "source" to "zx_playback"))
                         }.padding(7.dp, 2.dp))
                Spacer(Modifier.weight(1f))
                listOf(.5f, 1f, 2f).forEach { s ->
                    val on = abs(speed - s) < 1e-6
                    Text("${s}x", fontSize = 9.sp,
                         color = if (on) Color.White else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(7.dp))
                             .background(if (on) PAL[0] else ZxT.Surface2)
                             .clickable { speed = s }.padding(7.dp, 2.dp))
                }
            }
            marks?.let { m ->
                val hit = (0 until m.length()).firstOrNull {
                    m.optJSONObject(it)?.optInt("f", -1) == cur }
                if (hit != null)
                    Text("第 ${cur + 1} 帧：${m.optJSONObject(hit)?.optString("n", "")}",
                         fontSize = 9.5.sp,
                         color = if (m.optJSONObject(hit)?.optBoolean("err", false) == true)
                             ZxT.Err else ZxT.Warn,
                         lineHeight = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text("已有 seek（进度条）只能拖、scrubber 是片段缩略，" +
                 "这个带**帧号和倍速**——回放要能停在某一帧看，也要能快进。" +
                 "没有帧号就只知道「能播」，不知道现在在第几帧",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_pipe 流水线吞吐 ══════════ */
    @Composable
    fun pipe(o: JSONObject, ctx: RenderContext) {
        val stages = o.optJSONArray("stages") ?: return
        val n = minOf(stages.length(), 6)
        var sel by remember { mutableIntStateOf(-1) }
        // 瓶颈 = 吞吐最低的那一段
        val rates = (0 until n).map { stages.optJSONObject(it)?.optDouble("rate", 0.0) ?: 0.0 }
        val bottleneck = rates.indices.filter { rates[it] > 0 }.minByOrNull { rates[it] } ?: -1

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "流水线"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                if (bottleneck >= 0)
                    Text("瓶颈：${stages.optJSONObject(bottleneck)?.optString("n", "")}",
                         fontSize = 9.5.sp, color = ZxT.Warn)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until n) {
                    val st = stages.optJSONObject(i) ?: continue
                    val rate = st.optDouble("rate", 0.0)
                    val on = sel == i
                    val isBn = i == bottleneck
                    val col = if (isBn) ZxT.Warn else PAL[i % PAL.size]
                    Column(Modifier.weight(1f),
                           horizontalAlignment = Alignment.CenterHorizontally) {
                        // 段宽按吞吐比例：吞吐小的段看起来"细"，一眼看出瓶颈
                        Box(Modifier.fillMaxWidth(.9f).height(30.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(col.copy(if (on) .85f else .55f))
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "stage" to st.optString("n", ""),
                                    "rate" to rate.toString(),
                                    "bottleneck" to isBn.toString(),
                                    "source" to "zx_pipe"))
                            }, contentAlignment = Alignment.Center) {
                            Text("%.0f".format(rate), fontSize = 10.sp, color = Color.White,
                                 fontFamily = ZxT.Mono)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(st.optString("n", ""), fontSize = 8.sp,
                             color = if (on) ZxT.Text else ZxT.Text3,
                             maxLines = 1, overflow = TextOverflow.Clip,
                             textAlign = TextAlign.Center)
                        Text(st.optString("u", ""), fontSize = 7.5.sp,
                             color = ZxT.Text3, maxLines = 1)
                    }
                    if (i < n - 1)
                        Text("→", fontSize = 9.sp, color = ZxT.Text3)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Text("队列 ${o.optInt("queue", 0)}", fontSize = 8.5.sp,
                     color = ZxT.Text3, fontFamily = ZxT.Mono,
                     modifier = Modifier.weight(1f))
                Text("端到端 %.0f".format(if (rates.isNotEmpty()) rates.minOf { it } else 0.0),
                     fontSize = 8.5.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Text("已有 flow 画**结构**、critical 算**工期浮动**，" +
                 "这个看**吞吐**——最窄的那段就是瓶颈，橙色标出。" +
                 "端到端吞吐取决于最慢的一段，不是各段之和",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_corr 相关性矩阵 ══════════ */
    @Composable
    fun corr(o: JSONObject, ctx: RenderContext) {
        val names = o.optJSONArray("names") ?: return
        val m = minOf(names.length(), 8)
        val mat = o.optJSONArray("m") ?: return
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "相关性"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$m×$m", fontSize = 9.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.Canvas(
                    Modifier.fillMaxWidth().aspectRatio(1f)) {
                    val W = size.width; val H = size.height
                    val pad = 30f
                    val cell = (W - pad) / m
                    for (r in 0 until m) {
                        val row = mat.optJSONArray(r) ?: continue
                        for (c in 0 until m) {
                            val v = row.optDouble(c, 0.0).coerceIn(-1.0, 1.0)
                            // 正负用两种颜色：热力图不区分正负，相关性必须区分
                            val mag = abs(v).toFloat()
                            val col = if (v >= 0)
                                Color(red = .31f + .38f * mag, green = .27f + .55f * mag,
                                      blue = .89f - .30f * mag)
                            else
                                Color(red = .90f - .20f * mag, green = .51f - .35f * mag,
                                      blue = .29f - .10f * mag)
                            drawRoundRect(col.copy(.25f + .65f * mag),
                                          Offset(pad + c * cell + 1f, r * cell + 1f),
                                          Size(cell - 2f, cell - 2f),
                                          cornerRadius =
                                          androidx.compose.ui.geometry.CornerRadius(2f, 2f))
                            // 对角线：自己和自己必然是 1，画了是噪音
                            if (r == c)
                                drawRoundRect(ZxT.Surface3,
                                              Offset(pad + c * cell + 1f, r * cell + 1f),
                                              Size(cell - 2f, cell - 2f),
                                              cornerRadius =
                                              androidx.compose.ui.geometry.CornerRadius(2f, 2f))
                        }
                    }
                }
                // 命中层
                Column(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    for (r in 0 until m) {
                        Row(Modifier.weight(1f)) {
                            Spacer(Modifier.width(30.dp))
                            for (c in 0 until m) {
                                val on = sel?.first == r && sel?.second == c
                                Box(Modifier.weight(1f).fillMaxHeight()
                                    .border(if (on) 1.6.dp else 0.dp,
                                        if (on) ZxT.Text else Color.Transparent)
                                    .clickable {
                                        sel = r to c
                                        ctx.callTool("zx_select", mapOf(
                                            "a" to names.optString(r, ""),
                                            "b" to names.optString(c, ""),
                                            "r" to (mat.optJSONArray(r)
                                                ?.optDouble(c, 0.0) ?: 0.0).toString(),
                                            "source" to "zx_corr"))
                                    })
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                sel?.let { (r, c) ->
                    val v = mat.optJSONArray(r)?.optDouble(c, 0.0) ?: 0.0
                    Text("${names.optString(r, "")} ↔ ${names.optString(c, "")}" +
                         " = %.2f".format(v), fontSize = 9.5.sp,
                         color = if (v >= 0) ZxT.PriLite else ZxT.Err,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                } ?: Text("点格子看相关系数", fontSize = 9.sp, color = ZxT.Text3,
                          modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("-1", fontSize = 8.sp, color = ZxT.Err)
                    Spacer(Modifier.width(3.dp))
                    Box(Modifier.width(30.dp).height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(ZxT.Surface3))
                    Spacer(Modifier.width(3.dp))
                    Text("+1", fontSize = 8.sp, color = ZxT.PriLite)
                }
            }
            Text("已有 matrixheat 是通用热力图，不区分正负、不标变量名。" +
                 "相关性**负相关和正相关是完全相反的结论**，必须两种颜色。" +
                 "对角线恒为 1，画了是噪音所以挖空",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_radar2 多对象雷达 ══════════ */
    @Composable
    fun radar2(o: JSONObject, ctx: RenderContext) {
        val axes = o.optJSONArray("axes") ?: return
        val na = minOf(axes.length(), 8)
        val series = o.optJSONArray("series") ?: return
        val ns = minOf(series.length(), 4)
        var sel by remember { mutableIntStateOf(-1) }
        var hide by remember { mutableStateOf(setOf<Int>()) }
        val max = o.optDouble("max", 100.0).coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "多维对比"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$na 维 × $ns", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(160.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2; val cy = size.height / 2
                    val R = minOf(cx, cy) * .82f
                    // 网格
                    for (g in 1..4) {
                        val r = R * g / 4
                        drawPath(Path().apply {
                            for (a in 0 until na) {
                                val ang = (-90f + a * 360f / na) * PI.toFloat() / 180f
                                val x = cx + r * cos(ang); val y = cy + r * sin(ang)
                                if (a == 0) moveTo(x, y) else lineTo(x, y)
                            }
                            close() }, ZxT.Surface3, style = Stroke(1f))
                    }
                    for (a in 0 until na) {
                        val ang = (-90f + a * 360f / na) * PI.toFloat() / 180f
                        drawLine(ZxT.Surface3, Offset(cx, cy),
                                 Offset(cx + R * cos(ang), cy + R * sin(ang)), 1f)
                    }
                    for (s in 0 until ns) {
                        if (s in hide) continue
                        val arr = series.optJSONObject(s)?.optJSONArray("v") ?: continue
                        val on = sel == s
                        val col = PAL[s % PAL.size]
                        drawPath(Path().apply {
                            for (a in 0 until na) {
                                val ang = (-90f + a * 360f / na) * PI.toFloat() / 180f
                                val t = (arr.optDouble(a, 0.0) / max)
                                    .toFloat().coerceIn(0f, 1f)
                                val x = cx + R * t * cos(ang)
                                val y = cy + R * t * sin(ang)
                                if (a == 0) moveTo(x, y) else lineTo(x, y)
                            }
                            close() }, col.copy(if (on) .38f else .18f))
                        drawPath(Path().apply {
                            for (a in 0 until na) {
                                val ang = (-90f + a * 360f / na) * PI.toFloat() / 180f
                                val t = (arr.optDouble(a, 0.0) / max)
                                    .toFloat().coerceIn(0f, 1f)
                                val x = cx + R * t * cos(ang)
                                val y = cy + R * t * sin(ang)
                                if (a == 0) moveTo(x, y) else lineTo(x, y)
                            }
                            close() }, col, style = Stroke(if (on) 2.2f else 1.4f))
                        for (a in 0 until na) {
                            val ang = (-90f + a * 360f / na) * PI.toFloat() / 180f
                            val t = (arr.optDouble(a, 0.0) / max).toFloat().coerceIn(0f, 1f)
                            drawCircle(col, if (on) 3.5f else 2.5f,
                                       Offset(cx + R * t * cos(ang), cy + R * t * sin(ang)))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                for (a in 0 until na)
                    Text(axes.optString(a, ""), fontSize = 7.5.sp, color = ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Clip)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in 0 until ns) {
                    val nm = series.optJSONObject(s)?.optString("n", "") ?: ""
                    val on = sel == s
                    val off = s in hide
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (on) ZxT.Surface2 else Color.Transparent)
                            .clickable {
                                sel = s
                                ctx.callTool("zx_select", mapOf(
                                    "series" to nm, "hidden" to off.toString(),
                                    "source" to "zx_radar2"))
                            }.padding(5.dp, 2.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape)
                            .background(if (off) ZxT.Surface3 else PAL[s % PAL.size]))
                        Spacer(Modifier.width(3.dp))
                        Text(nm, fontSize = 8.5.sp,
                             color = if (off) ZxT.Text3 else
                                 (if (on) ZxT.Text else ZxT.Text2),
                             maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        // 图例可点隐藏：4 条线叠在一起看不清，必须能关掉
                        Text(if (off) "显示" else "隐藏", fontSize = 7.5.sp,
                             color = ZxT.Text3,
                             modifier = Modifier.clickable {
                                 hide = if (off) hide - s else hide + s })
                    }
                }
            }
            Text("已有 radar 是**单对象**、polar 是**极坐标柱**，" +
                 "这个是**多个对象叠在同一组轴上**——比谁在哪个维度强。" +
                 "超过 2 条就必然重叠，所以图例要能点掉",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_choro 区域着色 ══════════ */
    @Composable
    fun choro(o: JSONObject, ctx: RenderContext) {
        val cells = o.optJSONArray("cells") ?: return
        val n = minOf(cells.length(), 24)
        val cols = o.optInt("cols", 6).coerceIn(2, 8)
        var sel by remember { mutableIntStateOf(-1) }
        val vals = (0 until n).map { cells.optJSONObject(it)?.optDouble("v", 0.0) ?: 0.0 }
        val mn = vals.minOrNull() ?: 0.0
        val mx = vals.maxOrNull() ?: 1.0
        val span = (mx - mn).coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "区域分布"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 个区域", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            val rowsN = (n + cols - 1) / cols
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (r in 0 until rowsN) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (c in 0 until cols) {
                            val i = r * cols + c
                            if (i >= n) { Spacer(Modifier.weight(1f)); continue }
                            val cell = cells.optJSONObject(i) ?: continue
                            val t = ((vals[i] - mn) / span).toFloat().coerceIn(0f, 1f)
                            val on = sel == i
                            Box(Modifier.weight(1f).height(30.dp)
                                .clip(RoundedCornerShape(3.dp))
                                // 靛蓝→青：低值不发灰，弱信号也要看得见
                                .background(Color(red = .31f + .38f * t,
                                                  green = .27f + .55f * t,
                                                  blue = .89f - .30f * t))
                                .border(if (on) 1.6.dp else 0.dp,
                                    if (on) ZxT.Text else Color.Transparent,
                                    RoundedCornerShape(3.dp))
                                .clickable {
                                    sel = i
                                    ctx.callTool("zx_select", mapOf(
                                        "region" to cell.optString("n", ""),
                                        "v" to vals[i].toString(),
                                        "source" to "zx_choro"))
                                }, contentAlignment = Alignment.Center) {
                                Text(cell.optString("n", ""), fontSize = 8.sp,
                                     color = if (t > .55f) Color.White else ZxT.Text,
                                     maxLines = 1, overflow = TextOverflow.Clip)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                sel.takeIf { it >= 0 }?.let {
                    Text("${cells.optJSONObject(it)?.optString("n", "")} = " +
                         "%.1f".format(vals[it]), fontSize = 9.5.sp,
                         color = ZxT.PriLite, modifier = Modifier.weight(1f))
                } ?: Text("点区域看数值", fontSize = 9.sp, color = ZxT.Text3,
                          modifier = Modifier.weight(1f))
                Text("%.1f ~ %.1f".format(mn, mx), fontSize = 8.5.sp,
                     color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Text("已有 geo 是**经纬度点阵**、datamap 是**抽象网格**，" +
                 "这个是**区域着色**——按数值深浅而不是按位置。" +
                 "低值不发灰，**弱信号也要看得见**，这是着色图的意义",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_toc 目录导航 ══════════ */
    @Composable
    fun toc(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 14)
        var cur by remember { mutableIntStateOf(o.optInt("cur", 0).coerceIn(0, n - 1)) }
        var collapsed by remember { mutableStateOf(setOf<Int>()) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "目录"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 节", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val lv = it.optInt("lv", 1).coerceIn(1, 3)
                val on = cur == i
                val hasKids = it.optBoolean("kids", false)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .padding(start = ((lv - 1) * 12).dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        cur = i
                        ctx.callTool("zx_select", mapOf(
                            "section" to it.optString("n", ""),
                            "level" to lv.toString(), "source" to "zx_toc"))
                    }.padding(6.dp, 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    // 当前项必须有标记，否则那只是列表不是导航
                    Box(Modifier.width(3.dp).height(14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (on) PAL[0] else Color.Transparent))
                    Spacer(Modifier.width(6.dp))
                    Text(it.optString("n", ""), fontSize = if (11 - lv >= 9) (11 - lv).sp else 9.sp,
                         color = if (on) ZxT.Text else
                             (if (lv == 1) ZxT.Text2 else ZxT.Text3),
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    if (hasKids)
                        Text(if (i in collapsed) "▸" else "▾", fontSize = 9.sp,
                             color = ZxT.Text3,
                             modifier = Modifier.clickable {
                                 collapsed = if (i in collapsed) collapsed - i
                                 else collapsed + i })
                }
            }
            Text("已有 breadcrumb 是**路径**（你在哪）、minmap 是**缩略图**，" +
                 "这个是**章节列表**——在一长篇里跳到某一节。" +
                 "没有当前项标记就只是个列表，不是导航",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_glossary 术语表 ══════════ */
    @Composable
    fun glossary(o: JSONObject, ctx: RenderContext) {
        val terms = o.optJSONArray("terms") ?: return
        val n = minOf(terms.length(), 12)
        var open by remember { mutableIntStateOf(-1) }
        var q by remember { mutableStateOf(o.optString("q", "")) }
        val shown = (0 until n).filter {
            q.isBlank() || terms.optJSONObject(it)?.optString("t", "")
                ?.contains(q, true) == true }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "术语表"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${shown.size} 条", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            if (shown.isEmpty())
                Text("没有匹配的术语", fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(vertical = 10.dp))
            shown.forEach { i ->
                val t = terms.optJSONObject(i) ?: return@forEach
                val on = open == i
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        open = if (on) -1 else i
                        ctx.callTool("zx_select", mapOf(
                            "term" to t.optString("t", ""), "source" to "zx_glossary"))
                    }.padding(6.dp, 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.optString("t", ""), fontSize = 10.sp,
                             color = if (on) ZxT.PriLite else ZxT.Text,
                             modifier = Modifier.weight(1f), maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        t.optString("abbr").takeIf { it.isNotBlank() }?.let {
                            Text(it, fontSize = 8.sp, color = ZxT.Text3,
                                 fontFamily = ZxT.Mono,
                                 modifier = Modifier.clip(RoundedCornerShape(7.dp))
                                     .background(ZxT.Surface3).padding(6.dp, 1.dp))
                        }
                        Text(if (on) "▾" else "▸", fontSize = 9.sp, color = ZxT.Text3)
                    }
                    if (on) {
                        Spacer(Modifier.height(3.dp))
                        Text(t.optString("d", ""), fontSize = 9.5.sp,
                             color = ZxT.Text2, lineHeight = 14.sp)
                        t.optString("see").takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(2.dp))
                            Text("参见：$it", fontSize = 9.sp, color = ZxT.Text3)
                        }
                    }
                }
            }
            Text("已有 keyvalue 是**键值展示**、datalist 是**列表**，" +
                 "这个是**术语解释**——点开看定义而不是并列展示。" +
                 "术语表不展开就失去意义：一屏塞不下所有定义",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_survey 问卷 ══════════ */
    @Composable
    fun survey(o: JSONObject, ctx: RenderContext) {
        val qs = o.optJSONArray("qs") ?: return
        val n = minOf(qs.length(), 6)
        // 答案存路径 → 值，中间态不回传（每帧回传会刷爆上下文）
        val ans = remember { mutableStateMapOf<String, String>() }
        var step by remember { mutableIntStateOf(0) }
        val req = (0 until n).map { qs.optJSONObject(it)?.optBoolean("req", false) ?: false }
        // 必答题未答就不让下一步，但不阻止输入——阻止输入用户会以为坏了
        val blocked = req.getOrNull(step) == true &&
            ans[qs.optJSONObject(step)?.optString("id", "$step")].isNullOrBlank()

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "问卷"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${step + 1}/$n", fontSize = 9.5.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 0 until n) {
                    Box(Modifier.weight(1f).height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= step) PAL[0] else ZxT.Surface3))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (step < n) {
                val q = qs.optJSONObject(step) ?: JSONObject()
                val id = q.optString("id", "$step")
                val kind = q.optString("k", "single")
                Text(q.optString("q", ""), fontSize = 11.sp, color = ZxT.Text,
                     lineHeight = 15.sp)
                if (req[step])
                    Text("必答", fontSize = 8.5.sp, color = ZxT.Warn,
                         modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(6.dp))
                val opts = q.optJSONArray("opts")
                when (kind) {
                    "scale" -> {
                        val mx = q.optInt("max", 5).coerceIn(2, 10)
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (v in 1..mx) {
                                val on = ans[id] == v.toString()
                                Box(Modifier.weight(1f).height(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (on) PAL[0] else ZxT.Surface2)
                                    .clickable { ans[id] = v.toString() },
                                    contentAlignment = Alignment.Center) {
                                    Text("$v", fontSize = 11.sp,
                                         color = if (on) Color.White else ZxT.Text2)
                                }
                            }
                        }
                    }
                    "multi" -> {
                        val picked = ans[id]?.split(",")?.filter { it.isNotBlank() }
                            ?: emptyList()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (k in 0 until minOf(opts?.length() ?: 0, 6)) {
                                val op = opts?.optString(k, "") ?: ""
                                val on = op in picked
                                Row(Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                                    .clickable {
                                        val next = if (on) picked - op else picked + op
                                        ans[id] = next.joinToString(",")
                                    }.padding(7.dp, 5.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(15.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(if (on) PAL[0] else ZxT.Surface3))
                                    Spacer(Modifier.width(7.dp))
                                    Text(op, fontSize = 10.sp,
                                         color = if (on) ZxT.Text else ZxT.Text2)
                                }
                            }
                        }
                    }
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (k in 0 until minOf(opts?.length() ?: 0, 6)) {
                                val op = opts?.optString(k, "") ?: ""
                                val on = ans[id] == op
                                Row(Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                                    .clickable { ans[id] = op }.padding(7.dp, 5.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(15.dp).clip(CircleShape)
                                        .background(if (on) PAL[0] else ZxT.Surface3))
                                    Spacer(Modifier.width(7.dp))
                                    Text(op, fontSize = 10.sp,
                                         color = if (on) ZxT.Text else ZxT.Text2)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (step > 0)
                        Text("上一题", fontSize = 10.sp, color = ZxT.Text3,
                             modifier = Modifier.clip(RoundedCornerShape(9.dp))
                                 .background(ZxT.Surface2)
                                 .clickable { step-- }.padding(11.dp, 4.dp))
                    Spacer(Modifier.weight(1f))
                    Text(if (step == n - 1) "提交" else "下一题",
                         fontSize = 10.sp,
                         color = if (blocked) ZxT.Text3 else Color.White,
                         modifier = Modifier.clip(RoundedCornerShape(9.dp))
                             .background(if (blocked) ZxT.Surface3 else PAL[0])
                             .clickable {
                                 if (blocked) {
                                     ctx.callTool("zx_select", mapOf(
                                         "blocked" to id, "source" to "zx_survey"))
                                     return@clickable
                                 }
                                 if (step == n - 1) {
                                     // 提交时才打包一次，中间态不回传
                                     ctx.callTool("zx_select", mapOf(
                                         "answers" to ans.entries.joinToString(";") {
                                             "${it.key}=${it.value}" },
                                         "source" to "zx_survey"))
                                 } else step++
                             }.padding(11.dp, 4.dp))
                }
                if (blocked)
                    Text("这题是必答的", fontSize = 9.sp, color = ZxT.Warn,
                         modifier = Modifier.padding(top = 4.dp))
            }
            Text("已有 form 是**一组字段平铺**、wizard 是**分步填表单**，" +
                 "这个是**问卷**——一屏一题、有进度条、必答未答拦在提交。" +
                 "多选和评分不是文本框能替代的",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_punch 打点卡 ══════════ */
    @Composable
    fun punch(o: JSONObject, ctx: RenderContext) {
        val days = o.optInt("days", 7).coerceIn(2, 7)
        val hours = o.optInt("hours", 24).coerceIn(2, 24)
        val pts = o.optJSONArray("pts") ?: return
        val n = minOf(pts.length(), 60)
        var sel by remember { mutableIntStateOf(-1) }
        val grid = Array(days) { IntArray(hours) }
        for (k in 0 until n) {
            val p = pts.optJSONObject(k) ?: continue
            val d = p.optInt("d", 0); val h = p.optInt("h", 0)
            if (d in 0 until days && h in 0 until hours) grid[d][h]++
        }
        val mx = grid.maxOf { it.maxOrNull() ?: 0 }.coerceAtLeast(1)
        val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "活动分布"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 次 · 峰值 $mx", fontSize = 9.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(16.dp)) {
                    for (d in 0 until days)
                        Text(dayNames.getOrElse(d) { "" }, fontSize = 7.sp,
                             color = ZxT.Text3, modifier = Modifier.height(13.dp))
                }
                Column(Modifier.weight(1f)) {
                    for (d in 0 until days) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            for (h in 0 until hours) {
                                val v = grid[d][h]
                                val t = (v.toFloat() / mx).coerceIn(0f, 1f)
                                val on = sel == d * hours + h
                                Box(Modifier.weight(1f).height(11.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    // 0 次也要有底色，否则分不清"没数据"和"零次"
                                    .background(if (v == 0) ZxT.Surface2
                                    else Color(red = .31f + .38f * t,
                                               green = .27f + .55f * t,
                                               blue = .89f - .30f * t))
                                    .border(if (on) 1.2.dp else 0.dp,
                                        if (on) ZxT.Text else Color.Transparent,
                                        RoundedCornerShape(1.5.dp))
                                    .clickable {
                                        sel = d * hours + h
                                        ctx.callTool("zx_select", mapOf(
                                            "day" to dayNames.getOrElse(d) { "$d" },
                                            "hour" to h.toString(),
                                            "count" to v.toString(),
                                            "source" to "zx_punch"))
                                    })
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 3.dp)) {
                Text("0 时", fontSize = 7.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("${hours - 1} 时", fontSize = 7.sp, color = ZxT.Text3)
            }
            sel.takeIf { it >= 0 }?.let { s ->
                val d = s / hours; val h = s % hours
                Text("周${dayNames.getOrElse(d) { "$d" }} ${h}:00 — ${grid[d][h]} 次",
                     fontSize = 9.5.sp, color = ZxT.PriLite,
                     modifier = Modifier.padding(top = 4.dp))
            }
            Text("已有 calheat 是**日历×天**、pulse 是**单轴时间强度**，" +
                 "这个是**星期×小时双轴**——看出「什么时段最活跃」。 " +
                 "零次也要画底色，否则分不清「没数据」和「确实是 0」",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }
}

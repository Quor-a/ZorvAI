@file:Suppress("FunctionName", "unused")
package com.ai.assistance.quro.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.*

// ══════════════════════════════════════
// Tool Call Icon Library — 100 ultra-small
// tool invocation icons organized in 20 categories.
// Each icon is Canvas-drawn at 22×22 dp target.
// ══════════════════════════════════════

/**
 * 20 categories of tools, each with a distinct color token.
 * All colors adapt to light/dark theme via [isLight].
 */
data class ToolCategoryToken(
    val id: String,
    val label: String,
    val colorLight: Color,
    val colorDark: Color,
) {
    fun color(isLight: Boolean): Color = if (isLight) colorLight else colorDark
}

val TOOL_CATEGORIES = listOf(
    ToolCategoryToken("file",   "文件系统", Color(0xFF7C3AED), Color(0xFFA78BFA)),
    ToolCategoryToken("web",    "网络/Web", Color(0xFF059669), Color(0xFF34D399)),
    ToolCategoryToken("term",   "终端/Shell", Color(0xFFD97706), Color(0xFFFBBF24)),
    ToolCategoryToken("code",   "代码执行", Color(0xFF0891B2), Color(0xFF22D3EE)),
    ToolCategoryToken("ai",     "AI/模型",  Color(0xFFDB2777), Color(0xFFF472B6)),
    ToolCategoryToken("media",  "媒体生成", Color(0xFFC026D3), Color(0xFFE879F9)),
    ToolCategoryToken("ui",     "UI自动化", Color(0xFF4F46E5), Color(0xFF818CF8)),
    ToolCategoryToken("sys",    "系统信息", Color(0xFFEA580C), Color(0xFFFB923C)),
    ToolCategoryToken("com",    "通信",     Color(0xFF16A34A), Color(0xFF4ADE80)),
    ToolCategoryToken("data",   "数据处理", Color(0xFF0284C7), Color(0xFF38BDF8)),
    ToolCategoryToken("sec",    "安全",     Color(0xFFDC2626), Color(0xFFF87171)),
    ToolCategoryToken("time",   "时间/日程",Color(0xFFCA8A04), Color(0xFFFACC15)),
    ToolCategoryToken("loc",    "定位/地图",Color(0xFF65A30D), Color(0xFFA3E635)),
    ToolCategoryToken("wx",     "天气/环境",Color(0xFF0369A1), Color(0xFF7DD3FC)),
    ToolCategoryToken("mus",    "媒体控制", Color(0xFFBE185D), Color(0xFFF9A8D4)),
    ToolCategoryToken("dev",    "DevOps",   Color(0xFF475569), Color(0xFF94A3B8)),
    ToolCategoryToken("cms",    "CMS/模块", Color(0xFF86198F), Color(0xFFC084FC)),
    ToolCategoryToken("bot",    "Bot/IM",   Color(0xFF0D9488), Color(0xFF5EEAD4)),
    ToolCategoryToken("mem",    "记忆库",   Color(0xFF1D4ED8), Color(0xFF93C5FD)),
    ToolCategoryToken("viz",    "可视化",   Color(0xFFB45309), Color(0xFFFDBA74)),
)

/**
 * 100 tool kinds — the complete enumeration of invocable tools.
 * Grouped by category; each maps to a unique Canvas drawing procedure.
 */
enum class ToolKind(val category: String, val displayName: String, val shortDesc: String) {
    // ── File System ──
    READ_FILE("file","read_file","读取文件"),
    WRITE_FILE("file","write_file","写入文件"),
    DELETE_FILE("file","delete_file","删除文件"),
    CREATE_FILE("file","create_file","新建文件"),
    LIST_DIR("file","list_dir","列目录"),
    DOWNLOAD("file","download","下载"),

    // ── Web / Network ──
    WEB_SEARCH("web","web_search","网页搜索"),
    WEB_FETCH("web","web_fetch","抓取网页"),
    OPEN_URL("web","open_url","打开链接"),
    BROWSER("web","browser","内置浏览器"),
    API_CALL("web","api_call","API 调用"),
    CLI_EXEC("web","cli_exec","命令行执行"),

    // ── Terminal / Shell ──
    TERMINAL("term","terminal","终端执行"),
    SHELL_RUN("term","shell_run","Shell 运行"),
    LINUX_ENV("term","linux_env","Linux 环境"),
    PROOT("term","proot","Proot 容器"),
    ROOT_EXEC("term","root_exec","ROOT 执行"),

    // ── Code Execution ──
    CODE_RUN("code","code_run","运行代码"),
    CODE_EDIT("code","code_edit","编辑代码"),
    CODE_LINT("code","code_lint","代码检查"),
    IDE_OPEN("code","ide_open","打开 IDE"),
    TEST_RUN("code","test_run","运行测试"),
    PACKAGE_MGR("code","package_mgr","包管理器"),

    // ── AI / Model ──
    LLM_CHAT("ai","llm_chat","LLM 对话"),
    CLASSIFY("ai","classify","文本分类"),
    TRANSLATE("ai","translate","翻译"),
    SUMMARIZE("ai","summarize","摘要"),
    OCR("ai","ocr","OCR 识别"),
    EMBEDDING("ai","embedding","向量嵌入"),

    // ── Media Generation ──
    IMAGE_GEN("media","image_gen","图像生成"),
    IMAGE_CROP("media","image_crop","裁剪图片"),
    VIDEO_GEN("media","video_gen","视频生成"),
    AUDIO_GEN("media","audio_gen","音频生成"),
    IMAGE_EDIT("media","image_edit","图像编辑"),
    FILTER_APPLY("media","filter_apply","滤镜处理"),

    // ── UI Automation ──
    TAP("ui","tap","点击元素"),
    SWIPE("ui","swipe","滑动手势"),
    SCROLL("ui","scroll","滚动屏幕"),
    INPUT_TEXT("ui","input_text","输入文字"),
    GET_SCREEN("ui","get_screen","截屏/读屏"),
    GRID_TAP("ui","grid_tap","网格点击"),

    // ── System Info ──
    DEVICE_INFO("sys","device_info","设备信息"),
    BATTERY("sys","battery","电池状态"),
    NETWORK("sys","network","网络状态"),
    STORAGE("sys","storage","存储空间"),
    CPU_MONITOR("sys","cpu_monitor","CPU 监控"),

    // ── Communication ──
    NOTIFY("com","notify","发通知"),
    SEND_EMAIL("com","send_email","发邮件"),
    PHONE_CALL("com","phone_call","打电话"),
    SEND_MSG("com","send_msg","发消息"),
    PUSH_BROADCAST("com","push_broadcast","推送广播"),

    // ── Data Processing ──
    DB_QUERY("data","db_query","数据库查询"),
    CSV_PARSE("data","csv_parse","CSV 解析"),
    JSON_XFORM("data","json_xform","JSON 转换"),
    DATA_CHART("data","data_chart","数据图表"),
    ANALYTICS("data","analytics","数据分析"),

    // ── Security ──
    ENCRYPT("sec","encrypt","加密"),
    DECRYPT("sec","decrypt","解密"),
    PERMISSION("sec","permission","权限检查"),
    VERIFY("sec","verify","签名验证"),
    RISK_CHECK("sec","risk_check","风险检查"),

    // ── Time / Schedule ──
    SET_ALARM("time","set_alarm","设闹钟"),
    TIMER("time","timer","计时器"),
    CALENDAR("time","calendar","日历事件"),
    SCHEDULE_TASK("time","schedule_task","计划任务"),
    COUNTDOWN("time","countdown","倒计时"),

    // ── Location / Map ──
    GPS_LOC("loc","gps_loc","GPS 定位"),
    GEOCODE("loc","geocode","地理编码"),
    NAVIGATE_TO("loc","navigate_to","导航"),
    TRACK_ROUTE("loc","track_route","轨迹追踪"),

    // ── Weather / Environment ──
    WEATHER_NOW("wx","weather_now","实时天气"),
    WEATHER_FORECAST("wx","weather_forecast","天气预报"),
    WEATHER_ALERT("wx","weather_alert","天气预警"),
    AIR_QUALITY("wx","air_quality","空气质量"),

    // ── Media Control ──
    MUSIC_PLAY("mus","music_play","播放音乐"),
    MUSIC_PAUSE("mus","music_pause","暂停"),
    VIDEO_PLAY("mus","video_play","播放视频"),
    VOLUME_CTRL("mus","volume_ctrl","音量控制"),

    // ── DevOps ──
    GIT_OP("dev","git_op","Git 操作"),
    BUILD_PROJ("dev","build_proj","构建项目"),
    DEPLOY("dev","deploy","部署发布"),
    DOCKER("dev","docker","Docker 容器"),
    CI_CD("dev","ci_cd","CI/CD 流水线"),

    // ── CMS / Module ──
    CMS_DEPLOY("cms","cms_deploy","CMS 部署"),
    CMS_PLUGIN("cms","cms_plugin","CMS 插件"),
    CMS_MODULE("cms","cms_module","CMS 模块"),
    CMS_TERMINAL("cms","cms_terminal","CMS 终端"),

    // ── Bot / IM ──
    BOT_SEND("bot","bot_send","Bot 发消息"),
    BOT_RECV("bot","bot_recv","Bot 收消息"),
    BOT_WEBHOOK("bot","bot_webhook","Webhook 推送"),
    BOT_CARD("bot","bot_card","Bot 卡片消息"),

    // ── Memory ──
    MEM_SAVE("mem","mem_save","保存记忆"),
    MEM_SEARCH("mem","mem_search","搜索记忆"),
    MEM_LIST("mem","mem_list","列出记忆"),
    MEM_DELETE("mem","mem_delete","删除记忆"),

    // ── Visualization ──
    CHART_BAR("viz","chart_bar","柱状图"),
    CHART_PIE("viz","chart_pie","饼图"),
    CHART_LINE("viz","chart_line","折线图"),
    TABLE_RENDER("viz","table_render","表格渲染"),

    // ── Health / Monitor ──
    HEALTH_CHECK("health","health_check","健康检查"),
    LOG_VIEW("health","log_view","日志查看"),
    ERROR_ALERT("health","error_alert","错误告警"),
    PERF_STATS("health","perf_stats","性能统计"),

    // ── Speech ──
    TTS("ai","tts","语音合成"),

    // ── Fallback for unknown tools ──
    GENERIC("tool","*","通用工具"),
    ;

    companion object {
        private val NAME_MAP = entries.associateBy { it.displayName.lowercase() }

        /**
         * Map an actual tool name emitted by the LLM (e.g. "read_file", "mcp_web_search")
         * to its [ToolKind]. Strips common prefixes (quro_/local_/mcp_/server_/builtin_)
         * and tail path/qualifier segments, then matches case-insensitively.
         * Unknown names fall back to [GENERIC].
         */
        fun fromToolName(raw: String): ToolKind {
            val rawNorm = raw.lowercase().trim()
            val simple = raw.substringAfterLast('.').substringAfterLast('/')
                .removePrefix("quro_").removePrefix("local_")
                .removePrefix("mcp_").removePrefix("server_").removePrefix("builtin_")
                .lowercase().trim()
            return NAME_MAP[simple] ?: NAME_MAP[rawNorm] ?: GENERIC
        }
    }
}

/** Resolve category token from [ToolKind]. */
fun ToolKind.categoryToken(): ToolCategoryToken =
    TOOL_CATEGORIES.firstOrNull { it.id == category } ?: TOOL_CATEGORIES.last()


// ══════════════════════════════════════
// ICON DRAWING — Canvas procedures
// Each ToolKind maps to a unique visual glyph.
// Target size: 22×22dp logical viewport.
// ══════════════════════════════════════

private fun DrawScope.drawFileRead(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // document shape
    drawRoundRect(color = tint.copy(alpha = 0.15f), topLeft = Offset(c.x - r, c.y - r * 1.1f), size = Size((c.x + r) - (c.x - r), (c.y + r * 1.1f) - (c.y - r * 1.1f)), cornerRadius = CornerRadius(r * 0.2f, r * 0.2f), style = Stroke(width = r * 0.12f))
    // fold corner
    drawLine(tint, Offset(c.x, c.y - r * 0.5f), Offset(c.x + r * 0.6f, c.y - r * 1.1f), strokeWidth = r * 0.1f)
    drawLine(tint, Offset(c.x + r * 0.6f, c.y - r * 1.1f), Offset(c.x + r * 0.6f, c.y - r * 0.5f), strokeWidth = r * 0.1f)
    // lines
    val y0 = c.y; val lw = r * 0.5f
    drawLine(tint, Offset(c.x - lw, y0), Offset(c.x + lw, y0), strokeWidth = r * 0.08f)
    drawLine(tint, Offset(c.x - lw, y0 + r * 0.35f), Offset(c.x + lw, y0 + r * 0.35f), strokeWidth = r * 0.08f)
}

private fun DrawScope.drawFileWrite(tint: Color) {
    val c = center; val r = size.minDimension * 0.32f
    // down arrow into doc
    drawLine(tint, Offset(c.x, c.y - r), Offset(c.x, c.y + r * 0.5f), strokeWidth = r * 0.15f)
    // arrow head
    drawLine(tint, Offset(c.x - r * 0.4f, c.y + r * 0.15f), Offset(c.x, c.y + r * 0.5f), strokeWidth = r * 0.12f)
    drawLine(tint, Offset(c.x + r * 0.4f, c.y + r * 0.15f), Offset(c.x, c.y + r * 0.5f), strokeWidth = r * 0.12f)
    // bracket bottom-right
    drawLine(tint, Offset(c.x + r * 0.3f, c.y + r * 0.5f), Offset(c.x + r * 0.9f, c.y + r * 0.5f), strokeWidth = r * 0.1f)
    drawLine(tint, Offset(c.x + r * 0.9f, c.y + r * 0.5f), Offset(c.x + r * 0.9f, c.y + r), strokeWidth = r * 0.1f)
}

private fun DrawScope.drawDeleteFile(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // trash can
    drawRoundRect(color = tint.copy(alpha = 0.12f), topLeft = Offset(c.x - r, c.y - r * 0.3f), size = Size((c.x + r) - (c.x - r), (c.y + r) - (c.y - r * 0.3f)), cornerRadius = CornerRadius(r * 0.15f, r * 0.15f), style = Stroke(r * 0.1f))
    // lid
    drawLine(tint, Offset(c.x - r * 0.7f, c.y - r * 0.3f), Offset(c.x + r * 0.7f, c.y - r * 0.3f), strokeWidth = r * 0.12f)
    // vertical lines
    drawLine(tint, Offset(c.x - r * 0.4f, c.y), Offset(c.x - r * 0.4f, c.y + r * 0.6f), strokeWidth = r * 0.07f)
    drawLine(tint, Offset(c.x + r * 0.4f, c.y), Offset(c.x + r * 0.4f, c.y + r * 0.6f), strokeWidth = r * 0.07f)
}

private fun DrawScope.drawCreateFile(tint: Color) {
    val c = center; val r = size.minDimension * 0.32f
    drawRect(color = tint.copy(alpha = 0.12f), topLeft = Offset(c.x - r, c.y - r), size = Size((c.x + r) - (c.x - r), (c.y + r) - (c.y - r)), style = Stroke(r * 0.08f))
    drawLine(tint, Offset(c.x - r * 0.5f, c.y), Offset(c.x + r * 0.5f, c.y), strokeWidth = r * 0.12f)
    drawLine(tint, Offset(c.x, c.y - r * 0.5f), Offset(c.x, c.y + r * 0.5f), strokeWidth = r * 0.12f)
}

private fun DrawScope.drawListDir(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // folder
    drawRoundRect(color = tint.copy(alpha = 0.14f), topLeft = Offset(c.x - r, c.y - r * 0.6f), size = Size((c.x + r) - (c.x - r), (c.y + r) - (c.y - r * 0.6f)), cornerRadius = CornerRadius(r * 0.2f, r * 0.15f), style = Stroke(r * 0.09f))
    // tab
    drawLine(tint, Offset(c.x - r * 0.6f, c.y - r * 0.6f), Offset(c.x - r * 0.1f, c.y - r * 0.6f), strokeWidth = r * 0.1f)
    drawLine(tint, Offset(c.x - r * 0.6f, c.y - r * 0.6f), Offset(c.x - r * 0.6f, c.y - r * 0.2f), strokeWidth = r * 0.1f)
}

private fun DrawScope.drawDownload(tint: Color) {
    val c = center; val r = size.minDimension * 0.33f
    // arrow down into tray
    drawLine(tint, Offset(c.x, c.y - r * 0.8f), Offset(c.x, c.y + r * 0.3f), strokeWidth = r * 0.14f)
    drawLine(tint, Offset(c.x - r * 0.45f, c.y - r * 0.05f), Offset(c.x, c.y + r * 0.3f), strokeWidth = r * 0.11f)
    drawLine(tint, Offset(c.x + r * 0.45f, c.y - r * 0.05f), Offset(c.x, c.y + r * 0.3f), strokeWidth = r * 0.11f)
    // tray
    drawLine(tint, Offset(c.x - r * 0.9f, c.y + r * 0.55f), Offset(c.x + r * 0.9f, c.y + r * 0.55f), strokeWidth = r * 0.1f)
    drawLine(tint, Offset(c.x - r * 0.9f, c.y + r * 0.55f), Offset(c.x - r * 0.9f, c.y + r), strokeWidth = r * 0.08f)
    drawLine(tint, Offset(c.x + r * 0.9f, c.y + r * 0.55f), Offset(c.x + r * 0.9f, c.y + r), strokeWidth = r * 0.08f)
}

// ── WEB ──
private fun DrawScope.drawWebSearch(tint: Color) {
    val c = center; val r = size.minDimension * 0.36f
    // magnifier circle
    drawCircle(color = tint.copy(alpha = 0.15f), radius = r * 0.65f, center = Offset(c.x, c.y - r * 0.15f), style = Stroke(r * 0.11f))
    // handle
    val hx = c.x + r * 0.5f; val hy = c.y + r * 0.45f
    drawLine(tint, Offset(hx - r * 0.25f, hy - r * 0.25f), Offset(hx + r * 0.15f, hy + r * 0.15f), strokeWidth = r * 0.11f)
}

private fun DrawScope.drawWebFetch(tint: Color) {
    val c = center; val r = size.minDimension * 0.38f
    // globe wireframe
    drawCircle(color = tint.copy(alpha = 0.12f), radius = r * 0.85f, center = c, style = Stroke(r * 0.08f))
    drawOval(color = tint.copy(alpha = 0.2f), topLeft = Offset(c.x - r * 0.85f, c.y - r * 0.3f), size = Size((c.x + r * 0.85f) - (c.x - r * 0.85f), (c.y + r * 0.3f) - (c.y - r * 0.3f)), style = Stroke(r * 0.06f))
    drawLine(tint, Offset(c.x, c.y - r * 0.85f), Offset(c.x, c.y + r * 0.85f), strokeWidth = r * 0.06f)
}

private fun DrawScope.drawOpenUrl(tint: Color) {
    val c = center; val r = size.minDimension * 0.33f
    // external link
    val boxR = r * 0.75f
    drawRect(color = tint.copy(alpha = 0.12f), topLeft = Offset(c.x - boxR, c.y - boxR * 0.6f), size = Size((c.x + boxR) - (c.x - boxR), (c.y + boxR * 0.6f) - (c.y - boxR * 0.6f)), style = Stroke(r * 0.08f))
    // arrow up-right from inside
    drawLine(tint, Offset(c.x - boxR * 0.3f, c.y + boxR * 0.2f), Offset(c.x + boxR * 0.7f, c.y - boxR * 0.6f), strokeWidth = r * 0.11f)
    // arrow head
    drawLine(tint, Offset(c.x + boxR * 0.4f, c.y - boxR * 0.6f), Offset(c.x + boxR * 0.7f, c.y - boxR * 0.3f), strokeWidth = r * 0.09f)
}

private fun DrawScope.drawBrowser(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    val w = r * 1.5f; val h = r * 1.1f
    drawRoundRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - w, c.y - h), size = Size((c.x + w) - (c.x - w), (c.y + h) - (c.y - h)), cornerRadius = CornerRadius(r * 0.12f, r * 0.12f), style = Stroke(r * 0.07f))
    // address bar
    drawRect(color = tint.copy(alpha = 0.08f), topLeft = Offset(c.x - w * 0.8f, c.y - h * 0.6f), size = Size((c.x + w * 0.8f) - (c.x - w * 0.8f), (c.y - h * 0.15f) - (c.y - h * 0.6f)), style = Stroke(r * 0.05f))
    // stand
    drawLine(tint, Offset(c.x, c.y + h), Offset(c.x, c.y + h + r * 0.35f), strokeWidth = r * 0.06f)
    drawLine(tint, Offset(c.x - r * 0.4f, c.y + h + r * 0.35f), Offset(c.x + r * 0.4f, c.y + h + r * 0.35f), strokeWidth = r * 0.06f)
}

private fun DrawScope.drawApiCall(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // envelope
    drawRoundRect(color = tint.copy(alpha = 0.12f), topLeft = Offset(c.x - r * 1.1f, c.y - r * 0.7f), size = Size((c.x + r * 1.1f) - (c.x - r * 1.1f), (c.y + r * 0.7f) - (c.y - r * 0.7f)), cornerRadius = CornerRadius(r * 0.12f, r * 0.12f), style = Stroke(r * 0.08f))
    // V flap
    drawLine(tint, Offset(c.x - r * 1.1f, c.y - r * 0.7f), Offset(c.x, c.y + r * 0.15f), strokeWidth = r * 0.07f)
    drawLine(tint, Offset(c.x + r * 1.1f, c.y - r * 0.7f), Offset(c.x, c.y + r * 0.15f), strokeWidth = r * 0.07f)
}

private fun DrawScope.drawCliExec(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // prompt >
    drawLine(tint, Offset(c.x - r * 0.8f, c.y - r * 0.4f), Offset(c.x - r * 0.25f, c.y), strokeWidth = r * 0.13f)
    drawLine(tint, Offset(c.x - r * 0.25f, c.y), Offset(c.x - r * 0.8f, c.y + r * 0.4f), strokeWidth = r * 0.13f)
    // cursor _
    drawLine(tint, Offset(c.x + r * 0.1f, c.y + r * 0.5f), Offset(c.x + r * 0.4f, c.y + r * 0.5f), strokeWidth = r * 0.1f)
}

// ── TERMINAL ──
private fun DrawScope.drawTerminalIcon(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawRoundRect(color = tint.copy(alpha = 0.12f), topLeft = Offset(c.x - r * 1.1f, c.y - r * 0.75f), size = Size((c.x + r * 1.1f) - (c.x - r * 1.1f), (c.y + r * 0.75f) - (c.y - r * 0.75f)), cornerRadius = CornerRadius(r * 0.1f, r * 0.1f), style = Stroke(r * 0.07f))
    // >_
    drawLine(tint, Offset(c.x - r * 0.7f, c.y - r * 0.2f), Offset(c.x - r * 0.15f, c.y + r * 0.2f), strokeWidth = r * 0.12f)
    drawLine(tint, Offset(c.x + r * 0.15f, c.y + r * 0.5f), Offset(c.x + r * 0.55f, c.y + r * 0.5f), strokeWidth = r * 0.09f)
}

private fun DrawScope.drawShellRun(tint: Color) { drawTerminalIcon(tint) }

private fun DrawScope.drawLinuxEnv(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 1.1f, c.y - r * 0.8f), size = Size((c.x + r * 1.1f) - (c.x - r * 1.1f), (c.y + r * 0.8f) - (c.y - r * 0.8f)), style = Stroke(r * 0.07f))
    drawLine(tint, Offset(c.x - r * 1.1f, c.y), Offset(c.x + r * 1.1f, c.y), strokeWidth = r * 0.06f) // horizontal split
    drawLine(tint, Offset(c.x - r * 0.5f, c.y - r * 0.8f), Offset(c.x - r * 0.5f, c.y), strokeWidth = r * 0.06f) // sidebar
    // $ prompt
    drawLine(tint, Offset(c.x - r * 0.3f, c.y + r * 0.3f), Offset(c.x + r * 0.5f, c.y + r * 0.3f), strokeWidth = r * 0.08f)
}

private fun DrawScope.drawProot(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r, c.y - r * 0.7f), size = Size((c.x + r) - (c.x - r), (c.y + r * 0.7f) - (c.y - r * 0.7f)), style = Stroke(r * 0.07f))
    drawLine(tint, Offset(c.x - r, c.y - r * 0.15f), Offset(c.x + r, c.y - r * 0.15f), strokeWidth = r * 0.05f)
    // >_ like terminal but smaller
    drawCircle(color = tint, radius = r * 0.12f, center = Offset(c.x, c.y + r * 0.25f), style = Stroke(r * 0.04f))
    drawLine(tint, Offset(c.x + r * 0.2f, c.y + r * 0.4f), Offset(c.x + r * 0.6f, c.y + r * 0.4f), strokeWidth = r * 0.07f)
}

private fun DrawScope.drawRootExec(tint: Color) {
    val c = center; val r = size.minDimension * 0.33f
    // wrench
    drawLine(tint, Offset(c.x - r * 0.5f, c.y - r * 0.5f), Offset(c.x + r * 0.5f, c.y + r * 0.5f), strokeWidth = r * 0.14f)
    drawLine(tint, Offset(c.x - r * 0.5f, c.y + r * 0.5f), Offset(c.x + r * 0.5f, c.y - r * 0.5f), strokeWidth = r * 0.14f)
    // # badge
    drawCircle(color = tint.copy(alpha = 0.2f), radius = r * 0.22f, center = Offset(c.x + r * 0.55f, c.y - r * 0.55f))
}

// ── CODE ──
private fun DrawScope.drawCodeRun(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // <> brackets
    drawLine(tint, Offset(c.x - r * 0.6f, c.y - r * 0.5f), Offset(c.x - r * 0.1f, c.y), strokeWidth = r * 0.12f)
    drawLine(tint, Offset(c.x - r * 0.6f, c.y + r * 0.5f), Offset(c.x - r * 0.1f, c.y), strokeWidth = r * 0.12f)
    drawLine(tint, Offset(c.x + r * 0.6f, c.y - r * 0.5f), Offset(c.x + r * 0.1f, c.y), strokeWidth = r * 0.12f)
    drawLine(tint, Offset(c.x + r * 0.6f, c.y + r * 0.5f), Offset(c.x + r * 0.1f, c.y), strokeWidth = r * 0.12f)
    // play triangle
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.18f, c.y - r * 0.25f); lineTo(c.x - r * 0.18f, c.y + r * 0.25f); lineTo(c.x + r * 0.3f, c.y); close()
    }, tint)
}

private fun DrawScope.drawCodeEdit(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // pencil
    drawLine(tint, Offset(c.x - r * 0.5f, c.y + r * 0.5f), Offset(c.x + r * 0.3f, c.y - r * 0.3f), strokeWidth = r * 0.12f)
    // tip
    drawLine(tint, Offset(c.x + r * 0.3f, c.y - r * 0.3f), Offset(c.x + r * 0.5f, c.y - r * 0.5f), strokeWidth = r * 0.1f)
    // eraser
    drawLine(tint, Offset(c.x - r * 0.5f, c.y + r * 0.5f), Offset(c.x - r * 0.7f, c.y + r * 0.7f), strokeWidth = r * 0.1f)
}

private fun DrawScope.drawCodeLint(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // gear + checkmark combined
    drawCircle(color = tint.copy(alpha = 0.12f), radius = r * 0.6f, center = c, style = Stroke(r * 0.08f))
    // gear teeth hints
    for (i in 0 until 8) {
        val a = Math.toRadians((i * 45).toDouble())
        val x1 = c.x + (r * 0.55f * cos(a)).toFloat()
        val y1 = c.y + (r * 0.55f * sin(a)).toFloat()
        val x2 = c.x + (r * 0.72f * cos(a)).toFloat()
        val y2 = c.y + (r * 0.72f * sin(a)).toFloat()
        drawLine(tint, Offset(x1, y1), Offset(x2, y2), strokeWidth = r * 0.04f)
    }
    // check
    drawLine(tint, Offset(c.x - r * 0.2f, c.y), Offset(c.x - r * 0.03f, c.y + r * 0.2f), strokeWidth = r * 0.07f)
    drawLine(tint, Offset(c.x - r * 0.03f, c.y + r * 0.2f), Offset(c.x + r * 0.3f, c.y - r * 0.2f), strokeWidth = r * 0.07f)
}

private fun DrawScope.drawIdeOpen(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 1.1f, c.y - r * 0.8f), size = Size((c.x + r * 1.1f) - (c.x - r * 1.1f), (c.y + r * 0.8f) - (c.y - r * 0.8f)), style = Stroke(r * 0.07f))
    drawLine(tint, Offset(c.x - r * 1.1f, c.y), Offset(c.x + r * 1.1f, c.y), strokeWidth = r * 0.05f)
    drawLine(tint, Offset(c.x - r * 0.5f, c.y - r * 0.8f), Offset(c.x - r * 0.5f, c.y), strokeWidth = r * 0.05f)
    // code dots
    drawCircle(color = tint.copy(alpha = 0.4f), radius = r * 0.08f, center = Offset(c.x - r * 0.2f, c.y - r * 0.4f))
    drawCircle(color = tint.copy(alpha = 0.4f), radius = r * 0.08f, center = Offset(c.x - r * 0.2f, c.y + r * 0.3f))
    drawCircle(color = tint.copy(alpha = 0.4f), radius = r * 0.08f, center = Offset(c.x + r * 0.3f, c.y + r * 0.3f))
}

private fun DrawScope.drawTestRun(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // doc with checkmark
    drawRoundRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r, c.y - r * 0.9f), size = Size((c.x + r) - (c.x - r), (c.y + r * 0.9f) - (c.y - r * 0.9f)), cornerRadius = CornerRadius(r * 0.15f, r * 0.15f), style = Stroke(r * 0.07f))
    // fold
    drawLine(tint, Offset(c.x - r * 0.6f, c.y - r * 0.9f), Offset(c.x + r * 0.2f, c.y - r * 0.9f), strokeWidth = r * 0.07f)
    drawLine(tint, Offset(c.x + r * 0.2f, c.y - r * 0.9f), Offset(c.x + r * 0.2f, c.y - r * 0.5f), strokeWidth = r * 0.07f)
    // ✓
    drawLine(tint, Offset(c.x - r * 0.35f, c.y), Offset(c.x - r * 0.1f, c.y + r * 0.25f), strokeWidth = r * 0.09f)
    drawLine(tint, Offset(c.x - r * 0.1f, c.y + r * 0.25f), Offset(c.x + r * 0.35f, c.y - r * 0.2f), strokeWidth = r * 0.09f)
}

private fun DrawScope.drawPackageMgr(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // cube/box
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.6f, c.y - r * 0.3f)
        lineTo(c.x, c.y - r * 0.7f); lineTo(c.x + r * 0.6f, c.y - r * 0.3f)
        lineTo(c.x + r * 0.6f, c.y + r * 0.4f); lineTo(c.x, c.y + r * 0.8f)
        lineTo(c.x - r * 0.6f, c.y + r * 0.4f); close()
    }, tint.copy(alpha = 0.12f), style = Stroke(r * 0.07f))
    // inner Y lines
    drawLine(tint, Offset(c.x - r * 0.6f, c.y - r * 0.3f), Offset(c.x, c.y + r * 0.15f), strokeWidth = r * 0.05f)
    drawLine(tint, Offset(c.x + r * 0.6f, c.y - r * 0.3f), Offset(c.x, c.y + r * 0.15f), strokeWidth = r * 0.05f)
    drawLine(tint, Offset(c.x, c.y - r * 0.7f), Offset(c.x, c.y + r * 0.15f), strokeWidth = r * 0.05f)
}

// ── AI ──
private fun DrawScope.drawLlmChat(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // chat bubble head
    drawCircle(color = tint.copy(alpha = 0.12f), radius = r * 0.5f, center = Offset(c.x, c.y - r * 0.2f), style = Stroke(r * 0.08f))
    // body
    drawArc(color = tint.copy(alpha = 0.1f), startAngle = 200f, sweepAngle = 140f,
        useCenter = false, topLeft = Offset(c.x - r * 0.55f, c.y + r * 0.15f),
        size = Size(r * 1.1f, r * 0.7f), style = Stroke(r * 0.07f))
    // antenna
    drawLine(tint, Offset(c.x, c.y - r * 0.7f), Offset(c.x, c.y - r * 0.95f), strokeWidth = r * 0.05f)
    drawCircle(color = tint.copy(alpha = 0.3f), radius = r * 0.08f, center = Offset(c.x, c.y - r * 0.95f))
}

private fun DrawScope.drawClassify(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawCircle(color = tint.copy(alpha = 0.1f), radius = r * 0.8f, center = c, style = Stroke(r * 0.07f))
    // ? mark
    drawLine(tint, Offset(c.x - r * 0.25f, c.y - r * 0.15f), Offset(c.x - r * 0.25f, c.y + r * 0.1f), strokeWidth = r * 0.08f)
    drawCircle(color = tint, radius = r * 0.06f, center = Offset(c.x - r * 0.25f, c.y + r * 0.3f))
}

private fun DrawScope.drawTranslate(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // A → 文 / 文 → B arrows
    drawLine(tint, Offset(c.x - r * 0.8f, c.y - r * 0.3f), Offset(c.x - r * 0.1f, c.y + r * 0.1f), strokeWidth = r * 0.09f)
    drawLine(tint, Offset(c.x + r * 0.1f, c.y - r * 0.3f), Offset(c.x + r * 0.8f, c.y + r * 0.1f), strokeWidth = r * 0.09f)
    // double arrow middle
    drawLine(tint, Offset(c.x - r * 0.3f, c.y), Offset(c.x + r * 0.3f, c.y), strokeWidth = r * 0.07f)
    // arrow heads
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x + r * 0.7f, c.y + r * 0.05f); lineTo(c.x + r * 0.85f, c.y + r * 0.2f); lineTo(c.x + r * 0.7f, c.y + r * 0.35f); close()
    }, tint)
}

private fun DrawScope.drawSummarize(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // doc with lines
    drawRoundRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r, c.y - r * 0.9f), size = Size((c.x + r) - (c.x - r), (c.y + r * 0.9f) - (c.y - r * 0.9f)), cornerRadius = CornerRadius(r * 0.15f, r * 0.15f), style = Stroke(r * 0.07f))
    drawLine(tint, Offset(c.x - r * 0.5f, c.y - r * 0.5f), Offset(c.x + r * 0.5f, c.y - r * 0.5f), strokeWidth = r * 0.06f)
    drawLine(tint, Offset(c.x - r * 0.5f, c.y), Offset(c.x + r * 0.5f, c.y), strokeWidth = r * 0.06f)
    drawLine(tint, Offset(c.x - r * 0.5f, c.y + r * 0.5f), Offset(c.x + r * 0.2f, c.y + r * 0.5f), strokeWidth = r * 0.06f)
}

private fun DrawScope.drawOcr(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // image frame
    drawRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r, c.y - r * 0.7f), size = Size((c.x + r) - (c.x - r), (c.y + r * 0.7f) - (c.y - r * 0.7f)), style = Stroke(r * 0.07f))
    // mountain + sun
    drawCircle(color = tint.copy(alpha = 0.25f), radius = r * 0.2f, center = Offset(c.x - r * 0.4f, c.y - r * 0.3f))
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.7f, c.y + r * 0.4f); lineTo(c.x - r * 0.2f, c.y - r * 0.1f)
        lineTo(c.x + r * 0.3f, c.y + r * 0.5f); lineTo(c.x + r * 0.7f, c.y + r * 0.1f)
    }, tint.copy(alpha = 0.2f), style = Stroke(r * 0.06f))
}

private fun DrawScope.drawEmbedding(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // hexagon with inner vectors
    val path = androidx.compose.ui.graphics.Path()
    for (i in 0 until 6) {
        val a = Math.toRadians((60 * i - 30).toDouble())
        val px = c.x + (r * 0.75f * cos(a)).toFloat()
        val py = c.y + (r * 0.75f * sin(a)).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, tint.copy(alpha = 0.12f), style = Stroke(r * 0.07f))
    // vertical arrow up
    drawLine(tint, Offset(c.x, c.y + r * 0.4f), Offset(c.x, c.y - r * 0.4f), strokeWidth = r * 0.08f)
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.15f, c.y - r * 0.2f); lineTo(c.x, c.y - r * 0.4f); lineTo(c.x + r * 0.15f, c.y - r * 0.2f); close()
    }, tint)
}

// ── MEDIA GEN ──
private fun DrawScope.drawImageGen(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r, c.y - r * 0.7f), size = Size((c.x + r) - (c.x - r), (c.y + r * 0.7f) - (c.y - r * 0.7f)), style = Stroke(r * 0.07f))
    // sun dot
    drawCircle(color = tint.copy(alpha = 0.3f), radius = r * 0.15f, center = Offset(c.x - r * 0.4f, c.y - r * 0.3f))
    // mountains
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.7f, c.y + r * 0.5f); lineTo(c.x - r * 0.15f, c.y - r * 0.05f)
        lineTo(c.x + r * 0.4f, c.y + r * 0.6f); lineTo(c.x + r * 0.7f, c.y + r * 0.15f)
    }, tint.copy(alpha = 0.2f), style = Stroke(r * 0.06f))
}

private fun DrawScope.drawImageCrop(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // image with crop corners
    drawRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 0.8f, c.y - r * 0.6f), size = Size((c.x + r * 0.8f) - (c.x - r * 0.8f), (c.y + r * 0.6f) - (c.y - r * 0.6f)), style = Stroke(r * 0.06f))
    // crop corners L
    drawLine(tint, Offset(c.x - r * 0.8f, c.y - r * 0.35f), Offset(c.x - r * 0.5f, c.y - r * 0.35f), strokeWidth = r * 0.07f)
    drawLine(tint, Offset(c.x - r * 0.8f, c.y - r * 0.35f), Offset(c.x - r * 0.8f, c.y - r * 0.6f), strokeWidth = r * 0.07f)
    // R
    drawLine(tint, Offset(c.x + r * 0.8f, c.y + r * 0.35f), Offset(c.x + r * 0.5f, c.y + r * 0.35f), strokeWidth = r * 0.07f)
    drawLine(tint, Offset(c.x + r * 0.8f, c.y + r * 0.35f), Offset(c.x + r * 0.8f, c.y + r * 0.6f), strokeWidth = r * 0.07f)
}

private fun DrawScope.drawVideoGen(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // video rect
    drawRoundRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 1.1f, c.y - r * 0.6f), size = Size((c.x + r * 0.3f) - (c.x - r * 1.1f), (c.y + r * 0.6f) - (c.y - r * 0.6f)), cornerRadius = CornerRadius(r * 0.08f, r * 0.08f), style = Stroke(r * 0.07f))
    // play triangle
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.5f, c.y - r * 0.3f); lineTo(c.x - r * 0.5f, c.y + r * 0.3f); lineTo(c.x + r * 0.1f, c.y); close()
    }, tint)
}

private fun DrawScope.drawAudioGen(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // music note
    drawCircle(color = tint.copy(alpha = 0.15f), radius = r * 0.25f, center = Offset(c.x - r * 0.25f, c.y + r * 0.4f), style = Stroke(r * 0.07f))
    drawLine(tint, Offset(c.x - r * 0.02f, c.y + r * 0.4f), Offset(c.x - r * 0.02f, c.y - r * 0.6f), strokeWidth = r * 0.08f)
    // note head 2
    drawCircle(color = tint.copy(alpha = 0.15f), radius = r * 0.22f, center = Offset(c.x + r * 0.4f, c.y + r * 0.15f), style = Stroke(r * 0.07f))
    drawLine(tint, Offset(c.x + r * 0.58f, c.y + r * 0.15f), Offset(c.x + r * 0.58f, c.y - r * 0.5f), strokeWidth = r * 0.08f)
}

private fun DrawScope.drawImageEdit(tint: Color) { /* reuse image gen variant */ drawImageGen(tint) }

private fun DrawScope.drawFilterApply(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r, c.y - r * 0.7f), size = Size((c.x + r) - (c.x - r), (c.y + r * 0.7f) - (c.y - r * 0.7f)), style = Stroke(r * 0.07f))
    drawCircle(color = tint.copy(alpha = 0.25f), radius = r * 0.15f, center = Offset(c.x - r * 0.4f, c.y - r * 0.3f))
    // slider lines
    drawLine(tint, Offset(c.x - r * 0.5f, c.y + r * 0.4f), Offset(c.x + r * 0.5f, c.y + r * 0.4f), strokeWidth = r * 0.06f)
    drawLine(tint, Offset(c.x + r * 0.1f, c.y + r * 0.3f), Offset(c.x + r * 0.1f, c.y + r * 0.5f), strokeWidth = r * 0.07f)
}

// ── UI AUTOMATION ──
private fun DrawScope.drawTap(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // phone rect
    drawRoundRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 0.6f, c.y - r * 1.0f), size = Size((c.x + r * 0.6f) - (c.x - r * 0.6f), (c.y + r * 0.9f) - (c.y - r * 1.0f)), cornerRadius = CornerRadius(r * 0.12f, r * 0.12f), style = Stroke(r * 0.07f))
    // touch dot
    drawCircle(color = tint.copy(alpha = 0.4f), radius = r * 0.15f, center = c)
}

private fun DrawScope.drawSwipe(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // curved swipe arrows
    drawLine(tint, Offset(c.x - r * 0.8f, c.y - r * 0.6f), Offset(c.x - r * 0.1f, c.y - r * 0.6f), strokeWidth = r * 0.09f)
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.25f, c.y - r * 0.75f); lineTo(c.x - r * 0.02f, c.y - r * 0.6f); lineTo(c.x - r * 0.25f, c.y - r * 0.45f); close()
    }, tint)
    // lower arrow opposite
    drawLine(tint, Offset(c.x + r * 0.8f, c.y + r * 0.6f), Offset(c.x + r * 0.1f, c.y + r * 0.6f), strokeWidth = r * 0.09f)
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x + r * 0.25f, c.y + r * 0.75f); lineTo(c.x + r * 0.02f, c.y + r * 0.6f); lineTo(c.x + r * 0.25f, c.y + r * 0.45f); close()
    }, tint)
}

private fun DrawScope.drawScroll(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawRoundRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 0.6f, c.y - r * 1.0f), size = Size((c.x + r * 0.6f) - (c.x - r * 0.6f), (c.y + r * 0.9f) - (c.y - r * 1.0f)), cornerRadius = CornerRadius(r * 0.12f, r * 0.12f), style = Stroke(r * 0.07f))
    // down arrow inside
    drawLine(tint, Offset(c.x, c.y - r * 0.2f), Offset(c.x, c.y + r * 0.4f), strokeWidth = r * 0.1f)
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.2f, c.y + r * 0.15f); lineTo(c.x, c.y + r * 0.4f); lineTo(c.x + r * 0.2f, c.y + r * 0.15f); close()
    }, tint)
}

private fun DrawScope.drawInputText(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // screen + keyboard hint
    drawRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 1.1f, c.y - r * 0.6f), size = Size((c.x + r * 1.1f) - (c.x - r * 1.1f), (c.y + r * 0.6f) - (c.y - r * 0.6f)), style = Stroke(r * 0.07f))
    // text line
    drawLine(tint, Offset(c.x - r * 0.7f, c.y), Offset(c.x + r * 0.3f, c.y), strokeWidth = r * 0.07f)
    // cursor blink
    drawLine(tint, Offset(c.x + r * 0.4f, c.y - r * 0.2f), Offset(c.x + r * 0.4f, c.y + r * 0.2f), strokeWidth = r * 0.06f)
}

private fun DrawScope.drawGetScreen(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // monitor
    drawRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 1.1f, c.y - r * 0.7f), size = Size((c.x + r * 1.1f) - (c.x - r * 1.1f), (c.y + r * 0.6f) - (c.y - r * 0.7f)), style = Stroke(r * 0.07f))
    // stand
    drawLine(tint, Offset(c.x, c.y + r * 0.6f), Offset(c.x, c.y + r * 0.85f), strokeWidth = r * 0.06f)
    drawLine(tint, Offset(c.x - r * 0.35f, c.y + r * 0.85f), Offset(c.x + r * 0.35f, c.y + r * 0.85f), strokeWidth = r * 0.06f)
}

private fun DrawScope.drawGridTap(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    val gs = r * 0.42f
    val gap = 2.dp.toPx()
    for (row in 0..2) for (col in 0..2) {
        val rx = c.x - r + col * (gs + gap)
        val ry = c.y - r + row * (gs + gap)
        drawRect(color = tint.copy(alpha = 0.08f), topLeft = Offset(rx, ry), size = Size((rx + gs) - (rx), (ry + gs) - (ry)), style = Stroke(r * 0.04f))
    }
}

// ── SYSTEM INFO ──
private fun DrawScope.drawDeviceInfo(tint: Color) { drawBrowser(tint) } // monitor shape
private fun DrawScope.drawBattery(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // battery body
    drawRoundRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 0.9f, c.y - r * 0.5f), size = Size((c.x + r * 0.8f) - (c.x - r * 0.9f), (c.y + r * 0.5f) - (c.y - r * 0.5f)), cornerRadius = CornerRadius(r * 0.08f, r * 0.08f), style = Stroke(r * 0.07f))
    // terminal nub
    drawLine(tint, Offset(c.x + r * 0.8f, c.y - r * 0.2f), Offset(c.x + r * 0.95f, c.y - r * 0.2f), strokeWidth = r * 0.07f)
    // level bars inside
    drawRect(color = tint.copy(alpha = 0.3f), topLeft = Offset(c.x - r * 0.7f, c.y - r * 0.25f), size = Size((c.x - r * 0.3f) - (c.x - r * 0.7f), (c.y + r * 0.25f) - (c.y - r * 0.25f)))
    drawRect(color = tint.copy(alpha = 0.2f), topLeft = Offset(c.x - r * 0.15f, c.y - r * 0.25f), size = Size((c.x + r * 0.2f) - (c.x - r * 0.15f), (c.y + r * 0.25f) - (c.y - r * 0.25f)))
}
private fun DrawScope.drawNetwork(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // wifi arcs
    drawArc(color = tint.copy(alpha = 0.15f), startAngle = -225f, sweepAngle = 90f,
        useCenter = false, topLeft = Offset(c.x - r * 0.9f, c.y - r * 0.9f),
        size = Size(r * 1.8f, r * 1.8f), style = Stroke(r * 0.08f))
    drawArc(color = tint.copy(alpha = 0.25f), startAngle = -210f, sweepAngle = 60f,
        useCenter = false, topLeft = Offset(c.x - r * 0.6f, c.y - r * 0.6f),
        size = Size(r * 1.2f, r * 1.2f), style = Stroke(r * 0.08f))
    drawDot(c, r * 0.12f, tint)
}
private fun DrawScope.drawStorage(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // stacked disks
    drawRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 0.9f, c.y - r * 0.6f), size = Size((c.x + r * 0.9f) - (c.x - r * 0.9f), (c.y - r * 0.1f) - (c.y - r * 0.6f)), style = Stroke(r * 0.06f))
    drawRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 0.9f, c.y), size = Size((c.x + r * 0.9f) - (c.x - r * 0.9f), (c.y + r * 0.6f) - (c.y)), style = Stroke(r * 0.06f))
    // dots
    drawCircle(color = tint.copy(alpha = 0.3f), radius = r * 0.06f, center = Offset(c.x - r * 0.6f, c.y - r * 0.35f))
    drawCircle(color = tint.copy(alpha = 0.3f), radius = r * 0.06f, center = Offset(c.x - r * 0.6f, c.y + r * 0.3f))
}
private fun DrawScope.drawCpuMonitor(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r, c.y - r), size = Size((c.x + r) - (c.x - r), (c.y + r) - (c.y - r)), style = Stroke(r * 0.06f))
    drawLine(tint, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = r * 0.05f)
    drawLine(tint, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = r * 0.05f)
    // center chip
    drawRect(color = tint.copy(alpha = 0.15f), topLeft = Offset(c.x - r * 0.3f, c.y - r * 0.3f), size = Size((c.x + r * 0.3f) - (c.x - r * 0.3f), (c.y + r * 0.3f) - (c.y - r * 0.3f)))
}

// ── COMMUNICATION ──
private fun DrawScope.drawNotify(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // bell
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.5f, c.y + r * 0.5f)
        cubicTo(c.x - r * 0.5f, c.y, c.x - r * 0.2f, c.y - r * 0.5f, c.x, c.y - r * 0.5f)
        cubicTo(c.x + r * 0.2f, c.y - r * 0.5f, c.x + r * 0.5f, c.y, c.x + r * 0.5f, c.y + r * 0.5f)
    }, tint.copy(alpha = 0.12f), style = Stroke(r * 0.08f))
    // clapper
    drawLine(tint, Offset(c.x - r * 0.15f, c.y - r * 0.5f), Offset(c.x - r * 0.15f, c.y - r * 0.8f), strokeWidth = r * 0.06f)
    drawLine(tint, Offset(c.x + r * 0.15f, c.y - r * 0.5f), Offset(c.x + r * 0.15f, c.y - r * 0.8f), strokeWidth = r * 0.06f)
    // sound waves
    drawArc(color = tint.copy(alpha = 0.2f), startAngle = 200f, sweepAngle = 40f, useCenter = false, topLeft = Offset(c.x + r * 0.4f, c.y - r * 0.2f), size = Size(r * 0.5f, r * 0.5f), style = Stroke(r * 0.04f))
}
private fun DrawScope.drawSendEmail(tint: Color) { drawApiCall(tint) } // envelope
private fun DrawScope.drawPhoneCall(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // handset
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.4f, c.y + r * 0.5f)
        cubicTo(c.x - r * 0.5f, c.y + r * 0.2f, c.x - r * 0.2f, c.y, c.x, c.y)
        cubicTo(c.x + r * 0.2f, c.y, c.x + r * 0.5f, c.y + r * 0.2f, c.x + r * 0.4f, c.y + r * 0.5f)
        cubicTo(c.x + r * 0.3f, c.y + r * 0.65f, c.x, c.y + r * 0.7f, c.x - r * 0.15f, c.y + r * 0.65f)
        cubicTo(c.x - r * 0.3f, c.y + r * 0.6f, c.x - r * 0.4f, c.y + r * 0.5f, c.x - r * 0.4f, c.y + r * 0.5f)
    }, tint.copy(alpha = 0.15f), style = Stroke(r * 0.08f))
}
private fun DrawScope.drawSendMsg(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // chat bubble
    drawRoundRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 0.9f, c.y - r * 0.6f), size = Size((c.x + r * 0.9f) - (c.x - r * 0.9f), (c.y + r * 0.5f) - (c.y - r * 0.6f)), cornerRadius = CornerRadius(r * 0.15f, r * 0.15f), style = Stroke(r * 0.07f))
    // tail
    drawLine(tint, Offset(c.x - r * 0.5f, c.y + r * 0.5f), Offset(c.x - r * 0.7f, c.y + r * 0.7f), strokeWidth = r * 0.06f)
}
private fun DrawScope.drawPushBroadcast(tint: Color) { drawNotify(tint) } // bell + broadcast

// ── DATA PROCESSING ──
private fun DrawScope.drawDbQuery(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // cylinder (db)
    drawOval(color = tint.copy(alpha = 0.12f), topLeft = Offset(c.x - r * 0.8f, c.y - r * 0.6f), size = Size((c.x + r * 0.8f) - (c.x - r * 0.8f), (c.y + r * 0.15f) - (c.y - r * 0.6f)), style = Stroke(r * 0.07f))
    drawLine(tint, Offset(c.x - r * 0.8f, c.y - r * 0.22f), Offset(c.x - r * 0.8f, c.y + r * 0.5f), strokeWidth = r * 0.06f)
    drawLine(tint, Offset(c.x + r * 0.8f, c.y - r * 0.22f), Offset(c.x + r * 0.8f, c.y + r * 0.5f), strokeWidth = r * 0.06f)
    drawOval(color = tint.copy(alpha = 0.12f), topLeft = Offset(c.x - r * 0.8f, c.y - r * 0.05f), size = Size((c.x + r * 0.8f) - (c.x - r * 0.8f), (c.y + r * 0.7f) - (c.y - r * 0.05f)), style = Stroke(r * 0.07f))
}
private fun DrawScope.drawCsvParse(tint: Color) { drawSummarize(tint) } // doc with rows
private fun DrawScope.drawJsonXform(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // right arrow (transform)
    drawLine(tint, Offset(c.x - r * 0.6f, c.y), Offset(c.x + r * 0.2f, c.y), strokeWidth = r * 0.11f)
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x, c.y - r * 0.25f); lineTo(c.x + r * 0.35f, c.y); lineTo(c.x, c.y + r * 0.25f); close()
    }, tint)
    // vertical arrow down
    drawLine(tint, Offset(c.x, c.y + r * 0.1f), Offset(c.x, c.y + r * 0.6f), strokeWidth = r * 0.08f)
}
private fun DrawScope.drawDataChart(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // bar chart
    val bw = r * 0.15f
    drawRect(color = tint.copy(alpha = 0.25f), topLeft = Offset(c.x - r * 0.5f, c.y - r * 0.1f), size = Size((c.x - r * 0.5f + bw) - (c.x - r * 0.5f), (c.y + r * 0.5f) - (c.y - r * 0.1f)))
    drawRect(color = tint.copy(alpha = 0.2f), topLeft = Offset(c.x - r * 0.12f, c.y - r * 0.4f), size = Size((c.x - r * 0.12f + bw) - (c.x - r * 0.12f), (c.y + r * 0.5f) - (c.y - r * 0.4f)))
    drawRect(color = tint.copy(alpha = 0.3f), topLeft = Offset(c.x + r * 0.25f, c.y - r * 0.6f), size = Size((c.x + r * 0.25f + bw) - (c.x + r * 0.25f), (c.y + r * 0.5f) - (c.y - r * 0.6f)))
    // baseline
    drawLine(tint, Offset(c.x - r * 0.6f, c.y + r * 0.5f), Offset(c.x + r * 0.5f, c.y + r * 0.5f), strokeWidth = r * 0.05f)
}
private fun DrawScope.drawAnalytics(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // zigzag line chart
    val pts = listOf(
        Offset(c.x - r * 0.8f, c.y + r * 0.3f),
        Offset(c.x - r * 0.35f, c.y - r * 0.4f),
        Offset(c.x + r * 0.1f, c.y + r * 0.1f),
        Offset(c.x + r * 0.5f, c.y - r * 0.5f),
        Offset(c.x + r * 0.8f, c.y + r * 0.2f),
    )
    for (i in 0 until pts.size - 1) drawLine(tint, pts[i], pts[i + 1], strokeWidth = r * 0.08f)
}

// ── SECURITY ──
private fun DrawScope.drawEncrypt(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // lock body
    drawRoundRect(color = tint.copy(alpha = 0.12f), topLeft = Offset(c.x - r * 0.6f, c.y - r * 0.1f), size = Size((c.x + r * 0.6f) - (c.x - r * 0.6f), (c.y + r * 0.5f) - (c.y - r * 0.1f)), cornerRadius = CornerRadius(r * 0.1f, r * 0.1f), style = Stroke(r * 0.07f))
    // shackle
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.3f, c.y - r * 0.1f)
        arcTo(Rect(c.x - r * 0.5f, c.y - r * 0.8f, c.x + r * 0.5f, c.y - r * 0.1f), 180f, 180f, true)
    }, tint.copy(alpha = 0.2f), style = Stroke(r * 0.08f))
}
private fun DrawScope.drawDecrypt(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // open lock
    drawRoundRect(color = tint.copy(alpha = 0.12f), topLeft = Offset(c.x - r * 0.6f, c.y - r * 0.1f), size = Size((c.x + r * 0.6f) - (c.x - r * 0.6f), (c.y + r * 0.5f) - (c.y - r * 0.1f)), cornerRadius = CornerRadius(r * 0.1f, r * 0.1f), style = Stroke(r * 0.07f))
    // shackle open
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.3f, c.y - r * 0.1f)
        arcTo(Rect(c.x - r * 0.5f, c.y - r * 0.8f, c.x + r * 0.5f, c.y - r * 0.1f), 180f, 180f, false)
    }, tint.copy(alpha = 0.2f), style = Stroke(r * 0.08f))
}
private fun DrawScope.drawPermission(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // shield
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x, c.y - r * 0.8f)
        lineTo(c.x - r * 0.7f, c.y - r * 0.4f)
        lineTo(c.x - r * 0.7f, c.y + r * 0.3f)
        lineTo(c.x, c.y + r * 0.8f)
        lineTo(c.x + r * 0.7f, c.y + r * 0.3f)
        lineTo(c.x + r * 0.7f, c.y - r * 0.4f)
        close()
    }, tint.copy(alpha = 0.12f), style = Stroke(r * 0.07f))
}
private fun DrawScope.drawVerify(tint: Color) {
    drawPermission(tint) // shield base
    val c = center; val r = size.minDimension * 0.35f
    // checkmark overlay
    drawLine(tint, Offset(c.x - r * 0.2f, c.y), Offset(c.x - r * 0.05f, c.y + r * 0.2f), strokeWidth = r * 0.07f)
    drawLine(tint, Offset(c.x - r * 0.05f, c.y + r * 0.2f), Offset(c.x + r * 0.25f, c.y - r * 0.15f), strokeWidth = r * 0.07f)
}
private fun DrawScope.drawRiskCheck(tint: Color) {
    drawPermission(tint) // shield base
    val c = center; val r = size.minDimension * 0.35f
    // ! mark
    drawLine(tint, Offset(c.x, c.y - r * 0.15f), Offset(c.x, c.y + r * 0.15f), strokeWidth = r * 0.08f)
    drawDot(Offset(c.x, c.y + r * 0.32f), r * 0.05f, tint)
}

// ── TIME ──
private fun DrawScope.drawSetAlarm(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawCircle(color = tint.copy(alpha = 0.1f), radius = r * 0.8f, center = c, style = Stroke(r * 0.07f))
    // hands
    drawLine(tint, c, Offset(c.x + r * 0.25f, c.y - r * 0.3f), strokeWidth = r * 0.07f)
    drawLine(tint, c, Offset(c.x + r * 0.4f, c.y + r * 0.1f), strokeWidth = r * 0.05f)
}
private fun DrawScope.drawTimer(tint: Color) { drawSetAlarm(tint) } // clock + tail
private fun DrawScope.drawCalendar(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawRoundRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 0.9f, c.y - r * 0.7f), size = Size((c.x + r * 0.9f) - (c.x - r * 0.9f), (c.y + r * 0.7f) - (c.y - r * 0.7f)), cornerRadius = CornerRadius(r * 0.08f, r * 0.08f), style = Stroke(r * 0.07f))
    // pins
    drawLine(tint, Offset(c.x - r * 0.4f, c.y - r * 0.7f), Offset(c.x - r * 0.4f, c.y - r * 0.9f), strokeWidth = r * 0.06f)
    drawLine(tint, Offset(c.x + r * 0.4f, c.y - r * 0.7f), Offset(c.x + r * 0.4f, c.y - r * 0.9f), strokeWidth = r * 0.06f)
    // grid dots
    drawDot(Offset(c.x - r * 0.4f, c.y - r * 0.3f), r * 0.05f, tint.copy(alpha = 0.3f))
    drawDot(Offset(c.x, c.y - r * 0.3f), r * 0.05f, tint.copy(alpha = 0.3f))
    drawDot(Offset(c.x + r * 0.4f, c.y - r * 0.3f), r * 0.05f, tint.copy(alpha = 0.3f))
    drawDot(Offset(c.x - r * 0.4f, c.y + r * 0.15f), r * 0.05f, tint.copy(alpha = 0.3f))
    drawDot(Offset(c.x, c.y + r * 0.15f), r * 0.05f, tint.copy(alpha = 0.3f))
}
private fun DrawScope.drawScheduleTask(tint: Color) { drawCalendar(tint) }
private fun DrawScope.drawCountdown(tint: Color) { drawSetAlarm(tint) }

// ── LOCATION ──
private fun DrawScope.drawGpsLoc(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // pin shape
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x, c.y + r * 0.7f)
        cubicTo(c.x - r * 0.7f, c.y + r * 0.2f, c.x - r * 0.7f, c.y - r * 0.4f, c.x, c.y - r * 0.5f)
        cubicTo(c.x + r * 0.7f, c.y - r * 0.4f, c.x + r * 0.7f, c.y + r * 0.2f, c.x, c.y + r * 0.7f)
    }, tint.copy(alpha = 0.12f), style = Stroke(r * 0.07f))
    // inner dot
    drawCircle(color = tint.copy(alpha = 0.25f), radius = r * 0.2f, center = c)
}
private fun DrawScope.drawGeocode(tint: Color) { drawGpsLoc(tint) } // map pin variant
private fun DrawScope.drawNavigateTo(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // folded map isometric-ish
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.8f, c.y - r * 0.3f); lineTo(c.x, c.y - r * 0.7f); lineTo(c.x + r * 0.8f, c.y - r * 0.3f)
        lineTo(c.x + r * 0.8f, c.y + r * 0.3f); lineTo(c.x, c.y + r * 0.7f); lineTo(c.x - r * 0.8f, c.y + r * 0.3f); close()
    }, tint.copy(alpha = 0.1f), style = Stroke(r * 0.06f))
    // direction arrow
    drawLine(tint, c, Offset(c.x, c.y - r * 0.5f), strokeWidth = r * 0.07f)
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.15f, c.y - r * 0.35f); lineTo(c.x, c.y - r * 0.5f); lineTo(c.x + r * 0.15f, c.y - r * 0.35f); close()
    }, tint)
}
private fun DrawScope.drawTrackRoute(tint: Color) { drawGpsLoc(tint) }

// ── WEATHER ──
private fun DrawScope.drawWeatherNow(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // cloud
    drawPath(androidx.compose.ui.graphics.Path().apply {
        addOval(Rect(c.x - r * 0.7f, c.y - r * 0.2f, c.x + r * 0.3f, c.y + r * 0.4f))
        addOval(Rect(c.x - r * 0.3f, c.y - r * 0.5f, c.x + r * 0.6f, c.y + r * 0.4f))
        addOval(Rect(c.x + r * 0.1f, c.y - r * 0.25f, c.x + r * 0.8f, c.y + r * 0.4f))
    }, tint.copy(alpha = 0.15f), style = Stroke(r * 0.06f))
}
private fun DrawScope.drawWeatherForecast(tint: Color) { drawWeatherNow(tint) } // cloud + sun rays
private fun DrawScope.drawWeatherAlert(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // warning triangle
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x, c.y - r * 0.7f); lineTo(c.x - r * 0.65f, c.y + r * 0.45f); lineTo(c.x + r * 0.65f, c.y + r * 0.45f); close()
    }, tint.copy(alpha = 0.12f), style = Stroke(r * 0.07f))
    // !
    drawLine(tint, Offset(c.x, c.y - r * 0.15f), Offset(c.x, c.y + r * 0.12f), strokeWidth = r * 0.07f)
    drawDot(Offset(c.x, c.y + r * 0.27f), r * 0.05f, tint)
}
private fun DrawScope.drawAirQuality(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // cloud with wind
    drawPath(androidx.compose.ui.graphics.Path().apply {
        addOval(Rect(c.x - r * 0.8f, c.y - r * 0.1f, c.x + r * 0.2f, c.y + r * 0.35f))
        addOval(Rect(c.x - r * 0.4f, c.y - r * 0.35f, c.x + r * 0.5f, c.y + r * 0.35f))
    }, tint.copy(alpha = 0.12f), style = Stroke(r * 0.06f))
    // wind lines
    drawLine(tint, Offset(c.x - r * 0.9f, c.y + r * 0.55f), Offset(c.x + r * 0.3f, c.y + r * 0.55f), strokeWidth = r * 0.06f)
}

// ── MEDIA CONTROL ──
private fun DrawScope.drawMusicPlay(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.4f, c.y - r * 0.5f); lineTo(c.x - r * 0.4f, c.y + r * 0.5f); lineTo(c.x + r * 0.45f, c.y); close()
    }, tint)
}
private fun DrawScope.drawMusicPause(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawRect(color = tint, topLeft = Offset(c.x - r * 0.4f, c.y - r * 0.5f), size = Size((c.x - r * 0.05f) - (c.x - r * 0.4f), (c.y + r * 0.5f) - (c.y - r * 0.5f)))
    drawRect(color = tint, topLeft = Offset(c.x + r * 0.05f, c.y - r * 0.5f), size = Size((c.x + r * 0.4f) - (c.x + r * 0.05f), (c.y + r * 0.5f) - (c.y - r * 0.5f)))
}
private fun DrawScope.drawVideoPlay(tint: Color) { drawVideoGen(tint) } // video + play
private fun DrawScope.drawVolumeCtrl(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // speaker
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.6f, c.y - r * 0.3f); lineTo(c.x - r * 0.2f, c.y - r * 0.3f)
        lineTo(c.x - r * 0.2f, c.y + r * 0.3f); lineTo(c.x - r * 0.6f, c.y + r * 0.3f); close()
    }, tint.copy(alpha = 0.15f), style = Stroke(r * 0.07f))
    // waves
    drawArc(color = tint.copy(alpha = 0.2f), startAngle = -30f, sweepAngle = 60f, useCenter = false, topLeft = Offset(c.x, c.y - r * 0.5f), size = Size(r * 0.6f, r * 1.0f), style = Stroke(r * 0.05f))
    drawArc(color = tint.copy(alpha = 0.15f), startAngle = -30f, sweepAngle = 60f, useCenter = false, topLeft = Offset(c.x + r * 0.1f, c.y - r * 0.6f), size = Size(r * 0.5f, r * 1.2f), style = Stroke(r * 0.05f))
}

// ── DEVOPS ──
private fun DrawScope.drawGitOp(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // branch diagram
    drawCircle(color = tint.copy(alpha = 0.2f), radius = r * 0.18f, center = Offset(c.x - r * 0.4f, c.y + r * 0.4f), style = Stroke(r * 0.05f))
    drawCircle(color = tint.copy(alpha = 0.2f), radius = r * 0.18f, center = Offset(c.x + r * 0.4f, c.y - r * 0.4f), style = Stroke(r * 0.05f))
    drawLine(tint, Offset(c.x - r * 0.25f, c.y + r * 0.3f), Offset(c.x + r * 0.15f, c.y - r * 0.2f), strokeWidth = r * 0.06f)
    drawLine(tint, Offset(c.x - r * 0.4f, c.y + r * 0.55f), Offset(c.x - r * 0.4f, c.y + r * 0.8f), strokeWidth = r * 0.05f)
}
private fun DrawScope.drawBuildProj(tint: Color) { drawBrowser(tint) } // monitor-like
private fun DrawScope.drawDeploy(tint: Color) { drawPermission(tint) } // shield + check
private fun DrawScope.drawDocker(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // whale/dock shape simplified
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.6f, c.y - r * 0.3f); lineTo(c.x + r * 0.6f, c.y - r * 0.3f)
        lineTo(c.x + r * 0.6f, c.y + r * 0.3f); lineTo(c.x - r * 0.6f, c.y + r * 0.3f); close()
    }, tint.copy(alpha = 0.12f), style = Stroke(r * 0.07f))
    // containers inside
    drawRect(color = tint.copy(alpha = 0.15f), topLeft = Offset(c.x - r * 0.4f, c.y - r * 0.12f), size = Size((c.x - r * 0.05f) - (c.x - r * 0.4f), (c.y + r * 0.12f) - (c.y - r * 0.12f)))
    drawRect(color = tint.copy(alpha = 0.15f), topLeft = Offset(c.x + r * 0.05f, c.y - r * 0.12f), size = Size((c.x + r * 0.4f) - (c.x + r * 0.05f), (c.y + r * 0.12f) - (c.y - r * 0.12f)))
}
private fun DrawScope.drawCiCd(tint: Color) { drawCodeLint(tint) } // gear-like

// ── CMS ──
private fun DrawScope.drawCmsDeploy(tint: Color) { drawPackageMgr(tint) } // cube
private fun DrawScope.drawCmsPlugin(tint: Color) { /* wrench */ drawRootExec(tint) }
private fun DrawScope.drawCmsModule(tint: Color) { drawGridTap(tint) } // grid
private fun DrawScope.drawCmsTerminal(tint: Color) { drawTerminalIcon(tint) }

// ── BOT / IM ──
private fun DrawScope.drawBotSend(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // robot head
    drawRoundRect(color = tint.copy(alpha = 0.1f), topLeft = Offset(c.x - r * 0.6f, c.y - r * 0.5f), size = Size((c.x + r * 0.6f) - (c.x - r * 0.6f), (c.y + r * 0.4f) - (c.y - r * 0.5f)), cornerRadius = CornerRadius(r * 0.15f, r * 0.15f), style = Stroke(r * 0.07f))
    // eyes
    drawCircle(color = tint.copy(alpha = 0.3f), radius = r * 0.07f, center = Offset(c.x - r * 0.2f, c.y - r * 0.1f))
    drawCircle(color = tint.copy(alpha = 0.3f), radius = r * 0.07f, center = Offset(c.x + r * 0.2f, c.y - r * 0.1f))
    // antenna
    drawLine(tint, Offset(c.x, c.y - r * 0.5f), Offset(c.x, c.y - r * 0.7f), strokeWidth = r * 0.05f)
    drawCircle(color = tint.copy(alpha = 0.3f), radius = r * 0.06f, center = Offset(c.x, c.y - r * 0.7f))
}
private fun DrawScope.drawBotRecv(tint: Color) { drawSendMsg(tint) } // incoming bubble
private fun DrawScope.drawBotWebhook(tint: Color) { drawNotify(tint) } // bell
private fun DrawScope.drawBotCard(tint: Color) { drawBotSend(tint) } // robot face

// ── MEMORY ──
private fun DrawScope.drawMemSave(tint: Color) { drawEmbedding(tint) } // hexagon + arrow
private fun DrawScope.drawMemSearch(tint: Color) { drawWebSearch(tint) } // magnifier
private fun DrawScope.drawMemList(tint: Color) { drawSummarize(tint) } // list doc
private fun DrawScope.drawMemDelete(tint: Color) { drawDeleteFile(tint) } // trash

// ── VISUALIZATION ──
private fun DrawScope.drawChartBar(tint: Color) { drawDataChart(tint) }
private fun DrawScope.drawChartPie(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    drawCircle(color = tint.copy(alpha = 0.1f), radius = r * 0.7f, center = c, style = Stroke(r * 0.07f))
    // slice hint
    drawArc(color = tint.copy(alpha = 0.25f), startAngle = -90f, sweepAngle = 120f, useCenter = true, topLeft = Offset(c.x - r * 0.7f, c.y - r * 0.7f), size = Size(r * 1.4f, r * 1.4f), style = Stroke(r * 0.07f))
}
private fun DrawScope.drawChartLine(tint: Color) { drawAnalytics(tint) }
private fun DrawScope.drawTableRender(tint: Color) { drawIdeOpen(tint) } // grid/table

// ── HEALTH ──
private fun DrawScope.drawHealthCheck(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // heartbeat ECG
    drawLine(tint, Offset(c.x - r * 0.8f, c.y), Offset(c.x - r * 0.3f, c.y), strokeWidth = r * 0.07f)
    drawLine(tint, Offset(c.x - r * 0.3f, c.y), Offset(c.x, c.y - r * 0.5f), strokeWidth = r * 0.08f)
    drawLine(tint, Offset(c.x, c.y - r * 0.5f), Offset(c.x + r * 0.3f, c.y + r * 0.5f), strokeWidth = r * 0.08f)
    drawLine(tint, Offset(c.x + r * 0.3f, c.y + r * 0.5f), Offset(c.x + r * 0.8f, c.y + r * 0.5f), strokeWidth = r * 0.07f)
}
private fun DrawScope.drawLogView(tint: Color) { drawSummarize(tint) } // doc lines
private fun DrawScope.drawErrorAlert(tint: Color) { drawWeatherAlert(tint) } // warning tri
private fun DrawScope.drawPerfStats(tint: Color) { drawSetAlarm(tint) } // clock/gauge

// ── SPEECH ──
private fun DrawScope.drawTts(tint: Color) {
    val c = center; val r = size.minDimension * 0.35f
    // speaker + sound waves
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.6f, c.y - r * 0.3f); lineTo(c.x - r * 0.2f, c.y - r * 0.3f)
        lineTo(c.x - r * 0.2f, c.y + r * 0.3f); lineTo(c.x - r * 0.6f, c.y + r * 0.3f); close()
    }, tint.copy(alpha = 0.15f), style = Stroke(r * 0.07f))
    // waves
    drawArc(color = tint.copy(alpha = 0.2f), startAngle = -30f, sweepAngle = 60f, useCenter = false, topLeft = Offset(c.x, c.y - r * 0.5f), size = Size(r * 0.6f, r * 1.0f), style = Stroke(r * 0.05f))
    drawArc(color = tint.copy(alpha = 0.15f), startAngle = -30f, sweepAngle = 60f, useCenter = false, topLeft = Offset(c.x + r * 0.1f, c.y - r * 0.6f), size = Size(r * 0.5f, r * 1.2f), style = Stroke(r * 0.05f))
}

// ══════════════════════════════════════
// HELPERS
// ══════════════════════════════════════
private fun DrawScope.drawDot(pos: Offset, radius: Float, color: Color) {
    drawCircle(color = color, radius = radius, center = pos)
}

// ══════════════════════════════════════
// PUBLIC COMPOSABLES
// ════════════════════════════════════

/**
 * Ultra-small tool invocation icon drawn on Canvas.
 * @param kind Which tool to depict (one of 100 [ToolKind] values).
 * @param tint Foreground color (defaults to category color).
 * @param modifier Size modifier; default 22×22dp.
 */
@Composable
fun ToolCallIcon(
    kind: ToolKind,
    tint: Color? = null,
    modifier: Modifier = Modifier.size(22.dp),
) {
    val isLight = !isSystemInDarkTheme()
    val cat = kind.categoryToken()
    val color = tint ?: cat.color(isLight).copy(alpha = 0.85f)

    Canvas(modifier.clip(RoundedCornerShape(5.dp))) {
        renderToolIcon(kind, color)
    }
}

private fun DrawScope.renderToolIcon(kind: ToolKind, color: Color) {
    when (kind) {
        ToolKind.READ_FILE -> drawFileRead(color)
        ToolKind.WRITE_FILE -> drawFileWrite(color)
        ToolKind.DELETE_FILE -> drawDeleteFile(color)
        ToolKind.CREATE_FILE -> drawCreateFile(color)
        ToolKind.LIST_DIR -> drawListDir(color)
        ToolKind.DOWNLOAD -> drawDownload(color)
        ToolKind.WEB_SEARCH -> drawWebSearch(color)
        ToolKind.WEB_FETCH -> drawWebFetch(color)
        ToolKind.OPEN_URL -> drawOpenUrl(color)
        ToolKind.BROWSER -> drawBrowser(color)
        ToolKind.API_CALL -> drawApiCall(color)
        ToolKind.CLI_EXEC -> drawCliExec(color)
        ToolKind.TERMINAL -> drawTerminalIcon(color)
        ToolKind.SHELL_RUN -> drawShellRun(color)
        ToolKind.LINUX_ENV -> drawLinuxEnv(color)
        ToolKind.PROOT -> drawProot(color)
        ToolKind.ROOT_EXEC -> drawRootExec(color)
        ToolKind.CODE_RUN -> drawCodeRun(color)
        ToolKind.CODE_EDIT -> drawCodeEdit(color)
        ToolKind.CODE_LINT -> drawCodeLint(color)
        ToolKind.IDE_OPEN -> drawIdeOpen(color)
        ToolKind.TEST_RUN -> drawTestRun(color)
        ToolKind.PACKAGE_MGR -> drawPackageMgr(color)
        ToolKind.LLM_CHAT -> drawLlmChat(color)
        ToolKind.CLASSIFY -> drawClassify(color)
        ToolKind.TRANSLATE -> drawTranslate(color)
        ToolKind.SUMMARIZE -> drawSummarize(color)
        ToolKind.OCR -> drawOcr(color)
        ToolKind.EMBEDDING -> drawEmbedding(color)
        ToolKind.IMAGE_GEN -> drawImageGen(color)
        ToolKind.IMAGE_CROP -> drawImageCrop(color)
        ToolKind.VIDEO_GEN -> drawVideoGen(color)
        ToolKind.AUDIO_GEN -> drawAudioGen(color)
        ToolKind.IMAGE_EDIT -> drawImageEdit(color)
        ToolKind.FILTER_APPLY -> drawFilterApply(color)
        ToolKind.TAP -> drawTap(color)
        ToolKind.SWIPE -> drawSwipe(color)
        ToolKind.SCROLL -> drawScroll(color)
        ToolKind.INPUT_TEXT -> drawInputText(color)
        ToolKind.GET_SCREEN -> drawGetScreen(color)
        ToolKind.GRID_TAP -> drawGridTap(color)
        ToolKind.DEVICE_INFO -> drawDeviceInfo(color)
        ToolKind.BATTERY -> drawBattery(color)
        ToolKind.NETWORK -> drawNetwork(color)
        ToolKind.STORAGE -> drawStorage(color)
        ToolKind.CPU_MONITOR -> drawCpuMonitor(color)
        ToolKind.NOTIFY -> drawNotify(color)
        ToolKind.SEND_EMAIL -> drawSendEmail(color)
        ToolKind.PHONE_CALL -> drawPhoneCall(color)
        ToolKind.SEND_MSG -> drawSendMsg(color)
        ToolKind.PUSH_BROADCAST -> drawPushBroadcast(color)
        ToolKind.DB_QUERY -> drawDbQuery(color)
        ToolKind.CSV_PARSE -> drawCsvParse(color)
        ToolKind.JSON_XFORM -> drawJsonXform(color)
        ToolKind.DATA_CHART -> drawDataChart(color)
        ToolKind.ANALYTICS -> drawAnalytics(color)
        ToolKind.ENCRYPT -> drawEncrypt(color)
        ToolKind.DECRYPT -> drawDecrypt(color)
        ToolKind.PERMISSION -> drawPermission(color)
        ToolKind.VERIFY -> drawVerify(color)
        ToolKind.RISK_CHECK -> drawRiskCheck(color)
        ToolKind.SET_ALARM -> drawSetAlarm(color)
        ToolKind.TIMER -> drawTimer(color)
        ToolKind.CALENDAR -> drawCalendar(color)
        ToolKind.SCHEDULE_TASK -> drawScheduleTask(color)
        ToolKind.COUNTDOWN -> drawCountdown(color)
        ToolKind.GPS_LOC -> drawGpsLoc(color)
        ToolKind.GEOCODE -> drawGeocode(color)
        ToolKind.NAVIGATE_TO -> drawNavigateTo(color)
        ToolKind.TRACK_ROUTE -> drawTrackRoute(color)
        ToolKind.WEATHER_NOW -> drawWeatherNow(color)
        ToolKind.WEATHER_FORECAST -> drawWeatherForecast(color)
        ToolKind.WEATHER_ALERT -> drawWeatherAlert(color)
        ToolKind.AIR_QUALITY -> drawAirQuality(color)
        ToolKind.MUSIC_PLAY -> drawMusicPlay(color)
        ToolKind.MUSIC_PAUSE -> drawMusicPause(color)
        ToolKind.VIDEO_PLAY -> drawVideoPlay(color)
        ToolKind.VOLUME_CTRL -> drawVolumeCtrl(color)
        ToolKind.GIT_OP -> drawGitOp(color)
        ToolKind.BUILD_PROJ -> drawBuildProj(color)
        ToolKind.DEPLOY -> drawDeploy(color)
        ToolKind.DOCKER -> drawDocker(color)
        ToolKind.CI_CD -> drawCiCd(color)
        ToolKind.CMS_DEPLOY -> drawCmsDeploy(color)
        ToolKind.CMS_PLUGIN -> drawCmsPlugin(color)
        ToolKind.CMS_MODULE -> drawCmsModule(color)
        ToolKind.CMS_TERMINAL -> drawCmsTerminal(color)
        ToolKind.BOT_SEND -> drawBotSend(color)
        ToolKind.BOT_RECV -> drawBotRecv(color)
        ToolKind.BOT_WEBHOOK -> drawBotWebhook(color)
        ToolKind.BOT_CARD -> drawBotCard(color)
        ToolKind.MEM_SAVE -> drawMemSave(color)
        ToolKind.MEM_SEARCH -> drawMemSearch(color)
        ToolKind.MEM_LIST -> drawMemList(color)
        ToolKind.MEM_DELETE -> drawMemDelete(color)
        ToolKind.CHART_BAR -> drawChartBar(color)
        ToolKind.CHART_PIE -> drawChartPie(color)
        ToolKind.CHART_LINE -> drawChartLine(color)
        ToolKind.TABLE_RENDER -> drawTableRender(color)
        ToolKind.HEALTH_CHECK -> drawHealthCheck(color)
        ToolKind.LOG_VIEW -> drawLogView(color)
        ToolKind.ERROR_ALERT -> drawErrorAlert(color)
        ToolKind.PERF_STATS -> drawPerfStats(color)
        ToolKind.TTS -> drawTts(color)
        ToolKind.GENERIC -> drawGeneric(color)
    }
}

/**
 * A compact chip showing which tool was invoked.
 * Used inline in chat bubbles or tool-call summary areas.
 *
 * @param kind The tool kind (determines icon + color).
 * @param label Override label (defaults to [kind.displayName]).
 * @param onClick Optional click handler.
 * @param modifier Outer modifier.
 */
@Composable
fun ToolCallChip(
    kind: ToolKind,
    label: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isLight = !isSystemInDarkTheme()
    val cs = MaterialTheme.colorScheme
    val cat = kind.categoryToken()
    val catColor = cat.color(isLight)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(catColor.copy(alpha = 0.1f))
            .border(0.6.dp, catColor.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ToolCallIcon(kind = kind, tint = catColor, modifier = Modifier.size(16.dp))
        Text(
            text = label ?: kind.displayName,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = catColor,
            maxLines = 1,
        )
    }
}

/**
 * Horizontal flow of tool chips — shows all tools invoked in one turn.
 * Wraps to multiple lines if needed.
 */
@Composable
fun ToolCallChipRow(
    kinds: List<ToolKind>,
    modifier: Modifier = Modifier,
    onChipClick: ((ToolKind) -> Unit)? = null,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        kinds.forEach { kind ->
            ToolCallChip(
                kind = kind,
                onClick = onChipClick?.let { { it(kind) } },
            )
        }
    }
}

/**
 * Fallback icon for unknown / unmapped tools — a simple wrench silhouette.
 */
private fun DrawScope.drawGeneric(color: Color) {
    val w = size.width
    val h = size.height
    val c = Offset(w * 0.5f, h * 0.5f)
    val r = minOf(w, h) * 0.34f

    // handle (vertical bar)
    drawRoundRect(
        color = color,
        topLeft = Offset(c.x - w * 0.07f, c.y - r * 0.2f),
        size = Size(w * 0.14f, h * 0.5f),
        cornerRadius = CornerRadius(w * 0.07f),
    )
    // head (rounded rectangle, slightly rotated feel via offset)
    drawRoundRect(
        color = color,
        topLeft = Offset(c.x - r * 0.62f, c.y - r * 0.95f),
        size = Size(r * 1.24f, r * 0.95f),
        cornerRadius = CornerRadius(r * 0.3f),
    )
    // hollow center
    drawCircle(
        color = Color.Transparent,
        radius = r * 0.28f,
        center = Offset(c.x, c.y - r * 0.48f),
    )
}

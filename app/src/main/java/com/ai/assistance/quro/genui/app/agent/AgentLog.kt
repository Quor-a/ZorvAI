package com.ai.assistance.quro.genui.app.agent

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 执行日志（Markdown 落盘）：AI 执行过程的流水账，按天一个 .md 文件。
 *
 * 双轨写入：
 *  - 系统自动：任务开始 / 工具调用 / 渲染交付 / 自检 / 小程序创建，由 AgentLog.append 记录；
 *  - AI 主动：log_write 工具写执行笔记，log_read 回看历史过程。
 *
 * 文件：filesDir/agent_logs/<yyyy-MM-dd>.md，Markdown 章节（## HH:MM · 标题 + 列表行）。
 */
object AgentLog {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    fun dir(context: Context): File = File(context.filesDir, "agent_logs").apply { mkdirs() }

    fun todayFile(context: Context): File = File(dir(context), dayFmt.format(Date()) + ".md")

    /** 追加一个章节。section 首行应为 "## HH:MM · 标题"，后续为 "- ..." 行（自动补时间戳头）。 */
    fun append(context: Context, title: String, lines: List<String>) {
        runCatching {
            val f = todayFile(context)
            if (!f.exists()) f.writeText("# ${dayFmt.format(Date())} 执行日志\n\n")
            val sb = StringBuilder()
            sb.append("## ").append(timeFmt.format(Date())).append(" · ").append(title.trim()).append("\n")
            lines.forEach { sb.append("- ").append(it.trim()).append("\n") }
            sb.append("\n")
            f.appendText(sb.toString())
        }
    }

    /** AI 主动写：往当天日志追加一条列表行（挂在"执行笔记"章节下） */
    fun note(context: Context, text: String) {
        runCatching {
            val f = todayFile(context)
            if (!f.exists()) f.writeText("# ${dayFmt.format(Date())} 执行日志\n\n## 执行笔记\n")
            if (!f.readText().contains("## 执行笔记")) f.appendText("\n## 执行笔记\n")
            f.appendText("- [${timeFmt.format(Date())}] ${text.trim()}\n")
        }
    }

    fun readDay(context: Context, day: String): String {
        val safe = day.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: dayFmt.format(Date())
        return runCatching { File(dir(context), "$safe.md").readText() }
            .getOrDefault("（$safe 无日志）")
    }

    fun listDays(context: Context): List<Pair<String, Long>> =
        dir(context).listFiles { f -> f.name.endsWith(".md") }
            ?.map { it.name.removeSuffix(".md") to it.length() }
            ?.sortedByDescending { it.first }
            ?: emptyList()
}

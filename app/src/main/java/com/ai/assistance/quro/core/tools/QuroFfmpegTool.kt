package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 在 proot 容器内跑 FFmpeg 完整链路：媒体探测/转码/裁剪/合并/提取音频等。
 * 输入/输出路径用容器内 /sdcard/...（即宿主共享存储）；首次使用自动 apt 安装 ffmpeg。
 */
class QuroFfmpegTool : QuroTool {
    override val name = "ffmpeg"
    override val description = "在 proot 容器内跑 FFmpeg 完整链路：媒体探测/转码/裁剪/合并/提取音频等。" +
        "参数 {\"action\":\"info|convert|execute\",\"input\":\"输入文件（容器内 /sdcard/...）\",\"output\":\"输出文件（convert 用）\",\"options\":\"转码参数（convert 用，如 -vf scale=640:-1 -b:v 1M）\",\"args\":\"原始 FFmpeg 参数（execute 用）,\"timeout_ms\":60000}。" +
        "首次使用自动 apt 安装 ffmpeg。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"info=探测媒体信息 | convert=转码/处理 | execute=原始参数执行"},
            "input":{"type":"string","description":"输入文件路径（容器内 /sdcard/...）"},
            "output":{"type":"string","description":"输出文件路径（convert 用）"},
            "options":{"type":"string","description":"转码选项（convert 用）"},
            "args":{"type":"string","description":"完整 FFmpeg 参数（execute 用）"},
            "timeout_ms":{"type":"integer","description":"超时毫秒，默认 60000，最大 300000"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = jo.optString("action", "info").trim().lowercase()
        val timeout = jo.optInt("timeout_ms", 60000).coerceIn(1000, 300000)
        return runBlocking {
            val ensure = "command -v ffmpeg >/dev/null 2>&1 || { apt-get update -qq >/dev/null 2>&1 && apt-get install -y ffmpeg >/dev/null 2>&1; }"
            val cmd = when (action) {
                "info" -> {
                    val input = toProot(jo.optString("input", "").trim())
                    if (input.isEmpty()) return@runBlocking "❌ info 需要 input"
                    "$ensure\nffprobe -v error -print_format json -show_format -show_streams \"$input\""
                }
                "convert" -> {
                    val input = toProot(jo.optString("input", "").trim())
                    val output = toProot(jo.optString("output", "").trim())
                    val options = jo.optString("options", "").trim()
                    if (input.isEmpty() || output.isEmpty()) return@runBlocking "❌ convert 需要 input 与 output"
                    "$ensure\nffmpeg -y -i \"$input\" $options \"$output\""
                }
                "execute" -> {
                    val args = jo.optString("args", "").trim()
                    if (args.isEmpty()) return@runBlocking "❌ execute 需要 args"
                    "$ensure\nffmpeg -y $args"
                }
                else -> return@runBlocking "❌ 未知 action：$action"
            }
            val (rc, out) = QuroLinuxEnv.run(context, cmd, timeout.toLong())
            if (rc != 0 && action == "info") return@runBlocking "❌ ffprobe 失败（exit=$rc）：\n${out.take(2000)}"
            "exit=$rc\n${out.take(6000)}"
        }
    }

    /** 把宿主路径映射到 proot 内路径（共享存储在容器内挂载于 /sdcard）。 */
    private fun toProot(p: String): String {
        if (p.startsWith("/storage/emulated/0")) return "/sdcard" + p.removePrefix("/storage/emulated/0")
        if (p.startsWith("/storage/")) return "/sdcard" + p.removePrefix("/storage")
        return p
    }
}

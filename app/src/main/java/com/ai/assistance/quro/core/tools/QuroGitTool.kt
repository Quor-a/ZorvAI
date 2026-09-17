package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 本地 Git 版本控制集成（对应「其他 AI」文件与编辑维的 Git 集成）。
 * 在 proot Ubuntu 容器内对设备存储上的仓库执行 git 命令；首次使用自动 apt 安装 git。
 * 仓库路径映射：/storage/emulated/0/... ↔ proot 内 /sdcard/...
 */
class QuroGitTool : QuroTool {
    override val name = "git"
    override val description = "本地 Git 版本控制集成：在 proot 容器内对设备存储上的仓库执行 git 命令。" +
        "参数 {\"action\":\"status|log|diff|branch|remote|add|commit|clone|pull|push\"," +
        "\"repo\":\"仓库本地路径（/storage/emulated/0/... 或 /sdcard/...；clone 时为目标目录）\"," +
        "\"url\":\"远程地址（clone 用）\",\"message\":\"提交说明（commit 用）\",\"args\":\"附加参数（可选）,\"timeout_ms\":60000}。" +
        "首次使用自动 apt 安装 git。status/log/diff/branch/remote/add/commit 需已存在的仓库；clone/pull/push 需网络与凭证。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"status | log | diff | branch | remote | add | commit | clone | pull | push"},
            "repo":{"type":"string","description":"仓库本地路径（clone 时为目标目录）"},
            "url":{"type":"string","description":"远程仓库地址（clone 用）"},
            "message":{"type":"string","description":"提交说明（commit 用）"},
            "args":{"type":"string","description":"附加 git 参数（可选，如 log 的 -n 20）"},
            "timeout_ms":{"type":"integer","description":"超时毫秒，默认 60000，最大 300000"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = jo.optString("action", "").trim().lowercase()
        if (action.isEmpty()) return "❌ 缺少 action（status/log/diff/branch/remote/add/commit/clone/pull/push）"
        val timeout = jo.optInt("timeout_ms", 60000).coerceIn(1000, 300000)
        return runBlocking {
            val ensure = "command -v git >/dev/null 2>&1 || { apt-get update -qq >/dev/null 2>&1 && apt-get install -y git >/dev/null 2>&1; }"
            val cmd = when (action) {
                "status" -> {
                    val repo = toProot(jo.optString("repo", "").trim()); if (repo.isEmpty()) return@runBlocking "❌ status 需要 repo"
                    "$ensure\ngit -C \"$repo\" status -s -b"
                }
                "log" -> {
                    val repo = toProot(jo.optString("repo", "").trim()); if (repo.isEmpty()) return@runBlocking "❌ log 需要 repo"
                    val extra = jo.optString("args", "").trim().ifBlank { "-n 10" }
                    "$ensure\ngit -C \"$repo\" log --oneline $extra"
                }
                "diff" -> {
                    val repo = toProot(jo.optString("repo", "").trim()); if (repo.isEmpty()) return@runBlocking "❌ diff 需要 repo"
                    "$ensure\ngit -C \"$repo\" diff --stat"
                }
                "branch" -> {
                    val repo = toProot(jo.optString("repo", "").trim()); if (repo.isEmpty()) return@runBlocking "❌ branch 需要 repo"
                    "$ensure\ngit -C \"$repo\" branch -a"
                }
                "remote" -> {
                    val repo = toProot(jo.optString("repo", "").trim()); if (repo.isEmpty()) return@runBlocking "❌ remote 需要 repo"
                    "$ensure\ngit -C \"$repo\" remote -v"
                }
                "add" -> {
                    val repo = toProot(jo.optString("repo", "").trim()); if (repo.isEmpty()) return@runBlocking "❌ add 需要 repo"
                    val a = jo.optString("args", ".").trim()
                    "$ensure\ngit -C \"$repo\" add $a"
                }
                "commit" -> {
                    val repo = toProot(jo.optString("repo", "").trim()); if (repo.isEmpty()) return@runBlocking "❌ commit 需要 repo"
                    val msg = jo.optString("message", "").trim(); if (msg.isEmpty()) return@runBlocking "❌ commit 需要 message"
                    "$ensure\ngit -C \"$repo\" commit -m \"$msg\""
                }
                "clone" -> {
                    val url = jo.optString("url", "").trim(); if (url.isEmpty()) return@runBlocking "❌ clone 需要 url"
                    val repo = toProot(jo.optString("repo", "").trim())
                    val dest = if (repo.isEmpty()) "/sdcard/quro_git_$(echo \"$url\" | md5sum | cut -c1-8)" else repo
                    "$ensure\ngit clone \"$url\" \"$dest\""
                }
                "pull" -> {
                    val repo = toProot(jo.optString("repo", "").trim()); if (repo.isEmpty()) return@runBlocking "❌ pull 需要 repo"
                    "$ensure\ngit -C \"$repo\" pull --ff-only"
                }
                "push" -> {
                    val repo = toProot(jo.optString("repo", "").trim()); if (repo.isEmpty()) return@runBlocking "❌ push 需要 repo"
                    "$ensure\ngit -C \"$repo\" push"
                }
                else -> return@runBlocking "❌ 未知 action：$action"
            }
            val (rc, out) = QuroLinuxEnv.run(context, cmd, timeout.toLong())
            "exit=$rc\n${out.take(6000)}"
        }
    }

    /** 把宿主路径映射到 proot 内路径（共享存储在容器内挂载于 /sdcard）。 */
    private fun toProot(p: String): String {
        if (p.isEmpty()) return ""
        if (p.startsWith("/storage/emulated/0")) return "/sdcard" + p.removePrefix("/storage/emulated/0")
        if (p.startsWith("/storage/")) return "/sdcard" + p.removePrefix("/storage")
        return p
    }
}

package com.ai.assistance.quro.core.scripting

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tools.Git 宿主 API 后端（JGit 纯 Java 实现，Android minSdk 26 可直接跑）。
 *
 * 定位：JS 沙盒 / 脚本包里的**本地仓库**操作——init / status / add / commit /
 * log / branch / checkout。网络操作（clone/push/pull）涉及凭据与传输层安全，
 * V1 不暴露；脚本要同步远端时用 Tools.Net 拉内容 + Files 写盘的方式自行组合。
 *
 * 调用方（[HostApiDispatcher]）保证 repo 目录已在 sandboxRoot 内解析，
 * 本类只做纯 Git 语义，不重复路径检查。
 */
object GitHostApi {

    private fun repoDir(dir: File): File = dir

    /** git init（已存在 .git 时幂等返回 reinitialized）。 */
    fun init(dir: File): JSONObject {
        if (File(dir, Constants.DOT_GIT).exists()) {
            return JSONObject().put("ok", true).put("reinitialized", true).put("repo", dir.absolutePath)
        }
        Git.init().setDirectory(repoDir(dir)).call().use { }
        return JSONObject().put("ok", true).put("repo", dir.absolutePath)
    }

    /** git status：分支 + 暂存/未暂存/未跟踪文件分组 + clean 标记。 */
    fun status(dir: File): JSONObject {
        Git.open(repoDir(dir)).use { git ->
            val st = git.status().call()
            val current = runCatching { git.repository.branch }.getOrDefault("")
            fun arr(c: Collection<String>): JSONArray = JSONArray().also { a -> c.forEach(a::put) }
            return JSONObject()
                .put("branch", current.substringAfterLast('/'))
                .put("clean", st.isClean)
                .put("staged", arr(st.added + st.changed))
                .put("modified", arr(st.modified))
                .put("removed", arr(st.removed))
                .put("untracked", arr(st.untracked))
        }
    }

    /** git add：files 为工作区相对路径数组，"." 表示全部。 */
    fun add(dir: File, files: List<String>): JSONObject {
        Git.open(repoDir(dir)).use { git ->
            val cmd = git.add()
            val pats = if (files.isEmpty() || files.all { it.trim() == "." }) listOf(".") else files
            pats.forEach { cmd.addFilepattern(it.trim()) }
            cmd.call()
        }
        return JSONObject().put("ok", true)
    }

    /** git commit：自动把已暂存内容提交；无暂存变更时报错（脚本侧应先 add）。 */
    fun commit(dir: File, message: String, name: String, email: String): JSONObject {
        if (message.isBlank()) return JSONObject().put("error", "commit message 不能为空")
        val person = PersonIdent(
            name.ifBlank { "ZorvAI" },
            email.ifBlank { "script@zorvai.local" },
        )
        Git.open(repoDir(dir)).use { git ->
            val st = git.status().call()
            if (st.added.isEmpty() && st.changed.isEmpty() && st.removed.isEmpty()) {
                return JSONObject().put("error", "没有已暂存的变更：先 Tools.Git.add(path, [\".\"]) 再 commit")
            }
            val rev = git.commit().setMessage(message).setAuthor(person).setCommitter(person).call()
            return JSONObject()
                .put("ok", true)
                .put("commit", rev.abbreviate(8).name())
                .put("message", rev.shortMessage)
                .put("branch", git.repository.branch)
        }
    }

    /** git log：最近 max 条（1~100），按时间倒序。 */
    fun log(dir: File, max: Int): JSONObject {
        Git.open(repoDir(dir)).use { git ->
            val arr = JSONArray()
            git.log().setMaxCount(max.coerceIn(1, 100)).call().forEach { c ->
                arr.put(JSONObject()
                    .put("id", c.abbreviate(8).name())
                    .put("message", c.shortMessage)
                    .put("author", c.authorIdent.name)
                    .put("time", c.commitTime * 1000L))
            }
            return JSONObject().put("commits", arr).put("count", arr.length())
        }
    }

    /** git branch：本地分支列表 + 当前分支标记。 */
    fun branchList(dir: File): JSONObject {
        Git.open(repoDir(dir)).use { git ->
            val current = git.repository.branch
            val arr = JSONArray()
            git.branchList().call().forEach { ref ->
                val short = ref.name.substringAfterLast('/')
                arr.put(JSONObject().put("name", short).put("current", short == current))
            }
            return JSONObject().put("branches", arr).put("current", current)
        }
    }

    /** git branch <name>（可选 checkout）。 */
    fun branchCreate(dir: File, name: String, checkout: Boolean): JSONObject {
        if (name.isBlank()) return JSONObject().put("error", "分支名不能为空")
        Git.open(repoDir(dir)).use { git ->
            val cmd = git.branchCreate().setName(name)
            cmd.call()
            if (checkout) git.checkout().setName(name).call()
            return JSONObject().put("ok", true).put("branch", name).put("checkedOut", checkout)
        }
    }

    /** git checkout <branch>。 */
    fun checkout(dir: File, name: String): JSONObject {
        if (name.isBlank()) return JSONObject().put("error", "分支名不能为空")
        Git.open(repoDir(dir)).use { git ->
            git.checkout().setName(name).call()
            return JSONObject().put("ok", true).put("branch", git.repository.branch)
        }
    }
}

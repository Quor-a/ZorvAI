package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.github.QuroGitHubClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 对话框 GitHub 搜索引擎（github_search）。
 *
 * AI 在对话中可直接调用，把 GitHub 变成对话内的搜索入口：
 *  - type=repositories：搜仓库
 *  - type=code：搜代码
 *  - type=issues：搜 Issue/PR
 *  - type=users：搜用户
 *
 * 未登录也能搜（GitHub 搜索接口对匿名有限额），登录后限额更高。
 */
class QuroGitHubSearchTool : QuroTool {
    override val name = "github_search"
    override val description = "在对话框内把 GitHub 当作搜索引擎使用：直接检索 GitHub 的仓库 / 代码 / Issue / 用户。" +
        "参数 {\"type\":\"repositories|code|issues|users\",\"query\":\"搜索词\",\"per_page\":20}。" +
        "type=repositories 搜仓库（如 'kotlin coroutine'）；code 搜代码（如 'language:Kotlin suspend'）；" +
        "issues 搜 Issue/PR（如 'repo:facebook/react bug'）；users 搜用户。返回结构化结果（名称/链接/星级等）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "type":{"type":"string","description":"搜索类型：repositories | code | issues | users"},
            "query":{"type":"string","description":"搜索关键词（GitHub 搜索语法，可带限定符如 language:/repo:/is:pr）"},
            "per_page":{"type":"integer","description":"返回条数，默认 20，最大 50"}
        },
        "required":["type","query"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val type = args.optString("type", "repositories").lowercase()
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) return "❌ 缺少 query 参数"
        val perPage = args.optInt("per_page", 20).coerceIn(1, 50)
        val anon = !QuroGitHubClient.isLoggedIn(context)
        return runBlocking(Dispatchers.IO) {
            try {
                val text = withContext(Dispatchers.IO) {
                    when (type) {
                        "code" -> formatCode(QuroGitHubClient.searchCode(context, query, perPage))
                        "issues" -> formatIssues(QuroGitHubClient.searchIssues(context, query, perPage))
                        "users" -> formatUsers(QuroGitHubClient.searchUsers(context, query, perPage))
                        else -> formatRepos(QuroGitHubClient.searchRepositories(context, query, perPage))
                    }
                }
                (if (anon) "（未登录，匿名限额较低）\n" else "") + text
            } catch (e: Exception) {
                "❌ GitHub 搜索失败：${e.message}"
            }
        }
    }

    private fun formatRepos(list: List<QuroGitHubClient.Repo>): String {
        if (list.isEmpty()) return "未找到匹配的仓库。"
        val sb = StringBuilder("🔍 仓库搜索结果（${list.size}）：\n")
        list.forEachIndexed { i, r ->
            sb.append("${i + 1}. ${r.fullName} ★${r.stars}")
            if (r.language.isNotBlank()) sb.append(" · ${r.language}")
            sb.append("\n   ${r.description.ifBlank { "无描述" }}\n   ${r.htmlUrl}\n")
        }
        return sb.toString()
    }

    private fun formatCode(list: List<QuroGitHubClient.CodeHit>): String {
        if (list.isEmpty()) return "未找到匹配的代码。"
        val sb = StringBuilder("🔍 代码搜索结果（${list.size}）：\n")
        list.forEachIndexed { i, c ->
            sb.append("${i + 1}. ${c.repo} / ${c.path}\n   ${c.htmlUrl}\n")
        }
        return sb.toString()
    }

    private fun formatIssues(list: List<QuroGitHubClient.Issue>): String {
        if (list.isEmpty()) return "未找到匹配的 Issue/PR。"
        val sb = StringBuilder("🔍 Issue/PR 搜索结果（${list.size}）：\n")
        list.forEachIndexed { i, it ->
            sb.append("${i + 1}. [${it.state}] ${it.repo}#${it.number} ${it.title}\n   ${it.htmlUrl}\n")
        }
        return sb.toString()
    }

    private fun formatUsers(list: List<QuroGitHubClient.User>): String {
        if (list.isEmpty()) return "未找到匹配的用户。"
        val sb = StringBuilder("🔍 用户搜索结果（${list.size}）：\n")
        list.forEachIndexed { i, u ->
            sb.append("${i + 1}. ${u.login} (${u.type}) ★${u.followers}\n   ${u.htmlUrl}\n")
        }
        return sb.toString()
    }
}

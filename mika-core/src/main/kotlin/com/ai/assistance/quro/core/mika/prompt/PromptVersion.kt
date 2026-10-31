package com.ai.assistance.quro.core.mika.prompt

import com.ai.assistance.quro.core.mika.model.ToolSpec
import java.security.MessageDigest

/** 本任务提示词的版本:系统提示词加工具定义的摘要,写进运行记录;对比评测时据此区分提示词版本。 */
object PromptVersion {
    fun of(system: String, tools: List<ToolSpec>): String {
        val text = system + tools.joinToString("") { "\n\n" + it.name + "\n" + it.description + "\n" + it.parametersJsonSchema }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
    }
}

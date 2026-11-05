package com.ai.assistance.quro.core.mika.skills

/** pending 草稿;trial 已收下、还没用它办成过事;active 用它办成过(见 LearnGate);suspended 最近不灵。 */
@Suppress("EnumEntryName")
enum class SkillStatus { active, pending, suspended, trial }

/**
 * 用户自己的技能:从办成的任务里沉淀、用户收下的(或待收下的草稿)。
 * [markdown] 是整份 SKILL.md(含头部);[replacesDraftOf] 是「更新」草稿指向的已有技能名;
 * [outcomes] 是最近几次使用结果(停用规则用)。
 */
data class PersonalSkill(
    val name: String,
    val markdown: String,
    val status: SkillStatus,
    val learnedFrom: String?,
    val replacesDraftOf: String?,
    val createdAt: Long,
    val outcomes: List<String>,
    /** 更新草稿起草时读到的原文指纹；收下前必须重新读原文核对。 */
    val baseHash: String? = null,
    /** 从哪儿来的:[SOURCE_USER] 是用户在任务里要求写下的(`skill_save`);为 null 是从办成的任务里沉淀的。 */
    val source: String? = null,
) {
    val createdByUser: Boolean get() = source == SOURCE_USER

    /** 停用后恢复时回到哪一档:还没办成过的(沉淀的、用户要求写下的)回到试用,办成过的回到正式。 */
    fun restoredStatus(): SkillStatus =
        if ((learnedFrom != null || createdByUser) && SkillResult.verified.name !in outcomes) SkillStatus.trial else SkillStatus.active

    companion object {
        const val SOURCE_USER = "user"
    }
}

data class SkillRevision(val id: String, val operation: String, val atMs: Long)

/** 个人技能与待收下草稿的存取;落盘由 app 层实现。 */
interface PersonalSkills {
    /** 已收下的(在用与停用的),不含草稿。 */
    fun list(): List<PersonalSkill>
    fun get(name: String): PersonalSkill?
    fun put(s: PersonalSkill)
    fun remove(name: String)
    /** 读改写一份技能,和别的写入互斥(并行任务同时记结果时不丢);[transform] 返回 null 表示不改。返回改后的。 */
    fun update(name: String, transform: (PersonalSkill) -> PersonalSkill?): PersonalSkill?

    fun pending(): List<PersonalSkill>
    /** 待收下的草稿,按草稿编号(界面收下、不要时用)。 */
    fun drafts(): Map<String, PersonalSkill>
    fun putPending(id: String, s: PersonalSkill)
    /** 收下草稿:成为在用的个人技能(更新草稿覆盖 [PersonalSkill.replacesDraftOf]);[editedMarkdown] 是用户改过的全文。草稿不存在 → null。 */
    fun approve(id: String, editedMarkdown: String? = null): PersonalSkill?
    fun reject(id: String)
    fun revisions(name: String): List<SkillRevision> = emptyList()
    fun restore(revision: String): Boolean = false

    companion object {
        /** 给模型看的版本号:原文指纹的前 12 位(读了再改,改的时候带上它,对不上说明中途被改过)。 */
        fun version(markdown: String): String = fingerprint(markdown).take(12)
        fun fingerprint(markdown: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(markdown.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        /** 技能名:小写字母、数字、连字符,同时也是目录名。 */
        val NAME = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

        /** 把头部的 `name:` 行改成 [name](收下更新草稿、改过全文时,头部名字要和存放的名字一致);没有头部原样返回。 */
        fun withName(markdown: String, name: String): String = withField(markdown, "name", name)

        /** 头部的 [key] 行换成 [value](没有就加上);没有头部的原样返回。 */
        fun withField(markdown: String, key: String, value: String): String {
            val lines = markdown.replace("\r\n", "\n").split("\n").toMutableList()
            if (lines.firstOrNull()?.trimEnd() != "---") return markdown
            val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return markdown
            val i = (1 until end).firstOrNull { lines[it].startsWith("$key:") }
            if (i != null) lines[i] = "$key: $value" else lines.add(end, "$key: $value")
            return lines.joinToString("\n")
        }

        /** 去掉头部的 [key] 行;没有头部或没有这一行的原样返回。 */
        fun withoutField(markdown: String, key: String): String {
            val lines = markdown.replace("\r\n", "\n").split("\n").toMutableList()
            if (lines.firstOrNull()?.trimEnd() != "---") return markdown
            val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return markdown
            val i = (1 until end).firstOrNull { lines[it].startsWith("$key:") } ?: return markdown
            lines.removeAt(i)
            return lines.joinToString("\n")
        }
    }
}

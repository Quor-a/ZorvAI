package com.ai.assistance.quro.core.mika.verify

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * 回答的一处来源:指回证据账本里的一条观察([obsId],见 [com.ai.assistance.quro.core.mika.observation.Observation.id]),
 * 不论它是什么(一个界面、一页、一个文件、一段命令输出、一段回复)。
 * [kind] 是观察的种类([com.ai.assistance.quro.core.mika.observation.ObsKind.wire];旧记录里是 screen / web / app_ai / output / fact);
 * [source] 是给出它的能力(旧记录没有);[place] 是在哪里读的(App 包名、「网站 域名」或网址、命令或文件),[title] 是那一页自己的标题;
 * [step] 是第几步读的;[mediaIds] 是那一步存下的画面编号(没有为空)。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnswerSource(
    @JsonNames("entryId") val obsId: Int, val kind: String, val place: String?, val title: String?, val step: Int,
    val mediaIds: List<Int> = emptyList(), val source: String? = null,
)

/** 回答的来源由代码找,不靠模型写:证据账本里回答的事实对得上的那几条观察。 */
object AnswerSources {
    /** 至多列这么多处。 */
    const val MAX = 6

    /**
     * [media] 是每一步存下的画面编号(步 → 编号)。回答里有对得上的事实时列出这些观察;
     * 回答里没有可对的事实(「已打开蓝牙」)时用最后一条观察,那是回答说的那个结果所在的地方。什么也没读过时为空。
     */
    fun of(ledger: EvidenceLedger, goal: String, answer: String, media: Map<Int, List<Int>>): List<AnswerSource> {
        val hits = ledger.supporting(goal, answer).ifEmpty { listOfNotNull(ledger.all.lastOrNull()) }
        return hits.map { AnswerSource(it.id, it.kind.wire, it.where, it.title, it.step, media[it.step].orEmpty(), it.source) }
            .takeLast(MAX)
    }
}

package com.ai.assistance.quro.core.mika.visual

import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.tool.RECALL_KEEP
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.fail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 最近 [RECALL_KEEP] 次读屏,供 view_past_screens 回看;受保护页面、付款页不留图。 */
internal class Recall(val id: Int, val step: Int, val app: String?, val png: ByteArray?, val elementsText: String)

/**
 * 几块屏共用的画面编号与回看:screenshotId 在一个任务里统一往上数,view_past_screens 不管是哪块屏的都找得到。
 * [step] 是本次调用所在的轮次(循环的「第 N 步」),回看记录里的 step。
 */
internal class Shots {
    var step = 0
    var lastId = 0
        private set
    val recalls = ArrayDeque<Recall>()

    fun next(): Int = ++lastId

    fun remember(r: Recall) {
        recalls.addLast(r)
        while (recalls.size > RECALL_KEEP) recalls.removeFirst()
    }

    /**
     * 把画面的 screenshotId 当成了 snapshotId(看着图操作时常这样):画面编号只用来回看,不能拿来点。
     * 读屏编号从来不是纯数字,纯数字且不超过已发过的画面编号的就是这种错;照实指出该用哪个。
     */
    fun idAsSnapshot(o: JsonObject): ToolExecution? {
        val p = o["snapshotId"] as? JsonPrimitive ?: return null
        val n = p.content.trim().toIntOrNull() ?: return null
        if (n < 1 || n > lastId) return null
        return fail("ref_expired", Feedback.format("screenshot_id_as_snapshot", "id" to n))
    }
}

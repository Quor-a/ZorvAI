package com.ai.assistance.quro.genui.app.llm

import com.ai.assistance.quro.genui.app.store.FeatureRouting
import com.ai.assistance.quro.genui.app.store.GenStore
import com.ai.assistance.quro.genui.app.store.ModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 快速模型通道 —— 「专项模型分派」里 fastProviderId 的真实落点。
 *
 * 历史上这个字段只存在于设置界面与存储里，全代码库无人使用（纯装饰）。
 * 这里给它三个真实职责（都要求「快、短、便宜」）：
 *  1. [suggestPrompts] 空态画布：根据灵魂与记忆，生成 3 条个性化指令建议
 *  2. [refineTitle]    界面标题：模型在 <title> 里写的名字太长/缺失时，用快模型压缩成人话
 *  3. [preflight]      意图预检：判断这条指令是否属于"该先联网查"的类型，给主脑一个提示
 *
 * 未配置快速模型时全部静默降级 —— 调用方拿不到结果，主流程不受影响。
 */
class FastClient(private val store: GenStore) {

    /** 取快速模型；未配置则回退到主脑（保证功能永远可用） */
    fun provider(): ModelProvider? {
        val routing: FeatureRouting = store.loadRouting()
        val all = store.loadProviders().filter { it.enabled }
        return all.find { it.id == routing.fastProviderId }
            ?: all.find { it.id == routing.mainProviderId }
            ?: all.firstOrNull()
    }

    /** 是否真的配置了独立的快速模型（用于 UI 提示分派是否生效） */
    fun hasDedicated(): Boolean {
        val r = store.loadRouting()
        return r.fastProviderId.isNotBlank() && r.fastProviderId != r.mainProviderId
    }

    /**
     * 生成 3 条指令建议（空态画布用）。返回空列表表示不可用。
     * 用 chatOnce（非流式），失败一律吞掉 —— 这是锦上添花的功能，不该影响主流程。
     */
    suspend fun suggestPrompts(soulName: String, memoryBrief: String): List<String> = withContext(Dispatchers.IO) {
        val p = provider() ?: return@withContext emptyList()
        runCatching {
            val sys = "你是一个输入提示生成器。用户正在使用一个「说一句话就生成整个界面」的 AI 应用。" +
                "请生成 3 条中文指令示例，每条 8-18 字，风格具体、可执行、彼此不同（如记账/清单/看板/查询/工具）。" +
                "只输出 JSON 数组，例如 [\"…\",\"…\",\"…\"]，不要任何解释。"
            val user = "这个 AI 的名字是「$soulName」。${if (memoryBrief.isBlank()) "" else "用户已知偏好：$memoryBrief"}"
            val msg = LLMClient().chatOnce(
                p,
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", user)),
                null
            )
            val raw = msg.optString("content").trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val arr = JSONArray(raw.substring(raw.indexOf('[').coerceAtLeast(0), (raw.lastIndexOf(']') + 1).coerceAtLeast(raw.length)))
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.take(3)
        }.getOrDefault(emptyList())
    }

    /**
     * 把模型自取的标题压缩成 ≤10 字的人话（界面栈里显示）。
     * 标题已经够短时直接返回原值，不浪费一次请求。
     */
    suspend fun refineTitle(raw: String): String = withContext(Dispatchers.IO) {
        val t = raw.trim()
        if (t.length <= 12) return@withContext t
        val p = provider() ?: return@withContext t.take(12)
        runCatching {
            val msg = LLMClient().chatOnce(
                p,
                JSONArray().put(JSONObject().put("role", "user")
                    .put("content", "把下面的界面标题压缩成不超过 10 个字的中文短语，只输出压缩结果，不要标点与解释：\n$t")),
                null
            )
            msg.optString("content").trim().trim('"').take(12).ifBlank { t.take(12) }
        }.getOrDefault(t.take(12))
    }

    /**
     * 意图预检：判断这条指令是否需要实时信息（新闻/价格/天气/赛果/版本…）。
     * 返回给主脑的补充提示；不需要联网时返回 null。
     */
    suspend fun preflight(prompt: String): String? = withContext(Dispatchers.IO) {
        if (prompt.isBlank() || prompt.length > 200) return@withContext null
        val p = provider() ?: return@withContext null
        runCatching {
            val msg = LLMClient().chatOnce(
                p,
                JSONArray().put(JSONObject().put("role", "user").put("content",
                    "判断这句话是否需要联网查询实时信息（新闻/价格/天气/比分/最新版本/汇率等）。" +
                    "只回答 YES 或 NO，不要其他任何字符。\n用户指令：$prompt")),
                null
            )
            if (msg.optString("content").trim().uppercase().startsWith("Y"))
                "用户这次的问题可能涉及实时信息，建议先用 web_search 确认再动手。"
            else null
        }.getOrNull()
    }
}

package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.cards.CardPatch
import com.ai.assistance.quro.core.cards.CardPatchBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * `card_patch` 工具：对**已经下发过**的可视化卡片做增量更新。
 *
 * ## 为什么必须有它
 *
 * 此前可视化组件只有「整张下发」一个方向 —— AI 想把进度从 10 改成 80，
 * 得把整张卡原样再写一遍（101 种组件各自的完整 JSON）。这既费 token，
 * 又容易在重写时把模型自己想保留的字段改掉。而真实场景里绝大多数更新
 * 都是**改一两个值**：进度刷新、倒计时走字、待办勾掉一项、表格改个单元格。
 *
 * 本工具对应 A2UI 的 `updateComponents` / `updateDataModel`：
 * 用 JSON Pointer 定位字段，只发改动的部分。
 *
 * ## 两个动作
 *
 * - `describe`：先探路，返回该卡当前**可改的合法路径**。**强烈建议先做这一步** ——
 *   模型看不见卡片的数据类字段，只能看到自己下发的 JSON；不探路就 patch 必然瞎猜。
 * - `apply`：执行补丁。一批补丁**全成功才生效**（原子），任一条失败整批放弃并
 *   回退原卡，同时把合法路径清单回喂给模型，让它下一轮就能改对。
 *
 * ## 失败为什么不抛异常
 *
 * 工具异常会中断整轮对话，而「路径写错」是模型最容易犯、也最容易自我纠正的错误。
 * 所以本工具把一切失败都变成**可读回喂**（[CardPatch.Result.feedback]），
 * 走工具结果正常返回给模型。
 */
class CardPatchTool : QuroTool {
    override val name = "card_patch"

    override val description =
        "增量更新已下发过的可视化卡片（ui_widget / ui_card / 围栏卡片），只发改动的字段，" +
            "不必重发整张卡。适合：进度/仪表读数刷新、倒计时走字、待办勾掉一项、表格改单元格、" +
            "统计数字更新、列表追加一条。" +
            "操作：set（设值）/ append（数组追加或补键）/ remove（删除）/ merge（深合并，最常用）" +
            " / inc（数值增减，倒计时与进度专用）/ replace（整体替换，会清掉未提及的键，慎用）。" +
            "路径用 JSON Pointer，如 /items/0/done；也认 items.0.done、$.items[0].done。" +
            "🔴 建议先 describe 一次拿合法路径：模型看不到卡片内部字段，不探路极易写错。" +
            "🔴 cardId 必须是下发时用的同一个 id；id 与 cardType 禁止修改（改了就等于换了一张卡）。" +
            "一批补丁全成功才生效，任一条失败整批放弃并回退。"

    /** 同 [CardCatalogTool]：用 [JSONObject] 逐层构造，避免数括号。 */
    override val parametersJson: String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("cardId", JSONObject().apply {
                put("type", "string")
                put("description", "目标卡片 id，即下发这张卡时指定的 id")
            })
            put("describe", JSONObject().apply {
                put("type", "boolean")
                put("description", "true 时只返回该卡当前可改的合法路径清单（探路，不改任何东西），默认 false")
            })
            put("patches", JSONObject().apply {
                put("type", "array")
                put(
                    "items",
                    JSONObject().apply {
                        put("type", "object")
                        put(
                            "properties",
                            JSONObject().apply {
                                put("op", JSONObject().apply {
                                    put("type", "string")
                                    put("enum", JSONArray(CardPatch.OPS))
                                    put("description", "操作类型")
                                })
                                put("path", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "JSON Pointer 路径，如 /items/0/done；数组末尾用 /items/-")
                                })
                                put("value", JSONObject().apply {
                                    put("description", "新值（set/append 用）；merge 时为对象；inc 时为数值")
                                })
                                put("key", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "仅 append 到根时用：指定要补的键名（此时 path 留空）")
                                })
                            }
                        )
                    }
                )
                put("description", "补丁列表，全成功才生效")
            })
            put("syntax", JSONObject().apply {
                put("type", "boolean")
                put("description", "true 时返回补丁语法说明（操作含义、路径写法、示例），默认 false")
            })
        })
        put("required", JSONArray())
    }.toString()

    /** 会改界面状态，故不是 readOnly。 */
    override val readOnly = false

    /**
     * 默认走 [CardPatchBridge] 上的宿主（ChatScreen 注册）。
     *
     * 工具是全局单例、拿不到 ViewModel，所以真正改卡片必须由宿主代劳；
     * 无宿主时 [CardPatchBridge.apply] 返回 null，本工具会如实回「没有界面宿主」，
     * 而**不**谎报成功。
     */
    override fun run(context: Context, arguments: String): String =
        handle(
            arguments,
            host = CardPatchBridge.Host { cardId, patchJson ->
                CardPatchBridge.apply(cardId, patchJson)
            },
            describeOf = { cardId -> CardPatchBridge.describe(cardId) },
        )

    companion object {
        /**
         * 纯逻辑入口，**不碰任何 Android 依赖**，故可 JVM 单测。
         *
         * 与 [CardCatalogTool.query] 同一个理由：工程无 Robolectric，
         * [QuroTool.run] 的 context 形参非空且字节码插桩会校验，逻辑必须搬出来。
         *
         * 副作用出口是 [CardPatchBridge]：工具本身是全局单例、拿不到 ViewModel，
         * 所以真正改卡片由宿主（ChatScreen 注册的处理器 → ViewModel.patchCard）执行。
         *
         * 两个参数刻意做成**可注入**：单测里摆一个假宿主即可验证全部分支，
         * 不必真起界面（工程无 Robolectric，ViewModel 测不了）。
         */
        fun handle(
            arguments: String,
            host: CardPatchBridge.Host = CardPatchBridge.Host { _, _ -> null },
            describeOf: (cardId: String) -> List<String> = { emptyList() },
        ): String {
            return try {
                val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }

                if (jo.optBoolean("syntax", false)) return CardPatch.syntaxJson()

                val cardId = jo.optString("cardId", "").ifBlank { jo.optString("id", "") }.trim()
                if (cardId.isEmpty()) {
                    return "card_patch：缺少 cardId。要改哪张卡必须指明，" +
                        "id 就是下发时 ui_widget / 围栏里写的那个 id。"
                }

                // 探路：与 apply 完全独立，且**优先** —— 先看清楚再改。
                if (jo.optBoolean("describe", false)) {
                    val paths = describeOf(cardId)
                    if (paths.isEmpty()) {
                        return "card_patch：找不到 id=$cardId 的卡片，无法探路。" +
                            "确认这个 id 是下发时用的，且卡片还留在当前会话里。"
                    }
                    return JSONObject().apply {
                        put("cardId", cardId)
                        put("note", "以上为该卡当前可改的合法路径（JSON Pointer）。" +
                            "数组会写成 /key/0 … /key/N-1（len=N）形式，实际下标可任取 0..N-1。")
                        put("paths", JSONArray(paths))
                        put("count", paths.size)
                    }.toString()
                }

                val patches = jo.optJSONArray("patches") ?: jo.optJSONArray("ops")
                // 单条摊平：{op,path,value} 直接给也认（模型常这么写）
                if (patches == null && jo.optString("op", "").isNotBlank()) {
                    val one = JSONArray().apply { put(jo) }
                    return doPatch(cardId, JSONObject().put("patches", one).toString(), host)
                }
                if (patches == null || patches.length() == 0) {
                    return "card_patch：没有补丁。要么给 patches 数组，要么 describe=true 先探路，" +
                        "要么 syntax=true 看语法。"
                }
                doPatch(cardId, JSONObject().put("patches", patches).toString(), host)
            } catch (e: Exception) {
                "❌ card_patch 失败：${e.message}"
            }
        }

        private fun doPatch(
            cardId: String,
            patchJson: String,
            host: CardPatchBridge.Host,
        ): String {
            // 🔴 只能做**不依赖卡片实例**的语法预校验：工具层拿不到卡片
            // （它在宿主 ViewModel 的消息里），路径是否存在、类型是否匹配
            // 一律由宿主 apply 时判定。
            //
            // 这里提前拦的是「模型自己就能看出错」的三类：op 拼错、路径格式错、
            // 碰了受保护键 —— 拦下来能省一整轮「提交→界面报错→再猜」的往返。
            val spec = runCatching { JSONObject(patchJson) }.getOrNull()
                ?: return "card_patch：补丁不是合法 JSON"
            val ops = spec.optJSONArray("patches") ?: spec.optJSONArray("ops")
            if (ops == null || ops.length() == 0) return "card_patch：补丁数组为空"
            val bad = ArrayList<String>()
            for (i in 0 until ops.length()) {
                val op = ops.optJSONObject(i)
                if (op == null) {
                    bad += "#${i + 1} 不是对象"
                    continue
                }
                val kind = op.optString("op", "").trim().lowercase()
                if (kind !in CardPatch.OPS) {
                    bad += "#${i + 1} 未知操作 ${op.optString("op", "(空)")}，可用：${CardPatch.OPS.joinToString("/")}"
                    continue
                }
                val path = op.optString("path", op.optString("pointer", "")).trim()
                if (path.isNotEmpty() && CardPatch.parsePath(path) == null) {
                    bad += "#${i + 1} 路径无法解析：$path"
                    continue
                }
                val segs = CardPatch.parsePath(path).orEmpty()
                if (segs.isNotEmpty() && segs[0] in CardPatch.PROTECTED_KEYS) {
                    bad += "#${i + 1} 禁止修改受保护键 ${segs[0]}（id/cardType 决定卡片身份）"
                }
            }
            if (bad.isNotEmpty()) {
                return "card_patch 补丁写错了，未提交：\n" + bad.joinToString("\n") { "✗ $it" } +
                    "\n可用操作：${CardPatch.OPS.joinToString("/")}；路径如 /items/0/done；" +
                    "不确定先 describe=true 看该卡合法路径。"
            }

            // 同步拿回执：补丁是「请求-响应」语义，模型这一轮就要知道成败。
            // 🔴 无宿主返回 null —— 必须与「有宿主但补丁失败」区别对待。
            //   谎报成功会让模型以为卡片已更新，转而去回复用户「已更新」，
            //   屏幕上却什么都没变 —— 静默失效最典型的形态。
            val r = host.apply(cardId, patchJson)
                ?: return "card_patch：已提交 ${ops.length()} 条补丁，但**当前没有界面宿主**，" +
                    "卡片未真正更新（ChatScreen 未注册补丁桥时会出现）。可稍后重试。"
            return r.feedback(cardId)
        }
    }
}

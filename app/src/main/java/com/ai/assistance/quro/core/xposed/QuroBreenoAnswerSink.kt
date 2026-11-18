package com.ai.assistance.quro.core.xposed

import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * 把 Zorv AI 的回答写回小布自己的对话气泡。
 *
 * 小布界面只认两种输入：
 *  · `MyAI/StreamTextCard` 指令（走 `agent.dispatchDirectives(List, String)` 直接下发）；
 *  · `AIChatViewBean`（chatType=2）经 `AIChatDataCenter` 派发。
 *
 * 这里两条都实现：优先走指令（可流式、可带推理态、可按 uniqueId 更新同一张卡），
 * 指令通道不可用时退到 bean 通道。两条都失败就返回 false，由调用方保留原生行为，
 * 绝不产生「提问被吞掉、回复也没出现」的空白回合。
 *
 * 净室实现：只依赖 [QuroBreenoTargets] 里已定位的类与方法，全部反射，全部可失败。
 */
internal object QuroBreenoAnswerSink {

    private const val NAMESPACE = "MyAI"
    private const val CARD_NAME = "StreamTextCard"
    private const val NAMESPACE_VERSION = "2.0.0"
    private const val HEADER_VERSION = "3.2"
    private const val DOWNSTREAM_VERSION = "3.0"
    private const val CHARS_PER_SECOND = 50
    private const val INJECTED_MARKER = "quroAgent"
    private const val CHAT_TYPE_ANSWER = 2

    /** 卡片类型：2 = 新建，0 = 更新同一张卡。 */
    private const val CARD_TYPE_CREATE = 2
    private const val CARD_TYPE_UPDATE = 0

    /**
     * 下发一帧卡片。
     *
     * @param isFinal false 用于「正在处理…」占位帧与推理片段，true 用于最终答案。
     * @return 是否成功写入小布界面。
     */
    fun dispatch(
        targets: QuroBreenoTargets,
        roomId: String,
        recordId: String,
        query: String,
        content: String,
        reasoning: String = "",
        uniqueId: String,
        isFinal: Boolean,
        firstFrame: Boolean,
    ): Boolean {
        if (roomId.isBlank()) return false
        val type = if (firstFrame) CARD_TYPE_CREATE else CARD_TYPE_UPDATE
        return dispatchByDirective(
            targets = targets,
            roomId = roomId,
            recordId = recordId,
            query = query,
            content = content,
            reasoning = reasoning,
            uniqueId = uniqueId,
            isFinal = isFinal,
            type = type,
        ) || dispatchByBean(
            targets = targets,
            roomId = roomId,
            recordId = recordId,
            query = query,
            content = content,
            reasoning = reasoning,
            isFinal = isFinal,
            type = type,
        )
    }

    // ---- 通道一：指令下发 ----

    private fun dispatchByDirective(
        targets: QuroBreenoTargets,
        roomId: String,
        recordId: String,
        query: String,
        content: String,
        reasoning: String,
        uniqueId: String,
        isFinal: Boolean,
        type: Int,
    ): Boolean {
        val directiveClass = targets.directiveClass ?: return false
        val headerClass = targets.headerClass ?: return false
        val cardClass = targets.streamTextCardClass ?: return false
        val agent = targets.agent() ?: return false
        val dispatch = targets.dispatchDirectives() ?: return false

        return runCatching {
            val header = headerClass.getDeclaredConstructor().newInstance()
            val headerId = newId()
            QuroHookSupport.invokeByName(header, "setId", headerId)
            QuroHookSupport.invokeByName(header, "setNamespace", NAMESPACE)
            QuroHookSupport.invokeByName(header, "setName", CARD_NAME)
            QuroHookSupport.invokeByName(header, "setNamespaceVersion", NAMESPACE_VERSION)
            QuroHookSupport.invokeByName(header, "setVersion", HEADER_VERSION)

            val card = cardClass.getDeclaredConstructor().newInstance()
            QuroHookSupport.invokeByName(card, "setContent", content)
            QuroHookSupport.invokeByName(card, "setRoomId", roomId)
            QuroHookSupport.invokeByName(card, "setFinal", isFinal)
            QuroHookSupport.invokeByName(card, "setType", type)
            QuroHookSupport.invokeByName(card, "setQuery", query)
            QuroHookSupport.invokeByName(card, "setHtml", false)
            QuroHookSupport.invokeByName(card, "setCharPerSec", CHARS_PER_SECOND)
            if (reasoning.isNotBlank()) {
                QuroHookSupport.invokeByName(card, "setReasoningContent", reasoning)
            }

            val directive = directiveClass.getDeclaredConstructor().newInstance()
            QuroHookSupport.invokeByName(directive, "setHeader", header)
            val setPayload = setPayloadMethod(directiveClass, card.javaClass)
            if (setPayload != null) setPayload.invoke(directive, card)
            else QuroHookSupport.invokeByName(directive, "setPayload", card)

            val downstream = downstreamJson(
                headerId = headerId,
                content = content,
                reasoning = reasoning,
                roomId = roomId,
                recordId = recordId,
                query = query,
                isFinal = isFinal,
                type = type,
                uniqueId = uniqueId,
            )
            dispatch.invoke(agent, arrayListOf(directive), downstream)
            true
        }.getOrDefault(false)
    }

    /** `setPayload` 的形参可能是 DirectivePayload 基类，按可赋值匹配。 */
    private fun setPayloadMethod(directiveClass: Class<*>, payloadClass: Class<*>): Method? =
        QuroHookSupport.findDeclaredMethods(directiveClass) { m ->
            m.name.contains("ayload") && m.parameterCount == 1 &&
                m.parameterTypes[0].isAssignableFrom(payloadClass)
        }.firstOrNull()

    // ---- 通道二：AIChatViewBean 派发 ----

    private fun dispatchByBean(
        targets: QuroBreenoTargets,
        roomId: String,
        recordId: String,
        query: String,
        content: String,
        reasoning: String,
        isFinal: Boolean,
        type: Int,
    ): Boolean {
        val beanClass = targets.beanClass ?: return false
        val dispatch = targets.chatDispatch ?: return false
        // bean 通道无法原地更新同一张卡；只在最终帧写入，避免重复气泡。
        if (!isFinal) return false
        val holder = dataCenterHolder ?: return false

        return runCatching {
            val bean = beanClass.getDeclaredConstructor().newInstance()
            QuroHookSupport.invokeByName(bean, "setChatType", CHAT_TYPE_ANSWER)
            QuroHookSupport.invokeByName(bean, "setContent", content)
            QuroHookSupport.invokeByName(bean, "setRoomId", roomId)
            QuroHookSupport.invokeByName(bean, "setRecordId", recordId)
            QuroHookSupport.invokeByName(bean, "setOriginRecordId", recordId)
            QuroHookSupport.invokeByName(
                bean,
                "setPayload",
                payloadJson(
                    headerId = newId(),
                    content = content,
                    reasoning = reasoning,
                    roomId = roomId,
                    query = query,
                    type = type,
                ),
            )
            // 打上自己的标记：否则这条回答回到 addChatBean 时会被「丢厂商回答」的逻辑再吞一次。
            QuroHookSupport.invokeByName(bean, "setClientResult", markedClientResult(roomId, recordId))
            dispatch.invoke(holder, bean)
            true
        }.getOrDefault(false)
    }

    /** 由 `addChatBean` 的拦截回调记录，bean 通道需要这个实例才能自己派发。 */
    @Volatile
    var dataCenterHolder: Any? = null

    // ---- JSON ----

    private fun downstreamJson(
        headerId: String,
        content: String,
        reasoning: String,
        roomId: String,
        recordId: String,
        query: String,
        isFinal: Boolean,
        type: Int,
        uniqueId: String,
    ): String = JSONObject()
        .put("version", DOWNSTREAM_VERSION)
        .put("originalRecordId", recordId)
        .put("recordId", recordId)
        .put("sessionId", "")
        .put("roomId", roomId)
        .put("uniqueId", uniqueId)
        .put("sequenceId", 0)
        .put("extend", JSONObject().put(INJECTED_MARKER, "true"))
        .put(
            "directives",
            JSONArray().put(
                cardJson(
                    headerId = headerId,
                    content = content,
                    reasoning = reasoning,
                    roomId = roomId,
                    query = query,
                    isFinal = isFinal,
                    type = type,
                ),
            ),
        )
        .toString()

    /**
     * bean 通道的自标注：`{"extend":{"quroAgent":"true"}}`。
     *
     * 拦截侧靠它认出「这张卡是我自己写的」。只靠文本指纹不够——小布会重排
     * markdown / 换行，指纹一旦对不上，我们自己的回答就会被自己的逻辑吞掉。
     */
    private fun markedClientResult(roomId: String, recordId: String): String = JSONObject()
        .put("roomId", roomId)
        .put("recordId", recordId)
        .put("extend", JSONObject().put(INJECTED_MARKER, "true"))
        .toString()

    private fun payloadJson(
        headerId: String,
        content: String,
        reasoning: String,
        roomId: String,
        query: String,
        type: Int,
    ): String = JSONObject()
        .put(
            "uiDirectives",
            JSONArray().put(
                cardJson(
                    headerId = headerId,
                    content = content,
                    reasoning = reasoning,
                    roomId = roomId,
                    query = query,
                    isFinal = true,
                    type = type,
                ),
            ),
        )
        .toString()

    private fun cardJson(
        headerId: String,
        content: String,
        reasoning: String,
        roomId: String,
        query: String,
        isFinal: Boolean,
        type: Int,
    ): JSONObject = JSONObject()
        .put(
            "header",
            JSONObject()
                .put("id", headerId)
                .put("namespace", NAMESPACE)
                .put("name", CARD_NAME)
                .put("namespaceVersion", NAMESPACE_VERSION)
                .put("version", HEADER_VERSION),
        )
        .put(
            "payload",
            JSONObject()
                .put("content", content)
                .put("roomId", roomId)
                .put("isFinal", isFinal)
                .put("type", type)
                .put("query", query)
                .put("isHtml", false)
                .put("charPerSec", CHARS_PER_SECOND)
                .apply { if (reasoning.isNotBlank()) put("reasoningContent", reasoning) },
        )

    fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
}

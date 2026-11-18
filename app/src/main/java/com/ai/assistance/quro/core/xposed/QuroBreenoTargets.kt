package com.ai.assistance.quro.core.xposed

import java.lang.reflect.Method

/**
 * 小布（ColorOS Breeno, `com.heytap.speechassist`）协议层定位表。
 *
 * 小布的对话**不经过 Activity Intent**。用户说的话在
 * `AIChatDataCenter.addChatBean(AIChatViewBean)`（chatType=1 为提问、=2 为回答）
 * 与 `CdmNode.voiceStart(DmParameter)`（文本/语音请求）之间流动，
 * 回复由 `HeytapSpeechEngine.getAgent().dispatchDirectives(List, String)` 下发到气泡。
 *
 * 定位策略（不依赖任何 DEX 解析库）：
 *  · 这些类名在小布上是稳定 Java 名，直接 Class.forName；
 *  · 方法名随版本改编，因此按「返回类型 + 参数类型 + 是否静态」做签名匹配；
 *  · **写回链路必须整条成立才允许接管**（见 [canDispatchAnswer]）。缺任何一环就
 *    不装吞答逻辑，宁可退回原生，也不能让用户面对「问题被吃、回答不出来」的空白回合。
 */
internal class QuroBreenoTargets private constructor(
    val beanClass: Class<*>?,
    /** `AIChatDataCenter.addChatBean(AIChatViewBean)`：对话派发（提问与回答都走这里）。 */
    val chatDispatch: Method?,
    val messageClass: Class<*>?,
    /** `MessageQueueManager.send(...)`：出站协议消息（只观测，绝不拦截）。 */
    val outboundSend: Method?,
    val dmParameterClass: Class<*>?,
    /** `CdmNode.voiceStart(DmParameter)`：语音/文本请求进入点。 */
    val cdmTextRequest: Method?,
    val directiveClass: Class<*>?,
    val headerClass: Class<*>?,
    val payloadClass: Class<*>?,
    val streamTextCardClass: Class<*>?,
    val engineClass: Class<*>?,
    val engineInstance: Any?,
    private val initialAgent: Any?,
    val classLoader: ClassLoader,
    private val dispatchCache: Method?,
    val insertRecordClass: Class<*>?,
    val repository: Any?,
    /** `AIChatRepository.insertRecord(roomId, agent, record, callback)`。 */
    val insertHistory: Method?,
) {
    @Volatile
    private var agentCache: Any? = initialAgent

    /**
     * agent 实例在模块安装时可能还没创建（语音引擎懒加载）。
     * 因此允许「按需再取一次」，取到就缓存；取不到返回 null 由调用方降级。
     */
    fun agent(): Any? {
        agentCache?.let { return it }
        val engine = engineInstance
            ?: QuroHookSupport.singletonInstance(classLoader, ENGINE_NAME)
            ?: return null
        val resolved = QuroHookSupport.invokeNoArgs(engine, "getMAgent")
            ?: QuroHookSupport.invokeNoArgs(engine, "getAgent")
            ?: QuroHookSupport.getFieldValue(engine, "mAgent")
        if (resolved != null) agentCache = resolved
        return resolved
    }

    /** `agent.dispatchDirectives(List<Directive>, String)`：把卡片下发到小布界面。 */
    fun dispatchDirectives(): Method? = dispatchCache

    /**
     * 能真正接管小布界面的必要条件。
     *
     * 少任何一项，接管的结果都是「原生回答被吞掉、我们却写不进去」，
     * 对用户来说就是彻底不能用 —— 所以这里必须是硬闸门，而不是软降级。
     */
    val canDispatchAnswer: Boolean
        get() = chatDispatch != null &&
            dispatchDirectives() != null &&
            shapesReady()

    private fun shapesReady(): Boolean =
        directiveClass != null && headerClass != null && streamTextCardClass != null

    /** 写回链路缺了哪些环节（用于安装报告，让「为什么没接管」有据可查）。 */
    val answerContractMissing: List<String>
        get() = buildList {
            if (chatDispatch == null) add("AIChatDataCenter.addChatBean")
            if (beanClass == null) add("AIChatViewBean")
            if (directiveClass == null) add("Directive")
            if (headerClass == null) add("DirectiveHeader")
            if (streamTextCardClass == null) add("StreamTextCard")
            if (dispatchDirectives() == null) add("agent.dispatchDirectives")
        }

    /** 观测链路缺了哪些环节（缺了只是「少一条捕获路径」，不影响接管安全性）。 */
    val captureMissing: List<String>
        get() = buildList {
            if (cdmTextRequest == null) add("CdmNode.voiceStart")
            if (outboundSend == null) add("MessageQueueManager.send")
        }

    companion object {
        const val PACKAGE_PREFIX = "com.heytap.speechassist"

        private const val BEAN = "$PACKAGE_PREFIX.aichat.bean.AIChatViewBean"
        private const val DATA_CENTER = "$PACKAGE_PREFIX.aichat.AIChatDataCenter"
        private const val REPOSITORY = "$PACKAGE_PREFIX.aichat.repository.AIChatRepository"
        private const val INSERT_RECORD = "$PACKAGE_PREFIX.aichat.repository.api.InsertRecord"
        private const val ENGINE = "com.heytap.speech.engine.HeytapSpeechEngine"
        const val ENGINE_NAME = ENGINE
        private const val MESSAGE_QUEUE =
            "com.heytap.speech.engine.connect.core.manager.MessageQueueManager"
        private const val MESSAGE = "com.heytap.speech.engine.protocol.event.Message"
        private const val DM_PARAMETER = "com.heytap.speech.engine.nodes.DmParameter"
        private const val CDM_NODE = "com.heytap.speech.engine.nodes.CdmNode"
        private const val DIRECTIVE = "com.heytap.speech.engine.protocol.directive.Directive"
        private const val HEADER = "com.heytap.speech.engine.protocol.directive.DirectiveHeader"
        private const val PAYLOAD = "com.heytap.speech.engine.protocol.directive.DirectivePayload"
        private const val STREAM_TEXT_CARD =
            "com.heytap.speech.engine.protocol.directive.myai.StreamTextCard"

        fun resolve(classLoader: ClassLoader): QuroBreenoTargets {
            val bean = QuroHookSupport.findClassOrNull(classLoader, BEAN)
            val dataCenter = QuroHookSupport.findClassOrNull(classLoader, DATA_CENTER)
            val insertRecord = QuroHookSupport.findClassOrNull(classLoader, INSERT_RECORD)
            val repository = QuroHookSupport.singletonInstance(classLoader, REPOSITORY)
            val message = QuroHookSupport.findClassOrNull(classLoader, MESSAGE)
            val messageQueue = QuroHookSupport.findClassOrNull(classLoader, MESSAGE_QUEUE)
            val dmParameter = QuroHookSupport.findClassOrNull(classLoader, DM_PARAMETER)
            val cdmNode = QuroHookSupport.findClassOrNull(classLoader, CDM_NODE)
            val directive = QuroHookSupport.findClassOrNull(classLoader, DIRECTIVE)
            val header = QuroHookSupport.findClassOrNull(classLoader, HEADER)
            val payload = QuroHookSupport.findClassOrNull(classLoader, PAYLOAD)
            val streamTextCard = QuroHookSupport.findClassOrNull(classLoader, STREAM_TEXT_CARD)
            val engineClass = QuroHookSupport.findClassOrNull(classLoader, ENGINE)

            val engine = engineClass?.let { QuroHookSupport.singletonInstance(classLoader, ENGINE) }
            val agent = engine?.let { readAgent(it) }

            return QuroBreenoTargets(
                beanClass = bean,
                chatDispatch = resolveChatDispatch(dataCenter, bean),
                messageClass = message,
                outboundSend = resolveOutbound(messageQueue, message),
                dmParameterClass = dmParameter,
                cdmTextRequest = resolveCdmTextRequest(cdmNode, dmParameter),
                directiveClass = directive,
                headerClass = header,
                payloadClass = payload,
                streamTextCardClass = streamTextCard,
                engineClass = engineClass,
                engineInstance = engine,
                initialAgent = agent,
                classLoader = classLoader,
                // 关键：通过 getAgent() 的**返回类型**解析下发放方法，不依赖实例已创建。
                dispatchCache = resolveDispatchDirectives(agentClassOf(engineClass), agent),
                insertRecordClass = insertRecord,
                repository = repository,
                insertHistory = resolveInsertHistory(repository, insertRecord),
            )
        }

        private fun readAgent(engine: Any): Any? =
            QuroHookSupport.invokeNoArgs(engine, "getMAgent")
                ?: QuroHookSupport.invokeNoArgs(engine, "getAgent")
                ?: QuroHookSupport.getFieldValue(engine, "mAgent")

        /** `HeytapSpeechEngine.getAgent()/getMAgent()` 的返回类型；取不到再退回实参类型。 */
        private fun agentClassOf(engineClass: Class<*>?): Class<*>? {
            if (engineClass == null) return null
            val getter = QuroHookSupport.findDeclaredMethods(engineClass) { m ->
                m.parameterCount == 0 &&
                    (m.name == "getAgent" || m.name == "getMAgent") &&
                    m.returnType != Void.TYPE
            }.firstOrNull() ?: return null
            return getter.returnType.takeIf { it != Any::class.java }
        }

        private fun resolveChatDispatch(dataCenter: Class<*>?, bean: Class<*>?): Method? {
            if (dataCenter == null || bean == null) return null
            // 优先「单参 bean」的实例方法（addChatBean）；没有就退到「bean + boolean」同步版本。
            QuroHookSupport.findDeclaredMethods(dataCenter) { m ->
                !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.returnType.name == "void" &&
                    m.parameterCount == 1 && m.parameterTypes[0] == bean
            }.singleOrNull()?.let { return it }
            return QuroHookSupport.findDeclaredMethods(dataCenter) { m ->
                !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.returnType.name == "void" &&
                    m.parameterCount == 2 && m.parameterTypes[0] == bean &&
                    m.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }.singleOrNull()
        }

        private fun resolveOutbound(messageQueue: Class<*>?, message: Class<*>?): Method? {
            if (messageQueue == null || message == null) return null
            // 精确签名：void(Message, boolean, Integer, boolean)
            QuroHookSupport.findDeclaredMethods(messageQueue) { m ->
                !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.returnType.name == "void" &&
                    m.parameterCount == 4 &&
                    m.parameterTypes[0] == message &&
                    m.parameterTypes[3] == Boolean::class.javaPrimitiveType
            }.singleOrNull()?.let { return it }
            return QuroHookSupport.findDeclaredMethods(messageQueue) { m ->
                !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.returnType.name == "void" &&
                    m.parameterCount >= 1 && m.parameterTypes[0] == message
            }.singleOrNull()
        }

        private fun resolveCdmTextRequest(cdmNode: Class<*>?, dmParameter: Class<*>?): Method? {
            if (cdmNode == null || dmParameter == null) return null
            return QuroHookSupport.findDeclaredMethods(cdmNode) { m ->
                !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.returnType.name == "void" &&
                    m.parameterCount == 1 && m.parameterTypes[0] == dmParameter
            }.singleOrNull()
        }

        private fun resolveDispatchDirectives(agentClass: Class<*>?, agent: Any?): Method? {
            val clazz = agentClass ?: agent?.javaClass ?: return null
            // agent 侧下发方法签名稳定：void(List, String)，方法名不稳定。
            QuroHookSupport.findDeclaredMethods(clazz) { m ->
                !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.returnType.name == "void" &&
                    m.parameterCount == 2 &&
                    m.parameterTypes[0] == java.util.List::class.java &&
                    m.parameterTypes[1] == String::class.java
            }.singleOrNull()?.let { return it }
            // 个别版本把 String 换成 Object。
            return QuroHookSupport.findDeclaredMethods(clazz) { m ->
                !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.returnType.name == "void" &&
                    m.parameterCount == 2 &&
                    m.parameterTypes[0] == java.util.List::class.java
            }.singleOrNull()
        }

        private fun resolveInsertHistory(repository: Any?, insertRecord: Class<*>?): Method? {
            if (repository == null || insertRecord == null) return null
            return QuroHookSupport.findDeclaredMethods(repository.javaClass) { m ->
                !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.returnType.name == "void" &&
                    m.parameterCount == 4 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == String::class.java &&
                    m.parameterTypes[2] == insertRecord &&
                    m.parameterTypes[3].name == "kotlin.jvm.functions.Function1"
            }.singleOrNull()
        }
    }
}

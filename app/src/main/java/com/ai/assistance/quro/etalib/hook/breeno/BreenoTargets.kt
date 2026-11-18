package com.ai.assistance.quro.etalib.hook.breeno

import com.ai.assistance.quro.etalib.core.DexKitTargets
import com.ai.assistance.quro.etalib.core.HookSupport
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 安装时完成定位；请求和流式渲染只调用已校验的反射成员，不再扫描 DEX。 */
internal class BreenoTargets private constructor(
    val outboundMessage: Method?,
    val inboundMessage: Method?,
    val cdmTextRequest: Method?,
    val chatDispatch: Method?,
    val currentRoomId: Method?,
    val currentAgentName: Method?,
    val fastModeEnabled: Method?,
    val historyList: Method?,
    val insertHistory: Method?,
    val dispatchDirectives: Method?,
    val serializeHistory: Method?,
) {
    val missingBridgeMethods: List<String> = listOf(
        "current-room" to currentRoomId,
        "current-agent" to currentAgentName,
        "history-list" to historyList,
        "history-insert" to insertHistory,
        "directive-dispatch" to dispatchDirectives,
        "history-serialize" to serializeHistory,
    ).filter { it.second == null }.map { it.first }

    companion object {
        const val DATA_CENTER = "com.heytap.speechassist.aichat.AIChatDataCenter"
        const val ROOM_MANAGER = "com.heytap.speechassist.aichat.AIChatRoomIdManager"
        const val FAST_MODE_MANAGER =
            "com.heytap.speechassist.aichathome.chat.ui.tip.AiChatFastModeStateManager"
        private const val MESSAGE_QUEUE = "com.heytap.speech.engine.connect.core.manager.MessageQueueManager"
        private const val MESSAGE = "com.heytap.speech.engine.protocol.event.Message"
        private const val DM_PARAMETER = "com.heytap.speech.engine.nodes.DmParameter"
        private const val VIEW_BEAN = "com.heytap.speechassist.aichat.bean.AIChatViewBean"
        const val REPOSITORY = "com.heytap.speechassist.aichat.repository.AIChatRepository"
        const val INSERT_RECORD = "com.heytap.speechassist.aichat.repository.api.InsertRecord"
        const val ENGINE = "com.heytap.speech.engine.HeytapSpeechEngine"
        private const val STRING = "java.lang.String"
        private const val VOID = "void"

        fun resolve(classLoader: ClassLoader, targets: DexKitTargets): BreenoTargets {
            val outbound = structuralMethod(
                classLoader, MESSAGE_QUEUE, VOID, MESSAGE, "boolean", "java.lang.Integer", "boolean",
            )
            val inbound = targets.findMethod(
                key = "breeno.inbound-message.v1",
                validate = { it.matches(VOID, STRING, STRING) && !Modifier.isStatic(it.modifiers) },
            ) {
                findMethod {
                    searchPackages("com.heytap.speech.engine.connect.core.manager")
                    matcher {
                        paramTypes(STRING, STRING)
                        returnType = VOID
                        usingStrings("messageContent", "processMessage , onDirectiveFilter , return.")
                    }
                }
            }
            val cdm = targets.findMethod(
                key = "breeno.cdm-text-request.v1",
                validate = { it.matches(VOID, DM_PARAMETER) && !Modifier.isStatic(it.modifiers) },
            ) {
                findMethod {
                    searchPackages("com.heytap.speech.engine.nodes")
                    matcher {
                        paramTypes(DM_PARAMETER)
                        returnType = VOID
                        usingStrings("CdmNode", "voiceStart , cdmStartEntity = ")
                    }
                }
            }
            val chatDispatch = targets.findMethod(
                key = "breeno.chat-dispatch.v1",
                validate = { it.declaringClass.name == DATA_CENTER && it.matches(VOID, VIEW_BEAN) },
            ) {
                findMethod {
                    matcher {
                        declaredClass = DATA_CENTER
                        paramTypes(VIEW_BEAN)
                        returnType = VOID
                        usingStrings("bean")
                        addInvoke {
                            declaredClass = DATA_CENTER
                            paramTypes(VIEW_BEAN, "boolean")
                            returnType = VOID
                            modifiers = Modifier.SYNCHRONIZED
                        }
                    }
                }
            }
            val currentRoom = targets.findMethod(
                key = "breeno.current-room.v1",
                validate = { it.declaringClass.name == ROOM_MANAGER && it.matches(STRING) },
            ) {
                findMethod {
                    matcher {
                        declaredClass = ROOM_MANAGER
                        paramTypes(STRING, STRING)
                        returnType = VOID
                        usingStrings("updateCurrentLandingPage : ")
                    }
                }.singleOrNull()?.invokes.orEmpty().filter {
                    it.className == ROOM_MANAGER && it.matches(STRING)
                }
            }
            val currentAgent = targets.findMethod(
                key = "breeno.current-agent.v1",
                validate = { it.declaringClass.name == ROOM_MANAGER && it.matches(STRING) },
            ) {
                // 当前房间与 Agent 都是字段 getter；房间类型 getter 还会调用状态查询，不能混用。
                if (currentRoom == null) return@findMethod emptyList()
                findMethod {
                    matcher {
                        declaredClass = ROOM_MANAGER
                        paramTypes("boolean", "boolean", STRING)
                        returnType = "boolean"
                        usingStrings("changeToDefaultRoom in default: ")
                    }
                }.singleOrNull()?.invokes.orEmpty().filter {
                    it.className == ROOM_MANAGER && it.matches(STRING) &&
                        it.methodName != currentRoom.name && it.invokes.isEmpty()
                }
            }
            val fastMode = targets.findMethod(
                key = "breeno.fast-mode.v1",
                validate = { it.declaringClass.name == FAST_MODE_MANAGER && it.matches("boolean") },
            ) {
                findMethod {
                    matcher {
                        declaredClass = FAST_MODE_MANAGER
                        paramTypes()
                        returnType = "boolean"
                        usingStrings("sp_key_smart_chat_enable_toast_count")
                    }
                }.singleOrNull()?.invokes.orEmpty().filter {
                    it.className == FAST_MODE_MANAGER && it.matches("boolean")
                }
            }
            val historyList = structuralMethod(
                classLoader, DATA_CENTER, "java.util.LinkedList", STRING, "java.lang.Boolean",
            )
            val insertHistory = structuralMethod(
                classLoader, REPOSITORY, VOID, STRING, STRING, INSERT_RECORD, "kotlin.jvm.functions.Function1",
            )
            val engine = HookSupport.findClassOrNull(classLoader, ENGINE)
            val agentClass = engine?.let {
                HookSupport.findMethod(it, "getAgent")?.returnType
            }
            val dispatchDirectives = uniqueBreenoInstanceMethod(
                agentClass, VOID, "java.util.List", STRING,
            )
            val serializeHistory = targets.findMethod(
                key = "breeno.history-serialize.v1",
                validate = { it.matches(STRING, "java.lang.Object") && Modifier.isStatic(it.modifiers) },
            ) {
                val serializer = findMethod {
                    searchPackages("com.heytap.speechassist.utils")
                    matcher {
                        modifiers = Modifier.STATIC
                        paramTypes("java.lang.Object")
                        returnType = STRING
                        usingStrings("toJsonStr exception: ")
                    }
                }.singleOrNull() ?: return@findMethod emptyList()
                serializer.invokes.filter { candidate ->
                    candidate.className == serializer.className && candidate.matches(STRING, "java.lang.Object") &&
                        candidate.invokes.any {
                            it.descriptor == "Lcom/fasterxml/jackson/databind/ObjectMapper;->writeValueAsString(Ljava/lang/Object;)Ljava/lang/String;"
                        }
                }
            }
            return BreenoTargets(
                outbound, inbound, cdm, chatDispatch, currentRoom, currentAgent,
                fastMode, historyList, insertHistory, dispatchDirectives, serializeHistory,
            )
        }

        private fun structuralMethod(
            classLoader: ClassLoader,
            className: String,
            returnType: String,
            vararg parameterTypes: String,
        ): Method? = uniqueBreenoInstanceMethod(
            HookSupport.findClassOrNull(classLoader, className), returnType, *parameterTypes,
        )

        private fun MethodData.matches(returnType: String, vararg parameterTypes: String): Boolean =
            returnTypeName == returnType && paramTypeNames == parameterTypes.toList()
    }
}

internal fun uniqueBreenoInstanceMethod(
    clazz: Class<*>?,
    returnType: String,
    vararg parameterTypes: String,
): Method? = clazz?.let { target ->
    HookSupport.findDeclaredMethods(target, makeAccessible = true) {
        !Modifier.isStatic(it.modifiers) && !it.isBridge && !it.isSynthetic &&
            it.matches(returnType, *parameterTypes)
    }.singleOrNull()
}

private fun Method.matches(returnType: String, vararg parameterTypes: String): Boolean =
    this.returnType.name == returnType && this.parameterTypes.map { it.name } == parameterTypes.toList()

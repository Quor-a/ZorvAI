package com.ai.assistance.quro.etalib.hook.xiaoai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ai.assistance.quro.etalib.agent.runtime.AgentEvent
import com.ai.assistance.quro.R
import com.ai.assistance.quro.etalib.core.HookSupport
import com.ai.assistance.quro.etalib.core.ModuleLogger
import com.ai.assistance.quro.etalib.core.safeLogType
import com.ai.assistance.quro.etalib.hook.EtaInjectedStrings
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class XiaoAiStreamRenderer(
    private val context: Context,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val dialogId: String,
    private val floatManagerProvider: () -> Any?,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val card = AtomicReference<Any?>()
    private val cancelled = AtomicBoolean(false)
    private val streamedText = StringBuilder()
    private val pendingText = AtomicReference<String?>(null)
    private val flushPosted = AtomicBoolean(false)
    private val cardRetryCount = AtomicInteger()

    /** 思考过程摘录（滚动保留最近一段，避免卡片被整段推理撑爆）。 */
    private val thinkingExcerpt = StringBuilder()
    /** 工具调用轨迹行：`Using xxx` / 完成结果摘要，与主对话框执行轨迹同源。 */
    private val processLines = StringBuilder()
    private val activeTools = java.util.LinkedHashMap<String, String>()

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ModelRetryScheduled -> render(event.displayMessage)

            is AgentEvent.RunStarted,
            is AgentEvent.ProviderRequestStarted,
            is AgentEvent.ProviderResponseStarted -> {
                if (streamedText.isEmpty() && processLines.isEmpty() && thinkingExcerpt.isEmpty()) {
                    render(text(R.string.injected_reasoning, "Eta is reasoning…"))
                }
            }

            is AgentEvent.AssistantBlockStart -> {
                if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                    synchronized(streamedText) { streamedText.setLength(0) }
                }
            }

            is AgentEvent.AssistantBlockDelta -> when (event.kind) {
                AgentEvent.AssistantBlockKind.TEXT -> {
                    val body = synchronized(streamedText) {
                        streamedText.append(event.delta).toString()
                    }
                    render(compose(body))
                }
                AgentEvent.AssistantBlockKind.THINKING -> {
                    appendThinking(event.delta)
                    render(compose(synchronized(streamedText) { streamedText.toString() }))
                }
                AgentEvent.AssistantBlockKind.TOOL_CALL -> {
                    appendProcess(event.delta)
                    render(compose(synchronized(streamedText) { streamedText.toString() }))
                }
            }

            is AgentEvent.ToolStarted -> {
                val label = text(R.string.injected_using_tool, "Using %1\$s", event.name)
                activeTools[event.toolCallId.ifBlank { event.name }] = label
                appendProcess(label)
                if (streamedText.isEmpty()) {
                    render(compose(""))
                }
            }

            is AgentEvent.ToolFinished -> {
                val key = event.toolCallId.ifBlank { event.name }
                val label = activeTools.remove(key)
                    ?: text(R.string.injected_using_tool, "Using %1\$s", event.name)
                val summary = event.resultSummary.trim().take(MAX_TOOL_SUMMARY_CHARS)
                val line = if (event.success == false && summary.isNotBlank()) {
                    "$label · $summary"
                } else label
                replaceLastProcess(label, line)
                if (streamedText.isEmpty()) {
                    render(compose(""))
                }
            }

            else -> Unit
        }
    }

    /** 把思考摘录 + 工具轨迹 + 正文合成一张卡片；过程区为空时原样返回正文，行为与 Eta 一致。 */
    private fun compose(body: String): String {
        val header = buildString {
            if (thinkingExcerpt.isNotEmpty()) {
                append(text(R.string.injected_reasoning_label, "Reasoning"))
                append("：")
                append(thinkingExcerpt.toString().trim())
                appendLine()
            }
            if (processLines.isNotEmpty()) {
                append(processLines.toString().trimEnd())
                appendLine()
            }
        }.trimEnd()
        if (header.isEmpty()) return body
        if (body.isEmpty()) return header
        return "$header\n\n$body"
    }

    private fun appendThinking(delta: String) {
        if (delta.isBlank()) return
        thinkingExcerpt.append(delta)
        if (thinkingExcerpt.length > MAX_THINKING_CHARS) {
            thinkingExcerpt.delete(0, thinkingExcerpt.length - MAX_THINKING_CHARS)
        }
    }

    private fun appendProcess(line: String) {
        if (line.isBlank()) return
        val lines = processLines.lines().filter { it.isNotBlank() }.toMutableList()
        lines.add(line.replace('\n', ' ').take(MAX_TOOL_LINE_CHARS))
        while (lines.size > MAX_PROCESS_LINES) lines.removeAt(0)
        processLines.setLength(0)
        lines.forEach { processLines.append(it).appendLine() }
    }

    private fun replaceLastProcess(oldLine: String, newLine: String) {
        val lines = processLines.lines().filter { it.isNotBlank() }.toMutableList()
        val index = lines.indexOfLast { it == oldLine || it.startsWith(oldLine) }
        if (index < 0) {
            appendProcess(newLine)
            return
        }
        lines[index] = newLine.replace('\n', ' ').take(MAX_TOOL_LINE_CHARS)
        processLines.setLength(0)
        lines.forEach { processLines.append(it).appendLine() }
    }

    fun complete(content: String) {
        if (cancelled.get()) return
        val finalText = content.trim().ifBlank {
            text(R.string.injected_completed, "Eta completed this task")
        }
        // 卡片保留思考摘录与工具轨迹（与 ZorvAI 对话框一致），朗读只读最终答复。
        render(compose(finalText), immediate = true)
        mainHandler.post {
            if (!cancelled.get()) speak(finalText)
        }
    }

    fun fail() {
        if (cancelled.get()) return
        render(
            text(
                R.string.injected_failed,
                "Eta could not complete the task. Try again later",
            ),
            immediate = true,
        )
    }

    fun cancel(loader: ClassLoader = classLoader) {
        if (!cancelled.compareAndSet(false, true)) return
        pendingText.set(null)
        mainHandler.post {
            runCatching {
                val player = ttsPlayer(loader) ?: return@runCatching
                HookSupport.findMethod(player.javaClass, "stopPlay")?.invoke(player)
            }
        }
    }

    private fun render(text: String, immediate: Boolean = false) {
        if (cancelled.get() || text.isBlank()) return
        pendingText.set(text)
        if (immediate) {
            mainHandler.post { flush() }
            return
        }
        if (flushPosted.compareAndSet(false, true)) {
            mainHandler.postDelayed(::flush, STREAM_FLUSH_DELAY_MILLIS)
        }
    }

    private fun text(
        resourceId: Int,
        englishFallback: String,
        vararg formatArgs: Any,
    ): String = EtaInjectedStrings.get(context, resourceId, englishFallback, *formatArgs)

    private fun flush() {
        flushPosted.set(false)
        if (cancelled.get()) return
        val text = pendingText.getAndSet(null) ?: return
        val targetCard = card.get() ?: createCard(text)
        if (targetCard == null) {
            pendingText.compareAndSet(null, text)
            if (cardRetryCount.incrementAndGet() <= MAX_CARD_RETRIES) {
                mainHandler.postDelayed(::flush, CARD_RETRY_DELAY_MILLIS)
            }
            return
        }
        cardRetryCount.set(0)
        if (card.get() === targetCard) {
            invokeCompatible(targetCard, "updateCardText", text)
        }
    }

    private fun createCard(initialText: String): Any? {
        if (cancelled.get()) return null
        return try {
            val cardClass = Class.forName(
                XiaoAiHooks.FLOW_TOAST_CARD_CLASS,
                false,
                classLoader,
            )
            val created = cardClass.getConstructor(String::class.java).newInstance(initialText)
            invokeCompatible(created, "setDialogId", dialogId)
            val floatManager = floatManagerProvider()
            if (floatManager == null) {
                logger.warnThrottled("xiaoai_card_sink_unavailable") {
                    "超级小爱结果卡片管理器暂不可用"
                }
                return null
            }
            invokeCompatible(floatManager, "addCard", created)
            card.compareAndSet(null, created)
            card.get()
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_card_create_failed") {
                "超级小爱结果卡创建失败: type=${exception.safeLogType()}"
            }
            null
        }
    }

    private fun speak(text: String) {
        try {
            val player = ttsPlayer(classLoader) ?: return
            HookSupport.findMethod(player.javaClass, "speakTts", String::class.java)
                ?.invoke(player, text)
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_tts_failed") {
                "超级小爱 Eta 结果朗读失败: type=${exception.safeLogType()}"
            }
        }
    }

    private fun ttsPlayer(loader: ClassLoader): Any? {
        val playerClass = Class.forName(XiaoAiHooks.TTS_PLAYER_CLASS, false, loader)
        val singletonField = playerClass.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) && field.type == playerClass
        } ?: return null
        singletonField.isAccessible = true
        return singletonField.get(null)
    }

    private fun invokeCompatible(target: Any, methodName: String, vararg args: Any?): Any? {
        val method = findCompatibleMethod(target.javaClass, methodName, args) ?: return null
        return method.invoke(target, *args)
    }

    private fun findCompatibleMethod(
        clazz: Class<*>,
        methodName: String,
        args: Array<out Any?>,
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val method = HookSupport.findDeclaredMethods(current, makeAccessible = true) { candidate ->
                candidate.name == methodName &&
                    candidate.parameterTypes.size == args.size &&
                    candidate.parameterTypes.zip(args).all { (type, arg) ->
                        arg == null || type.wrapPrimitive().isAssignableFrom(arg.javaClass)
                    }
            }.firstOrNull()
            if (method != null) return method
            current = current.superclass
        }
        return null
    }

    private fun Class<*>.wrapPrimitive(): Class<*> =
        when (this) {
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Float::class.javaPrimitiveType -> Float::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            else -> this
        }

    private companion object {
        const val STREAM_FLUSH_DELAY_MILLIS = 80L
        const val CARD_RETRY_DELAY_MILLIS = 250L
        const val MAX_CARD_RETRIES = 6
        const val MAX_THINKING_CHARS = 420
        const val MAX_PROCESS_LINES = 8
        const val MAX_TOOL_LINE_CHARS = 160
        const val MAX_TOOL_SUMMARY_CHARS = 100
    }
}

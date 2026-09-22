package com.ai.assistance.quro.genui.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ai.assistance.quro.genui.sdk.components.BuiltinComponents
import com.ai.assistance.quro.genui.sdk.dsl.DslParser
import com.ai.assistance.quro.genui.sdk.dsl.StreamingParser
import com.ai.assistance.quro.genui.sdk.dsl.UISpec
import com.ai.assistance.quro.genui.sdk.interaction.ActionHost
import com.ai.assistance.quro.genui.sdk.render.ComponentRegistry
import com.ai.assistance.quro.genui.sdk.render.GenUIRenderer
import com.ai.assistance.quro.genui.sdk.style.FontProvider
import com.ai.assistance.quro.genui.sdk.style.GenUITheme

/**
 * GenUI SDK 顶层入口对象
 *
 * 提供便捷的解析、编码、渲染方法，是 SDK 的主要对外 API。
 *
 * 用法示例：
 * ```
 * // 解析 GenUI DSL JSON
 * val spec = GenUI.parse(jsonString)
 *
 * // 安全解析（失败返回空 spec）
 * val spec = GenUI.safeParse(jsonString)
 *
 * // 编码为 JSON
 * val json = GenUI.encode(spec)
 *
 * // 在 Compose 中渲染
 * GenUI.Screen(spec = spec, host = actionHost)
 * ```
 */
object GenUI {

    /** 字体提供者 */
    val fonts: FontProvider = FontProvider

    /** 内置组件类型集合 */
    val builtinTypes: Set<String> = BuiltinComponents.types

    /**
     * 解析 GenUI DSL JSON 为 UISpec 对象
     *
     * @param json GenUI DSL JSON 字符串
     * @return 解析后的 UISpec 对象
     * @throws Exception 如果 JSON 格式无效
     */
    fun parse(json: String): UISpec {
        return DslParser.parse(json)
    }

    /**
     * 安全解析 GenUI DSL JSON
     * 解析失败时返回一个降级的错误 UI，不会抛出异常
     *
     * @param json GenUI DSL JSON 字符串
     * @return 解析后的 UISpec 对象，或降级错误 UI
     */
    fun safeParse(json: String): UISpec {
        return DslParser.safeParse(json)
    }

    /**
     * 尝试流式解析不完整的 JSON
     *
     * 用于 AI 流式输出场景，JSON 可能尚未完整。
     * 通过智能补全和部分解析技术，尽可能提取已生成的 UI 结构。
     *
     * 解析策略：
     * 1. 首先尝试完整解析
     * 2. 失败则智能补全后解析（闭合括号、引号等）
     * 3. 再失败则截断到最后一个完整结构后解析
     *
     * @param json 可能不完整的 GenUI DSL JSON 字符串
     * @return 解析成功返回 UISpec，完全无法解析返回 null
     */
    fun tryParsePartial(json: String): UISpec? {
        return StreamingParser.tryParsePartial(json)
    }

    /**
     * 流式安全解析
     *
     * 与 tryParsePartial 类似，但解析失败时返回一个加载中的降级 UI，
     * 而不是 null。适用于流式渲染时始终显示有意义的内容。
     *
     * @param json 可能不完整的 GenUI DSL JSON 字符串
     * @param loadingMessage 加载中显示的提示文字
     * @return 解析后的 UISpec，或加载中 UI
     */
    fun safeParseStreaming(json: String, loadingMessage: String = "正在生成界面..."): UISpec {
        return tryParsePartial(json) ?: StreamingParser.createLoadingUI(loadingMessage)
    }

    /**
     * 将 UISpec 编码为 JSON 字符串
     *
     * @param spec UISpec 对象
     * @return JSON 字符串
     */
    fun encode(spec: UISpec): String {
        return DslParser.encode(spec)
    }

    /**
     * 渲染 GenUI 界面的顶层 Composable 函数
     *
     * @param spec UI 规范描述
     * @param modifier 外层 Modifier
     * @param host Action 宿主，用于处理交互事件
     * @param theme GenUI 主题（默认跟随系统暗色：此前恒为亮色，暗色模式下生成界面会是刺眼白底）
     * @param registry 组件注册表
     */
    @Composable
    fun Screen(
        spec: UISpec,
        modifier: Modifier = Modifier,
        host: ActionHost,
        theme: GenUITheme = defaultTheme(isDark = androidx.compose.foundation.isSystemInDarkTheme()),
        registry: ComponentRegistry = BuiltinComponents.sharedRegistry()
    ) {
        GenUIRenderer(
            spec = spec,
            modifier = modifier,
            host = host,
            theme = theme,
            registry = registry
        )
    }

    /**
     * 获取默认主题
     *
     * @param isDark 是否为暗色主题
     * @return GenUITheme 实例
     */
    fun defaultTheme(isDark: Boolean): GenUITheme {
        return GenUITheme.default(isDark)
    }
}

package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * GenUI DSL JSON 解析器
 *
 * 使用显式 SerializersModule 注册所有 GenUIAction 子类的多态序列化，
 * 确保在深层嵌套路径下（如 events['onTap'].actions[0]）也能正确反序列化。
 */
@OptIn(ExperimentalSerializationApi::class)
object DslParser {

    /**
     * 显式的多态序列化模块
     *
     * 虽然 GenUIAction 是 sealed class，但在某些深层嵌套场景下
     * （例如 List<GenUIAction> 嵌套在 Map 内部的深层路径），
     * 自动多态发现可能失效。通过显式注册所有子类可以确保稳定工作。
     */
    private val genUISerializersModule: SerializersModule = SerializersModule {
        polymorphic(GenUIAction::class) {
            // 导航类
            subclass(GenUIAction.Navigate::class)
            subclass(GenUIAction.GoBack::class)
            subclass(GenUIAction.OpenUrl::class)

            // 状态类
            subclass(GenUIAction.UpdateState::class)
            subclass(GenUIAction.SetState::class)
            subclass(GenUIAction.ToggleState::class)

            // 对话框类
            subclass(GenUIAction.ShowDialog::class)
            subclass(GenUIAction.DismissDialog::class)

            // 反馈类
            subclass(GenUIAction.Toast::class)
            subclass(GenUIAction.Snackbar::class)
            subclass(GenUIAction.Haptic::class)
            subclass(GenUIAction.Vibrate::class)

            // 事件类
            subclass(GenUIAction.Emit::class)
            subclass(GenUIAction.Log::class)

            // API 类
            subclass(GenUIAction.CallApi::class)

            // 剪贴板
            subclass(GenUIAction.CopyToClipboard::class)

            // 自定义
            subclass(GenUIAction.Custom::class)

            // 组件操作类
            subclass(GenUIAction.RegisterComponent::class)
            subclass(GenUIAction.ScrollTo::class)

            // 分享类
            subclass(GenUIAction.Share::class)

            // 页面控制类
            subclass(GenUIAction.Refresh::class)
            subclass(GenUIAction.LoadMore::class)
        }
    }

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "action"
        isLenient = true
        coerceInputValues = true
        // 显式注册多态序列化模块，确保深层嵌套下也能正确解析
        serializersModule = genUISerializersModule
    }

    fun parse(jsonString: String): UISpec {
        return json.decodeFromString<UISpec>(jsonString)
    }

    fun safeParse(jsonString: String): UISpec {
        return runCatching { parse(jsonString) }.getOrElse { e ->
            val errorText = e.message ?: "未知错误"
            UISpec(
                id = "parse_error",
                title = "解析失败",
                root = UIComponent(
                    type = "text",
                    properties = buildJsonObject {
                        put("text", JsonPrimitive("⚠️ UI 解析失败: $errorText"))
                    },
                    style = UIStyle(
                        padding = EdgeInsets.all(24f),
                        textColor = "#B3261E",
                        textSize = 14f
                    )
                ),
                schemaVersion = "error"
            )
        }
    }

    fun parseComponent(jsonString: String): UIComponent {
        return json.decodeFromString<UIComponent>(jsonString)
    }

    fun parseComponent(obj: JsonObject): UIComponent {
        return json.decodeFromJsonElement(UIComponent.serializer(), obj)
    }

    fun encode(spec: UISpec): String {
        return json.encodeToString(UISpec.serializer(), spec)
    }

    fun encodeComponent(component: UIComponent): String {
        return json.encodeToString(UIComponent.serializer(), component)
    }
}

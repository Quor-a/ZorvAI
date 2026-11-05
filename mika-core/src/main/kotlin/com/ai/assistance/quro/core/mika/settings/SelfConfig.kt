package com.ai.assistance.quro.core.mika.settings

import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Concurrency
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.ok
import com.ai.assistance.quro.core.mika.tool.str
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 模型改 ZorvAI 自己设置的唯一入口(App 实现,核心只认这几个键)。
 *
 * 能改什么由 App 定:**模型与 Key、删除数据一律不给**——前者等于让模型换掉自己的脑子、花用户的钱,
 * 后者不可逆。给的都是可逆的偏好项。
 *
 * 用户没开「允许 ZorvAI 改自己的设置」时这个工具根本不提供,所以模型碰不到它。
 */
interface SelfConfig {
    /** 现在能改哪些项、各自是什么值。 */
    fun options(): List<Option>

    /** 改一项;[value] 必须在 [Option.choices] 里。成功返回 null,否则是一句原因。 */
    fun set(key: String, value: String): String?

    /** 一项设置;[label] 是给人看的名字,[value] 是现在的值,[choices] 是可选的几个值(空的表示自由文本)。 */
    data class Option(val key: String, val label: String, val value: String, val choices: List<String> = emptyList())
}

const val SETTINGS_LIST = "settings_list"
const val SETTINGS_SET = "settings_set"

private fun port(ctx: ToolContext): SelfConfig =
    ctx.config.selfConfig ?: error("settings tools are only offered when the user allows it")

/** 列出 ZorvAI 自己能改的设置:键、给人看的名字、现在的值、可选值。只读。 */
internal object SettingsList : AgentTool {
    override val verb = Verb.SettingsList
    override val label = "查看设置"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe

    override fun available(ctx: ToolContext) = ctx.config.selfConfig != null

    override val def = ToolDef(SETTINGS_LIST, Prompts.tool(SETTINGS_LIST), emptyMap(), emptyList())

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ok {
        putJsonArray("settings") {
            port(ctx).options().forEach { o ->
                addJsonObject {
                    put("key", o.key); put("label", o.label); put("value", o.value)
                    if (o.choices.isNotEmpty()) putJsonArray("choices") { o.choices.forEach { add(it) } }
                }
            }
        }
    }
}

/** 改 ZorvAI 自己的一项设置;键和值都得来自 [SettingsList]。 */
internal object SettingsSet : AgentTool {
    override val verb = Verb.SettingsSet
    override val label = "改设置"
    override val risk = Risk.Write
    /** 不动屏幕:改的是 ZorvAI 自己的设置,和当前任务的屏无关。 */
    override val offScreen = true

    override fun available(ctx: ToolContext) = ctx.config.selfConfig != null

    override val def = ToolDef(SETTINGS_SET, Prompts.tool(SETTINGS_SET), mapOf(
        "key" to str("The setting key from settings_list."),
        "value" to str("The new value, one of that setting's choices."),
    ), listOf("key", "value"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val key = a.str("key") ?: return fail("missing_key", "Give the setting key from settings_list.")
        val value = a.str("value") ?: return fail("missing_value", "Give the new value.")
        val problem = port(ctx).set(key, value)
        if (problem != null) return fail("bad_value", problem)
        return ok {
            put("key", key)
            put("value", port(ctx).options().firstOrNull { it.key == key }?.value ?: value)
        }
    }
}

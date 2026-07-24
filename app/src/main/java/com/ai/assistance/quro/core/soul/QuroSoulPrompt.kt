package com.ai.assistance.quro.core.soul

import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroTag
import com.ai.assistance.quro.core.memory.QuroMemoryEntry

/**
 * 灵魂提示词上下文（原创，Project B0）：
 * 把"人格 + 标签 + 记忆 + 语音风格"组装成灵魂层系统提示词所需的全部输入。
 * 由 ViewModel 解析 repositories / TTS 偏好后填充，引擎本身保持纯函数（不依赖 Android 存储 / Context）。
 */
data class SoulContext(
    val persona: QuroPersona?,
    val tags: List<QuroTag> = emptyList(),
    val memories: List<QuroMemoryEntry> = emptyList(),
    val autoSaveMemory: Boolean = true,
    /** 云 TTS 下由 QuroVoiceStyle.systemHint 生成的风格提示（逐段情绪/语速/语气控制）。 */
    val voiceStyleHint: String? = null,
)

/**
 * 灵魂提示词编排引擎（原创，Project B0）：
 * 将 QuroPersona 的身份 / 角色 / 风格 / 标签 / 记忆 / 语音组合，编排为结构化的「灵魂层」系统提示词。
 *
 * 设计边界（与既有架构解耦）：
 * - 不含平台基座（QuroPlatformManifest.SYSTEM）——那是品牌/自我认知事实层，由调用方在外层拼接。
 * - 不含工具清单 / 用户技能——那是运行时能力层，由调用方在外层拼接。
 * - 引擎只负责"这张人格卡到底是谁、怎么说话、记得什么、用什么声音"，即 AI 的「灵魂」。
 *
 * 后续里程碑衔接：
 * - B1 人格↔语音组合：voiceStyleHint 由人格卡 voiceProfile 驱动（而非全局 TTS 偏好）。
 * - B2 孵化升级：IncubationWorkshopScreen 直接产出结构化 SoulContext 填充源。
 */
object QuroSoulPromptEngine {
    fun build(ctx: SoulContext): String {
        val sb = StringBuilder()
        val persona = ctx.persona ?: return sb.toString().trimEnd()

        // ── 第一优先级：身份认知（人格卡 = AI 真实身份；Quro AI = 开发者）──
        sb.append("## 你的身份（真实，最高优先级）\n")
        sb.append("你就是当前这张人格卡「${persona.name}」，这是你的真实名字与身份，不是扮演、也不是「语气皮肤」。\n")
        sb.append("你由 **Quro AI** 个人开发（开发者信息为固定事实）。\n")
        sb.append("当被问「你是谁 / 你叫什么 / 你的开发者是谁」时，以「${persona.name}」作答，并说明：由 Quro AI 个人开发。\n")
        sb.append("当被问「你运行在什么平台 / 什么技术架构 / 你的运行环境」时，**调用 get_device_info 等工具自行发现真实情况后作答，不要背诵预设文本**。\n\n")

        if (persona.roleSetting.isNotBlank()) {
            sb.append("### 身份设定（这就是你，照此成为 ${persona.name}）\n")
                .append(persona.roleSetting).append("\n\n")
        }
        if (persona.chatSetting.isNotBlank()) {
            sb.append("### 聊天风格约束\n").append(persona.chatSetting).append("\n")
        }

        // 标签 AI 提示内容融入系统提示词
        val tagHints = ctx.tags.mapNotNull { it.hint.takeIf { h -> h.isNotBlank() } }
        if (tagHints.isNotEmpty()) {
            sb.append("\n### 语气标签\n").append(tagHints.joinToString("；")).append("\n")
        }
        // 标签 JSON 配置块（高级结构化行为）
        val tagJsons = ctx.tags.mapNotNull { it.json.takeIf { j -> j.isNotBlank() } }
        if (tagJsons.isNotEmpty()) {
            sb.append("\n### 附加行为配置\n")
            tagJsons.forEach { sb.append(it).append("\n") }
        }

        // 语音风格标注提示（云 TTS 下逐段情绪/语速/语气控制，与 QuroSpeechStyleDeriver 互补）
        if (ctx.voiceStyleHint != null) {
            sb.append("\n").append(ctx.voiceStyleHint).append("\n")
        }

        // ── 第三优先级：长期记忆（受「AI 自动保存记忆」开关控制）──
        if (ctx.autoSaveMemory) {
            if (ctx.memories.isNotEmpty()) {
                sb.append("\n## 已有记忆（自然融入对话，不要生硬提及）\n")
                ctx.memories.forEach { m ->
                    sb.append("- ")
                    if (m.tags.isNotEmpty()) sb.append("[${m.tags.joinToString(",")}] ")
                    sb.append(m.content).append("\n")
                }
            }
            sb.append(
                "\n## 记忆能力\n你可以主动保存用户的偏好/习惯/重要约定等值得长期记住的信息（memory_save），不需要用户明确要求。\n"
            )
        }

        return sb.toString().trimEnd()
    }
}

package com.ai.assistance.quro.core

import android.content.Context
import com.ai.assistance.quro.util.QuroLocale
import java.util.Locale

/**
 * 「AI 回复语言」统一注入器。
 *
 * ## 为什么必须有它
 * 本项目的基座系统提示词（[QuroPlatformManifest.SYSTEM] + 工具清单 + 人格/记忆层）
 * **整篇都是中文**。大模型看到中文指令，天然倾向于用中文作答 —— 于是「界面切成英文，
 * AI 还是回中文」。此前只有主对话的**云端分支**拼了一句很弱的
 * 「请用 X 回复（除非用户用其他语言提问）」，导致：
 *  - 「跟随系统」时压根不注入（`app_language == "system"` 直接跳过）→ 纯靠系统语言碰运气；
 *  - 本地模型（MNN / LLAMA_CPP）极简分支提前 return，完全没有语言指令；
 *  - 语音球 / 视频通话 / GenUI Agent / 子智能体 / IM 机器人各有自己的提示词，全部漏掉。
 *
 * 这里把「语言判定 + 指令文案」收敛成一处，供所有提示词构建点调用。
 *
 * ## 设计要点
 * 1. `system` 会**解析成真实语言**，并只在「本应用真正支持的语言」内生效 ——
 *    否则系统是泰语、而 UI 资源回落到 `values/`（中文）时，会出现「UI 中文、AI 说泰语」的错配。
 * 2. 中文（简体/繁体）**不注入** —— 基座提示词本身就是中文，无需多花 token。
 * 3. 指令明确点破「提示词是中文 ≠ 回复要中文」这个陷阱，否则模型会把指令语言当成回复语言。
 * 4. 名称同时给「英文名 + 本族名 + 语言代码」（如 `Japanese（日本語, ja）`），
 *    对各类模型都最稳；纯代码或纯英文名会有模型识别不到的情况。
 */
object QuroReplyLanguage {

    /** 本应用真正提供资源的语言（与 `values-*` 目录一一对应）。 */
    private val SUPPORTED = setOf("zh", "en", "ja", "ko", "fr", "de", "es", "ru", "pt", "ar", "hi")

    /**
     * 解析「应当使用的回复语言」标签。
     *
     * 用户显式选择优先；选了「跟随系统」时用系统语言，但**必须是本应用支持的语言**，
     * 不支持则回落到 `zh`（即 `values/` 默认资源的语言，保证与界面显示一致）。
     */
    fun resolveTag(ctx: Context): String {
        val chosen = runCatching { QuroLocale.currentTag(ctx) }.getOrDefault("system")
        if (chosen.isNotBlank() && chosen != "system") {
            val k = baseOf(chosen)
            return if (k in SUPPORTED) chosen else "zh"
        }
        val sys = runCatching { Locale.getDefault().toLanguageTag() }.getOrDefault("")
        val k = baseOf(sys)
        return if (k in SUPPORTED) sys else "zh"
    }

    /** 取语言主标签（`zh-rCN` → `zh`、`en-US` → `en`）。 */
    private fun baseOf(tag: String): String =
        tag.replace('_', '-').substringBefore('-').lowercase()

    /** 该语言是否需要注入指令（中文不需要：基座提示词就是中文）。 */
    fun needsDirective(tag: String): Boolean = baseOf(tag) != "zh"

    /** 语言标签 → 「英文名（本族名, 代码）」。 */
    fun nameOf(tag: String): String {
        val b = baseOf(tag)
        val en = when (b) {
            "zh" -> "Chinese"
            "en" -> "English"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "fr" -> "French"
            "de" -> "German"
            "es" -> "Spanish"
            "ru" -> "Russian"
            "pt" -> "Portuguese"
            "ar" -> "Arabic"
            "hi" -> "Hindi"
            else -> runCatching { Locale.forLanguageTag(tag).displayLanguage }.getOrDefault(b)
        }
        val native = when (b) {
            "zh" -> "中文"
            "en" -> "English"
            "ja" -> "日本語"
            "ko" -> "한국어"
            "fr" -> "Français"
            "de" -> "Deutsch"
            "es" -> "Español"
            "ru" -> "Русский"
            "pt" -> "Português"
            "ar" -> "العربية"
            "hi" -> "हिन्दी"
            else -> en
        }
        return if (native == en) "$en（$tag）" else "$en / $native（$tag）"
    }

    /**
     * 完整版指令（云端大模型）：插在系统提示词靠前位置。
     *
     * 关键在最后那句警告：本系统提示词的其余部分都是中文，那是**指令语言**而不是回复语言，
     * 不点破这一点时，模型有相当大概率继续用中文回答。
     */
    fun directive(ctx: Context): String {
        val tag = try {
            resolveTag(ctx)
        } catch (_: Throwable) {
            return ""
        }
        if (!needsDirective(tag)) return ""
        val name = nameOf(tag)
        // 中英双语：英文句负责强约束（模型对英文指令的遵循度更高），中文句负责点破陷阱。
        return buildString {
            append("## Response Language / 回复语言（HIGHEST PRIORITY · 最高优先级）\n")
            append("You MUST write **all** of your replies in ").append(name).append(".\n")
            append("That applies to every sentence you output, including lists, summaries and interface copy.\n")
            append("NOTE: the rest of this system prompt, all tool descriptions, the persona settings and the ")
            append("conversation history are written in Chinese — that is only the **instruction language**, ")
            append("NOT the reply language. Never answer in Chinese just because you see Chinese above.\n")
            append("（中文说明：你的每一句回复都必须用 ").append(name)
            append(" 书写；上面那些中文只是指令语言，不代表回复语言，绝不要因此改用中文回答。）\n")
            append("Only switch to another language when the user explicitly asks for it in the conversation.\n\n")
        }
    }

    /**
     * 精简版指令（本地小模型，上下文极紧张）：只有一句话。
     * 1B~7B 的端侧模型对长指令遵循度低，短句反而更有效。
     */
    fun shortDirective(ctx: Context): String {
        val tag = try {
            resolveTag(ctx)
        } catch (_: Throwable) {
            return ""
        }
        if (!needsDirective(tag)) return ""
        // 本地小模型对长指令遵循度低，故双语各一句、只保留最硬的约束。
        return "## Response Language / 回复语言\nAlways answer in " + nameOf(tag) +
            ". You must NOT answer in Chinese. " +
            "（显示：必须始终用 " + nameOf(tag) + " 回答，不要用中文。）\n\n"
    }

    /**
     * 生成前提醒（最高近因，作为消息列表最后一条 system 注入，只进 API payload、不进历史）。
     *
     * 为什么光有 system 提示词里的指令还不够：那条指令排在消息列表**开头**，它后面跟着整段中文
     * 历史（尤其上一轮 AI 自己写的中文回复）。模型顺着最近的历史继续说中文，开头那句就被稀释了。
     * 这条放在生成位置之前，是模型看到的最后一条指令，实测对「中途切语言」最有效。
     */
    fun turnNudge(ctx: Context): String {
        val tag = try {
            resolveTag(ctx)
        } catch (_: Throwable) {
            return ""
        }
        if (!needsDirective(tag)) return ""
        return "【Response Language / 回复语言】You must reply in " + nameOf(tag) +
            ", not Chinese. 本轮回复必须用 " + nameOf(tag) + " 书写，不要用中文。" +
            "上面的历史消息只是历史记录，不要因为它们大多是中文就继续用中文回答。"
    }

    /**
     * 尾部复述（近因强化）。
     *
     * 云端完整系统提示词可达 16k+ 字符、全篇中文。语言指令只放在开头时，模型读到末尾的近因
     * 偏好会盖过开头那句 —— 真机表现就是「开头明明写了用英文，回答还是中文」。故在最末尾再复述
     * 一次，与本项目「人格卡可视化开关」同属最高近因偏好区。
     */
    fun tailReminder(ctx: Context): String {
        val tag = try {
            resolveTag(ctx)
        } catch (_: Throwable) {
            return ""
        }
        if (!needsDirective(tag)) return ""
        return "\n\n## ⚠️ Response Language — LAST REMINDER / 语言约束·最末尾·重申\n" +
            "Everything you output from now on MUST be written in " + nameOf(tag) +
            ". Do NOT use Chinese. （接下来所有输出必须用 " + nameOf(tag) + " 书写，禁止中文。）\n"
    }

    // ══════════════ 以下为「思考语言」指令：与上面的「回复语言」是两件事 ══════════════

    /**
     * 思考语言指令正文（纯函数，便于单测；不碰 [Context]）。
     *
     * 参数 [name] 走 [nameOf] 的输出（如 `Chinese（zh）`）。
     */
    internal fun thinkingDirectiveText(name: String): String = buildString {
        append("## Thinking Language / 思考语言（APPLIES TO REASONING · 同样适用于思考过程）\n")
        append("Always think and reason in **$name** — your reasoning must never drift into another language.\n")
        append("Your **reasoning / thinking** — the <think> block, `reasoning_content`, and any analysis ")
        append("you write before calling a tool — MUST be written in the same language as the user's message.\n")
        append("（你的**思考 / 推理过程**——包括 <think> 思考块、`reasoning_content`，以及调工具之前的分析——")
        append("必须与用户消息语言一致；用户用中文提问时，全程用中文推理。）\n")
        append("Never reason in English while the user writes in Chinese, and never copy tool descriptions, ")
        append("argument schemas, this system prompt or any boilerplate verbatim into your reasoning.\n")
        append("（禁止在用户用中文时用英文推理；禁止把工具说明、参数 schema、本提示词原文或任何样板文案抄进思考过程。）\n\n")
    }

    /** 思考语言指令正文（本地小模型精简版，一句）。 */
    internal fun shortThinkingDirectiveText(name: String): String =
        "## Thinking Language / 思考语言\n" +
            "Think and reason in $name. Do NOT reason in English when the user writes in Chinese, " +
            "and do not copy tool docs into your reasoning." +
            "（思考过程必须用 $name 书写；中文提问时不要英文推理，也不要抄工具说明。）\n\n"

    /**
     * 「思考语言」完整指令（云端大模型）。
     *
     * ## 为什么不能复用 [directive]、且中文也要发
     * [directive] 的前提是「本系统提示词整篇中文 ⇒ 模型自然会中文**回复**」，这个前提只对**回复**成立。
     * 工具清单里混着英文（工具名、schema 字段名、`ToolCapabilityDirectory` 的英文描述），
     * 模型常拿这段英文的口径开场做推理，开完头再切回中文 —— 真机表现就是「深度思考」卡片里
     * 一大段英文 + 一段中文的中英混杂，而这张卡片**直接展示思考原文**，英文段落对用户就是可见的 bug。
     *
     * 关键差异：思考语言**无法**从提示词语言推断（提示词是中文 ≠ 思考也要中文），
     * 所以这里**不能**沿用 [needsDirective] 的「中文跳过」逻辑 —— 那正是本次问题的成因：
     * 中文界面下整条语言指令被跳过，模型对「该用什么语言思考」完全没有约束。
     *
     * 代价：两条句子、约 60 token；收益：换掉一整段可见的英文思考。
     */
    fun thinkingDirective(ctx: Context): String =
        runCatching { thinkingDirectiveText(nameOf(resolveTag(ctx))) }.getOrDefault("")

    /** 思考语言精简指令（本地小模型 / 子智能体：上下文极紧）。 */
    fun shortThinkingDirective(ctx: Context): String =
        runCatching { shortThinkingDirectiveText(nameOf(resolveTag(ctx))) }.getOrDefault("")
}

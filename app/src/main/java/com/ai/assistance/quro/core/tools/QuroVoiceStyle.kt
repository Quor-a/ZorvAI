package com.ai.assistance.quro.core.tools

import android.content.Context

/**
 * 语音风格标注解析（小米 MiMo TTS 风格标签）。
 *
 * 核心设计（纠正旧实现「所有开启标签强制一次性前缀」的错误）：
 * 风格标签是一个「池子」，LLM 在每段文本前自行决定用哪些标签（决定权在 LLM），
 * 同一段可叠加多个，未标注段落用默认音色自然朗读。
 *
 * 标注格式（每行开头，半角括号）：
 *   (开心) 今天天气真好呀！            ← 单个标签
 *   (温柔) 出门记得带伞。
 *   (开心 俏皮) 一起去玩吧！          ← 单括号多标签（空格分隔，等价写法一）
 *   (唱歌) 哗啦啦啦啦啦下雨了～
 *   (唱歌)(东北话) 来一段！           ← 多括号连写（等价写法二，两种都支持）
 *
 * 说明：同一行开头的多个标签可用「单括号空格分隔」或「多括号连写」任意一种；
 * 解析时会全部提取（如 (唱歌)(东北话) 文本 → 标签 [唱歌, 东北话] + 文本「文本」）。
 */
object QuroVoiceStyle {
    data class Segment(val text: String, val tags: List<String>)

    /**
     * 匹配【行首】单个「(标签组)」标记：标签组内部允许空格分隔多个标签。
     * 只锚定行首，用于「逐轮剥掉开头一个括号」的循环，从而正确支持 (a)(b) 多括号连写。
     */
    private val ONE_MARKER_RE = Regex("^\\(\\s*([^()]*?)\\s*\\)\\s*")

    /**
     * 提取一行开头的【连续多个】风格标记（同时支持 `(a)(b)` 多括号连写 与 `(a b)` 单括号多标签 两种写法），
     * 返回 (解析出的标签列表, 剥除所有开头标记后的剩余文本)。
     *
     * 例：
     *   "(开心) 你好"            → (["开心"], "你好")
     *   "(开心 俏皮) 你好"       → (["开心","俏皮"], "你好")
     *   "(唱歌)(东北话) 来一段"  → (["唱歌","东北话"], "来一段")
     *   "(开心)(俏皮 温柔) 嗯"   → (["开心","俏皮","温柔"], "嗯")
     *   无标记的普通行           → ([], 原文本)
     *
     * 容错：未闭合的 "(" 或不成对的括号不会被吞掉，会作为普通文本留在剩余部分。
     */
    private fun extractLeadingMarkers(line: String): Triple<List<String>, String, String?> {
        val tags = mutableListOf<String>()
        var rest = line
        var voiceColor: String? = null
        while (true) {
            val m = ONE_MARKER_RE.find(rest) ?: break
            val tagPart = m.groupValues[1].trim()
            if (tagPart.isNotBlank()) {
                // 语色路由：识别 (语色:xxx) / (语色：xxx)，作为「音色」单独抽取（不混入情绪标签池）
                val lower = tagPart.lowercase()
                if (lower.startsWith("语色")) {
                    val idx = tagPart.indexOfAny(charArrayOf(':', '：'))
                    val v = if (idx >= 0) tagPart.substring(idx + 1).trim() else ""
                    if (v.isNotBlank()) voiceColor = v
                } else {
                    for (tok in tagPart.split(Regex("\\s+"))) {
                        val t = tok.trim().trimEnd('，', '。', '、', ',', '.', '：', ':')
                        if (t.isNotEmpty()) tags.add(t)
                    }
                }
            }
            rest = rest.removeRange(m.range)
        }
        return Triple(tags, rest, voiceColor)
    }

    /**
     * 把 LLM 回复按行切分为带标签片段。
     * @param availableTags 当前激活人格卡允许的标签白名单；仅保留白名单内的标签（容错：忽略自创/错字）。
     */
    fun segment(input: String, availableTags: List<String>): List<Segment> {
        val pool = availableTags.toSet()
        if (pool.isEmpty()) return listOf(Segment(input, emptyList()))
        return input.split("\n").mapNotNull { line ->
            val (rawTags, text, _) = extractLeadingMarkers(line)
            if (text.isBlank() && rawTags.isEmpty()) return@mapNotNull null
            val tags = rawTags.mapNotNull { t -> if (t in pool) t else null }
            Segment(text, tags)
        }
    }

    /** 是否含有有效风格标记（用于判断是否需要逐段合成）。 */
    fun hasMarkers(input: String, availableTags: List<String>): Boolean {
        val pool = availableTags.toSet()
        if (pool.isEmpty()) return false
        return input.split("\n").any { line ->
            val (tags, _, _) = extractLeadingMarkers(line)
            tags.any { it in pool }
        }
    }

    /** 去除每行开头的风格标记，返回纯文本（聊天界面显示 / 复制用）。 */
    fun strip(input: String): String {
        return input.split("\n").joinToString("\n") { line ->
            extractLeadingMarkers(line).second
        }
    }

    /**
     * 把文本按行重建为 MiMo 风格标记格式（保留行首标签），供【流式】合成直接送给 MiMo。
     * 与非流式 [segment] 后用 "(标签) 文本" 重建 assistant content 的逻辑一致，
     * 区别仅是流式一次性送出整段、不逐段切分。仅保留白名单内标签（容错自创/错字）。
     *
     * 例："(唱歌)(东北话) 文本" → "(唱歌 东北话) 文本"（标签保留，供 MiMo 真情感合成）。
     */
    fun toMimoMarkup(input: String, availableTags: List<String>): String {
        val pool = availableTags.toSet()
        if (pool.isEmpty()) return input
        return input.split("\n").joinToString("\n") { line ->
            val (rawTags, text, _) = extractLeadingMarkers(line)
            val tags = rawTags.filter { it in pool }
            if (tags.isEmpty()) text else "(${tags.joinToString(" ")}) $text"
        }
    }

    // NOTE: 语色（多音色）不再以文本标签 (语色:xxx) 形式内嵌解析 —— 该方案在「语色+情绪混合格式」与长文本下
    // 解析脆弱，导致换错声/窜英文/不完整。改为：音色由 speak 工具的 voice 参数显式指定，需要多角色就多次调用
    // speak 工具（每次独立合成，按序串行播放 = 多次合成完成多音色）。情绪标签（(开心) 等）仍按原样留在文本里。
    // [extractLeadingMarkers] 仍会静默剥除 (语色:xxx)，作为「AI 仍残留旧写法」的安全兜底，避免被念成字面。

    /**
     * 多音色（语色）系统提示词片段。
     *
     * 设计修正（关键）：音色【不再】写成文本标签 (语色:xxx) —— 旧方案在「语色+情绪混合格式」与长文本下
     * 解析脆弱，导致换错声 / 窜英文 / 不完整（用户实测复现）。改为【用 speak 工具的 voice 参数】：
     * 需要多角色就多次调用 speak 工具，每次设 voice 为该角色真实音色，系统逐次独立合成、按序串行播放
     * （= 多次合成完成多音色）。情绪标签（(开心) 等）仍按原样留在文本里。
     *
     * @param catalog 由 [QuroCloudTtsCatalog.voiceColorCatalogText] 生成的完整音色清单（每条带 id + 语言）。
     */
    fun systemHintVoiceColor(catalog: String): String {
        return """## 多音色朗读（用 speak 工具的 voice 参数，不要写文本标签）

你的回复会被朗读出来。想让不同【角色 / 旁白】用【不同的声音】，请用「speak 工具的 voice 参数」，而不是在文本里写标签。

⚠️ 最重要：音色【不是文本标签】！
- 不要写 (语色:xxx) 这类标记，它们不会被解析、还会污染正文。
- 情绪标签（如 (开心) (温柔)）仍按原样写在文本里（标签在文本中），但不要写进 voice 参数。

**怎么做多音色（多次调用 speak 工具）：**
- 把整段拆成「每个角色一句/一段」，对每一句【单独调用一次 speak 工具】，并设置 voice 参数为该角色的真实音色名或 id。
- 每次调用独立合成、按调用顺序串行播放，自然形成多音色演绎。
- 例（角色对话）：
    speak("悟空，你又闯祸了！", voice="晓晓（女）")      ← 女声念这句
    speak("师父饶命！俺老孙再不敢了！", voice="苏打（男）") ← 男声念这句
    speak("师徒四人继续向西前行。", voice="白桦（男）")     ← 旁白用另一个声
- voice 参数省略则该句用默认音色。

**可用音色（【必须】从下面清单挑真实名字或 id，一字不差，含其中的全角括号（女）；不要自创）：**
  $catalog

**关于语言：** 每条清单都标了「语言=xxx」。中文角色用「语言=中文」的音色，英文角色用「语言=英文」的音色，别把中文文本配到英文音色上（会洋腔或失效）。

**克制原则（像导演选角）：**
- 只在明显的角色 / 旁白切换处换声；同一角色整段固定同一音色；不加 voice 的句子用默认音色。
- 纯代码 / 表格 / 列表不要调用 speak 朗读。

**记住：多音色靠「多次调用 speak + 每次设 voice」实现，不是靠文本标签。目标：像角色分明、层次清楚的真人演绎。**""".trimIndent()
    }

    /**
     * 系统提示词片段：告知 LLM 如何标注风格（当前选中的云端服务商支持风格标签时注入）。
     * 不绑定任何单一服务商——文案使用通用「云语音合成」措辞，标签来自调用方传入的可用标签池。
     *
     * v224 增强：强调「自动组合情绪标签形成自然人类语音」——LLM 应像人说话一样
     * 自由切换语气，不是机械地给每句话加标记，而是在情绪转折处自然标注。
     */
    fun systemHint(availableTags: List<String>): String {
        val tags = if (availableTags.isEmpty()) "（未配置可用标签）" else availableTags.joinToString(" / ")
        return """## 语音风格标注（重要 — 像人类一样自然说话）
你已接入云端语音合成（TTS），你的回复会被朗读出来。
请用「情绪/风格标记」让语音像真人一样有自然起伏和情感变化：

**核心原则（像人说话）：**
- 人在说话时会自然切换语气——开心时轻快、安慰人时温柔、讲道理时稳重。你也应该这样。
- 不是每句都加标记！只在情绪明显变化的地方标注，其余用默认音色自然朗读。
- 一段话里可以有多个情绪层次：(温柔) 别难过… (开心) 一切都会好起来的！

**标记格式：每行开头半角括号 + 标签**
  (开心) 今天天气真好呀！
  (温柔) 不过出门记得带伞哦。
  (唱歌) 哗啦啦啦啦啦下雨了～
  (开心 俏皮) 我们一起去玩吧！

**可选风格标签池（只能从下列中选，不要自创）：**
  $tags

**规则：**
  - 下列情绪/风格标签在「情绪标签开关」开启时全部可用，你根据内容自动选择最合适的组合（可自由叠加多个）。
  - 同一段可叠加多个标签（如 (开心 俏皮)），表达复杂情感。
  - 不加标记的句子用默认音色自然朗读（大部分句子不需要标记）。
  - 标记只用于语音合成，不会显示给用户，放心使用。
  - 纯代码 / 表格 / 列表段落不要加标记。
  - **目标：让听的人感觉在和一个有温度、有情绪变化的真人对话。**""".trimIndent()
    }

    /**
     * 自然语言情绪提示（用于【非 MiMo】云服务商）。
     *
     * 与 [systemHint] 的区别：小米 MiMo 客户端能逐段解析「(开心)」括号标记做情感合成；
     * 其余服务商不解析该标记，若输出会被念成字面括号文字。因此这里改用【自然语言】方式要求 LLM
     * 通过措辞、语气词、标点自然流露情绪，配合全局「整体语气」指令(styleNL)共同作用。
     * 绝不要输出 (标签) 形式的括号标记。
     */
    fun systemHintNatural(availableTags: List<String>): String {
        val tags = if (availableTags.isEmpty()) "（未配置可用标签）" else availableTags.joinToString(" / ")
        return """## 语音情绪表达（重要 — 像人类一样自然说话）
你已接入云端语音合成（TTS），你的回复会被朗读出来。
请用自然的语言方式让语音有情感和起伏，像一个有温度的真人在说话：

**核心原则（像人说话）：**
- 人在说话时会自然流露情绪——开心时轻快、安慰人时温柔、讲道理时稳重。你也应该通过措辞和语气自然体现。
- 不要使用任何括号标签（如 (开心)）来标注情绪——当前语音引擎不会解析这类标记，写出来会被原样念出。
- 通过自然方式表达情绪：选用贴切的词语、恰当的语气词（呀/哦/呢/啦）、合适的标点（"！""～"），让朗读者自然带出情绪。
- 情绪转折处用措辞变化体现，不要机械地每句都强调。

**可参考的情绪/风格（用自然语言体现，不要写成标签）：**
  $tags

**规则：**
  - 上述情绪/风格作为表达参考，你根据内容自然选择最合适的语气（可自由组合多种情绪层次）。
  - 不加修饰的句子用平实语气自然朗读（大部分句子不需要特别情绪）。
  - 纯代码 / 表格 / 列表段落保持平实、不加情绪修饰。
  - **目标：让听的人感觉在和一个有温度、有情绪变化的真人对话。**""".trimIndent()
    }

    /**
     * 统一构建「LLM 情绪标签」系统提示词（对话框与语音球共用）。
     *
     * 设计（v347 重构 · 全跟随）：情绪标签来源 = 【实际播放服务商】([QuroTtsPrefs.getSource])，
     * 不再让用户单独选情绪来源（旧 [QuroVoiceFeaturePrefs.getEmotionProviderId] 已弃用）。
     *
     * 为什么弃用单独选择：之前「情绪来源」与「播放服务商」是两个互不校验的开关，
     * 用户选了 MiMo 来源、但播放用的是 Edge（默认），标签语言/格式（中文括号 vs 英文 express-as）
     * 对不上 → 被念成字面或无情绪，即用户反馈的「用情绪标签不准确」。改为「来源 = 播放服务商」
     * 后，二者永远一致，标签必然匹配当前发声引擎。
     *
     * 提示词类型按【实际播放服务商种类】分派：
     * - 小米 MiMo：逐段「(情绪)」中文括号标记式 [systemHint]（MiMo 客户端原生解析做真·情感合成）
     * - 其余（本地系统 TTS / 其他云服务商）：自然语言 [systemHintNatural]（绝不输出括号，避免被念成字面；
     *   靠措辞/语气词自然流露；这些源由 QuroCloudTts 在合成时剥离中文括号、不做真情感合成）
     *
     * 已知局限（非本次范围，下个迭代增强）：仅 MiMo 原生逐段解析中文括号做情感合成；若要让 Edge/火山/
     * 讯飞/腾讯/MiniMax 等也做真情感合成，需增强 QuroCloudTts 将中文括号翻译为各服务商 style 参数。
     *
     * 开关关闭时返回 null（不注入任何情绪提示）。
     */
    fun hintForContext(ctx: Context): String? {
        val parts = mutableListOf<String>()
        // 情绪标签提示（独立开关）：来源全跟随实际播放服务商
        if (QuroVoiceFeaturePrefs.getEmotionTagsEnabled(ctx)) {
            val src = QuroTtsPrefs.getSource(ctx)
            val isLocalLike = src == QuroTtsPrefs.SOURCE_LOCAL || src == QuroTtsPrefs.SOURCE_MODEL
            if (isLocalLike) {
                parts.add(systemHintNatural(QuroCloudTtsCatalog.ALL_EMOTION_TAGS))
            } else {
                val effProviderId = if (src == QuroTtsPrefs.SOURCE_MIMO) "mimo"
                else QuroTtsProviderPrefs.getProvider(ctx)
                val effDef = QuroTtsProviders.byId(effProviderId)
                val pool = effDef?.providerTags
                    ?.takeIf { it.isNotEmpty() }
                    ?: QuroTtsProviderPrefs.getSelectedStyleTags(ctx)
                        .takeIf { it.isNotEmpty() }
                    ?: QuroCloudTtsCatalog.ALL_EMOTION_TAGS
                parts.add(if (effDef?.kind == QuroTtsProviderKind.MIMO) systemHint(pool) else systemHintNatural(pool))
            }
        }
        // 语色路由提示（独立开关）：仅在路由开启且播放服务商为云端 / 小米 MiMo 时注入（本地系统 TTS 不换音色）
        if (QuroVoiceFeaturePrefs.getVoiceColorRoutingEnabled(ctx)) {
            val src = QuroTtsPrefs.getSource(ctx)
            val isCloudLike = src == QuroTtsPrefs.SOURCE_CLOUD || src == QuroTtsPrefs.SOURCE_MIMO
            if (isCloudLike) {
                // 语色路由：跟随「实际播放服务商」的真实音色清单（不再写死 MiMo 调色板，避免非 MiMo 服务商被固定死）
                val vpDef = QuroTtsProviders.byId(QuroTtsProviderPrefs.getProvider(ctx)) ?: QuroTtsProviders.byId("edge")!!
                val vpCfg = QuroTtsProviderPrefs.getConfig(ctx, vpDef.id)
                parts.add(systemHintVoiceColor(QuroCloudTtsCatalog.voiceColorCatalogText(vpDef, vpCfg)))
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString("\n\n")
    }
}

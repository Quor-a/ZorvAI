package com.ai.assistance.quro.core.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * 风格/情绪标签词库 + 小米 MiMo 内置目录。
 *
 * - [EMOTION_TAGS] / [INLINE_TAGS] / [ALL_EMOTION_TAGS]：统一的中文情绪/风格词库，
 *   作为「语音风格标签」UI 的通用池（不绑定任何单一服务商）。
 * - [MODELS] / [PRESET_VOICES]：小米 MiMo 的内置模型与预置音色（被 MiMo 服务商定义引用）。
 */
object QuroCloudTtsCatalog {
    data class ModelInfo(
        val id: String,
        val name: String,
        val desc: String,
        val supportsPreset: Boolean,
        val supportsDesign: Boolean,
        val supportsClone: Boolean,
    )

    val MODELS = listOf(
        ModelInfo(
            "mimo-v2.5-tts", "预置精品音色",
            "使用内置精品音色（冰糖/茉莉/苏打/白桦…），支持唱歌模式",
            supportsPreset = true, supportsDesign = false, supportsClone = false,
        ),
        ModelInfo(
            "mimo-v2.5-tts-voicedesign", "文本设计音色",
            "用一段文字描述生成定制音色，无需音频样本",
            supportsPreset = false, supportsDesign = true, supportsClone = false,
        ),
        ModelInfo(
            "mimo-v2.5-tts-voiceclone", "音频复刻音色",
            "上传一段音频样本精准复刻任意声音",
            supportsPreset = false, supportsDesign = false, supportsClone = true,
        ),
    )

    fun modelById(id: String): ModelInfo? = MODELS.firstOrNull { it.id == id }

    data class PresetVoice(val id: String, val name: String, val lang: String, val gender: String)
    val PRESET_VOICES = listOf(
        PresetVoice("mimo_default", "MiMo-默认", "默认", "默认"),
        PresetVoice("冰糖", "冰糖", "中文", "女"),
        PresetVoice("茉莉", "茉莉", "中文", "女"),
        PresetVoice("苏打", "苏打", "中文", "男"),
        PresetVoice("白桦", "白桦", "中文", "男"),
        PresetVoice("Mia", "Mia", "英文", "女"),
        PresetVoice("Chloe", "Chloe", "英文", "女"),
        PresetVoice("Milo", "Milo", "英文", "男"),
        PresetVoice("Dean", "Dean", "英文", "男"),
    )

    /**
     * 段级/句级风格标签（主流服务商通用情绪词）。
     *
     * v184 基于各服务商官方文档完整对齐：
     * - Edge TTS（Microsoft）：31 种 express-as 官方样式
     * - 腾讯云 TTS：16 种 EmotionCategory
     * - 阿里百炼 CosyVoice3：7 情绪 + 情境 + 角色
     * - MiniMax TTS：7 情绪 + 22 语气词
     * - 火山引擎豆包：自然语言指令（无预定义枚举，此处映射为通用中文标签）
     *
     * 中文标签作为 UI 展示，各 Provider 的 [QuroTtsProviderDef.providerTags] 持有
     * 该服务商原生 API 所需的英文/原始值。
     */
    val EMOTION_TAGS = listOf(
        // ═══ 基础情绪（全服务商通用） ═══
        "开心", "悲伤", "愤怒", "恐惧", "惊讶", "兴奋", "委屈", "平静", "冷漠",
        "怅然", "欣慰", "无奈", "愧疚", "释然", "嫉妒", "厌倦", "忐忑", "动情",
        // ═══ 语调/风格（Edge TTS 官方 + 腾讯云扩展） ═══
        "温柔", "高冷", "活泼", "严肃", "慵懒", "俏皮", "深沉", "干练", "凌厉",
        "亲切", "友善", "温和", "希望", "同情", "羡慕", " narration-professional(专业旁白)",
        " narration-relaxed(舒缓旁白)", "documentary-narration(纪录片)", "lyrical(抒情诗意)",
        // ═══ 场景/角色（阿里百炼/腾讯云/火山引擎） ═══
        "聊天", "客服", "新闻播报", "新闻-随意", "新闻-正式",
        "故事讲述", "广播", "诗歌朗诵", "解说", "体育解说", "体育解说-激情",
        "广告- upbeat", "智能助手", "撒娇", "傲娇", "震惊", "厌恶",
        "害怕-颤抖", " shout(喊叫)", " whisper(耳语)", " unfriendly(冷淡)",
        // ═══ 音色类型 ═══
        "磁性", "醇厚", "清亮", "空灵", "稚嫩", "苍老", "甜美", "沙哑", "醇雅",
        "夹子音", "御姐音", "正太音", "大叔音", "台湾腔",
        // ═══ 方言（CosyVoice3 官方支持列表扩展） ═══
        "东北话", "四川话", "河南话", "粤语", "山东话", "湖南话", "陕西话",
        // ═══ 角色/特殊 ═══
        "孙悟空", "林黛玉", "唱歌",
        // ═══ 阿里百炼 CosyVoice3 专属情境标签 ═══
        "闲聊对话", "课堂教学", "比赛解说", "深夜电台", "剧情解说", "科普推广",
        "产品推广", "脱口秀", "广告促销", "语音导航", "儿童内容解说",
        // ═══ 阿里百炼 CosyVoice3 专属角色标签 ═══
        "温和客服", "傲娇公主", "元气少女", "可爱孩童", "机器人", "小猪佩奇",
        "旁白", "故事机", "儿童玩具",
    )

    /** 行内细粒度标签（吸气/叹气/笑/哭等微表情）。 */
    val INLINE_TAGS = listOf(
        "吸气", "深呼吸", "叹气", "长叹一口气", "喘息", "屏息",
        "紧张", "害怕", "激动", "疲惫", "委屈", "撒娇", "心虚", "震惊", "不耐烦",
        "颤抖", "声音颤抖", "变调", "破音", "鼻音", "气声", "沙哑",
        "笑", "轻笑", "大笑", "冷笑", "抽泣", "呜咽", "哽咽", "嚎啕大哭",
    )

    /** 全部可用标签（段级 + 行内），供风格提示与白名单使用。 */
    val ALL_EMOTION_TAGS = EMOTION_TAGS + INLINE_TAGS

    /** 标签分组（v134）：按语义类别归类，便于语音服务屏「按类别分组 + 可收缩」展示，
     *  解决此前所有平台/类别标签平铺混在一起、无法收缩占用面积的问题。 */
    data class TagGroup(val name: String, val tags: List<String>)

    val EMOTION_TAG_GROUPS = listOf(
        TagGroup("基础情绪", listOf(
            "开心", "悲伤", "愤怒", "恐惧", "惊讶", "兴奋", "委屈", "平静", "冷漠",
            "怅然", "欣慰", "无奈", "愧疚", "释然", "嫉妒", "厌倦", "忐忑", "动情",
        )),
        TagGroup("语调风格", listOf(
            "温柔", "高冷", "活泼", "严肃", "慵懒", "俏皮", "深沉", "干练", "凌厉",
            "亲切", "友善", "温和", "希望", "同情", "羡慕",
            "narration-professional(专业旁白)", "narration-relaxed(舒缓旁白)",
            "documentary-narration(纪录片)", "lyrical(抒情诗意)",
        )),
        TagGroup("场景角色", listOf(
            "聊天", "客服", "新闻播报", "新闻-随意", "新闻-正式",
            "故事讲述", "广播", "诗歌朗诵", "解说", "体育解说", "体育解说-激情",
            "广告-upbeat", "智能助手", "撒娇", "傲娇", "震惊", "厌恶",
            "害怕-颤抖", "shout(喊叫)", "whisper(耳语)", "unfriendly(冷淡)",
        )),
        TagGroup("音色类型", listOf(
            "磁性", "醇厚", "清亮", "空灵", "稚嫩", "苍老", "甜美", "沙哑", "醇雅",
            "夹子音", "御姐音", "正太音", "大叔音", "台湾腔",
        )),
        TagGroup("方言", listOf("东北话", "四川话", "河南话", "粤语", "山东话", "湖南话", "陕西话")),
        TagGroup("CosyVoice3 情境", listOf(
            "闲聊对话", "课堂教学", "比赛解说", "深夜电台", "剧情解说", "科普推广",
            "产品推广", "脱口秀", "广告促销", "语音导航", "儿童内容解说",
        )),
        TagGroup("CosyVoice3 角色", listOf(
            "温和客服", "傲娇公主", "元气少女", "可爱孩童", "机器人", "小猪佩奇",
            "旁白", "故事机", "儿童玩具",
        )),
        TagGroup("角色/特殊", listOf("孙悟空", "林黛玉", "唱歌")),
    )

    val INLINE_TAG_GROUPS = listOf(
        TagGroup("呼吸气声", listOf("吸气", "深呼吸", "叹气", "长叹一口气", "喘息", "屏息")),
        TagGroup("情绪微表情", listOf("紧张", "害怕", "激动", "疲惫", "委屈", "撒娇", "心虚", "震惊", "不耐烦")),
        TagGroup("声音状态", listOf("颤抖", "声音颤抖", "变调", "破音", "鼻音", "气声", "沙哑")),
        TagGroup("笑声哭声", listOf("笑", "轻笑", "大笑", "冷笑", "抽泣", "呜咽", "哽咽", "嚎啕大哭")),
    )

    /** 全部标签分组（段级 + 行内），供语音服务屏分组折叠展示。 */
    val ALL_TAG_GROUPS = EMOTION_TAG_GROUPS + INLINE_TAG_GROUPS

    /**
     * 小米 MiMo 语义别名（兼容历史 AI 输出）：当 AI 写 (语色:旁白)/(语色:悟空) 等「语义角色名」时，
     * 回落到 MiMo 预置音色 id。仅当【实际服务商为 MiMo】且未命中真实音色名时生效。
     *
     * 现代写法推荐 AI 直接用真实音色名（见 [selectableVoiceNames]）——本表仅为兼容保留，
     * 不再作为「可选语色白名单」注入（旧实现写死 MiMo 预设 id，导致非 MiMo 服务商
     * voiceColorToVoice 恒返回 null，表现为「AI 拿不到已配置音色 / 被固定死一个服务商」）。
     */
    val VOICE_COLOR_ALIASES: Map<String, String> = mapOf(
        "旁白" to "白桦", "解说" to "白桦", "叙述" to "白桦", "唐僧" to "白桦",
        "悟空" to "苏打", "孙悟空" to "苏打", "男主角" to "苏打",
        "林黛玉" to "茉莉", "女主角" to "茉莉",
        "御姐音" to "冰糖", "俏皮" to "冰糖", "活泼" to "冰糖",
        "大叔音" to "白桦", "苍老" to "白桦", "沉稳" to "白桦", "磁性" to "白桦",
        "温柔" to "茉莉", "甜美" to "茉莉", "正太音" to "苏打",
    )

    /**
     * 当前实际在用服务商的「可选语色清单」（供提示词与设置页展示，AI 从中自由选真实音色名）。
     *
     * 动态跟随「实际播放服务商」，不再写死任何单一服务商：
     * - 预置音色服务商（Edge / MiMo …）：列出该服务商全部真实音色名；
     * - 自由文本服务商（OpenAI / 硅基流动 / TTS302 … 无预置枚举）：列出用户已配置的默认 voice + 自定义音色名；
     * - 始终并入用户自定义音色（设计/复刻），确保「AI 分配的自定义/复刻音色」也在可选范围内。
     */
    fun selectableVoiceNames(def: QuroTtsProviderDef, cfg: QuroTtsProviderConfig): List<String> {
        val out = mutableListOf<String>()
        if (def.voices.isNotEmpty()) out += def.voices.map { it.name }
        if (cfg.voice.isNotBlank()) out += cfg.voice
        cfg.customVoices.forEach { out += it.name }
        return out.distinct()
    }

    /**
     * 语色路由「完整可选音色清单」（纯文本，注入提示词）。
     * 与 [selectableVoiceNames]（仅给显示名，供设置页 chip）的区别：本函数【逐条带语言 + 真实 voice id】，
     * 直接解决 AI「看不到语言 / 拿不到正确 id / 写错名回落默认音」三大痛点。
     *
     * 格式（每条一行，便于 AI 复制真实音色名或 id）：
     *   晓晓（女）[id=zh-CN-XiaoxiaoNeural · 语言=中文]
     *
     * - 预置音色服务商（Edge / MiMo / OpenAI …）：枚举 def.voices（含 lang/id）+ 自定义音色；
     * - 自由文本服务商（MiniMax / 硅基流动 / 火山 / 讯飞 / 腾讯 / TTS302 …）：
     *     无统一音色枚举，AI 必须写「该服务商真实支持的 voice id」（字面透传）；
     *     这里给出「当前默认 id」+ 自定义复刻/设计音色 id 作为锚点，并明确警告写错名会回落默认音；
     * - 始终并入用户自定义音色（设计/复刻），带其 registeredId。
     */
    fun voiceColorCatalogText(def: QuroTtsProviderDef, cfg: QuroTtsProviderConfig): String {
        val lines = mutableListOf<String>()
        if (def.voices.isNotEmpty()) {
            for (v in def.voices) {
                val lang = v.lang.ifBlank { "未知" }
                lines += "${v.name} [id=${v.id} · 语言=$lang]"
            }
            cfg.customVoices.forEach {
                val id = it.registeredId.ifBlank { it.name }
                lines += "${it.name} [id=$id · 语言=自定义]"
            }
        } else if (def.voiceFreeText) {
            if (cfg.voice.isNotBlank()) lines += "（默认）${cfg.voice} [自由文本·真实id]"
            cfg.customVoices.forEach {
                val id = it.registeredId.ifBlank { it.name }
                lines += "${it.name} [id=$id · 语言=自定义]"
            }
            lines += if (cfg.voice.isBlank() && cfg.customVoices.isEmpty())
                "（自由文本音色：请在 (语色:…) 中写【该服务商真实支持的 voice id】，任意真实 id 皆可字面采用；写错/中文名/语义名将回落默认音）"
            else
                "（自由文本音色：除上列锚点外，也可写该服务商真实支持的其他 voice id，字面透传）"
        } else {
            if (cfg.voice.isNotBlank()) lines += "${cfg.voice} [id=${cfg.voice}]"
            cfg.customVoices.forEach {
                val id = it.registeredId.ifBlank { it.name }
                lines += "${it.name} [id=$id · 语言=自定义]"
            }
        }
        return if (lines.isEmpty()) "（当前服务商无可选音色，语色路由暂不可用）" else lines.distinct().joinToString("\n  ")
    }

    /**
     * 语色路由：把 AI 写的 (语色:xxx) 解析为【当前实际在用服务商】可识别的真实 voice id。
     * 动态跟随服务商——不再写死任何单一服务商音色（旧实现写死 MiMo 预设 id，非 MiMo 服务商恒返回 null）。
     *
     * 解析优先级：
     *  1) 服务商预置音色 id / 名称（Edge 的 zh-CN-XiaoxiaoNeural、MiMo 的白桦/茉莉…）直接命中；
     *  2) 用户自定义音色（设计/复刻）按名称命中其注册 id / 名称；
     *  3) 自由文本服务商（OpenAI/硅基流动/TTS302… 无预置列表）：AI 写的字面即 voice id，直接透传；
     *  4) MiMo 语义别名（旁白/悟空…）回落映射（兼容历史 AI 输出）。
     * 命中不到返回 null（该段回落全局音色，不影响播放）。
     */
    /** 去掉音色显示名末尾的括号修饰，如「晓晓（女）」→「晓晓」，容错 AI 漏写（女）的情况。 */
    private fun baseVoiceName(name: String): String {
        val idx = name.indexOfFirst { it == '（' || it == '(' }
        return if (idx > 0) name.substring(0, idx).trim() else ""
    }

    fun voiceColorToVoice(def: QuroTtsProviderDef, cfg: QuroTtsProviderConfig, colorName: String): String? {
        val raw = colorName.trim()
        if (raw.isEmpty()) return null
        val norm = { s: String -> s.lowercase().replace(Regex("\\s+"), "") }
        val n = norm(raw)
        // 1) 预置音色 id / 名称 / 去修饰基名 命中（兼容 AI 漏写「（女）」等括号修饰）
        for (v in def.voices) {
            if (norm(v.id) == n || norm(v.name) == n) return v.id
            val base = baseVoiceName(v.name)
            if (base.isNotEmpty() && norm(base) == n) return v.id
        }
        // 2) 自定义音色：名称命中（复刻已注册的走 registeredId，未注册/设计走 name）
        for (cv in cfg.customVoices) {
            if (norm(cv.name) == n) return cv.registeredId.ifBlank { cv.name }
        }
        // 3) 自由文本服务商：AI 写的字面即 voice id，直接透传（该类型无预置枚举可校验）
        if (def.voiceFreeText) return raw
        // 4) MiMo 语义别名回落
        if (def.kind == QuroTtsProviderKind.MIMO) {
            val alias = VOICE_COLOR_ALIASES[raw] ?: VOICE_COLOR_ALIASES[n]
            if (alias != null) return alias
        }
        return null
    }
}

/** 自定义音色条目（设计=文字描述；复刻=音频样本路径 + 旁白文本 + 注册回填 ID）。 */
data class CloudCustomVoice(
    val name: String,
    val type: String, // "design" | "clone"
    val designText: String = "",
    val cloneUri: String = "",
    val cloneText: String = "",     // 参考音频旁白文本（硅基流动 CosyVoice 注册必需）
    val registeredId: String = "",  // 注册式复刻后回填的克隆音色 ID/URI（MiniMax voice_id / 硅基流动 speech: uri）
)

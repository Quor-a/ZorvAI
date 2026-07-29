package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 语音试听默认文本（文件级常量，供 QuroTtsProviderConfig 与 QuroTtsProviderPrefs 共用）。 */
private const val DEFAULT_PREVIEW = "这是一条语音合成测试，Zorv AI 正在朗读。"

/**
 * 云端 TTS 服务商目录 + 配置持久化。
 *
 * 设计目标：把所有「接口调用类」TTS 服务商（Edge / 小米 MiMo / 火山引擎 / 科大讯飞 / 腾讯云 /
 * 阿里百炼 CosyVoice / OpenAI / MiniMax / 硅基流动 / TTS302 / CozeCn / Gizwits / ACGN …）
 * 统一成「服务商 + 配置字段 + 音色 + 风格标签（可扩展）」的数据模型，由 [QuroTtsClients] 按 kind 分发合成。
 *
 * 风格标签：UI 选用统一的 [QuroCloudTtsCatalog.ALL_EMOTION_TAGS] 中文情绪/风格词库，
 * 各服务商在合成时按自身能力翻译/忽略（见各 Client 的 styleSupport）。
 */

enum class QuroTtsProviderKind {
    OPENAI_COMPAT,   // OpenAI /audio/speech 兼容：OpenAI / 硅基流动 / TTS302 / CozeCn / Gizwits / ACGN / 阿里百炼CosyVoice
    EDGE_TTS,        // 微软 Edge TTS（免费，WebSocket + SSML express-as）
    MIMO,            // 小米 MiMo speech-synthesis-v2.5（/chat/completions + audio）
    VOLCENGINE,      // 火山引擎 豆包 / 灵犀流式 TTS（REST）
    IFLYTEK,         // 科大讯飞 语音合成（WebSocket + HMAC-SHA256）
    TENCENT,         // 腾讯云 语音合成（REST + TC3-HMAC-SHA256）
    MINIMAX,         // MiniMax t2a_v2（REST）
}

/** 服务商配置字段定义（用于动态渲染设置 UI）。 */
data class QuroTtsField(
    val key: String,
    val label: String,
    val placeholder: String = "",
    val secret: Boolean = false,
)

/** 预置音色。 */
data class QuroTtsVoice(
    val id: String,
    val name: String,
    val gender: String = "",
    val lang: String = "",
    val note: String = "",
)

/**
 * 服务商定义。
 * @param voiceFreeText true 时无预置音色列表，UI 用自由输入（多数第三方 OpenAI 兼容网关）。
 * @param requiredFields 判定 isConfigured 的必填字段 key。
 * @param streamingSupport 是否支持流式输出（默认全部支持）。
 * @param cloneSupport 是否支持语音克隆/声音复刻。
 * @param providerTags 该服务商自有情绪/风格/语气标签（空列表时 UI 回退到通用词库）。
 */
data class QuroTtsProviderDef(
    val id: String,
    val name: String,
    val desc: String,
    val kind: QuroTtsProviderKind,
    val defaultBaseUrl: String,
    val fields: List<QuroTtsField>,
    val voices: List<QuroTtsVoice> = emptyList(),
    val styleSupport: Boolean,
    val formatOptions: List<String>,
    val defaultFormat: String,
    val defaultModel: String = "",
    val requiredFields: List<String> = emptyList(),
    val voiceFreeText: Boolean = false,
    val streamingSupport: Boolean = true,
    val cloneSupport: Boolean = false,
    val providerTags: List<String> = emptyList(),
)

object QuroTtsProviders {
    // ─────────────────────────── Edge TTS（默认免费） ───────────────────────────
    private val EDGE : QuroTtsProviderDef = QuroTtsProviderDef(
        id = "edge", name = "Edge TTS（微软）", desc = "基于微软语音合成，免费使用，无需任何 API Key。支持 SSML express-as 情感风格控制。",
        kind = QuroTtsProviderKind.EDGE_TTS, defaultBaseUrl = "",
        fields = emptyList(),
        styleSupport = true,
        formatOptions = listOf("mp3"), defaultFormat = "mp3",
        requiredFields = emptyList(),
        voices = listOf(
            QuroTtsVoice("zh-CN-XiaoxiaoNeural", "晓晓（女）", "女", "中文"),
            QuroTtsVoice("zh-CN-XiaoyiNeural", "一伊（女）", "女", "中文"),
            QuroTtsVoice("zh-CN-YunyangNeural", "云扬（男）", "男", "中文"),
            QuroTtsVoice("zh-CN-YunxiNeural", "云希（男）", "男", "中文"),
            QuroTtsVoice("zh-CN-XiaomoNeural", "晓墨（女）", "女", "中文"),
            QuroTtsVoice("zh-CN-XiaoxuanNeural", "晓萱（女）", "女", "中文"),
            QuroTtsVoice("zh-CN-YunjianNeural", "云健（男）", "男", "中文"),
            QuroTtsVoice("en-US-AriaNeural", "Aria（女·英）", "女", "英文"),
            QuroTtsVoice("en-US-GuyNeural", "Guy（男·英）", "男", "英文"),
            QuroTtsVoice("en-US-JennyNeural", "Jenny（女·英）", "女", "英文"),
            QuroTtsVoice("ja-JP-NanamiNeural", "Nanami（女·日）", "女", "日文"),
            QuroTtsVoice("ko-KR-SunHiNeural", "SunHi（女·韩）", "女", "韩文"),
        ),
        streamingSupport = true,
        cloneSupport = false,
        providerTags = listOf(
            // ═══ Edge TTS 官方完整 express-as 样式列表（v186 逐条对齐 Microsoft Azure 文档，36 项） ═══
            "advertisement_upbeat", "affectionate", "angry", "assistant", "calm", "chat",
            "cheerful", "customerservice", "depressed", "disgruntled", "disgusted", "surprised",
            "documentary-narration", "embarrassed", "empathetic", "envious", "excited",
            "fearful", "friendly", "gentle", "hopeful", "lyrical",
            "narration-professional", "narration-relaxed",
            "newscast", "newscast-casual", "newscast-formal",
            "poetry-reading", "sad", "serious", "shouting",
            "sports_commentary", "sports_commentary_excited",
            "whispering", "terrified", "unfriendly",
        ),
    )

    // ─────────────────────────── OpenAI TTS ───────────────────────────
    private val OPENAI : QuroTtsProviderDef = QuroTtsProviderDef(
        id = "openai", name = "OpenAI TTS", desc = "OpenAI 官方 /audio/speech 接口。gpt-4o-mini-tts 支持 instructions 自然语言情绪控制。",
        kind = QuroTtsProviderKind.OPENAI_COMPAT, defaultBaseUrl = "https://api.openai.com/v1",
        fields = listOf(
            QuroTtsField("api_key", "API Key", "sk-...", secret = true),
            QuroTtsField("base_url", "Base URL", "https://api.openai.com/v1"),
            QuroTtsField("model", "模型", "tts-1"),
        ),
        styleSupport = true,
        formatOptions = listOf("mp3", "wav", "pcm16"), defaultFormat = "mp3", defaultModel = "tts-1",
        requiredFields = listOf("api_key"),
        voices = listOf(
            QuroTtsVoice("alloy", "Alloy"), QuroTtsVoice("echo", "Echo"),
            QuroTtsVoice("fable", "Fable"), QuroTtsVoice("onyx", "Onyx"),
            QuroTtsVoice("nova", "Nova"), QuroTtsVoice("shimmer", "Shimmer"),
            QuroTtsVoice("coral", "Coral"), QuroTtsVoice("sage", "Sage"),
            QuroTtsVoice("ballad", "Ballad"), QuroTtsVoice("verse", "Verse"),
            QuroTtsVoice("marin", "Marin"), QuroTtsVoice("cedar", "Cedar"),
            QuroTtsVoice("ash", "Ash"),
        ),
        streamingSupport = true,
        cloneSupport = false,
        providerTags = listOf(),
    )

    // ─────────────────────────── MiniMax TTS ───────────────────────────
    private val MINIMAX : QuroTtsProviderDef = QuroTtsProviderDef(
        id = "minimax", name = "MiniMax TTS", desc = "MiniMax t2a_v2 语音合成。支持 7 种情绪、21 种语气词标签、语音克隆（voice_clone API）。",
        kind = QuroTtsProviderKind.MINIMAX, defaultBaseUrl = "https://api.minimax.chat/v1",
        fields = listOf(
            QuroTtsField("group_id", "Group ID", ""),
            QuroTtsField("api_key", "API Key", "", secret = true),
            QuroTtsField("base_url", "Base URL", "https://api.minimax.chat/v1"),
            QuroTtsField("model", "模型", "speech-01-turbo"),
        ),
        styleSupport = true,
        formatOptions = listOf("mp3", "wav", "pcm16"), defaultFormat = "mp3", defaultModel = "speech-01-turbo",
        requiredFields = listOf("group_id", "api_key"),
        voiceFreeText = true,
        streamingSupport = true,
        cloneSupport = true,
        providerTags = listOf(
            // ═══ MiniMax 情绪参数（voice_setting.emotion 官方枚举，speech-2.8 系列） ═══
            "happy","sad","angry","fear","surprise","disgust","neutral",
            // ═══ MiniMax 语气词标签（speech-2.8-hd/turbo 文本内联，官方完整 22 项） ═══
            "laughs","chuckle","coughs","clear-throat","groans","breath","pant","inhale",
            "exhale","gasps","sniffs","sighs","snorts","burps","lip-smacking","humming",
            "hissing","emm","whistles","sneezes","crying","applause",
        ),
    )

    // ─────────────────────────── 硅基流动 CosyVoice ───────────────────────────
    private val SILICONFLOW : QuroTtsProviderDef = QuroTtsProviderDef(
        id = "siliconflow", name = "硅基流动 CosyVoice", desc = "SiliconFlow 兼容 OpenAI /audio/speech，内置 CosyVoice2。支持情感控制、用户预置/动态音色克隆。",
        kind = QuroTtsProviderKind.OPENAI_COMPAT, defaultBaseUrl = "https://api.siliconflow.cn/v1",
        fields = listOf(
            QuroTtsField("api_key", "API Key", "", secret = true),
            QuroTtsField("base_url", "Base URL", "https://api.siliconflow.cn/v1"),
            QuroTtsField("model", "模型", "FunAudioLLM/CosyVoice2-0.9B"),
        ),
        styleSupport = true,
        formatOptions = listOf("mp3", "wav"), defaultFormat = "mp3", defaultModel = "FunAudioLLM/CosyVoice2-0.9B",
        requiredFields = listOf("api_key"), voiceFreeText = true,
        streamingSupport = true,
        cloneSupport = true,
        providerTags = listOf(
            // ═══ CosyVoice2 官方情感（与阿里百炼同族：开心/悲伤/愤怒/恐惧/惊讶/中性） ═══
            "快乐","悲伤","愤怒","恐惧","惊讶","平静",
            // ═══ 指令风格（instruct 自由描述，如「用温柔的语气」） ═══
            "温柔","严肃","兴奋","新闻播报","故事讲述","客服","撒娇","震惊","傲娇","解说",
        ),
    )

    // ─────────────────────────── TTS302AI ───────────────────────────
    private val TTS302 : QuroTtsProviderDef = QuroTtsProviderDef(
        id = "tts302", name = "TTS302AI", desc = "TTS302AI 兼容 OpenAI /audio/speech。",
        kind = QuroTtsProviderKind.OPENAI_COMPAT, defaultBaseUrl = "https://api.tts302.ai/v1",
        fields = listOf(
            QuroTtsField("api_key", "API Key", "", secret = true),
            QuroTtsField("base_url", "Base URL", "https://api.tts302.ai/v1"),
            QuroTtsField("model", "模型", "tts-1"),
        ),
        styleSupport = true,
        formatOptions = listOf("mp3", "wav"), defaultFormat = "mp3", defaultModel = "tts-1",
        requiredFields = listOf("api_key"), voiceFreeText = true,
        streamingSupport = true, cloneSupport = false, providerTags = emptyList(),
    )

    // ─────────────────────────── CozeCn TTS ───────────────────────────
    private val COZECN : QuroTtsProviderDef = QuroTtsProviderDef(
        id = "cozecn", name = "CozeCn TTS", desc = "Coze 国内版兼容 OpenAI /audio/speech。",
        kind = QuroTtsProviderKind.OPENAI_COMPAT, defaultBaseUrl = "https://api.coze.cn/v1",
        fields = listOf(
            QuroTtsField("api_key", "API Key / PAT", "", secret = true),
            QuroTtsField("base_url", "Base URL", "https://api.coze.cn/v1"),
            QuroTtsField("model", "模型", "alloy"),
        ),
        styleSupport = true,
        formatOptions = listOf("mp3"), defaultFormat = "mp3", defaultModel = "alloy",
        requiredFields = listOf("api_key"), voiceFreeText = true,
        streamingSupport = true, cloneSupport = false, providerTags = emptyList(),
    )

    // ─────────────────────────── Gizwits TTS ───────────────────────────
    private val GIZWITS : QuroTtsProviderDef = QuroTtsProviderDef(
        id = "gizwits", name = "Gizwits TTS", desc = "Gizwits 兼容 OpenAI /audio/speech（自助填写网关地址）。",
        kind = QuroTtsProviderKind.OPENAI_COMPAT, defaultBaseUrl = "",
        fields = listOf(
            QuroTtsField("api_key", "API Key", "", secret = true),
            QuroTtsField("base_url", "Base URL", "https://your-gizwits-gateway/v1"),
            QuroTtsField("model", "模型", "tts-1"),
        ),
        styleSupport = true,
        formatOptions = listOf("mp3"), defaultFormat = "mp3", defaultModel = "tts-1",
        requiredFields = listOf("api_key", "base_url"), voiceFreeText = true,
        streamingSupport = true, cloneSupport = false, providerTags = emptyList(),
    )

    // ─────────────────────────── ACGN TTS ───────────────────────────
    private val ACGN : QuroTtsProviderDef = QuroTtsProviderDef(
        id = "acgn", name = "ACGN TTS", desc = "ACGN 兼容 OpenAI /audio/speech（自助填写网关地址）。",
        kind = QuroTtsProviderKind.OPENAI_COMPAT, defaultBaseUrl = "",
        fields = listOf(
            QuroTtsField("api_key", "API Key", "", secret = true),
            QuroTtsField("base_url", "Base URL", "https://your-acgn-gateway/v1"),
            QuroTtsField("model", "模型", "tts-1"),
        ),
        styleSupport = true,
        formatOptions = listOf("mp3"), defaultFormat = "mp3", defaultModel = "tts-1",
        requiredFields = listOf("api_key", "base_url"), voiceFreeText = true,
        streamingSupport = true, cloneSupport = false, providerTags = emptyList(),
    )

    // ─────────────────────────── 阿里百炼 CosyVoice（DashScope OpenAI 兼容） ───────────────────────────
    private val ALIYUN : QuroTtsProviderDef = QuroTtsProviderDef(
        id = "aliyun", name = "阿里百炼 CosyVoice", desc = "DashScope 兼容 OpenAI /audio/speech。CosyVoice3 支持 3s 零样本音色复刻、9 种情感控制、双向流式合成。",
        kind = QuroTtsProviderKind.OPENAI_COMPAT, defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        fields = listOf(
            QuroTtsField("api_key", "DashScope API Key", "", secret = true),
            QuroTtsField("base_url", "Base URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            QuroTtsField("model", "模型", "cosyvoice-v1"),
        ),
        styleSupport = true,
        formatOptions = listOf("mp3", "wav"), defaultFormat = "mp3", defaultModel = "cosyvoice-v1",
        requiredFields = listOf("api_key"), voiceFreeText = true,
        streamingSupport = true,
        cloneSupport = true,
        providerTags = listOf(
            // ═══ CosyVoice 官方情感 TTS 六类（cosyvoice.org 官方：开心/悲伤/愤怒/恐惧/惊讶 + 中性） ═══
            "happy(快乐)", "sad(悲伤)", "angry(愤怒)", "fearful(恐惧)",
            "surprised(惊讶)", "neutral(中性/平静)",
            // ═══ CosyVoice Instruct 情境标签（指令控制模型支持，官方文档 v2026-07） ═══
            "闲聊对话", "课堂教学", "比赛解说", "深夜电台", "剧情解说",
            "科普推广", "产品推广", "脱口秀", "广告促销", "语音导航", "儿童内容解说",
            // ═══ CosyVoice Instruct 角色标签 ═══
            "温和客服", "傲娇公主", "元气少女", "可爱孩童", "机器人", "小猪佩奇",
            "旁白", "故事机", "儿童玩具",
        ),
    )

    // ─────────────────────────── 小米 MiMo ───────────────────────────
    private val MIMO : QuroTtsProviderDef = QuroTtsProviderDef(
        id = "mimo", name = "小米 MiMo TTS", desc = "MiMo-V2.5-TTS 系列：预置/设计/复刻三模型。支持自然语言指令+音频标签[crying][pause]等多情绪混合控制，流式输出。",
        kind = QuroTtsProviderKind.MIMO, defaultBaseUrl = "https://api.xiaomimimo.com/v1",
        fields = listOf(
            QuroTtsField("api_key", "API Key", "MIMO_API_KEY", secret = true),
            QuroTtsField("base_url", "Base URL", "https://api.xiaomimimo.com/v1"),
            QuroTtsField("model", "模型", "mimo-v2.5-tts"),
        ),
        styleSupport = true,
        formatOptions = listOf("wav", "pcm16"), defaultFormat = "wav", defaultModel = "mimo-v2.5-tts",
        requiredFields = listOf("api_key"),
        voices = QuroCloudTtsCatalog.PRESET_VOICES.map { QuroTtsVoice(it.id, it.name, it.gender, it.lang) },
        streamingSupport = true,
        cloneSupport = true,
        providerTags = listOf(
            // ═══ 基础情绪（官方 mimo-v2-5-tts：开心/悲伤/愤怒/恐惧/惊讶/兴奋/委屈/平静/冷漠） ═══
            "开心", "悲伤", "愤怒", "恐惧", "惊讶", "兴奋", "委屈", "平静", "冷漠",
            // ═══ 复合情绪（官方：怅然/欣慰/无奈/愧疚/释然/忐忑/动情） ═══
            "怅然", "欣慰", "无奈", "愧疚", "释然", "忐忑", "动情",
            // ═══ 整体语调（官方：温柔/高冷/活泼/严肃/慵懒/俏皮/深沉/干练/凌厉） ═══
            "温柔", "高冷", "活泼", "严肃", "慵懒", "俏皮", "深沉", "干练", "凌厉",
            // ═══ 音色定位（官方：磁性/醇厚/清亮/空灵/稚嫩/苍老/甜美/沙哑） ═══
            "磁性", "醇厚", "清亮", "空灵", "稚嫩", "苍老", "甜美", "沙哑",
            // ═══ 人设腔调（官方：夹子音/御姐音/正太音/大叔音/台湾腔） ═══
            "夹子音", "御姐音", "正太音", "大叔音", "台湾腔",
            // ═══ 方言（官方：东北话/四川话/河南话/粤语） ═══
            "东北话", "四川话", "河南话", "粤语",
            // ═══ 角色扮演 / 唱歌（官方：孙悟空/林黛玉/唱歌） ═══
            "孙悟空", "林黛玉", "唱歌",
            // ═══ 语气态度扩展（既有合理词，官方未单列分类） ═══
            "威压", "冰冷", "戏谑", "得意", "敬佩", "压抑", "亲切", "阴冷", "明亮", "高亢", "轻快上扬", "低沉",
            // ═══ 场景职业语调（既有合理词） ═══
            "播报", "旁白", "客服", "广告", "解说", "新闻", "聊天", "吟诵", "低语", "嘶吼",
            // ═══ 行内情绪状态（官方：紧张/害怕/激动/疲惫/撒娇/心虚/震惊/不耐烦） ═══
            "紧张", "害怕", "激动", "疲惫", "撒娇", "心虚", "震惊", "不耐烦",
            // ═══ 行内语音特征（官方：颤抖/声音颤抖/变调/破音/鼻音/气声） ═══
            "颤抖", "声音颤抖", "变调", "破音", "鼻音", "气声",
            // ═══ 行内哭笑表达（官方：笑/轻笑/大笑/冷笑/抽泣/呜咽/哽咽/嚎啕大哭） ═══
            "笑", "轻笑", "大笑", "冷笑", "抽泣", "呜咽", "哽咽", "嚎啕大哭",
            // ═══ 行内节奏/其他（官方 + 社区：语速加快/语速变慢/小声/沉默片刻/清嗓子/耳语/咳嗽/停顿/提高音量喊话） ═══
            "语速加快", "语速变慢", "小声", "沉默片刻", "清嗓子", "耳语", "咳嗽", "停顿", "提高音量喊话",
            // ═══ MiMo 音频标签（官方 inline 标签，置于 assistant content 字粒度控制拟声/气息） ═══
            "[crying]", "[laughing]", "[whispering]", "[shouting]", "[pause]", "[sniffles]",
            "[sighs]", "[gasps]", "[breath]", "[coughs]", "[clear-throat]", "[pant]", "[inhale]", "[exhale]",
        ),
    )

    // ─────────────────────────── 火山引擎 豆包 / 灵犀 ───────────────────────────
    private val VOLCENGINE : QuroTtsProviderDef = QuroTtsProviderDef(
        id = "volcengine", name = "火山引擎 豆包 TTS", desc = "字节豆包语音合成 2.0（Doubao-Seed-TTS）。支持指令式情感控制、5s 极速声音复刻（97.5%相似度）、WebSocket 流式 <300ms 延迟。",
        kind = QuroTtsProviderKind.VOLCENGINE, defaultBaseUrl = "https://openspeech.bytedance.com/api/v1/tts",
        fields = listOf(
            QuroTtsField("app_id", "App ID", ""),
            QuroTtsField("token", "Access Token", "", secret = true),
            QuroTtsField("cluster", "Cluster", "volcabcluster"),
        ),
        styleSupport = true,
        formatOptions = listOf("mp3", "wav", "pcm16", "ogg"), defaultFormat = "mp3",
        requiredFields = listOf("app_id", "token"), voiceFreeText = true,
        streamingSupport = true,
        cloneSupport = true,
        providerTags = listOf(
            // ═══ 火山引擎 豆包 官方 7 大情感（精品长文本情感预测版 + 大模型 TTS emotion 参数） ═══
            "happy","sad","angry","fear","disgusted","surprised","peaceful",
            // ═══ 指令式场景风格（<整体情绪:...> 自然语言指令亦可） ═══
            "excited","calm","news","story","radio","poetry","call","customer-service",
        ),
    )

    // ─────────────────────────── 科大讯飞 ───────────────────────────
    private val IFLYTEK : QuroTtsProviderDef = QuroTtsProviderDef(
        id = "iflytek", name = "科大讯飞 TTS", desc = "讯飞开放平台语音合成 + 一句话声音复刻（标准版/多风格版，支持8语种5方言）。WebSocket 流式。",
        kind = QuroTtsProviderKind.IFLYTEK, defaultBaseUrl = "wss://iat-api.xfyun.cn/v2/tts",
        fields = listOf(
            QuroTtsField("app_id", "APP ID", ""),
            QuroTtsField("api_key", "API Key", "", secret = true),
            QuroTtsField("api_secret", "APISecret", "", secret = true),
        ),
        styleSupport = true,
        formatOptions = listOf("mp3", "pcm16"), defaultFormat = "mp3",
        requiredFields = listOf("app_id", "api_key", "api_secret"), voiceFreeText = true,
        streamingSupport = true,
        cloneSupport = true,
        providerTags = listOf(
            // ═══ 科大讯飞 多风格/情感（发音人内置风格，非固定枚举，按官方音色描述归纳） ═══
            "标准","温柔","活泼","高冷","沉稳","甜美","磁性","激情","知性","俏皮","慵懒","清冷","诙谐","严肃",
            "新闻播报","故事讲述","客服语气","纪录旁白","广告促销","广播电台","撒娇","傲娇","亲切","治愈",
        ),
    )

    // ─────────────────────────── 腾讯云 ───────────────────────────
    private val TENCENT : QuroTtsProviderDef = QuroTtsProviderDef(
        id = "tencent", name = "腾讯云 TTS", desc = "腾讯云语音合成 + 一句话声音复刻（5-15s）。支持 EmotionCategory 情感控制（15种+强度）、实时流式 WebSocket。",
        kind = QuroTtsProviderKind.TENCENT, defaultBaseUrl = "https://tts.tencentcloudapi.com/",
        fields = listOf(
            QuroTtsField("secret_id", "SecretId", "", secret = true),
            QuroTtsField("secret_key", "SecretKey", "", secret = true),
        ),
        styleSupport = true,
        formatOptions = listOf("mp3", "wav", "pcm16"), defaultFormat = "mp3",
        requiredFields = listOf("secret_id", "secret_key"), voiceFreeText = true,
        streamingSupport = true,
        cloneSupport = true,
        providerTags = listOf("neutral","sad","happy","angry","fear","news","story","radio","poetry","call","sajiao","disgusted","amaze","peaceful","exciting","aojiao","jieshuo"),
    )

    val ALL: List<QuroTtsProviderDef> = listOf(
        EDGE, OPENAI, MINIMAX, SILICONFLOW, TTS302, COZECN, GIZWITS, ACGN, ALIYUN, MIMO, VOLCENGINE, IFLYTEK, TENCENT,
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}

/** 单服务商配置（内存态）。 */
data class QuroTtsProviderConfig(
    val fields: Map<String, String> = emptyMap(),
    val voice: String = "",
    val styleTags: List<String> = emptyList(),
    val customStyleTags: List<String> = emptyList(),
    val format: String = "mp3",
    val model: String = "",
    val preview: String = DEFAULT_PREVIEW,
    val customVoices: List<CloudCustomVoice> = emptyList(),
    val streaming: Boolean = true,
    val cloneEnabled: Boolean = false,
)

object QuroTtsProviderPrefs {
    private const val PREFS = "quro_tts_providers"
    private const val KEY_PROVIDER = "provider"

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getProvider(ctx: Context): String = prefs(ctx).getString(KEY_PROVIDER, "edge") ?: "edge"
    fun setProvider(ctx: Context, id: String) = prefs(ctx).edit().putString(KEY_PROVIDER, id).apply()

    private fun cfgKey(id: String) = "cfg_$id"

    fun getConfig(ctx: Context, id: String): QuroTtsProviderConfig {
        val def = QuroTtsProviders.byId(id) ?: return QuroTtsProviderConfig()
        return runCatching {
            val jo = JSONObject(prefs(ctx).getString(cfgKey(id), "{}") ?: "{}")
            val fields = mutableMapOf<String, String>()
            jo.optJSONObject("fields")?.let { o -> o.keys().forEach { fields[it] = o.optString(it, "") } }
            QuroTtsProviderConfig(
                fields = fields,
                voice = jo.optString("voice", ""),
                styleTags = parseList(jo.optJSONArray("styleTags")),
                customStyleTags = parseList(jo.optJSONArray("customStyleTags")),
                format = jo.optString("format", def.defaultFormat).ifBlank { def.defaultFormat },
                model = jo.optString("model", def.defaultModel),
                preview = jo.optString("preview", DEFAULT_PREVIEW).ifBlank { DEFAULT_PREVIEW },
                customVoices = parseCustomVoices(jo.optJSONArray("customVoices")),
                streaming = jo.optBoolean("streaming", true),
                cloneEnabled = jo.optBoolean("cloneEnabled", false),
            )
        }.getOrDefault(
            QuroTtsProviderConfig(format = def.defaultFormat, model = def.defaultModel, customVoices = emptyList()),
        )
    }

    fun saveConfig(ctx: Context, id: String, cfg: QuroTtsProviderConfig) {
        val o = JSONObject().apply {
            put("fields", JSONObject().apply { cfg.fields.forEach { (k, v) -> put(k, v) } })
            put("voice", cfg.voice)
            put("styleTags", JSONArray(cfg.styleTags))
            put("customStyleTags", JSONArray(cfg.customStyleTags))
            put("format", cfg.format)
            put("model", cfg.model)
            put("preview", cfg.preview)
            put("streaming", cfg.streaming)
            put("cloneEnabled", cfg.cloneEnabled)
            put("customVoices", JSONArray().apply {
                cfg.customVoices.forEach {
                    put(JSONObject().apply {
                        put("name", it.name); put("type", it.type)
                        put("designText", it.designText); put("cloneUri", it.cloneUri)
                        put("cloneText", it.cloneText); put("registeredId", it.registeredId)
                    })
                }
            })
        }
        prefs(ctx).edit().putString(cfgKey(id), o.toString()).apply()
    }

    fun getActiveConfig(ctx: Context): QuroTtsProviderConfig = getConfig(ctx, getProvider(ctx))

    /**
     * 删除某服务商的已保存配置（删除已配置模型 / 服务商）。
     * 仅移除该服务商独立的配置 JSON，不影响其它服务商与当前选中服务商标记。
     * 调用后该服务商回落到「未配置」状态（无必填项的服务商如 Edge 仍为默认可用）。
     */
    fun clearConfig(ctx: Context, id: String) {
        prefs(ctx).edit().remove(cfgKey(id)).apply()
    }

    /** 是否已配置：必填字段全部非空（Edge 无需字段 → 恒 true）。 */
    fun isConfigured(ctx: Context): Boolean {
        val id = getProvider(ctx)
        val def = QuroTtsProviders.byId(id) ?: return false
        if (def.requiredFields.isEmpty()) return true
        val cfg = getConfig(ctx, id)
        return def.requiredFields.all { (cfg.fields[it] ?: "").isNotBlank() }
    }

    /** 指定服务商是否已配置（B1：人格语音组合可能强制切换服务商，需独立判定）。 */
    fun isConfiguredFor(ctx: Context, id: String): Boolean {
        val def = QuroTtsProviders.byId(id) ?: return false
        if (def.requiredFields.isEmpty()) return true
        val cfg = getConfig(ctx, id)
        return def.requiredFields.all { (cfg.fields[it] ?: "").isNotBlank() }
    }

    /** 当前服务商选中的全部风格标签（内置 + 自定义）。 */
    fun getSelectedStyleTags(ctx: Context): List<String> {
        val cfg = getActiveConfig(ctx)
        return (cfg.styleTags + cfg.customStyleTags).distinct()
    }

    fun getPreview(ctx: Context): String = getActiveConfig(ctx).preview.ifBlank { DEFAULT_PREVIEW }

    private fun parseList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until a.length()) {
            val s = a.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun parseCustomVoices(a: JSONArray?): List<CloudCustomVoice> {
        if (a == null) return emptyList()
        val out = mutableListOf<CloudCustomVoice>()
        for (i in 0 until a.length()) {
            runCatching {
                val jo = a.getJSONObject(i)
                out.add(
                    CloudCustomVoice(
                        name = jo.optString("name", ""),
                        type = jo.optString("type", "design"),
                        designText = jo.optString("designText", ""),
                        cloneUri = jo.optString("cloneUri", ""),
                        cloneText = jo.optString("cloneText", ""),
                        registeredId = jo.optString("registeredId", ""),
                    ),
                )
            }
        }
        return out
    }
}

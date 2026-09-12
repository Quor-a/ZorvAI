package com.ai.assistance.quro.genui.app.llm

import com.ai.assistance.quro.genui.app.store.Protocol

/**
 * 供应商预设模板 —— 把"接一个模型"从填五个字段变成点一下。
 *
 * 设计动机：手填 baseUrl 是最高频的出错点（少 /v1、多 /chat/completions、
 * 用了 http 而非 https）。这里把每个厂商的正确端点和推荐模型固化下来，
 * 用户只需粘一个 Key。
 *
 * 维护约定：新增厂商只改本文件；模型配置屏的"从预设添加"与连接测试都读它。
 */
object ProviderPresets {

    /**
     * @param key        内部标识
     * @param name       展示名
     * @param protocol   协议族
     * @param baseUrl    正确的 API 根地址（含版本号，不带具体路径）
     * @param models     推荐模型（第一个为默认）
     * @param docHint    去哪拿 Key —— 写给用户看
     * @param region     区域标记：cn / global / local，用于分组展示
     * @param supportsTools  是否支持 function calling（不支持的话工具调用会退化）
     * @param supportsVision 是否支持图片输入
     */
    data class Preset(
        val key: String,
        val name: String,
        val protocol: Protocol,
        val baseUrl: String,
        val models: List<String>,
        val docHint: String,
        val region: String = "cn",
        val supportsTools: Boolean = true,
        val supportsVision: Boolean = false,
    ) {
        val defaultModel: String get() = models.firstOrNull().orEmpty()
    }

    val all: List<Preset> = listOf(
        // ————— 国内 —————
        Preset(
            "deepseek", "DeepSeek 深度求索", Protocol.OPENAI,
            "https://api.deepseek.com/v1",
            listOf("deepseek-chat", "deepseek-reasoner"),
            "platform.deepseek.com → API Keys", "cn"
        ),
        Preset(
            "glm", "智谱 GLM", Protocol.OPENAI,
            "https://open.bigmodel.cn/api/paas/v4",
            listOf("glm-4-plus", "glm-4-air", "glm-4-flash"),
            "open.bigmodel.cn → API Keys", "cn", supportsVision = true
        ),
        Preset(
            "moonshot", "Moonshot 月之暗面", Protocol.OPENAI,
            "https://api.moonshot.cn/v1",
            listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
            "platform.moonshot.cn → API Key", "cn"
        ),
        Preset(
            "siliconflow", "硅基流动 SiliconFlow", Protocol.OPENAI,
            "https://api.siliconflow.cn/v1",
            listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct"),
            "cloud.siliconflow.cn → API 密钥", "cn"
        ),
        Preset(
            "qwen", "通义千问 DashScope", Protocol.OPENAI,
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            listOf("qwen-plus", "qwen-max", "qwen-turbo"),
            "dashscope.console.aliyun.com → API-KEY", "cn", supportsVision = true
        ),
        Preset(
            "baichuan", "百川智能", Protocol.OPENAI,
            "https://api.baichuan-ai.com/v1",
            listOf("Baichuan4", "Baichuan3-Turbo"),
            "platform.baichuan-ai.com → API 密钥", "cn"
        ),
        Preset(
            "minimax", "MiniMax", Protocol.OPENAI,
            "https://api.minimax.chat/v1",
            listOf("abab6.5s-chat", "abab6.5-chat"),
            "platform.minimaxi.com → 接口密钥", "cn"
        ),
        Preset(
            "stepfun", "阶跃星辰 StepFun", Protocol.OPENAI,
            "https://api.stepfun.com/v1",
            listOf("step-2-16k", "step-1-8k"),
            "platform.stepfun.com → API Key", "cn", supportsVision = true
        ),
        // ————— 国际 —————
        Preset(
            "openai", "OpenAI", Protocol.OPENAI,
            "https://api.openai.com/v1",
            listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini"),
            "platform.openai.com → API keys", "global",
            supportsVision = true
        ),
        Preset(
            "anthropic", "Anthropic Claude", Protocol.ANTHROPIC,
            "https://api.anthropic.com/v1",
            listOf("claude-sonnet-4-20250514", "claude-3-5-haiku-20241022"),
            "console.anthropic.com → API keys", "global",
            supportsVision = true
        ),
        Preset(
            "gemini", "Google Gemini", Protocol.GEMINI,
            "https://generativelanguage.googleapis.com/v1beta",
            listOf("gemini-2.0-flash", "gemini-2.5-pro"),
            "aistudio.google.com → Get API key", "global",
            supportsVision = true
        ),
        Preset(
            "openrouter", "OpenRouter（聚合）", Protocol.OPENAI,
            "https://openrouter.ai/api/v1",
            listOf("anthropic/claude-sonnet-4", "deepseek/deepseek-chat", "google/gemini-2.0-flash-001"),
            "openrouter.ai → Keys", "global", supportsVision = true
        ),
        // ————— 本地 —————
        Preset(
            "ollama", "Ollama（本地）", Protocol.OPENAI,
            "http://127.0.0.1:11434/v1",
            listOf("qwen2.5:7b", "llama3.1:8b", "deepseek-r1:7b"),
            "本机运行 ollama serve，API Key 随便填（如 ollama）", "local"
        ),
        Preset(
            "custom", "自定义（任意 OpenAI 兼容）", Protocol.OPENAI,
            "https://",
            listOf(""),
            "填入你自己的端点（需含 /v1），适用于 vLLM / LM Studio / 自建网关", "local"
        ),
    )

    fun byKey(key: String): Preset? = all.firstOrNull { it.key == key }

    /** 区域分组标题 */
    fun regionLabel(region: String): String = when (region) {
        "cn" -> "国内服务"
        "global" -> "国际服务"
        else -> "本地 / 自定义"
    }

    /** 该预设是否已填入可用端点（自定义未填时不能直接用） */
    fun Preset.isUsable(): Boolean = baseUrl.length > 10 && defaultModel.isNotBlank()

    /**
     * 采样参数的合理默认值与范围 —— 让模型配置屏不用硬编码魔法数字。
     * 对"写界面"这种任务，温度偏高有创意、偏低更稳定；默认取 0.7。
     */
    object Sampling {
        const val TEMP_DEFAULT = 0.7f
        const val TEMP_MIN = 0f
        const val TEMP_MAX = 2f

        const val TOP_P_DEFAULT = 1.0f
        const val TOP_P_MIN = 0.01f
        const val TOP_P_MAX = 1f

        /** 0 表示不限制 */
        const val MAX_TOKENS_DEFAULT = 8192
        val MAX_TOKENS_CHOICES = listOf(2048, 4096, 8192, 16384, 32768, 0)

        /**
         * 单轮生成中工具决策的轮数。
         *
         * **0 = 不限制**（默认）：模型想查多少轮就查多少轮，自己用 NO_TOOLS / 直接成稿收尾；
         * 不再被任何固定数字腰斩。这个数字现在只是【软提醒阈值】——超过后轻推一次，
         * 绝不强制中断。真正的硬停止只有 AgentLoop 里的两道安全闸（死循环检测 + 总时长兜底），
         * 它们只拦失控、不拦正常调研。
         */
        const val MAX_TOOL_ROUNDS_DEFAULT = 0
        const val MAX_TOOL_ROUNDS_MIN = 0
        const val MAX_TOOL_ROUNDS_MAX = 64
        val MAX_TOOL_ROUNDS_CHOICES = listOf(0, 8, 16, 24, 32, 48, 64)

        /** 续写时喂给模型的尾部字符数（上下文窗口） */
        const val CONTEXT_CHARS_DEFAULT = 3000
        val CONTEXT_CHARS_CHOICES = listOf(1000, 2000, 3000, 4000, 6000, 8000)

        fun maxTokensLabel(v: Int): String = if (v <= 0) "不限制" else "$v tokens"

        /** 工具调用轮次展示：0 = 不限制（默认），正数 = 软提醒阈值（到点仅提醒、不强制中断） */
        fun maxToolRoundsLabel(v: Int): String =
            if (v <= 0) "不限制（模型自定）" else "$v 轮（超阈值仅提醒）"

        /**
         * 不同用途的推荐温度 —— 界面配置屏给出"稳定性 / 平衡 / 创造力"三档。
         */
        fun tempLabel(t: Float): String = when {
            t <= 0.3f -> "更稳定（少发挥）"
            t <= 0.85f -> "平衡（推荐）"
            else -> "更有创意（多发挥）"
        }
    }
}

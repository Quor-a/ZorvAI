package com.ai.assistance.quro.core.assistant

import android.content.Context
import com.ai.assistance.quro.core.privilege.QuroLSPosed
import com.ai.assistance.quro.core.privilege.QuroRootGateway
import com.ai.assistance.quro.core.privilege.QuroShizukuBridge
import com.ai.assistance.quro.core.adb.QuroAdbDebug
import com.ai.assistance.quro.core.mcp.QuroMcpClientPrefs
import com.ai.assistance.quro.core.memory.QuroMemoryRepository
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.skill.QuroSkillStore
import com.ai.assistance.quro.core.tools.QuroToolRouter
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「助手的一切」能力目录（净室实现）。
 *
 * 用户要求：厂商助手入口（小布 / 超级小爱 / Gemini / 一圈即搜 / 电源键）接进来之后，
 * 不能只是把一句话转发给一个裸 LLM，而要能调用并理解 **QuroAI 这个助手的全部**：
 *  · 运行环境与配置（跑在哪个进程、当前模型 / 服务商 / 人格 / 工具集开关）
 *  · 全部工具（含生图 / 翻译 / 写作 / 屏幕 / 语音 / 视频 / 知识库 / 终端 / 特权）
 *  · Skills、MCP 连接器、长期记忆与 RAG 知识库
 *  · 四条特权通道（Root / ADB / LSPosed / Shizuku）当前是否可用
 *  · 以及**厂商助手自身**的能力（小布记忆、技能、翻译、写作、生图等），
 *    让模型知道「我是从哪个入口被唤起、对方原本能做什么、我该用 QuroAI 的哪个能力去接住」。
 *
 * 这里只负责「盘点 + 序列化 + 提示词段」，不执行回合；执行在 [QuroAgentTurnRunner]。
 * 所有探测都是同步且非阻塞的：Root 只读缓存结果（绝不在这里 spawn su），
 * 其余走已有状态层，保证 system_server 侧调用 capabilities 不会卡住 binder 线程。
 */
object QuroAssistantCapabilities {

    /** 四条特权通道——按用户指定，不引入第五种通道。 */
    enum class Channel(val id: String, val label: String) {
        ROOT("root", "Root（su / Magisk / KernelSU）"),
        ADB("adb", "ADB（USB / 无线调试）"),
        LSPOSED("lsposed", "LSPosed / Vector（Xposed Hook）"),
        SHIZUKU("shizuku", "Shizuku（ADB 或 Root 启动）"),
    }

    data class ChannelState(
        val id: String,
        val label: String,
        /** null = 尚未实测，不得当成「不可用」展示。 */
        val available: Boolean?,
        val status: String,
        val detail: String,
    )

    /** 厂商助手自身的一项能力，以及 QuroAI 侧可完成同等任务的代表工具。 */
    data class VendorCapability(
        val id: String,
        val label: String,
        val description: String,
        val quroCounterparts: List<String>,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("label", label)
            put("description", description)
            put("quro_counterparts", JSONArray(quroCounterparts))
        }
    }

    /**
     * 各入口的厂商能力清单。这些是**入口侧可理解的能力**，不是通过 Hook 反向调用厂商私有实现：
     * 请求一旦接管，就由 QuroAI 运行时用自己的工具/Skills 完成，并把映射关系写进提示词，
     * 让模型知道「小布记忆 → memory_*」「生图 → image_gen」这一层对应关系。
     */
    val VENDOR_CAPABILITIES: Map<String, List<VendorCapability>> = mapOf(
        QuroAgentBridgeProtocol.SOURCE_BREENO to listOf(
            VendorCapability("breeno_memory", "小布记忆", "厂商侧的个人记忆 / 偏好 / 历史摘要", listOf("memory")),
            VendorCapability("breeno_skills", "小布技能", "厂商侧的技能与快捷指令", listOf("skill", "assistant_skill")),
            VendorCapability("breeno_translate", "小布翻译", "文本 / 对话实时翻译", listOf("translate")),
            VendorCapability("breeno_writing", "小布写作", "润色、续写、文案生成", listOf("writer", "enhanced_doc_create")),
            VendorCapability("breeno_image_gen", "小布生图", "文生图 / 图像创作", listOf("image_gen", "img_gen_provider")),
            VendorCapability("breeno_screen", "小布识屏", "屏幕内容理解与一圈即搜", listOf("screen", "ui_tree", "visual_analysis")),
            VendorCapability("breeno_voice", "小布语音", "语音输入与播报", listOf("speak", "audio_recognition")),
        ),
        QuroAgentBridgeProtocol.SOURCE_XIAOAI to listOf(
            VendorCapability("xiaoai_memory", "超级小爱记忆", "跨设备记忆与个人上下文", listOf("memory")),
            VendorCapability("xiaoai_skills", "超级小爱技能", "技能 / 快捷指令 / 联动", listOf("skill", "assistant_skill")),
            VendorCapability("xiaoai_translate", "超级小爱翻译", "同传与文本翻译", listOf("translate")),
            VendorCapability("xiaoai_writing", "超级小爱写作", "摘要、润色、创作", listOf("writer", "enhanced_doc_create")),
            VendorCapability("xiaoai_image_gen", "超级小爱生图", "文生图与图片理解", listOf("image_gen", "image_recognition")),
            VendorCapability("xiaoai_screen", "超级小爱识屏", "圈选识屏与屏幕问答", listOf("screen", "ui_tree")),
            VendorCapability("xiaoai_voice", "超级小爱语音", "连续语音对话", listOf("speak", "audio_recognition")),
        ),
        QuroAgentBridgeProtocol.SOURCE_GEMINI to listOf(
            VendorCapability("gemini_circle", "一圈即搜", "圈选屏幕内容发起搜索", listOf("screen", "web", "ui_tree")),
            VendorCapability("gemini_hotword", "Hey Google 热词", "息屏 / 亮屏语音唤起", listOf("speak", "audio_recognition")),
            VendorCapability("gemini_lens", "Google 智能镜头", "图像理解与检索", listOf("image_recognition", "web")),
        ),
        QuroAgentBridgeProtocol.SOURCE_CIRCLE to listOf(
            VendorCapability("circle_search", "一圈即搜", "圈选即搜的系统入口", listOf("screen", "web", "visual_analysis")),
        ),
        QuroAgentBridgeProtocol.SOURCE_POWER to listOf(
            VendorCapability("power_assistant", "长按电源键助手", "系统默认数字助理入口", listOf("screen", "speak")),
        ),
    )

    /**
     * 能力域 → 代表工具名。给模型的「用户说人话 → 我该用哪个能力」映射表，
     * 而不是把 200+ 工具名一股脑塞进提示词。
     */
    val CAPABILITY_DOMAINS: List<Pair<String, List<String>>> = listOf(
        "生图 / 视频创作" to listOf("image_gen", "img_gen_provider", "video_gen"),
        "翻译" to listOf("translate"),
        "写作 / 文档" to listOf("writer", "enhanced_doc_create", "aiwps_create", "workspace"),
        "屏幕理解 / GUI 操作" to listOf("screen", "ui_tree", "visual_analysis", "bg_screen"),
        "语音 / 音频" to listOf("speak", "audio_recognition"),
        "图像 / 视频理解" to listOf("image_recognition", "video_understanding"),
        "知识库 / 记忆 / RAG" to listOf("memory"),
        "技能 / MCP 连接器" to listOf("skill", "assistant_skill", "mcp", "cms"),
        "联网检索 / 浏览器" to listOf("web", "ai_browser", "http_request"),
        "文件 / 工作区 / 代码" to listOf("workspace", "file_read", "codecanvas_script"),
        "终端 / Linux / 沙箱" to listOf("terminal", "linux_env", "sandbox"),
        "设备控制 / 特权通道" to listOf("priv_exec", "priv_status", "adb_term", "lsposed", "root"),
    )

    /** 工具注册表缓存：构造一次即可，capabilities 可能被频繁读取。 */
    @Volatile
    private var cachedToolSnapshot: List<Triple<String, String, String>>? = null

    private fun toolSnapshot(ctx: Context): List<Triple<String, String, String>> {
        cachedToolSnapshot?.let { return it }
        return synchronized(this) {
            cachedToolSnapshot ?: runCatching {
                buildQuroRegistry(ctx.applicationContext).all().map { tool ->
                    Triple(tool.name, tool.category, tool.description)
                }
            }.getOrDefault(emptyList()).also { cachedToolSnapshot = it }
        }
    }

    fun invalidateToolCache() {
        cachedToolSnapshot = null
    }

    /** 四通道当前状态（同步、非阻塞，Root 一律用缓存值）。 */
    fun channels(ctx: Context): List<ChannelState> {
        val rootCached = runCatching { QuroRootGateway.cachedRootAvailable() }.getOrNull()
        val shizuku = runCatching { QuroShizukuBridge.state(ctx) }.getOrNull()
        val usb = runCatching { QuroAdbDebug.usbDebugEnabled(ctx) }.getOrNull()
        val frameworkConnected = runCatching { QuroLSPosed.isFrameworkConnected() }.getOrDefault(false)
        val frameworkLabel = runCatching { QuroLSPosed.frameworkLabel(ctx) }.getOrDefault("Xposed 框架")
        val api102 = runCatching { QuroLSPosed.supportsApi102(ctx) }.getOrDefault(false)
        val scopeSize = runCatching { QuroLSPosed.scopePackages().size }.getOrDefault(0)

        return listOf(
            ChannelState(
                id = Channel.ROOT.id,
                label = Channel.ROOT.label,
                available = rootCached,
                status = when (rootCached) {
                    true -> "已实测可用"
                    false -> "已实测不可用"
                    null -> "尚未验证（不主动触发 su）"
                },
                detail = "su / Magisk / KernelSU；经 QuroRootGateway 统一网关，Shizuku-root 优先降级到 su",
            ),
            ChannelState(
                id = Channel.ADB.id,
                label = Channel.ADB.label,
                available = if (usb == true) true else null,
                status = when {
                    usb == true -> "USB 调试已开启"
                    usb == false -> "USB 调试未开启"
                    else -> "状态未知"
                },
                detail = "本机 ADB 客户端 / 被电脑 adb connect；Shizuku 以 ADB 身份启动时即走此通道",
            ),
            ChannelState(
                id = Channel.LSPOSED.id,
                label = Channel.LSPOSED.label,
                available = frameworkConnected && api102,
                status = when {
                    !frameworkConnected -> "未收到框架服务连接"
                    !api102 -> "已连接但 API < 102，模块无法加载"
                    else -> "$frameworkLabel 已连接，作用域 $scopeSize 项"
                },
                detail = "Vector 2.2 + ZygiskNext/NeoZygisk 或 LSPosed 2.x；libxposed API 102 现代模块",
            ),
            ChannelState(
                id = Channel.SHIZUKU.id,
                label = Channel.SHIZUKU.label,
                available = shizuku?.available,
                status = shizuku?.details ?: "状态未知",
                detail = "可由 ADB 或 Root 启动；提供 UID 2000 / UID 0 的系统级 API 调用",
            ),
        )
    }

    /** 完整能力快照（跨进程返回给 Hook 侧 / 注入系统提示词）。 */
    fun snapshot(ctx: Context): JSONObject {
        val app = ctx.applicationContext
        val cfg = runCatching { QuroModelConfigRepository(app).load() }.getOrNull()
        val personaId = runCatching { com.ai.assistance.quro.core.QuroPersonaRepository(app).getActiveId() }
            .getOrDefault("")
        val skills = runCatching { QuroSkillStore.enabledListFor(app, personaId) }.getOrDefault(emptyList())
        val mcp = runCatching { QuroMcpClientPrefs.loadEnabled(app) }.getOrDefault(emptyList())
        val memories = runCatching { QuroMemoryRepository(app).loadAll().size }.getOrDefault(0)
        val tools = toolSnapshot(app)

        return JSONObject().apply {
            put("protocol_version", QuroAgentBridgeProtocol.VERSION)
            put("runtime", JSONObject().apply {
                put("package", QuroAgentBridgeProtocol.SELF_PACKAGE)
                put("process", "com.ai.assistance.quro（主进程）")
                put("engine", "QuroAssistant.ask + buildQuroRegistry + QuroAgentTurnRunner")
                put("provider", cfg?.provider ?: JSONObject.NULL)
                put("model", cfg?.model ?: JSONObject.NULL)
                put("temperature", cfg?.temperature ?: JSONObject.NULL)
                put("enable_tools", cfg?.enableTools ?: JSONObject.NULL)
                put("persona_id", personaId)
                put("tool_count", tools.size)
                put("skill_count", skills.size)
                put("mcp_server_count", mcp.size)
                put("memory_entry_count", memories)
                put("progressive_tool_router", QuroToolRouter.PROGRESSIVE)
                put("note", "工具全集、Skills、MCP、记忆、Soul 与系统提示词均在 app 主进程内可用；" +
                    "Hook 进程只负责把请求与入口上下文送进来并回写结果。")
            })
            put("channels", JSONArray().apply {
                channels(app).forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("label", c.label)
                        put("available", c.available ?: JSONObject.NULL)
                        put("status", c.status)
                        put("detail", c.detail)
                    })
                }
            })
            put("capability_domains", JSONArray().apply {
                CAPABILITY_DOMAINS.forEach { (domain, names) ->
                    put(JSONObject().apply {
                        put("domain", domain)
                        put("tools", JSONArray(names))
                    })
                }
            })
            put("tools", JSONArray().apply {
                tools.forEach { (name, category, description) ->
                    put(JSONObject().apply {
                        put("name", name)
                        put("category", category)
                        put("description", description.take(240))
                    })
                }
            })
            put("skills", JSONArray().apply {
                skills.take(64).forEach { s ->
                    put(JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("description", s.description.take(160))
                    })
                }
            })
            put("mcp_servers", JSONArray().apply {
                mcp.forEach { s ->
                    put(JSONObject().apply {
                        put("alias", s.alias)
                        put("kind", s.kind)
                    })
                }
            })
            put("entry_sources", JSONArray().apply {
                QuroAgentBridgeProtocol.SOURCES.forEach { src ->
                    put(JSONObject().apply {
                        put("source", src)
                        put("label", QuroAgentBridgeProtocol.sourceLabel(src))
                        put("vendor_capabilities", JSONArray().apply {
                            VENDOR_CAPABILITIES[src].orEmpty().forEach { put(it.toJson()) }
                        })
                    })
                }
            })
        }
    }

    /** 轻量运行时帧：让 Hook 侧 / 模型快速知道助手在哪、能做什么。 */
    fun runtimeFrame(ctx: Context, source: String): JSONObject {
        val app = ctx.applicationContext
        val cfg = runCatching { QuroModelConfigRepository(app).load() }.getOrNull()
        return JSONObject().apply {
            put("protocol_version", QuroAgentBridgeProtocol.VERSION)
            put("source", QuroAgentBridgeProtocol.normalizeSource(source))
            put("source_label", QuroAgentBridgeProtocol.sourceLabel(source))
            put("process", "com.ai.assistance.quro（主进程）")
            put("model", cfg?.model ?: JSONObject.NULL)
            put("provider", cfg?.provider ?: JSONObject.NULL)
            put("tool_count", toolSnapshot(app).size)
            put("channels", JSONArray().apply {
                channels(app).forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("available", c.available ?: JSONObject.NULL)
                    })
                }
            })
        }
    }

    /**
     * 注入系统提示词的能力感知段。
     *
     * 这是「让助手理解自己在哪里运行、这个入口原本有什么能力、我该用 QuroAI 的什么去接住」的关键：
     * 不注入这段，模型在厂商入口里会以为自己只有一段文本通道，进而回答「我不会生图 / 不能翻译」。
     */
    fun promptSection(ctx: Context, source: String): String {
        val app = ctx.applicationContext
        val src = QuroAgentBridgeProtocol.normalizeSource(source)
        val tools = toolSnapshot(app)
        val cfg = runCatching { QuroModelConfigRepository(app).load() }.getOrNull()
        val personaId = runCatching { com.ai.assistance.quro.core.QuroPersonaRepository(app).getActiveId() }
            .getOrDefault("")
        val skillCount = runCatching { QuroSkillStore.enabledListFor(app, personaId).size }.getOrDefault(0)
        val mcpCount = runCatching { QuroMcpClientPrefs.loadEnabled(app).size }.getOrDefault(0)
        val memoryCount = runCatching { QuroMemoryRepository(app).loadAll().size }.getOrDefault(0)

        return buildString {
            append("\n## 当前运行环境与入口\n")
            append("你正通过「${QuroAgentBridgeProtocol.sourceLabel(src)}」入口被唤起，")
            append("但你的完整运行时在 **Zorv AI（com.ai.assistance.quro）主进程**，不是厂商助手进程。\n")
            append("当前模型：${cfg?.model ?: "未配置"}（${cfg?.provider ?: "未知服务商"}）；")
            append("人格卡：$personaId；可用工具 ${tools.size} 个、Skills $skillCount 项、")
            append("MCP 连接器 $mcpCount 个、长期记忆 $memoryCount 条。\n")
            append("**这些能力全部可用**：需要时正常调用工具 / `skill` / `mcp` / `memory`，")
            append("不要因为入口是语音助手就回答「我做不到」。\n\n")

            append("### 能力域速查（用户口语 → 你的能力）\n")
            CAPABILITY_DOMAINS.forEach { (domain, names) ->
                append("- $domain：${names.joinToString()}\n")
            }
            append("\n")

            val vendor = VENDOR_CAPABILITIES[src].orEmpty()
            if (vendor.isNotEmpty()) {
                append("### 本入口的厂商能力映射\n")
                append("用户可能以为自己在使用下列厂商能力；请求已被接管，请用 Zorv AI 的对应能力完成，")
                append("不要声称自己没有这些能力，也不要复述这张映射表：\n")
                vendor.forEach { v ->
                    append("- ${v.label}（${v.description}）→ ${v.quroCounterparts.joinToString()}\n")
                }
                append("\n")
            }

            append("### 四条特权通道（按可用性决定是否调用）\n")
            channels(app).forEach { c ->
                val state = when (c.available) {
                    true -> "可用"
                    false -> "不可用"
                    null -> "未验证"
                }
                append("- ${c.label}：$state（${c.status}）\n")
            }
            append("通道不可用或状态未验证时不要假装已完成特权操作；可直接改用普通工具或如实说明。\n")
        }
    }
}

package com.ai.assistance.quro.core.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.core.directive.AssistantDirectiveBus
import com.ai.assistance.quro.util.QuroDiag
import java.io.File

/**
 * 内置能力表 —— ZorvAI 助手能力账本。
 *
 * 这份表就是"所有功能"的清单：语音 / 文本指令 / 分享 / 工具中心 / AI 工具等入口
 * 全部经 [AssistantSkillRegistry] 路由到同一张表，新增能力只注册一处。
 *
 * ## 明确不搬（诚实标注，不假装做了）
 * - **AI 接电话**（`BIND_CALL_SERVICE` / `CALL_PRIVILEGED`）：要系统签名级特权，普通应用拿不到。
 * - **OCar 车机通道**（`com.oplus.intent.action.OCAR`）：依赖 OPPO 车机协议栈。
 * - **支付结果页**：与本应用价值无关。
 * - **端侧唤醒词 / 声纹**：需要模型权重与常驻音频前处理，属独立工程。
 */
object AssistantBuiltinSkills {
    private const val TAG = "assistant-builtin"

    /** 驾驶模式偏好键（广场/磁贴可切，界面侧按需读取）。 */
    const val PREF_DRIVING = "quro_assistant_driving"
    private const val PREFS = "quro_assistant_flags"

    private fun flagsPrefs(ctx: Context) =
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()

    fun drivingModeOn(ctx: Context): Boolean =
        flagsPrefs(ctx)?.getBoolean(PREF_DRIVING, false) ?: false

    /** 幂等注册：多次调用不会重复插入（同 id 覆盖）。 */
    fun registerAll() {
        AssistantSkillRegistry.registerAll(skills())
        QuroDiag.log(TAG, "builtin skills registered total=${AssistantSkillRegistry.all().size}")
    }

    private fun skills(): List<AssistantSkill> = listOf(

        // ── 对话 ────────────────────────────────────────────────
        AssistantSkill(
            id = "chat.ask",
            title = "问 ZorvAI",
            summary = "把一句话交给 AI 处理（不确定用什么能力时的万能入口）",
            category = AssistantCategory.CHAT,
            keywords = listOf("问", "帮我", "查一下", "assistant", "ask", "zai"),
            external = true,
        ) { req ->
            val text = req.text.ifBlank { req.raw }
            if (text.isBlank()) {
                AssistantResult.fail(AssistantCodes.NO_TEXT, "没有要问的内容")
            } else {
                AssistantDirectiveBus.post(text, "skill:chat.ask")
                AssistantResult.ok(
                    "chat.ask",
                    "已投递给会话",
                    mapOf("chars" to text.length),
                    listOf(
                        AssistantAction(
                            "open-chat", "去对话看看", AssistantAction.KIND_DEEPLINK, "quro://assistant",
                            primary = true,
                        )
                    ),
                )
            }
        },

        AssistantSkill(
            id = "chat.explain-selection",
            title = "解释这段文字",
            summary = "对选中/分享进来的文本做解释（PROCESS_TEXT）",
            category = AssistantCategory.CHAT,
            keywords = listOf("解释这段", "explain selection", "这段什么意思"),
            external = true,
        ) { req ->
            val body = req.raw.ifBlank { req.text }
            if (body.isBlank()) {
                AssistantResult.fail(AssistantCodes.NO_TEXT, "没有收到选中的文本")
            } else {
                AssistantDirectiveBus.post("请解释下面这段内容，并给出一句总结：\n\n$body", "skill:explain-selection")
                AssistantResult.ok("chat.explain-selection", "已把选中的 ${body.length} 字发给 AI")
            }
        },

        // ── 终端（ZorvAI 自研强项）────────────
        AssistantSkill(
            id = "terminal.env-check",
            title = "检查终端环境",
            summary = "看内置 Ubuntu rootfs 与终端 bash 是否就绪（不用进终端页）",
            category = AssistantCategory.TERMINAL,
            keywords = listOf("终端环境", "rootfs", "环境检查", "terminal check"),
            external = true,
        ) { req ->
            val ctx = requireCtx()
            val rootfs = File(ctx.filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu")
            val bash = File(ctx.filesDir, "usr/bin/bash")
            val quroBash = File(ctx.filesDir, "usr/bin/quro-bash")
            val ready = rootfs.isDirectory && (rootfs.list()?.isNotEmpty() == true)
            val data = linkedMapOf<String, Any?>(
                "rootfsPath" to rootfs.absolutePath,
                "rootfsReady" to ready,
                "bash" to bash.exists(),
                "quroBash" to quroBash.exists(),
            )
            if (ready) {
                AssistantResult.ok("terminal.env-check", "终端环境就绪", data)
            } else {
                // 不给 deeplink 动作：`quro://terminal` 这个 host 尚未注册，
                // 给一个点了没反应的动作比不给更糟（用户会判定"按钮坏了"）。
                AssistantResult.fail(
                    AssistantCodes.ENV_NOT_READY,
                    "终端 rootfs 未安装或为空：${rootfs.absolutePath}。去「工具箱 → 包管理」安装。",
                    data = data,
                )
            }
        },

        // ── 设备 ────────────────────────────────────────────────
        AssistantSkill(
            id = "device.find-phone",
            title = "我手机呢",
            summary = "最大声闹铃 + 持续震动 + 亮屏",
            category = AssistantCategory.DEVICE,
            keywords = listOf("找手机", "我手机呢", "find my phone", "手机在哪", "查手机"),
            external = true,
        ) { req ->
            val secs = req.arg("seconds")?.toIntOrNull() ?: 30
            FindMyPhone.start(requireCtx(), secs)
        },

        AssistantSkill(
            id = "device.find-phone.stop",
            title = "停止找手机",
            summary = "立刻停掉闹铃与震动",
            category = AssistantCategory.DEVICE,
            keywords = listOf("别响了", "停止找手机", "stop ringing"),
            external = true,
        ) { FindMyPhone.stop("skill") },

        AssistantSkill(
            id = "device.info",
            title = "设备与环境信息",
            summary = "机型 / 系统版本 / 应用版本 / ABI，出问题时报数用",
            category = AssistantCategory.DEVICE,
            keywords = listOf("设备信息", "机型", "系统版本", "device info"),
            external = true,
        ) { req ->
            val ctx = requireCtx()
            val pkg = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
            AssistantResult.ok(
                "device.info",
                "已读取设备信息",
                linkedMapOf(
                    "model" to Build.MODEL,
                    "manufacturer" to Build.MANUFACTURER,
                    "android" to Build.VERSION.RELEASE,
                    "sdk" to Build.VERSION.SDK_INT,
                    "abis" to Build.SUPPORTED_ABIS.joinToString(","),
                    "appVersion" to (pkg?.versionName ?: "?"),
                    "versionCode" to (pkg?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: 0L),
                ),
            )
        },

        AssistantSkill(
            id = "device.driving-mode",
            title = "驾驶模式",
            summary = "大字 + 语音优先的免手操作形态",
            category = AssistantCategory.DEVICE,
            keywords = listOf("驾驶模式", "开车模式", "driving"),
            external = true,
        ) { req ->
            val ctx = requireCtx()
            val want = when (req.arg("on")?.lowercase() ?: req.text.lowercase()) {
                "off", "false", "0", "关" -> false
                "on", "true", "1", "开" -> true
                else -> !drivingModeOn(ctx)
            }
            flagsPrefs(ctx)?.edit()?.putBoolean(PREF_DRIVING, want)?.apply()
            QuroDiag.log(TAG, "driving-mode=$want")
            AssistantResult.ok(
                "device.driving-mode",
                if (want) "驾驶模式已开：界面放大、回复朗读优先" else "驾驶模式已关",
                mapOf("driving" to want),
            )
        },

        // ── 系统 / 中转 ──────────────────────────────────────────
        AssistantSkill(
            id = "sys.relay-submit",
            title = "提交后台任务",
            summary = "外部把长任务交给宿主在后台跑，跑完回执广播（SUBMIT_BACKGROUND_TASK_RELAY）",
            category = AssistantCategory.SYSTEM,
            keywords = listOf("后台任务", "relay", "提交任务"),
            external = false,
        ) { req ->
            val target = req.arg("skillId") ?: req.arg("target") ?: ""
            if (target.isBlank()) {
                AssistantResult.fail(AssistantCodes.SKILL_BAD_ARGS, "缺少 skillId：要跑哪个能力？")
            } else {
                BackgroundTaskRelay.submit(
                    requireCtx(),
                    target,
                    req.args,
                    req.requestId.ifBlank { req.arg("requestId").orEmpty() },
                    req.arg("targetPackage"),
                )
            }
        },

        AssistantSkill(
            id = "sys.diagnostics",
            title = "导出诊断目录",
            summary = "拿到可发出来的诊断路径",
            category = AssistantCategory.SYSTEM,
            keywords = listOf("诊断", "日志", "diagnostics", "log"),
            external = true,
        ) { req ->
            val ctx = requireCtx()
            val dir = File(ctx.filesDir, "diag")
            val exists = dir.isDirectory
            val files = if (exists) dir.listFiles()?.size ?: 0 else 0
            AssistantResult.ok(
                "sys.diagnostics",
                "诊断目录：${dir.absolutePath}（$files 个文件）",
                mapOf("path" to dir.absolutePath, "exists" to exists, "files" to files),
            )
        },

        // ── 跨设备 ──────────────────────────────────────────────
        AssistantSkill(
            id = "link.peers",
            title = "看附近的 Agent",
            summary = "列出已发现可互联的 Agent（agents.proto 的发现结果）",
            category = AssistantCategory.LINK,
            keywords = listOf("附近设备", "peers", "agent 列表", "互联设备"),
            external = true,
        ) { req ->
            val ctx = requireCtx()
            if (AgentLink.peers().isEmpty()) AgentLink.registerSelf(ctx)
            val peers = AgentLink.peers()
            AssistantResult.ok(
                "link.peers",
                "已知 ${peers.size} 个 Agent",
                data = mapOf(
                    "count" to peers.size,
                    "agents" to peers.joinToString(" | ") { "${it.displayName}(${it.strategy.wire}, caps=${it.capabilities.size})" },
                ),
            )
        },

        AssistantSkill(
            id = "link.discover",
            title = "开始发现设备",
            summary = "启动局域网 Agent 发现（无传输实现时会明确报错，不会静静空着）",
            category = AssistantCategory.LINK,
            keywords = listOf("发现设备", "discover", "扫描设备"),
            external = true,
        ) { req ->
            val ctx = requireCtx()
            val started = AgentLink.startDiscovery(ctx)
            if (started.isEmpty()) {
                // 不假装在扫描：没有任何 adapter 就是没有，明确说清楚，并列出已注册的
                AssistantResult.fail(
                    AssistantCodes.UNSUPPORTED,
                    "没有任何已注册的传输实现（已注册：${AgentLink.availableStrategies().map { it.wire }}）。" +
                        "协议与总线已就绪，真发现需要接 AgentTransportAdapter。",
                    data = mapOf(
                        "strategies" to AgentLink.availableStrategies().joinToString(",") { it.wire },
                        "agents" to AgentLink.peers().size,
                    ),
                )
            } else {
                AssistantResult.ok(
                    "link.discover",
                    "已启动发现：${started.joinToString("+") { it.wire }}",
                    data = mapOf("strategies" to started.joinToString(",") { it.wire }),
                )
            }
        },

        // ── 自省 ────────────────────────────────────────────────
        AssistantSkill(
            id = "skill.catalog",
            title = "能力清单",
            summary = "列出 ZorvAI 当前全部能力",
            category = AssistantCategory.KNOWLEDGE,
            keywords = listOf("能力清单", "会什么", "skills", "能做什么"),
            external = true,
        ) { req ->
            val all = AssistantSkillRegistry.all()
            val byCat = all.groupBy { it.category }
            AssistantResult.ok(
                "skill.catalog",
                "共 ${all.size} 项能力，${byCat.size} 个分类",
                data = byCat.entries.associate { (c, list) ->
                    c.label to list.joinToString("、") { it.title }
                },
            )
        },
    )

    /**
     * 技能执行期才拿 Context —— 注册发生在启动很早的时候，那时 appCtx 可能还没被赋值。
     * 拿不到就明确失败，绝不 NPE 崩在广播接收器里。
     */
    private fun requireCtx(): Context =
        com.ai.assistance.quro.activity.QuroApplication.appCtx
            ?: throw IllegalStateException("QuroApplication.appCtx 尚未就绪")
}

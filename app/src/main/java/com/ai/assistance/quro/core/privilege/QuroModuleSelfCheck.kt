package com.ai.assistance.quro.core.privilege

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.ai.assistance.quro.core.assistant.QuroAgentBridgeInbox
import com.ai.assistance.quro.core.assistant.QuroAgentBridgeProtocol
import com.ai.assistance.quro.core.xposed.QuroHookReport
import com.ai.assistance.quro.core.xposed.QuroModuleConfig
import com.ai.assistance.quro.core.xposed.QuroModulePrefs
import com.ai.assistance.quro.core.xposed.QuroModuleServiceState

/**
 * 「为什么开了还是没生效」的一页自检。
 *
 * 之前的界面只会告诉用户「缺少作用域」这一条结论，用户没法自己往下排查：
 * 是模块没启用？作用域没勾？目标进程没重启？还是钩子根本没命中这个版本的小布？
 * 这里把整条链路按顺序摆出来，每一环给「已满足 / 未满足 / 本机不适用」三态，
 * 这样「不能用」至少能定位到具体一环，而不是一个笼统的红字。
 *
 * 只读：不改任何配置，不触发 Hook，不写系统设置。
 */
object QuroModuleSelfCheck {

    /** 生成可直接展示 / 复制给他人排查的报告。 */
    fun report(ctx: Context): String {
        val status = QuroModuleServiceState.status()
        val installed = QuroModuleConfig.installedPackages(ctx.packageManager)
        val local = QuroModuleConfig.DECLARED_SCOPE.filter {
            QuroModuleConfig.isTargetPresent(it, installed)
        }
        val configured = local.count { it in status.scope }

        return buildString {
            appendLine("【设备】${romLabel()} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine()

            // ① 框架
            appendLine("① 框架")
            if (!status.connected) {
                appendLine("  ✗ 未收到框架服务连接：本模块的所有 Hook 都不会运行")
                appendLine("    → 在管理器里启用本模块并重启手机；若使用 Vector，管理器可能无桌面图标")
            } else {
                val name = status.frameworkName?.takeIf { it.isNotBlank() } ?: QuroLSPosed.frameworkLabel(ctx)
                appendLine("  ✓ 已连接：$name ${status.frameworkVersion ?: ""}（API ${status.apiVersion}）")
                appendLine("    注：连接只代表框架把配置通道推给了本应用，不代表模块已在目标进程生效")
            }
            appendLine()

            // ② 作用域
            appendLine("② 作用域（本机适用 $configured/${local.size} 项）")
            appendLine("  本模块声明 staticScope=true：scope.list 已经是完整清单。")
            appendLine("  管理器里若没有可勾的列表，属正常，不需要手动勾；有列表才需要逐项确认。")
            local.forEach { pkg ->
                val on = pkg in status.scope
                val loaded = status.isScopeActive(pkg)
                val mark = if (on) "✓" else "✗"
                val tail = when {
                    !on -> "未勾选 → 管理器里勾上并重启目标应用"
                    loaded -> "Hook 已加载"
                    else -> "已勾选，但目标进程还没加载模块 → 重启该应用或重启手机"
                }
                appendLine("  $mark ${QuroModuleConfig.scopeLabel(pkg)}：$tail")
            }
            val absent = QuroModuleConfig.DECLARED_SCOPE.filterNot { it in local }
            if (absent.isNotEmpty()) {
                appendLine("  · 本机未安装（不需要勾选）：${absent.joinToString("、") { QuroModuleConfig.scopeLabel(it) }}")
            }
            appendLine()

            // ③ 开关
            appendLine("③ 开关")
            QuroModuleConfig.MAIN_SWITCHES.forEach { def ->
                val on = QuroModulePrefs.isEnabled(def.key)
                val gap = if (on) status.unmetScope(def, installed) else emptyList()
                val tail = when {
                    !on -> "已关闭"
                    gap.isEmpty() -> "已开启 · 条件满足"
                    else -> "已开启 · 还缺 ${gap.joinToString()}"
                }
                appendLine("  ${if (on) "✓" else "·"} ${def.title}：$tail")
            }
            appendLine()

            // ④ 钩子安装账本（Hook 进程写进系统设置，应用直接读）
            appendLine("④ 钩子安装账本")
            val parsed = QuroHookReport.read(ctx)
            if (parsed == null) {
                appendLine("  · 没有任何账本 → 模块尚未在任何目标进程里跑起来")
                appendLine("    这条为空说明「连 ModuleMain.onModuleLoaded 都没执行」，")
                appendLine("    先解决 ①②（启用模块 + 勾作用域 + 重启），不要怀疑功能本身")
            } else {
                appendLine("  更新时间：${parsed.stamp(parsed.updatedAt)}")
                parsed.groupNames().forEach { group ->
                    val (installed, missing, failed, skipped) = parsed.counts(group)
                    appendLine("  · ${groupLabel(group)}：装上 $installed / 缺失 $missing / 失败 $failed / 跳过 $skipped")
                    parsed.problems(group).take(4).forEach { appendLine("      ✗ $it") }
                }
                val events = parsed.events()
                appendLine("  最近触发（${events.size} 条）：")
                if (events.isEmpty()) {
                    appendLine("    · 一条都没有 → 钩子装上了但系统/厂商从没走到这些入口")
                    appendLine("      电源键这类入口需要「先按一次」才会产生记录")
                } else {
                    events.take(6).forEach { (at, what, detail) ->
                        appendLine("    · ${parsed.stamp(at)} [$what] $detail")
                    }
                }
            }
            appendLine()

            // ⑤ 最近一次真实接管
            appendLine("⑤ 最近接管")
            val last = QuroAgentBridgeInbox.recent(ctx, 1).firstOrNull()
            if (last == null) {
                appendLine("  · 没有任何接管记录 → 厂商助手入口没有把请求交过来")
                appendLine("    小布/小爱入口需先「已勾选 + 进程已加载模块 + 开关开启」，再在厂商助手里正常说一句话")
            } else {
                appendLine(
                    "  ${if (last.ok) "✓" else "✗"} ${QuroAgentBridgeProtocol.sourceLabel(last.source)} · " +
                        "${if (last.ok) "成功" else "失败"} · ${last.elapsedMs}ms",
                )
                appendLine("    请求：${last.request.take(60)}")
                appendLine("    ${if (last.ok) "回复" else "错误"}：${(if (last.ok) last.reply else last.error).take(80)}")
            }
            appendLine()
            appendLine("⑥ 判定顺序")
            appendLine("  ①框架 → ②作用域 → ③开关 → ④钩子装没装上/有没有被调用 → ⑤请求有没有交过来")
            appendLine("  最后一环为空时，问题一定在前一环；不要跳过 ④ 去猜功能。")
        }
    }

    private fun groupLabel(group: String): String = when (group) {
        "SystemServer" -> "system_server"
        "com.heytap.speechassist" -> "小布"
        "com.miui.voiceassist" -> "超级小爱"
        "com.android.systemui" -> "系统界面"
        "com.google.android.googlequicksearchbox" -> "Google App"
        "com.coloros.colordirectservice" -> "ColorOS 识屏"
        "com.miui.home", "com.mi.android.globallauncher" -> "小米桌面"
        else -> group
    }

    /** 复制报告，方便贴给开发者定位（不包含任何账号 / 聊天内容）。 */
    fun copy(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Zorv AI 自检", report(ctx)))
        true
    }.getOrDefault(false)

    /** ROM 家族标签，仅用于报告头；读不到属性就退回 Build 字段，绝不因此失败。 */
    private fun romLabel(): String {
        val brand = Build.BRAND.orEmpty()
        val display = Build.DISPLAY.orEmpty()
        val oplus = readProperty("ro.build.version.oplusrom").orEmpty()
        val mi = readProperty("ro.mi.os.version.name")
            ?: readProperty("ro.miui.ui.version.name").orEmpty()
        return when {
            oplus.isNotEmpty() -> "ColorOS/OPPO（$oplus）"
            mi.isNotEmpty() -> "HyperOS/小米（$mi）"
            display.contains("HyperOS", true) -> "HyperOS"
            display.contains("ColorOS", true) -> "ColorOS"
            brand.contains("Xiaomi", true) || brand.contains("Redmi", true) -> "MIUI/HyperOS"
            else -> brand.ifBlank { "未知 ROM" }
        }
    }

    private fun readProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

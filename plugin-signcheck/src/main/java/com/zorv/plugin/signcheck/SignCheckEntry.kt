package com.zorv.plugin.signcheck

import com.ai.assistance.quro.plugin.contract.ParamType
import com.ai.assistance.quro.plugin.contract.PluginContext
import com.ai.assistance.quro.plugin.contract.PluginEntry
import com.ai.assistance.quro.plugin.contract.ToolResult
import com.ai.assistance.quro.plugin.dsl.plugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 签名核验插件。
 *
 * 它回答一个具体问题：**这个 APK 和正在跑的宿主，是不是同一把钥匙签的？**
 * 宿主接受插件的唯一条件就是证书 SHA-256 相同（`PluginInstaller.sameSignature()`），
 * 所以当某个插件莫名装不上时，这个插件能把「是谁的问题」定位到具体的 APK 与指纹上。
 *
 * 注册了 5 个 AI 工具、1 条斜杠指令、1 个界面。
 */
class SignCheckEntry : PluginEntry {

    override fun onCreate(ctx: PluginContext) {
        ctx.log("SignCheck", "插件启动 v${ctx.pluginVersion}")

        plugin(ctx) {

            // ---------- 1. 宿主自身签名 ----------
            aiTool(
                name = "sig_host",
                description = "查询 ZorvAI 宿主自身的包名、版本与签名证书 SHA-256 指纹。" +
                    "当用户问「宿主是什么签名」「当前 App 用哪把钥匙签的」「宿主的签名指纹是多少」时调用。"
            ) {
                execute {
                    ToolResult.text(withContext(Dispatchers.IO) {
                        SignCheck.detail(SignCheck.hostSig(ctx.appContext)).let { "═══ 宿主 ═══\n$it" }
                    })
                }
            }

            // ---------- 2. 任意 APK 的签名 ----------
            aiTool(
                name = "sig_apk",
                description = "读取设备上任意 APK 文件的包名、版本与签名证书 SHA-256 指纹，" +
                    "能判断该 APK 是否签名以及用的是哪把密钥。" +
                    "当用户说「看看这个 APK 的签名」「这个插件包签了吗」「检查下载的 APK 签名」时调用。"
            ) {
                param(
                    "path", ParamType.STRING,
                    "APK 在设备上的绝对路径，例如 /storage/emulated/0/Download/plugin.apk"
                )
                execute { args ->
                    val path = args.string("path").trim()
                    if (path.isBlank()) {
                        ToolResult.error("缺少参数 path（APK 的绝对路径）")
                    } else {
                        ToolResult.text(withContext(Dispatchers.IO) {
                            SignCheck.detail(SignCheck.apkAt(ctx.appContext, File(path)))
                        })
                    }
                }
            }

            // ---------- 3. 两个 APK 互相比对 ----------
            aiTool(
                name = "sig_match",
                description = "比对两个 APK 的签名证书是否一致，并说明宿主会不会放行安装。" +
                    "当用户说「这两个包签名一样吗」「插件和宿主是不是同签名」「为什么这个插件装不上」时调用。"
            ) {
                param("path_a", ParamType.STRING, "第一个 APK 的绝对路径")
                param("path_b", ParamType.STRING, "第二个 APK 的绝对路径")
                execute { args ->
                    val a = args.string("path_a").trim()
                    val b = args.string("path_b").trim()
                    if (a.isBlank() || b.isBlank()) {
                        ToolResult.error("需要同时提供 path_a 与 path_b")
                    } else {
                        ToolResult.text(withContext(Dispatchers.IO) {
                            val sa = SignCheck.apkAt(ctx.appContext, File(a), File(a).name)
                            val sb = SignCheck.apkAt(ctx.appContext, File(b), File(b).name)
                            buildString {
                                append("A  ").append(sa.title).append("  ").append(sa.short).append('\n')
                                append("B  ").append(sb.title).append("  ").append(sb.short).append('\n')
                                append("结论：").append(SignCheck.verdict(sa, sb))
                            }
                        })
                    }
                }
            }

            // ---------- 4. 本插件自检 ----------
            aiTool(
                name = "sig_self",
                description = "核验本插件自身的签名，并与宿主比对，给出宿主是否会放行安装的结论。" +
                    "当用户说「这个插件自己签名对吗」「自检一下插件签名」时调用。"
            ) {
                execute {
                    ToolResult.text(withContext(Dispatchers.IO) {
                        val app = ctx.appContext
                        val host = SignCheck.hostSig(app)
                        val self = SignCheck.apkAt(
                            app, SignCheck.selfApk(app, ctx.pluginId), "本插件"
                        )
                        buildString {
                            append("═══ 本插件 ═══\n").append(SignCheck.detail(self)).append("\n\n")
                            append("═══ 宿主 ═══\n")
                            append("包名：").append(host.packageName ?: "—").append('\n')
                            append("版本：").append(host.versionName ?: "—").append('\n')
                            append("签名：").append(host.fingerprint ?: "未签名")
                            append("\n\n结论：").append(SignCheck.verdict(host, self))
                        }
                    })
                }
            }

            // ---------- 5. 全量扫描已装插件 ----------
            aiTool(
                name = "sig_scan",
                description = "扫描宿主 plugins 目录下全部已装插件，逐个与宿主比对签名，" +
                    "列出哪些插件的签名与宿主不一致（这类插件会被拒绝安装）。" +
                    "当用户说「扫一下所有插件签名」「哪些插件签名有问题」「插件都装得上吗」时调用。"
            ) {
                execute {
                    ToolResult.text(withContext(Dispatchers.IO) {
                        SignCheck.fullReport(ctx.appContext, ctx.pluginId)
                    })
                }
            }

            // ---------- 斜杠指令 ----------
            command("sigcheck", "/sigcheck  核验宿主与全部已装插件的签名一致性") { _ ->
                // 这里同步执行：只读少量 APK 的清单，耗时在毫秒级；
                // 用 runBlocking 反而会在主线程上等待，是 ANR 反模式
                val report = SignCheck.fullReport(ctx.appContext, ctx.pluginId)
                ctx.log("command", report.replace('\n', ' '))
                true
            }

            // ---------- 界面 ----------
            uiSurface(
                id = "signcheck_panel",
                label = "签名核验",
                title = "签名核验面板",
                build = { actCtx, host -> SignCheckPanel(actCtx, host, ctx.pluginId) },
                onRelease = {
                    // 无 WebView / 无监听器 / 无计时器，没有需要释放的资源；
                    // 保留空实现是为了给示例插件一个正确的示范位置
                }
            )
        }
    }

    override fun onDestroy(ctx: PluginContext) {
        ctx.unregisterAll()
    }
}

package com.ai.assistance.quro.etalib.hook.hyperos

import android.content.Context
import android.os.Bundle
import io.github.libxposed.api.XposedModule
import com.ai.assistance.quro.etalib.config.PowerAssistantTarget
import com.ai.assistance.quro.etalib.config.Prefs
import com.ai.assistance.quro.etalib.core.HookInstallation
import com.ai.assistance.quro.etalib.core.HookRegistrar
import com.ai.assistance.quro.etalib.core.HookSupport
import com.ai.assistance.quro.etalib.core.ModuleLogger
import com.ai.assistance.quro.etalib.hook.system.AssistantManager

internal object HyperOsPowerHooks {
    fun install(module: XposedModule, logger: ModuleLogger, loader: ClassLoader): HookInstallation {
        val hooks = HookRegistrar(module, logger, "HyperOsPower")
        return hooks.install {
            val type = HookSupport.findClassOrNull(loader, "com.miui.server.input.util.ShortCutActionsUtils")
            if (type == null) {
                skipped("hyperos.power-shortcut", "ShortCutActionsUtils", "当前系统未提供 HyperOS 快捷动作分发器")
                return@install
            }
            val contextField = HookSupport.findField(type, "mContext")
            if (contextField == null || !Context::class.java.isAssignableFrom(contextField.type)) {
                missing("hyperos.power-shortcut", "ShortCutActionsUtils.mContext", "HyperOS: 快捷动作分发器缺少 Context")
                return@install
            }
            val parameters = arrayOf(String::class.java, String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType!!)
            for (signature in listOf(parameters, parameters + String::class.java)) {
                val method = HookSupport.findMethod(type, "triggerFunction", *signature)
                val id = "hyperos.power-shortcut-${signature.size}"
                if (method == null || method.returnType != Boolean::class.javaPrimitiveType) {
                    missing(id, "ShortCutActionsUtils.triggerFunction/${signature.size}", "HyperOS: 快捷动作分发签名 ${signature.size} 不匹配")
                    continue
                }
                intercept(id, method, "ShortCutActionsUtils.triggerFunction/${signature.size}") { chain ->
                    // 同时匹配功能与来源，不能把关机菜单、SOS 或其他快捷动作改成助理。
                    if (!HyperOsPowerPolicy.isAssistantShortcut(chain.getArg(0) as? String, chain.getArg(1) as? String)) {
                        return@intercept chain.proceed()
                    }
                    val target = Prefs.powerAssistantTarget()
                    val context = contextField.get(chain.getThisObject()) as? Context
                    if (target == PowerAssistantTarget.OEM || context == null) return@intercept chain.proceed()
                    if (AssistantManager.showAssistantSession(context, target, hooks.logger, "HyperOsPower", logFailures = true)) {
                        true
                    } else chain.proceed()
                }
            }
        }
    }
}

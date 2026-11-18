package com.ai.assistance.quro.etalib.hook.system

import com.ai.assistance.quro.etalib.hook.hyperos.HyperOsPowerHooks
import com.ai.assistance.quro.etalib.core.HookInstallation
import com.ai.assistance.quro.etalib.core.ModuleLogger

import io.github.libxposed.api.XposedModule

internal object SystemServerHooks {

    fun install(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation = HookInstallation.combine(
        group = "SystemServer",
        installations = listOf(
            AccessibilityProtectionHooks.install(module, logger, classLoader),
            ContextualSearchHooks.install(module, logger, classLoader),
            AssistantManager.install(module, logger, classLoader),
            HotwordSelfHealHooks.install(module, logger, classLoader),
            PowerHooks.install(module, logger, classLoader),
            HyperOsPowerHooks.install(module, logger, classLoader),
            VirtualDisplaySystemHooks.install(module, logger, classLoader)
        )
    )
}

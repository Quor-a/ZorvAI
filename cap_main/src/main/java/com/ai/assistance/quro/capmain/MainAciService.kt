package com.ai.assistance.quro.capmain

import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.BaseAidlAciService
import ai.aidl.aci.core.Capability
import com.ai.assistance.quro.capmain.init.installMainCapabilities
import com.ai.assistance.quro.libaci.AciRouter
import com.ai.assistance.quro.libaci.CapabilityRegistry
import com.ai.assistance.quro.libaci.PermissionGuard

/**
 * 主应用业务能力受控端 Service（新增子模块，非重构）。
 *
 * 粘合层：只负责把框架（BaseAidlAciService + AciRouter + PermissionGuard + CapabilityRegistry）
 * 与本应用的业务能力（:cap_main 的 Handler）接起来，自身不含业务代码。
 *
 * 与既有 .service.QuroMainAciService 并存：两者是不同的 AIDL Service、不同能力集、
 * 不同 intent-filter 端点；本 Service 专注 main.* 业务能力，互不干扰。
 */
class MainAciService : BaseAidlAciService() {

    override fun onCreate() {
        // 确保能力在 BaseAidlAciService.onCreate() -> onCreateCapabilities() 之前注册就绪。
        installMainCapabilities()
        super.onCreate()
    }

    /** 把注册中心里的能力声明暴露给 AI 中枢（getCapabilities 据此生成 prompt tools 段）。 */
    override fun onCreateCapabilities(capabilities: MutableList<Capability>) {
        for (handler in CapabilityRegistry.all()) {
            capabilities.add(handler.spec.toCapability())
        }
    }

    /** 同步调用：交给 AciRouter 统一派发（含能力存在性校验 + 异常兜底）。 */
    override fun onCall(request: AidlAciRequest): AidlAciResponse = AciRouter.dispatch(request)

    /** 调用方鉴权：包名白名单 + 高危能力 CALL_DANGEROUS 闸门。 */
    override fun onCheckPermission(request: AidlAciRequest, callerPkg: String?): Boolean =
        PermissionGuard.allow(this, request, callerPkg)
}

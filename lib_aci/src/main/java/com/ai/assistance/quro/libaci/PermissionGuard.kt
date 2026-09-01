package com.ai.assistance.quro.libaci

import ai.aidl.aci.core.AidlAciRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder

/**
 * 调用方鉴权守卫。
 *
 * 规则：
 * 1. 包名白名单：只有本应用自身及其浏览器子模块可调用（与 QuroMainAciService 一致）；
 * 2. 高危能力（spec.dangerous）：额外要求调用方持有 ai.aci.permission.CALL_DANGEROUS
 *    （manifest protectionLevel=dangerous，安装时/运行时需要用户授予），作为"二次确认"的系统级闸门。
 *    业务层真正的 UI 二次确认由调用方（AI 中枢）在发起前完成，这里做的是服务端兜底拦截。
 *
 * BaseAidlAciService.onCheckPermission(req, callerPkg) 会把调用方包名传进来，
 * 这里再配合 Context.checkPermission(..., Binder.getCallingUid()) 校验危险权限。
 */
object PermissionGuard {

    /** 允许调用本受控端 Service 的调用方包名白名单。 */
    private val CONTROLLER_PKGS: Set<String> = setOf(
        "com.ai.assistance.quro",
        "com.ai.assistance.quro.browser"
    )

    /** 高危能力所需的权限（与 :app manifest 定义一致）。 */
    private const val DANGEROUS_PERMISSION = "ai.aci.permission.CALL_DANGEROUS"

    /**
     * @param context  受控端 Service 自身（Context）
     * @param req      请求
     * @param callerPkg 调用方包名（来自 Binder UID 反查，由基类传入）
     * @return true = 放行
     */
    fun allow(context: Context, req: AidlAciRequest, callerPkg: String?): Boolean {
        if (callerPkg == null) return false
        if (callerPkg !in CONTROLLER_PKGS) return false

        val handler = CapabilityRegistry.get(req.capability)
        if (handler != null && handler.spec.dangerous) {
            // 危险能力：要求调用方持有 CALL_DANGEROUS 权限
            val granted = context.checkPermission(
                DANGEROUS_PERMISSION,
                Binder.getCallingPid(),
                Binder.getCallingUid()
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }
}

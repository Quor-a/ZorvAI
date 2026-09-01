package com.ai.assistance.quro.libaci

import ai.aidl.aci.core.AidlAciError
import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import android.os.Bundle

/**
 * 能力路由器：AIDL 请求 → 业务 Handler 的唯一派发入口。
 *
 * 职责：
 * 1. 解析 req.capability；
 * 2. 查 CapabilityRegistry 找对应 Handler；
 * 3. 调用 handler.handle(params)，把结果 Bundle 包成 AidlAciResponse.success；
 * 4. 统一错误码映射：capability 缺失 → CAPABILITY_NOT_FOUND，参数/业务异常 → INTERNAL_ERROR。
 *
 * 鉴权（onCheckPermission）由 BaseAidlAciService.dispatch 在调用 onCall 之前完成，
 * 这里不再重复做包名白名单，只做能力存在性 + 业务异常兜底。
 */
object AciRouter {

    fun dispatch(req: AidlAciRequest): AidlAciResponse {
        val id = req.capability
        if (id.isNullOrBlank()) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "capability is null or empty")
        }

        val handler = CapabilityRegistry.get(id)
            ?: return AidlAciResponse.error(
                AidlAciError.CAPABILITY_NOT_FOUND,
                "unknown capability: $id"
            )

        return try {
            val params = req.params ?: Bundle.EMPTY
            val result = handler.handle(params)
            AidlAciResponse.success(result ?: Bundle.EMPTY)
        } catch (e: Throwable) {
            // 业务 Handler 内部未捕获的异常统一兜成 INTERNAL_ERROR，绝不抛给 Binder 线程。
            AidlAciResponse.error(
                AidlAciError.INTERNAL_ERROR,
                e.message ?: "handler '${handler.spec.id}' failed: ${e.javaClass.simpleName}"
            )
        }
    }
}

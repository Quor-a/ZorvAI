package com.ai.assistance.quro.core.aidlaci

import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.IAidlAciCallback
import ai.aidl.aci.core.IAidlAciService
import ai.aci.core.ACIRequest as LegacyACIRequest
import ai.aci.core.ACIResponse as LegacyACIResponse
import ai.aci.core.IACICallback as LegacyIACICallback
import ai.aci.core.IACIService as LegacyIACIService
import android.os.IBinder
import android.os.RemoteException

/**
 * ACI 服务代理统一抽象：屏蔽底层是「新契约 ai.aidl.aci.core」还是「旧契约 ai.aci.core」，
 * 控制端全部面向本接口编程（发现 / 调用 / 心跳），无需感知对端版本。
 *
 * 背景：控制端在「ACI → AIDL ACI 重命名」重构中将契约包名由 ai.aci.core 改为 ai.aidl.aci.core、
 * 接口/类名同步更名（IACIService→IAidlAciService、ACIRequest→AidlAciRequest 等）。
 * 旧受控端（如浏览器）仍基于旧契约 ai.aci.core.IACIService 构建，其 Binder 描述符与新契约不符，
 * IAidlAciService.Stub.asInterface 会返回 null → 能力永远拉不到（真机「发现 0 个能力」的根因）。
 * 故在此做双契约兼容：新契约优先，旧契约兜底，统一包成 AciServiceProxy。
 */
interface AciServiceProxy {
    /** 拉取能力声明（每项为一个 Capability 的 JSON 字符串），与契约无关。 */
    fun getCapabilities(): Array<String>?

    /** 同步调用。入参 / 出参统一为控制端新契约类型，内部按需转换。 */
    fun call(req: AidlAciRequest): AidlAciResponse

    /** 异步调用。 */
    fun callAsync(req: AidlAciRequest, cb: IAidlAciCallback)

    /** 心跳。 */
    fun ping(): Boolean

    /** 是否为旧契约（ai.aci.core）受控端 —— 旧契约强制走 AIDL，不走 LocalSocket。 */
    fun isLegacy(): Boolean

    /** 返回底层 Binder（用于死亡监听解绑等）。 */
    fun asBinder(): IBinder?
}

/** 新契约代理：直接委托 ai.aidl.aci.core.IAidlAciService，零转换。 */
class NewAciProxy(private val svc: IAidlAciService) : AciServiceProxy {
    override fun getCapabilities(): Array<String>? = svc.getCapabilities()

    override fun call(req: AidlAciRequest): AidlAciResponse = svc.call(req)

    override fun callAsync(req: AidlAciRequest, cb: IAidlAciCallback) = svc.callAsync(req, cb)

    override fun ping(): Boolean = runCatching { svc.ping() }.getOrDefault(false)

    override fun isLegacy(): Boolean = false
    override fun asBinder(): IBinder? = svc.asBinder()
}

/**
 * 旧契约代理：委托 ai.aci.core.IACIService，做请求 / 响应双向转换。
 * 依赖 app/libs/aci-core-legacy.aar 提供的字节一致的旧契约类，保证跨进程序列化兼容。
 */
class LegacyAciProxy(private val svc: LegacyIACIService) : AciServiceProxy {
    override fun getCapabilities(): Array<String>? = runCatching { svc.getCapabilities() }.getOrNull()

    override fun call(req: AidlAciRequest): AidlAciResponse {
        val legacyReq = LegacyACIRequest(req.getCapability(), req.getParams())
        legacyReq.setCallerPkg(req.getCallerPkg())
        legacyReq.setCallId(req.getCallId())
        return try {
            fromLegacy(svc.call(legacyReq))
        } catch (e: RemoteException) {
            AidlAciResponse.error(500, "Remote: ${e.message}")
        }
    }

    override fun callAsync(req: AidlAciRequest, cb: IAidlAciCallback) {
        val legacyReq = LegacyACIRequest(req.getCapability(), req.getParams())
        legacyReq.setCallerPkg(req.getCallerPkg())
        legacyReq.setCallId(req.getCallId())
        val legacyCb = object : LegacyIACICallback.Stub() {
            override fun onResult(response: LegacyACIResponse?) {
                cb.onResult(response?.let { fromLegacy(it) } ?: AidlAciResponse.error(500, "回调为空"))
            }

            override fun onProgress(progress: Int, message: String?) {
                cb.onProgress(progress, message ?: "")
            }
        }
        try {
            svc.callAsync(legacyReq, legacyCb)
        } catch (e: RemoteException) {
            cb.onResult(AidlAciResponse.error(500, "Remote: ${e.message}"))
        }
    }

    override fun ping(): Boolean = runCatching { svc.ping() }.getOrDefault(false)

    override fun isLegacy(): Boolean = true
    override fun asBinder(): IBinder? = svc.asBinder()

    private fun fromLegacy(r: LegacyACIResponse): AidlAciResponse {
        val out =
            if (r.isSuccess) AidlAciResponse.success() else AidlAciResponse.error(r.errorCode, r.errorMessage)
        val res = r.result
        if (res != null) out.setResult(res)
        out.setCallId(r.callId)
        return out
    }
}

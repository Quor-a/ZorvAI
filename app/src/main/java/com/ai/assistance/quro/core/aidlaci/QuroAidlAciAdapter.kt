package com.ai.assistance.quro.core.aidlaci

import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.Capability
import ai.aidl.aci.core.IAidlAciService
import android.os.Bundle

/**
 * ACI 2.0 Transport / Adapter 抽象（契约与运行时分离的关键种子）。
 *
 * 旧版控制端 [QuroAidlAciManager] 直接持有 IAidlAciService（Binder）并内联调用逻辑；
 * 抽象出 [AidlAciAdapter] 后，未来可无缝接入 WebSocket / HTTP / gRPC 等传输，
 * 第三方只需实现一套 Adapter 即可被 ZorvAI 调度，无需关心底层传输与鉴权。
 *
 * 当前落地：[BinderAciAdapter] 包裹既有 Binder 调用（委托给 [QuroAidlAciManager] 的
 * 超时/重试/审计逻辑），不改热路径行为；后续 Phase 再派生 WsAciAdapter / HttpAciAdapter。
 */
interface AidlAciAdapter {
    /** 传输类型标识（如 "binder" / "ws" / "http"）。 */
    val transport: String

    /** 列出该端点暴露的能力。 */
    fun listCapabilities(): List<Capability>

    /** 同步调用某能力。 */
    fun call(capability: String, params: Bundle): AidlAciResponse

    /** 协商协议版本（无返回 null）。 */
    fun negotiateProtocol(): String?
}

/**
 * Binder 传输的 Adapter 实现：复用既有 [QuroAidlAciManager] 调用链路（超时/重试/审计/协议协商）。
 * 通过构造函数注入 call / caps / protocol 三个 provider，避免与 [QuroAidlAciManager] 形成循环依赖，
 * 也让 Adapter 成为纯粹的「传输适配」而非逻辑副本。
 */
class BinderAciAdapter(
    private val pkg: String,
    @Suppress("UNUSED_PARAMETER") private val service: IAidlAciService,
    private val callFunc: (String, Bundle) -> AidlAciResponse,
    private val capsProvider: () -> List<Capability>,
    private val protocolProvider: () -> String?
) : AidlAciAdapter {
    override val transport: String = "binder"

    override fun listCapabilities(): List<Capability> = capsProvider()

    override fun call(capability: String, params: Bundle): AidlAciResponse = callFunc(capability, params)

    override fun negotiateProtocol(): String? = protocolProvider()
}

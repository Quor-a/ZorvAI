package com.ai.assistance.quro.core.aci

/**
 * ACI 2.0 事件订阅模型（P1 种子，依托 aci-core 控制流集成）。
 *
 * 设计：当前实现为「进程内事件总线」，是 ACI 2.0 事件订阅能力的第一块落地。
 * QuroAciManager 在 aci-core 控制流的关键生命周期节点（绑定成功 / 断开 /
 * 调用失败 / 协议协商完成）emit 事件，UI 或上层逻辑 subscribe 即可收到，
 * 全程不新增 Binder 传输、不影响既有 AIDL 调用路径。
 *
 * 未来演进：事件可经 aci-core 的 IACICallback 进度通道外发到对端
 * （控制端 ↔ 受控端双向事件订阅），本对象的 subscribe/emit 接口保持稳定，
 * 仅传输层从「进程内」升级为「跨进程」，调用方无需改动。
 */
object QuroAciEvents {

    // ── 事件类型（aci-protocol 命名空间，字符串常量便于跨进程传输）──
    const val EVT_SERVICE_BOUND = "service_bound"        // 某包绑定成功
    const val EVT_SERVICE_UNBOUND = "service_unbound"    // 某包断开
    const val EVT_CALL_FAILED = "call_failed"            // 某次 aci_call 失败
    const val EVT_DISCOVERED = "discovered"              // 发现新 ACI 服务
    const val EVT_PROTOCOL_NEGOTIATED = "protocol_negotiated" // 协议协商完成

    data class Event(
        val type: String,
        val pkg: String,
        val detail: String,
        val ts: Long = System.currentTimeMillis()
    )

    private val listeners = mutableListOf<(Event) -> Unit>()
    private val lock = Any()

    /** 订阅事件，返回取消订阅的 lambda。 */
    fun subscribe(cb: (Event) -> Unit): () -> Unit {
        synchronized(lock) { listeners.add(cb) }
        return { unsubscribe(cb) }
    }

    fun unsubscribe(cb: (Event) -> Unit) {
        synchronized(lock) { listeners.remove(cb) }
    }

    /** 发出事件；单个订阅者异常不影响其余订阅者。 */
    fun emit(type: String, pkg: String, detail: String) {
        val e = Event(type, pkg, detail)
        synchronized(lock) { listeners.toList() }.forEach { listener ->
            runCatching { listener(e) }.onFailure { t ->
                android.util.Log.w("QuroAciEvents", "订阅者处理事件异常：${t.message}")
            }
        }
    }
}

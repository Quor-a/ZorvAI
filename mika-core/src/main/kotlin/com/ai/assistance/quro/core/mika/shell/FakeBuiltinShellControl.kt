package com.ai.assistance.quro.core.mika.shell

import kotlinx.coroutines.flow.MutableStateFlow

/** 测试和还没接上真实现时用:记下调用,状态由调用方摆。[pairingResult] 是 [startPairing] 之后的状态。 */
class FakeBuiltinShellControl(
    initial: BuiltinShellStatus = BuiltinShellStatus.NotPaired,
    var pairingResult: BuiltinShellStatus = BuiltinShellStatus.Running,
    var retryResult: BuiltinShellStatus = BuiltinShellStatus.Running,
) : BuiltinShellControl {
    override val status = MutableStateFlow(initial)
    override val pairingDiscovery = MutableStateFlow<PairingDiscovery>(PairingDiscovery.Idle)
    val calls = mutableListOf<String>()

    override fun startDiscovery() {
        calls += "discover"
        if (pairingDiscovery.value == PairingDiscovery.Idle) pairingDiscovery.value = PairingDiscovery.Searching
    }

    override fun stopDiscovery() {
        calls += "stopDiscovery"
        pairingDiscovery.value = PairingDiscovery.Idle
    }

    override suspend fun startPairing(code: String, port: Int?) {
        calls += "pair $code" + (port?.let { " $it" } ?: "")
        status.value = BuiltinShellStatus.Connecting
        status.value = pairingResult
    }

    override suspend fun retry() {
        calls += "retry"
        status.value = BuiltinShellStatus.Connecting
        status.value = retryResult
    }

    override suspend fun unpair() {
        calls += "unpair"
        status.value = BuiltinShellStatus.NotPaired
    }
}

package com.ai.assistance.quro.libaci

import android.os.Bundle

/**
 * 能力 Handler 接口：一个能力一个实现。
 * - spec：能力声明（id / desc / dangerous）。
 * - handle(params)：执行业务，返回结果 Bundle（交给 AciRouter 包成 AidlAciResponse.success）。
 *
 * 约定：handler 内部自行处理参数缺失/非法，返回带 `error` 字段的 Bundle 或在 AciRouter 层抛异常
 * （AciRouter 会统一包成 INTERNAL_ERROR）。业务代码不要碰 ACI 协议对象，只认 Bundle。
 */
interface AciHandler {
    val spec: CapabilitySpec
    fun handle(params: Bundle): Bundle
}

package com.ai.assistance.quro.capmain.init

import com.ai.assistance.quro.capmain.note.CreateNoteHandler
import com.ai.assistance.quro.capmain.note.SearchNoteHandler
import com.ai.assistance.quro.capmain.order.ListOrdersHandler
import com.ai.assistance.quro.capmain.order.QueryOrderHandler
import com.ai.assistance.quro.libaci.CapabilityRegistry

/**
 * 能力注册总入口（单一注册点）。
 *
 * 所有 main.* 业务 Handler 在此登记进框架层 CapabilityRegistry。
 * MainAciService 启动时会调用 installMainCapabilities()，确保 AIDL 绑定前能力已就绪。
 *
 * 新增业务能力：在此追加一行 register(...)，并实现对应的 AciHandler 即可，
 * 不改框架、不改 manifest（除非引入新的独立 Service）。
 */
fun installMainCapabilities() {
    CapabilityRegistry.register(QueryOrderHandler)
    CapabilityRegistry.register(ListOrdersHandler)
    CapabilityRegistry.register(SearchNoteHandler)
    CapabilityRegistry.register(CreateNoteHandler)
}

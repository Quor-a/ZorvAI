package com.ai.assistance.quro.capmain.order

import android.os.Bundle
import com.ai.assistance.quro.libaci.AciHandler
import com.ai.assistance.quro.libaci.CapabilitySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * 订单域能力 Handler（main.* 命名空间）。
 *
 * 说明：当前为自包含内存实现（OrderRepo），用于打通 ACI 链路并可直接演示。
 * 后续接真实业务时，只需把 OrderRepo 换成对 App 数据库 / 后端 API 的调用，
 * Handler 的 spec.id / 入参 / 返回结构保持不变，对 LLM 与调用方透明。
 */

/** 演示用内存订单仓储（线程安全）。 */
private object OrderRepo {
    private val orders = LinkedHashMap<String, JSONObject>()
    private var seq = 1000

    init {
        // 预置两条演示数据
        put("20260801", JSONObject().apply {
            put("order_id", "20260801")
            put("status", "paid")
            put("amount", 199.0)
            put("item", "ZorvAI 年卡")
        })
        put("20260815", JSONObject().apply {
            put("order_id", "20260815")
            put("status", "shipped")
            put("amount", 59.0)
            put("item", "终端增强包")
        })
    }

    @Synchronized
    fun put(id: String, o: JSONObject) = orders.put(id, o)

    @Synchronized
    fun get(id: String): JSONObject? = orders[id]

    @Synchronized
    fun list(status: String?): List<JSONObject> =
        orders.values.filter { status == null || it.optString("status") == status }
}

/** main.query_order：按订单号查询单条订单。 */
object QueryOrderHandler : AciHandler {
    override val spec: CapabilitySpec = CapabilitySpec(
        id = "main.query_order",
        desc = "按订单号查询单条订单详情。参数 order_id(必填,string)。返回 order_id/status/amount/item。" +
                "只读，无副作用。当用户问'我的订单 20260801 到哪了'时调用。"
    )

    override fun handle(params: Bundle): Bundle {
        val orderId = params.getString("order_id")
            ?: return Bundle().apply { putString("error", "missing required param: order_id") }
        val o = OrderRepo.get(orderId)
            ?: return Bundle().apply {
                putBoolean("found", false)
                putString("error", "order not found: $orderId")
            }
        return Bundle().apply {
            putBoolean("found", true)
            putString("order", o.toString())
        }
    }
}

/** main.list_orders：列出订单，可按 status 过滤。 */
object ListOrdersHandler : AciHandler {
    override val spec: CapabilitySpec = CapabilitySpec(
        id = "main.list_orders",
        desc = "列出当前用户订单，可按状态过滤。参数 status(选填,string: paid/shipped/cancelled)。" +
                "返回 orders(JSON 数组)。只读，无副作用。"
    )

    override fun handle(params: Bundle): Bundle {
        val status = params.getString("status")
        val list = OrderRepo.list(status)
        val arr = JSONArray().apply { list.forEach { put(it) } }
        return Bundle().apply { putString("orders", arr.toString()) }
    }
}

package com.ai.assistance.quro.dshmobile

import org.json.JSONArray
import org.json.JSONObject

/**
 * 测试用 JSON 深度等价（键序无关）——`ControlProtocolV2Test` 等跨语言门禁的比对基座。
 *
 * 语义（与 ControlProtocolV2Test:62 的注释一致）：
 *  - **对象按 map 比较，键序无关**：键集合相同，且每个键的值递归等价；
 *  - **值必须完全一致**：数组逐位、标点按值；`Integer`/`Long`/`Double` 的装箱差异
 *   不算不等（org.json 对同一字面量的解析类型随位数变化，跨语言往返门禁关心的是数值）；
 *  - `JSONObject.NULL` 与 `null` 是两回事：前者是 org.json 的哨兵对象，按值相等处理。
 *
 * 为什么不用 `actual.toString() == expected.toString()`：org.json 的 `toString()`
 * **保留插入/解析顺序**，Kotlin 编码器与 TS 编码器的键序没有约束，逐字节比会假红。
 * 「键序无关 + 值完全一致」正是跨语言协议门禁需要的判据。
 */
internal fun JSONObject.similar(other: JSONObject): Boolean = jsonDeepEquals(this, other)

internal fun JSONArray.similar(other: JSONArray): Boolean = jsonDeepEquals(this, other)

private fun jsonDeepEquals(a: Any?, b: Any?): Boolean {
  if (a === b) return true
  if (a == null || b == null) return false
  if (a is JSONObject && b is JSONObject) {
    if (a.length() != b.length()) return false
    val keys = a.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      if (!b.has(key)) return false
      if (!jsonDeepEquals(a.get(key), b.get(key))) return false
    }
    return true
  }
  if (a is JSONArray && b is JSONArray) {
    if (a.length() != b.length()) return false
    for (index in 0 until a.length()) {
      if (!jsonDeepEquals(a.get(index), b.get(index))) return false
    }
    return true
  }
  if (a is Number && b is Number) {
    // 数值等价：长整与浮点表述差异（1 vs 1.0）不算不等，NaN 天然不等（org.json 不产 NaN）。
    return a.toDouble() == b.toDouble() && a.toLong() == b.toLong()
  }
  return a == b
}

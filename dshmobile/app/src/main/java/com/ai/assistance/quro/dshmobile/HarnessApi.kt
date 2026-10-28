package com.ai.assistance.quro.dshmobile

import android.content.Context
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import org.json.JSONObject

/**
 * ZorvAI ⇄ Harness 互控 API 客户端（#506）——引擎插件 `dsh-android-harness-api`
 * （`plugins/dsh-android-harness-api/src/index.ts`）的壳侧对应面。
 *
 * 插件把引擎的 ToolRuntime 与 SkillRegistry 以 HTTP JSON 暴露在引擎自己的 webServer
 * 上（127.0.0.1，不出设备），本对象是 ZorvAI 侧的唯一入口：
 *
 *   GET  /api/harness/tools       列举 harness 已装配的全部工具（含 zorvai_* 与各插件工具）
 *   POST /api/harness/tools/call  让 ZorvAI 的 AI **任意调用** harness 的任一工具
 *   GET  /api/harness/skills      列举技能目录
 *   POST /api/harness/skills/load 加载任一技能完整定义
 *
 * ## HTTP 纪律（与 [EngineProbe.rpc] / [DshmobileControl.rpc] 完全一致）
 *
 * - `Proxy.NO_PROXY` + 显式 connect/read 超时；cookie 经 [EngineAuth.attach] 带上
 *   （引擎 /api 全量鉴权，无环回豁免）；
 * - 401/403（cookie 被拒/轮换）→ [EngineAuth.handleUnauthorized] 刷新后**重发一次**，
 *   与 `DshmobileControl.rpc` 同款；其余 4xx/5xx 的 JSON 错误体是**业务回执**，
 *   照常解析返回（插件面对 400/404/500 都带 `{ok:false,error}`）；
 * - 任何网络/解析异常都不抛错：返回 null，由门面转成可读结论。
 *
 * ## 与插件路由的对应是硬契约
 *
 * 四个路径常量与插件的 `ROUTE_*` 一一对应；插件改路径必须同改本文件，
 * `HarnessApiRouteContractTest` 以源码字面量双向锁定。
 */
internal object HarnessApi {

  // ── 路由（与插件 src/index.ts 的 ROUTE_* 常量一一对应，勿单改一侧）──────────
  const val ROUTE_TOOLS = "/api/harness/tools"
  const val ROUTE_TOOLS_CALL = "/api/harness/tools/call"
  const val ROUTE_SKILLS = "/api/harness/skills"
  const val ROUTE_SKILLS_LOAD = "/api/harness/skills/load"

  private const val CONNECT_TIMEOUT_MS = 3_000
  /** 列举类默认读超时（15s）：注册表投影是进程内操作，不该慢。 */
  private const val LIST_READ_TIMEOUT_MS = 15_000
  /** 工具调用读超时余量（ms）：引擎侧超时上限 600s，HTTP 侧多等 15s 收错误回执。 */
  private const val CALL_READ_MARGIN_MS = 15_000
  /** 引擎侧工具调用超时上下限（秒），与插件 DEFAULT/MAX_TIMEOUT_MS 对应。 */
  private const val ENGINE_TIMEOUT_MIN_S = 1
  private const val ENGINE_TIMEOUT_MAX_S = 600
  private const val ENGINE_TIMEOUT_DEFAULT_S = 120

  // ── 公开面（全部阻塞，不得在主线程调用；与 DshmobileControl 线程契约一致）────

  /** 列举 harness 全部可见工具（name/description/parameters/deferLoading）。 */
  fun toolsList(context: Context): DshmobileControl.DshResult {
    val body = request(context.applicationContext, ROUTE_TOOLS, "GET", null, LIST_READ_TIMEOUT_MS)
    return formatToolsList(body)
  }

  /**
   * 调用 harness 的任一工具（引擎全管线：visibility / UNKNOWN_TOOL / 审批门保留）。
   *
   * @param name 工具名（如 zorvai_read_screen、bash）。
   * @param args 工具实参；null = 空对象。
   * @param timeoutSeconds 引擎侧执行超时（秒）；null = 插件默认 120s。夹取到 [1, 600]。
   */
  fun toolCall(
    context: Context,
    name: String,
    args: JSONObject?,
    timeoutSeconds: Int?,
  ): DshmobileControl.DshResult {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return DshmobileControl.DshResult(false, "缺少工具名 name")
    val body = buildToolCallBody(trimmed, args, timeoutSeconds)
    val readTimeoutMs = readTimeoutFor(timeoutSeconds)
    val resp = request(context.applicationContext, ROUTE_TOOLS_CALL, "POST", body, readTimeoutMs)
    return formatToolCall(resp)
  }

  /** 列举技能目录（name/description/invocation/source/provider）。 */
  fun skillsList(context: Context): DshmobileControl.DshResult {
    val body = request(context.applicationContext, ROUTE_SKILLS, "GET", null, LIST_READ_TIMEOUT_MS)
    return formatSkillsList(body)
  }

  /** 加载一个技能的完整定义（含 markdown 正文）。不存在 → ok=false。 */
  fun skillLoad(context: Context, name: String): DshmobileControl.DshResult {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return DshmobileControl.DshResult(false, "缺少技能名 name")
    val body = buildSkillLoadBody(trimmed)
    val resp = request(context.applicationContext, ROUTE_SKILLS_LOAD, "POST", body, LIST_READ_TIMEOUT_MS)
    return formatSkillLoad(resp)
  }

  // ── 纯函数（单测直测，无网络）──────────────────────────────────────────────

  /** tools/call 请求体：arguments 缺省空对象；timeoutMs 仅在给出时携带（秒→毫秒）。 */
  internal fun buildToolCallBody(name: String, args: JSONObject?, timeoutSeconds: Int?): JSONObject {
    val body = JSONObject().put("name", name)
    body.put("arguments", args ?: JSONObject())
    if (timeoutSeconds != null) body.put("timeoutMs", clampEngineTimeoutS(timeoutSeconds) * 1000L)
    return body
  }

  /** skills/load 请求体。 */
  internal fun buildSkillLoadBody(name: String): JSONObject = JSONObject().put("name", name)

  /** 引擎侧超时夹取（秒）：[1, 600]，null → 默认 120。 */
  internal fun clampEngineTimeoutS(timeoutSeconds: Int?): Int =
    (timeoutSeconds ?: ENGINE_TIMEOUT_DEFAULT_S).coerceIn(ENGINE_TIMEOUT_MIN_S, ENGINE_TIMEOUT_MAX_S)

  /** HTTP 读超时（ms）：引擎超时 + 余量（让引擎侧的 abort 回执有时间到达）。 */
  internal fun readTimeoutFor(timeoutSeconds: Int?): Int =
    clampEngineTimeoutS(timeoutSeconds) * 1000 + CALL_READ_MARGIN_MS

  /**
   * tools 列举回执 → 可读结论。`ok=false`（API 面自身失败）→ 透传错误文本。
   */
  internal fun formatToolsList(body: JSONObject?): DshmobileControl.DshResult {
    if (body == null) return unreachable()
    if (!body.optBoolean("ok")) return apiFailure(body)
    val tools = body.optJSONArray("tools")
    if (tools == null || tools.length() == 0) {
      return DshmobileControl.DshResult(true, "harness 当前没有可见工具（插件可能尚未装配）")
    }
    val lines = ArrayList<String>(tools.length() + 1)
    lines.add("harness 可见工具 " + tools.length() + " 个：")
    for (i in 0 until tools.length()) {
      val tool = tools.optJSONObject(i) ?: continue
      val deferred = if (tool.optBoolean("deferLoading")) "（渐进披露）" else ""
      lines.add("· " + tool.optString("name") + deferred + " — " + tool.optString("description"))
    }
    lines.add("调用方式：harness 工具 action=tool_call，name 填上面的工具名，tool_args 传实参 JSON。")
    return DshmobileControl.DshResult(true, lines.joinToString("\n"))
  }

  /**
   * tool_call 回执 → 可读结论。
   *
   * 三层判别（与插件响应契约一致）：`ok=false` = API 面自身失败（协议/管线异常，
   * 配 4xx/5xx）；`isError=true` = 工具级失败（与 harness 里模型调用所见一致）；
   * 其余 = 工具成功，回执文本优先，空文本时退到 value JSON。
   */
  internal fun formatToolCall(body: JSONObject?): DshmobileControl.DshResult {
    if (body == null) return unreachable()
    if (!body.optBoolean("ok")) return apiFailure(body)
    val name = body.optString("name").ifBlank { "(未命名工具)" }
    if (body.optBoolean("isError")) {
      val error = body.optJSONObject("error")
      val message = error?.optString("message").orEmpty().ifBlank { "（无错误信息）" }
      return DshmobileControl.DshResult(false, "harness 工具 " + name + " 执行失败：" + message)
    }
    val text = body.optString("text").trim()
    if (text.isNotEmpty()) return DshmobileControl.DshResult(true, text)
    val value = body.opt("value")
    return if (value == null || value == JSONObject.NULL) {
      DshmobileControl.DshResult(true, "harness 工具 " + name + " 执行成功（无输出）")
    } else {
      DshmobileControl.DshResult(true, value.toString())
    }
  }

  /** skills 列举回执 → 可读结论。 */
  internal fun formatSkillsList(body: JSONObject?): DshmobileControl.DshResult {
    if (body == null) return unreachable()
    if (!body.optBoolean("ok")) return apiFailure(body)
    val skills = body.optJSONArray("skills")
    if (skills == null || skills.length() == 0) {
      return DshmobileControl.DshResult(true, "harness 技能库为空")
    }
    val lines = ArrayList<String>(skills.length() + 1)
    lines.add("harness 技能 " + skills.length() + " 个：")
    for (i in 0 until skills.length()) {
      val skill = skills.optJSONObject(i) ?: continue
      lines.add("· " + skill.optString("name") + " — " + skill.optString("description"))
    }
    lines.add("加载全文：harness 工具 action=skill_load，skill_name 填上面的技能名。")
    return DshmobileControl.DshResult(true, lines.joinToString("\n"))
  }

  /** skill_load 回执 → 可读结论（正文 + 元数据）。 */
  internal fun formatSkillLoad(body: JSONObject?): DshmobileControl.DshResult {
    if (body == null) return unreachable()
    if (!body.optBoolean("ok")) return apiFailure(body)
    val skill = body.optJSONObject("skill")
    if (skill == null) return apiFailure(body)
    val name = body.optString("name").ifBlank { skill.optString("name") }
    val content = skill.optString("content")
    val head = "=== 技能 " + name + "（来源 " + skill.optString("source") + "）==="
    return DshmobileControl.DshResult(true, head + "\n" + content)
  }

  private fun unreachable(): DshmobileControl.DshResult = DshmobileControl.DshResult(
    false,
    "harness API 面不可达：引擎未运行、尚未装配 dsh-android-harness-api 插件，或请求超时。" +
      "先 action=status 看引擎状态；插件面随快照装配，确认插件在列可 action=plugins。",
  )

  private fun apiFailure(body: JSONObject): DshmobileControl.DshResult {
    val error = body.optString("error").ifBlank { "（无错误信息）" }
    return DshmobileControl.DshResult(false, "harness API 面返回错误：" + error)
  }

  // ── HTTP（EngineProbe.rpc 同款纪律）────────────────────────────────────────

  /**
   * 一次带鉴权重试的 HTTP 调用。
   *
   * @return 解析出的 JSON 响应体；网络失败 / 非 JSON / 401 重试后仍被拒 → null。
   */
  private fun request(
    app: Context,
    path: String,
    method: String,
    body: JSONObject?,
    readTimeoutMs: Int,
  ): JSONObject? {
    if (!EngineEndpoint.confirmed) return null
    var retried = false
    while (true) {
      var conn: HttpURLConnection? = null
      try {
        conn = URL(EngineEndpoint.url + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = readTimeoutMs
        if (body != null) {
          conn.doOutput = true
          conn.setRequestProperty("content-type", "application/json")
        }
        EngineAuth.attach(app, conn)
        if (body != null) {
          conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        if (code == 401 || code == 403) {
          if (!retried) {
            retried = true
            runCatching { EngineAuth.handleUnauthorized(app) }
            continue
          }
          return null
        }
        // 4xx/5xx 的 JSON 错误体是业务回执（插件面统一 {ok:false,error}），照常读。
        val text = readBody(conn)
        return runCatching { JSONObject(text) }.getOrNull()
      } catch (_: Throwable) {
        return null
      } finally {
        conn?.disconnect()
      }
    }
  }

  private fun readBody(conn: HttpURLConnection): String {
    val stream: InputStream = try {
      if (conn.responseCode >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
    } catch (_: Throwable) {
      conn.inputStream
    }
    return stream?.bufferedReader().use { it?.readText().orEmpty() }
  }
}

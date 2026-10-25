package com.ai.assistance.quro.dshmobile

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * ZorvAI ⇄ DeepSeek Harness 互控门面（#404）。
 *
 * ## 为什么需要它
 *
 * 内置 dshmobile 后，引擎的全部能力都沉在模块内部：`EngineEndpoint` / `EngineProbe.rpc`
 * 是 **internal**，`EngineService` 的起停语义（userShutdown 持久标志）散落在看门狗与
 * Activity 启动流里。ZorvAI（app 模块）要「完整操控」harness，需要的是一层 **public 门面**：
 * 起 / 停 / 重启 / 探活 / 派任务 / 读日志 / 写凭据，一次调用拿到一个可读结论。
 *
 * ## 与既有入口的关系（不是新造一套语义）
 *
 * - **spawn 权威**仍是 [EngineManager.startEngine]（四道闸门一道不少）；
 * - **保活**仍交给 [EngineService]（前台服务 + 看门狗），本门面只负责把它拉起来；
 * - **停机**复用 [EngineService.requestShutdown] 的既有语义（userShutdown 置位 + 停看门狗），
 *   再补一次本进程持有句柄的子进程停止——与 `EngineStartFlow.shutdownToGuide()` 同构；
 * - **任务派发**走引擎 RPC（`session/create` → `session/prompt` → 轮询 `session/page`），
 *   信封与 `OverlayService.postRpc` 完全一致（Typert 强校验：`payload.args` 必须恰好一个
 *   plain-object 字段）。
 *
 * ## 进程级单一 EngineManager（本文件的关键设计）
 *
 * 全模块原有多个 `EngineManager` 实例（MainActivity / EngineService / ConsoleSession /
 * UpdateManager 各一个），而 `engineProcess` 是**实例字段**：谁 spawn 谁才持有 kill 句柄，
 * 别的实例 `stopOwnedEngine()` 会以「保护未归属监听器」为由拒绝。ZorvAI 要能停掉引擎，
 * 就必须让「UI 入口 / 前台服务 / 本门面」共用同一个实例——否则用户先开工作台界面起了引擎，
 * ZorvAI 的 AI 再让它停，会出现「端口还在听但谁都没有句柄」的不可操控态。
 * 故 [manager] 是进程级单例，MainActivity 与 EngineService 也都改用它（构造参数语义不变：
 * filesDir 同一、pickToken 同为进程级 `ensurePickToken()`）。
 *
 * ## 线程契约
 *
 * 除 [openWorkbench] 外全部方法都是**阻塞式**的（refreshSnapshot 首启可达分钟级、
 * dispatchTask 等一个完整 agent 回合）：**不得在主线程调用**。调用方（QuroTool 执行器）
 * 本就跑在 IO 线程。
 */
object DshmobileControl {

  /** 门面调用的统一回执：ok = 事实达成；text = 给调用方（AI/用户）看的可读结论。 */
  data class DshResult(val ok: Boolean, val text: String)

  // ── 进程级单一 EngineManager ────────────────────────────────────────────────

  private val managerRef = java.util.concurrent.atomic.AtomicReference<EngineManager?>(null)

  /**
   * 进程级单例（applicationContext + 进程级 pickToken）。
   *
   * 为什么必须是单例而不是每次 new：`engineProcess` 句柄是实例字段（见类注释）。
   * MainActivity 与 EngineService 的既有字段都指向这里，保证「谁都能停、谁都能探到」。
   */
  fun manager(context: Context): EngineManager {
    val existing = managerRef.get()
    if (existing != null) return existing
    synchronized(this) {
      managerRef.get()?.let { return it }
      val app = context.applicationContext
      val created = EngineManager(app, EngineManager.ensurePickToken())
      managerRef.set(created)
      return created
    }
  }

  // ── 界面入口 ────────────────────────────────────────────────────────────────

  /**
   * 打开 harness 工作台（MainActivity，引擎未起时由它自己的启动流接管）。
   *
   * 这是「给人用」的入口：会话 / 插件 / 设置 / 主题都是 harness 自己的 UI。
   * exported=false 无所谓——ZorvAI 与 dshmobile 编译进同一个应用（同 uid）。
   */
  fun openWorkbench(context: Context): DshResult {
    val app = context.applicationContext
    return try {
      val intent = Intent(app, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 不抢 LAUNCHER：只从工具箱/harness 卡片显式进入。
        addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
      }
      app.startActivity(intent)
      DshResult(true, "已打开 DeepSeek Harness 工作台（界面由 harness 自己提供）")
    } catch (t: Throwable) {
      DshResult(false, "打开工作台失败：" + (t.message ?: t.javaClass.simpleName))
    }
  }

  /** 引擎 HTTP 端点（仅端点；带 token 的完整 URL 由工作台 UI 自己交换 cookie）。 */
  fun engineUrl(): String = runCatching { EngineProbe.ENGINE_URL }.getOrElse { "http://127.0.0.1:4145" }

  // ── 状态 ────────────────────────────────────────────────────────────────────

  /** 一行式状态总览（探活 + 端口 + 运行时树 + 凭据 + 服务 + patch 层）。 */
  fun status(context: Context): DshResult {
    val app = context.applicationContext
    val text = statusText(app)
    val running = text.contains("引擎      : 运行中")
    return DshResult(running, text)
  }

  private fun statusText(app: Context): String = buildString {
    val probe = EngineProbe.check(800)
    val running = probe.optBoolean("running", false)
    val auth = probe.optString("auth", "unknown")
    val listening = probe.optBoolean("listening", false)
    val portUp = EngineProbe.portReachable(600)
    appendLine("=== DeepSeek Harness（内置引擎）状态 ===")
    appendLine("引擎      : " + when {
      running && auth == "ok" -> "运行中（已认证）"
      running && auth == "required" -> "运行中（cookie 待重认证）"
      running -> "运行中"
      listening -> "端口有应答但判据不成立"
      else -> "未运行"
    })
    appendLine("端点      : " + engineUrl() + if (portUp) "（端口可连）" else "（端口不可连）")
    val m = manager(app)
    appendLine("运行时    : " + when {
      m.engineReady && m.liveRuntimeComplete() -> "已就绪"
      m.engineReady -> "部分就绪（live 树缺条目）"
      else -> "未解压（首次 start 会自动解压，耗时分钟级）"
    })
    appendLine("快照新鲜  : " + if (m.snapshotFresh()) "是" else "否（下次 start 会重解压/更新）")
    val keyFile = File(app.filesDir, "deepseek-key.txt")
    appendLine("模型凭据  : " + if (keyFile.isFile && keyFile.readText().trim().isNotEmpty()) "已配置" else "未配置（harness 需要自己的模型 key，与 ZorvAI 的模型配置互不相干）")
    appendLine("保活服务  : " + if (EngineService.instance != null) "运行中（看门狗在盯）" else "未运行")
    val patch = File(File(File(app.filesDir, "home"), ".dsh"), "profiles/web/cordis.patch.yml")
    appendLine("配置补丁  : " + if (patch.isFile) "✓ " + patch.absolutePath else "✗ 缺失（快照未解压前正常）")
    val probeCode = probe.optInt("code", -1)
    if (probeCode > 0) appendLine("HTTP 码   : $probeCode")
  }

  // ── 起停 ────────────────────────────────────────────────────────────────────

  /**
   * 启动引擎（阻塞）。首启会自动解压内置快照（130MB+，分钟级），之后是 20-45s 冷启动。
   *
   * 步骤与 MainActivity 的启动流同构（不新造语义）：
   *   ① 解 userShutdown 锁（用户此前停过的话，否则前台服务拒绝拉起）；
   *   ② 已在跑 → 直接回执；
   *   ③ 快照不新鲜 → refreshSnapshot（除非降级闸门放行：同一快照连续失败达阈且 live 完整）；
   *   ④ startEngine() spawn（四道闸门）；
   *   ⑤ 起前台服务（看门狗保活）；
   *   ⑥ 轮询 HTTP 就绪（401 ⇒ 走既有重新认证，不算就绪）。
   *
   * @param waitBudgetMs 第 ⑥ 步的预算（spawn 只保证进程起来，不保证 HTTP 已 listen）。
   */
  fun start(context: Context, waitBudgetMs: Long = 180_000L): DshResult {
    val app = context.applicationContext
    // ① 解锁：requestShutdown 会把 userShutdown 持久化，不解除则服务与看门狗都不再拉起。
    EngineService.setUserShutdown(app, false)
    // ② 早退：已在跑（且不需要重认证）。
    val early = EngineProbe.check(800)
    if (early.optBoolean("running", false) && early.optString("auth") != "required") {
      return DshResult(true, "引擎已在运行，无需重复启动\n\n" + statusText(app))
    }
    val m = manager(app)
    // ③ 运行时树：不新鲜就刷新（降级闸门放行时跳过，以现有 live 树启动）。
    if (!m.snapshotFresh() && !m.shouldDegradeRefresh()) {
      val refreshed = runCatching { m.refreshSnapshot({ _, _ -> }, { _ -> }) }.getOrElse { false }
      if (!refreshed) {
        val cause = m.lastRefreshFailure
        val code = m.lastRefreshFailureCode
        return DshResult(
          false,
          "内置运行时解压/更新失败" + (code?.let { "（code=$it）" } ?: "") +
            (cause?.let { "：" + (it.message ?: it.javaClass.simpleName) } ?: "") +
            "\n可用 action=open 打开工作台，引导页会给出更细的失败原因。"
        )
      }
    }
    // ④ spawn。false 的两种形态要分开：早退（已在跑）与被闸门拒绝。
    val spawned = runCatching { m.startEngine() }.getOrElse { false }
    if (!spawned) {
      val recheck = EngineProbe.check(800)
      if (recheck.optBoolean("running", false) && recheck.optString("auth") != "required") {
        armService(app)
        return DshResult(true, "引擎已在运行（spawn 早退）\n\n" + statusText(app))
      }
      val refusal = m.lastStartRefusal ?: m.lastStartRefusalCode
      return DshResult(false, "引擎启动被拒绝：" + (refusal ?: "未知原因（无 refusal 记录）") + "\n\n" + statusText(app))
    }
    // ⑥ 等 HTTP 就绪后再挂保活（与 MainActivity：spawn → 轮询 → startEngineService）。
    val ready = waitHttpReady(app, waitBudgetMs)
    if (!ready.ok) return ready
    armService(app)
    return DshResult(true, "引擎已启动并就绪\n\n" + statusText(app))
  }

  /**
   * 停止引擎（阻塞）。
   *
   * 与 `EngineStartFlow.shutdownToGuide()` 同构的两步：先请前台服务停机（userShutdown 置位 +
   * 停看门狗 + 服务侧管理器停自己的子进程），再停本门面单例持有的子进程。
   * 端口在预算内未释放时如实回执（可能是别入口持有的监听器，本门面不按名字杀进程）。
   */
  fun stop(context: Context): DshResult {
    val app = context.applicationContext
    val notes = mutableListOf<String>()
    runCatching { EngineService.instance?.requestShutdown() }
      .onFailure { notes += "前台服务停机请求失败：" + (it.message ?: it.javaClass.simpleName) }
      .onSuccess { notes += "已请求前台服务停机（看门狗停止，userShutdown 置位）" }
    val stopped = runCatching { manager(app).stopOwnedEngine() }.getOrDefault(false)
    notes += if (stopped) "已停止本进程托管的引擎子进程" else "本进程没有可直接停止的引擎子进程句柄"
    var released = false
    val deadline = System.currentTimeMillis() + 12_000L
    while (System.currentTimeMillis() < deadline) {
      if (!EngineProbe.portReachable(400)) { released = true; break }
      try { Thread.sleep(400) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
    }
    return if (released) {
      DshResult(true, "已停止 DeepSeek Harness\n  " + notes.joinToString("\n  "))
    } else {
      DshResult(
        false,
        "停止请求已发出，但端口仍有监听（可能是其它入口持有的引擎）。\n  " +
          notes.joinToString("\n  ") +
          "\n  可在工作台界面内关闭，或重启应用。\n\n" + statusText(app)
      )
    }
  }

  /** 重启（阻塞）：先停（含 userShutdown 解锁）再起。 */
  fun restart(context: Context, waitBudgetMs: Long = 180_000L): DshResult {
    val app = context.applicationContext
    val stopResult = stop(app)
    // 停不干净也要继续：startEngine 的 pre-spawn 判据会拒绝占用端口（PORT_FOREIGN），
    // 那时如实回执比假装重启成功好。
    EngineService.setUserShutdown(app, false)
    EngineManager.lastStartAttemptAt = 0
    val startResult = start(app, waitBudgetMs)
    return DshResult(
      startResult.ok,
      "【停止】" + (if (stopResult.ok) "已停止" else "未完全停止（" + stopResult.text.replace("\n", " ") + "）") +
        "\n\n【启动】" + startResult.text
    )
  }

  /** 起前台服务把引擎交给看门狗保活（失败只留痕：引擎仍在跑，只是没有看门狗）。 */
  private fun armService(app: Context) {
    try {
      app.startForegroundService(Intent(app, EngineService::class.java))
    } catch (t: Throwable) {
      // Android 12+ 后台起前台服务可能被拒；引擎本身已就绪，保活等下次前台再补。
      LogCollector.log("dshmobile-control", "foreground service start failed: " + (t.message ?: t.javaClass.simpleName))
    }
  }

  /** 轮询 HTTP 就绪。401 走既有重新认证且**不算就绪**（下一拍 200 才是）。 */
  private fun waitHttpReady(app: Context, budgetMs: Long): DshResult {
    val deadline = System.currentTimeMillis() + budgetMs
    val step = 1_000L
    var lastNote = ""
    while (System.currentTimeMillis() < deadline) {
      val probe = EngineProbe.check(800)
      when {
        probe.optString("auth") == "required" -> {
          // 启动探活本身不构成归属证据；只有 owned 引擎才允许重认证（与启动流同一判据）。
          val availability = manager(app).probeAvailability(500)
          if (EngineProbe.shouldAutoRecoverAuth(401, true, EngineProbe.ENGINE_URL, availability)) {
            lastNote = "引擎应答 401，正在重新认证"
            runCatching { EngineAuth.handleUnauthorized(app) }
          } else {
            lastNote = "引擎应答 401 但归属未证实，跳过重认证"
          }
        }
        probe.optBoolean("running", false) -> {
          LogCollector.markListen(app)
          LogCollector.markFirstHttp(app)
          return DshResult(true, "引擎 HTTP 已就绪（$lastNote）".trim())
        }
        else -> lastNote = probe.optString("error", "等待引擎监听…")
      }
      try { Thread.sleep(step) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
    }
    return DshResult(false, "引擎进程已拉起，但 ${budgetMs / 1000}s 内 HTTP 未就绪（$lastNote）\n\n" + statusText(app))
  }

  // ── 任务派发（ZorvAI → harness） ─────────────────────────────────────────────

  /**
   * 把一个任务交给 harness 的 agent 完整执行（读文件 / 跑命令 / 改代码 / 用它自己的插件），
   * 返回它的最终答复。这是「ZorvAI 完整操控 harness」的核心通道。
   *
   * 链路：确保引擎在跑 → `session/create` → `session/prompt(mode=queue)` →
   * 轮询 `session/page` 取最后一条 `assistant/message` 的 text block，以 `session/list`
   * 的 running 位判完成。超时返回已收到的部分（如实标注），不假装成功。
   *
   * @param sessionId 续用既有会话（多轮上下文）；null = 新建。
   * @param timeoutMs 整个回合的预算（agent 干活可能很慢，默认 10 分钟）。
   */
  fun dispatchTask(
    context: Context,
    task: String,
    sessionId: String? = null,
    timeoutMs: Long = 600_000L,
  ): DshResult {
    val app = context.applicationContext
    val body = task.trim()
    if (body.isEmpty()) return DshResult(false, "任务文本为空")
    val up = start(app, 120_000L)
    if (!up.ok) return DshResult(false, "引擎未就绪，无法派任务\n" + up.text)

    val catalog = rpc(app, "session/modelCatalog", JSONObject())
    val defaultModel = catalog?.optJSONObject("result")?.optJSONObject("value")?.optJSONObject("default")
    if (defaultModel == null ||
      defaultModel.optString("provider").isBlank() || defaultModel.optString("model").isBlank()) {
      return DshResult(
        false,
        "harness 还没有可用的模型配置（default provider/model 为空）。\n" +
          "请先在 harness 工作台里选好模型（它用自己的模型 key，与 ZorvAI 的配置互不相干）。"
      )
    }

    val sid = sessionId?.takeIf { it.isNotBlank() } ?: createSession(app)
      ?: return DshResult(false, "创建 harness 会话失败（session/create 无回执）")

    val requestId = "zorvai-" + System.currentTimeMillis() + "-" + (0..9999).random()
    val promptArgs = JSONObject().put("request", JSONObject()
      .put("requestId", requestId)
      .put("sessionId", sid)
      .put("mode", "queue")
      .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", body))))
    val promptResp = rpc(app, "session/prompt", promptArgs, 15_000)
    if (promptResp == null) return DshResult(false, "任务下发失败（session/prompt 无回执；引擎可能正忙）")

    val deadline = System.currentTimeMillis() + timeoutMs
    var lastText = ""
    while (System.currentTimeMillis() < deadline) {
      val texts = assistantTexts(app, sid)
      if (texts.isNotEmpty()) lastText = texts.last().second
      val running = sessionRunning(app, sid)
      if (running == false) {
        // 会话回到空闲：最后再取一次页，收齐刚落地的答复再收口。
        val finalTexts = assistantTexts(app, sid)
        if (finalTexts.isNotEmpty()) lastText = finalTexts.last().second
        return DshResult(true, buildString {
          appendLine("harness 执行完成（会话 $sid）")
          appendLine()
          append(lastText.ifBlank { "（agent 未产出文本答复）" })
        })
      }
      try { Thread.sleep(2_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
    }
    return DshResult(
      lastText.isNotEmpty(),
      buildString {
        appendLine("harness 执行超时（${timeoutMs / 1000}s，会话 $sid 仍在运行）")
        appendLine("以下是她已产出的部分答复；可用 session_id=$sid 续问，或用 action=stop 停掉引擎。")
        appendLine()
        append(lastText.ifBlank { "（尚无文本产出）" })
      }
    )
  }

  /** 新建会话；落地在与悬浮球一致的「临时工作区」（读不到就退回默认分组）。 */
  private fun createSession(app: Context): String? {
    val req = JSONObject()
    tempWorkspaceId(app)?.let { req.put("workspaceId", it) }
    val resp = rpc(app, "session/create", JSONObject().put("request", req), 10_000) ?: return null
    return try {
      val root = resp.optJSONObject("result") ?: return null
      when (val value = root.opt("value")) {
        is JSONObject -> value.optString("sessionId").ifBlank { null }
        is String -> JSONObject(value).optString("sessionId").ifBlank { null }
        else -> null
      }
    } catch (_: Throwable) { null }
  }

  /** 与 OverlayService.tempWorkspaceId 同源：读 workspace.json 找「临时工作区」。 */
  private fun tempWorkspaceId(app: Context): String? = try {
    val file = File(app.filesDir, "home/.dsh/storages/workspace.json")
    if (!file.isFile) return null
    val tables = JSONObject(file.readText()).optJSONObject("tables")?.optJSONObject("workspaces") ?: return null
    var fallback: String? = null
    for (key in tables.keys()) {
      val entry = tables.optJSONObject(key) ?: continue
      if (fallback == null) fallback = key
      if (entry.optString("title") == "临时工作区") return key
    }
    fallback
  } catch (_: Throwable) { null }

  /** 会话是否在跑；null = 查不到（会话未注册或列表无回执）。 */
  private fun sessionRunning(app: Context, sid: String): Boolean? {
    val resp = rpc(app, "session/list", JSONObject().put("_request", JSONObject()), 5_000) ?: return null
    val items = resp.optJSONObject("result")?.optJSONObject("value")?.optJSONArray("items") ?: return null
    for (i in 0 until items.length()) {
      val item = items.optJSONObject(i) ?: continue
      val id = item.optString("sessionId").ifBlank { item.optString("id") }
      if (id == sid) return item.optBoolean("running", false)
    }
    return null
  }

  /** 取会话页里全部 assistant 文本（seq, text），按 seq 升序。 */
  private fun assistantTexts(app: Context, sid: String): List<Pair<Long, String>> {
    val args = JSONObject().put("request", JSONObject()
      .put("address", JSONObject().put("kind", "session").put("sessionId", sid))
      .put("throughSeq", 0)
      .put("maxMessages", 400))
    val resp = rpc(app, "session/page", args, 10_000) ?: return emptyList()
    val records = resp.optJSONObject("result")?.opt("value")?.let { value ->
      when (value) {
        is JSONObject -> value.optJSONArray("records")
        is String -> runCatching { JSONObject(value).optJSONArray("records") }.getOrNull()
        else -> null
      }
    } ?: resp.optJSONObject("result")?.optJSONArray("records") ?: return emptyList()
    val out = mutableListOf<Pair<Long, String>>()
    for (i in 0 until records.length()) {
      val record = records.optJSONObject(i) ?: continue
      if (record.optString("type") != "event") continue
      val event = record.optJSONObject("event") ?: continue
      val type = event.optString("type")
      if (type != "assistant/message" && type != "assistant_message" && type != "message") continue
      val data = event.optJSONObject("data") ?: continue
      val message = data.optJSONObject("message") ?: continue
      val text = when (val content = message.opt("content")) {
        is String -> content
        is JSONArray -> {
          val sb = StringBuilder()
          for (j in 0 until content.length()) {
            val block = content.optJSONObject(j) ?: continue
            if (block.optString("type", "text") == "text") sb.append(block.optString("text"))
          }
          sb.toString()
        }
        else -> ""
      }
      if (text.isNotBlank()) out.add(event.optLong("seq", i.toLong()) to text)
    }
    return out
  }

  // ── RPC 信封 ────────────────────────────────────────────────────────────────

  /**
   * 引擎 RPC（与 [EngineProbe.rpc] 同一条通道：`payload.args` 单字段、401 自动重认证一次）。
   *
   * 为什么在这里再包一层：`EngineProbe.rpc` 只管发；「401 ⇒ 换 cookie ⇒ 重发一次」是
   * 调用方纪律（OverlayService.postRpc 的同款逻辑），散落只会各写各的。
   */
  private fun rpc(app: Context, method: String, args: JSONObject, readTimeoutMs: Int = 8_000): JSONObject? {
    var resp = EngineProbe.rpc(app, method, args, readTimeoutMs)
    if (resp == null) {
      val probe = EngineProbe.check(800)
      if (probe.optString("auth") == "required") {
        runCatching { EngineAuth.handleUnauthorized(app) }
        resp = EngineProbe.rpc(app, method, args, readTimeoutMs)
      }
    }
    return resp
  }

  // ── 日志 / 凭据 ──────────────────────────────────────────────────────────────

  /** engine.log 尾部（默认 64KB 起读，token 已脱敏）。 */
  fun engineLogs(context: Context, tailLines: Int = 80): String {
    val app = context.applicationContext
    val log = File(app.filesDir, "engine.log")
    if (!log.isFile) return "（暂无 engine.log——引擎可能还没起过）"
    val text = runCatching { tailOf(log, 64 * 1024) }.getOrElse { "（读取失败：" + (it.message ?: it.javaClass.simpleName) + "）" }
    val redacted = EngineAuth.redact(text)
    val lines = redacted.lines()
    return if (lines.size <= tailLines) redacted else lines.takeLast(tailLines).joinToString("\n")
  }

  private fun tailOf(file: File, maxBytes: Int): String {
    RandomAccessFile(file, "r").use { raf ->
      val length = raf.length()
      val start = if (length > maxBytes) length - maxBytes else 0L
      raf.seek(start)
      val buf = ByteArray((length - start).toInt())
      raf.readFully(buf)
      return String(buf, Charsets.UTF_8)
    }
  }

  /**
   * 写 harness 自己的模型 key（`filesDir/deepseek-key.txt`，引擎 spawn 时经 shellEnv 注入
   * `DEEPSEEK_API_KEY`）。与 ZorvAI 的模型配置互不相干——harness 是独立 agent。
   */
  fun setApiKey(context: Context, key: String): DshResult {
    val app = context.applicationContext
    val trimmed = key.trim()
    if (trimmed.isEmpty()) return DshResult(false, "key 为空")
    return try {
      val f = File(app.filesDir, "deepseek-key.txt")
      f.writeText(trimmed + "\n")
      DshResult(true, "已保存 harness 的模型 key（${f.absolutePath}）\n下次 start / 派任务时生效（引擎重启后注入 DEEPSEEK_API_KEY）。")
    } catch (t: Throwable) {
      DshResult(false, "写入 key 失败：" + (t.message ?: t.javaClass.simpleName))
    }
  }
}

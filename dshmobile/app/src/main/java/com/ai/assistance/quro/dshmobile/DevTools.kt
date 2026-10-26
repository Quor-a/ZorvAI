package com.ai.assistance.quro.dshmobile

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import org.json.JSONObject

/**
 * 开发者选项门面（#418：功能从 harness WebUI「开发者选项」设置区迁入 ZorvAI 清理存储页）。
 *
 * ── 为什么是「门面」而不是让 ZorvAI 直接调各单例────────────────────────────
 * 与 [PhoneControl] / [NotifyCenter] / [DshmobileControl] 同一模式：本区的真源散落在
 * 模块内多个对象里——开关在 [DevLogControl] / [OverlayController]（后者是 internal 语义的
 * 壳侧单一真源），起停在 [DshmobileControl]（阻塞式），清理在**引擎侧插件**的 HTTP 端点
 * （`/api/android/file-incoming`、`/api/android/runtime-cache/scan|execute`，白名单与记账的权威实现
 * 都在那边）。ZorvAI 主模块只看得见 public 面，逐个去调既会把「哪些能调」泄漏成隐式契约，
 * 也会让主模块为了一个开关 import 一堆内部类。故收一个公开门面：ZorvAI 只依赖
 * `DevTools` 一个 object。
 *
 * ── 为什么不把清理逻辑在 Kotlin 侧重写一遍──────────────────────────────────
 * 运行时缓存的白名单/跳过原因码表、来件临时区的「只删自有临时项」记账，权威实现都在引擎侧
 * TS 插件里（dsh-android-linux-env / dsh-android-file-open）。Kotlin 侧重写一份必然漂移
 * （白名单更新只改一边 = 该删的不删、不该删的误删）。故本门面对这两个面**调用同一 HTTP
 * 端点**，与 WebUI 当年走的是同一条路；引擎未起 = 连接被拒 = 如实「读不到」。
 *
 * ── 数据纪律（从 WebUI 侧继承，改一行即违约）──────────────────────────────
 *  1. **读不到就如实说读不到**：HTTP 非 2xx / body 不可解析一律回落 `readable:false` 的
 *     结构（带 code/http/path/guidance），绝不拿「0 B / 暂无可清理」这类默认值冒充事实
 *     （S3-16：读不到 ≠ 0）。
 *  2. **写后回读**：开关类一律回读壳侧真值（[DevLogControl.isEnabled] = 偏好 && 采集器在跑；
 *     [OverlayController.isEnabled] = 偏好 && 悬浮窗权限 && 服务实例在场），不做乐观置位。
 *  3. **鉴权双通道**：connection-first 的浏览器会话 cookie（[EngineAuth.attach]）+ 原生
 *     loopback 控制令牌兜底（`X-DSH-Control-Token`，authorizeMobileRoute 语义：cookie 优先，
 *     browser-session 401 才落到令牌）。401 自动换 cookie 重试一次（同 [DshmobileControl]）。
 *  4. **代理纪律**：所有本地引擎 HTTP 一律 `Proxy.NO_PROXY`（#118：系统代理会把 loopback
 *     请求吞掉，表现为「网页打得开、应用说引擎没起」）。
 *  5. 所有方法返回结构化 JSON（`ok` + 业务字段 + `code`/`guidance`），失败不静默、
 *     不抛异常穿过 UI 层。
 *
 * ── 线程契约──────────────────────────────────────────────────────────────
 *  - [restartEngine] / [stopEngine]（[DshmobileControl.restart] 阻塞可达分钟级）、
 *    [exportConfig] / [importConfig]（文件 IO）、以及全部引擎 HTTP 读（[incomingUsage] /
 *    [runtimeCacheScan] / [runtimeCacheExecute] / [cleanIncoming] / [snapshot]）都是
 *    **阻塞式**的：**不得在主线程调用**。UI 侧一律 `Dispatchers.IO` + busy 态。
 *  - [devLogEnabled] / [overlayEnabled] / [hasAllFilesAccess] / [openConsole] 是轻量本地读/
 *    一次 startActivity，主线程可调（但仍建议与轮询同路，避免分叉）。
 */
object DevTools {

  private const val TAG = "dsh-dev-tools"

  // ── 引擎侧端点（权威实现都在插件里；路径即契约，勿改） ────────────────────
  private const val PATH_INCOMING = "/api/android/file-incoming"
  private const val PATH_INCOMING_CLEAN = "/api/android/file-incoming/clean"
  private const val PATH_CACHE_SCAN = "/api/android/runtime-cache/scan"
  private const val PATH_CACHE_EXECUTE = "/api/android/runtime-cache/execute"

  /** 控制令牌头（与 [DeviceControlService] 生成、引擎侧 live 比对的那一枚同名同值）。 */
  private const val HEADER_CONTROL_TOKEN = "X-DSH-Control-Token"

  // ── 调试日志 ─────────────────────────────────────────────────────────────

  /**
   * 调试日志开关当前值（ST-11：偏好 **&&** 采集器在跑——EngineService.onDestroy 会无条件
   * 停采集器，此后只回读偏好就是乐观置位：开关显示「开」而日志文件不再增长）。
   */
  fun devLogEnabled(context: Context): Boolean = DevLogControl.isEnabled(context)

  /** 写调试日志开关并回读真值（采集器起不来时 enabled=false，绝不冒充写入成功）。 */
  fun setDevLogEnabled(context: Context, on: Boolean): JSONObject {
    DevLogControl.setEnabled(context, on)
    val enabled = DevLogControl.isEnabled(context)
    return JSONObject()
      .put("ok", enabled == on)
      .put("requested", on)
      .put("enabled", enabled)
  }

  // ── 悬浮球 ───────────────────────────────────────────────────────────────

  /**
   * 悬浮球开关当前值（ST-02 真源合并：偏好 && Settings.canDrawOverlays && 服务实例在场；
   * 权限被系统撤销后展示值随事实回落，不会「显示开、球却不在」）。
   */
  fun overlayEnabled(context: Context): Boolean = OverlayController.isEnabled(context)

  /**
   * 写悬浮球开关并回读真源（[OverlayController.setEnabled]：未授 overlay 权限时偏好回落
   * false、跳一次系统授权页、Toast 说明；授权回来后由壳侧 onResume 的补启路径自动开启）。
   *
   * @return `{ok, requested, started, enabled, canDrawOverlays}`；`started` = 球此刻是否已在跑，
   *   `enabled` = 回读真值（UI 展示以它为准）。
   */
  fun setOverlayEnabled(context: Context, on: Boolean): JSONObject {
    val started = OverlayController.setEnabled(context, on)
    val enabled = OverlayController.isEnabled(context)
    return JSONObject()
      .put("ok", enabled == on)
      .put("requested", on)
      .put("started", started)
      .put("enabled", enabled)
      .put("canDrawOverlays", OverlayController.canDrawOverlays(context))
  }

  // ── 所有文件访问（决定调试日志落点提示文案） ──────────────────────────────

  /** True when the app holds All Files Access（API 30+ 才有该权限模型；与桥实现同口径）。 */
  fun hasAllFilesAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

  // ── 来件临时区（文件直达分享进来的临时文件） ──────────────────────────────

  /**
   * 来件临时区占用（GET `/api/android/file-incoming`）：
   * `{ok, pending, items, bytes}`（bytes = 临时工作区除 `.sessions` 外文件体积和）。
   *
   * 读不到回落 `readable:false`——**不是 0 B**（S3-16 口径）。
   */
  fun incomingUsage(context: Context): JSONObject =
    engineRequest(context, "GET", PATH_INCOMING, 8_000)

  /**
   * 一键清理来件临时区（POST `/api/android/file-incoming/clean`，FX-205.5：只删本工具
   * 自有临时项；`.sessions`/`.pending-notify.ndjson`/`.meta.ndjson`/用户文件一律保留）。
   *
   * 回包 `{ok, removed}` 原样透出；失败回落 `readable:false`。破坏性操作——UI 必须二次确认。
   */
  fun cleanIncoming(context: Context): JSONObject =
    engineRequest(context, "POST", PATH_INCOMING_CLEAN, 20_000)

  // ── 运行时缓存（白名单制；先给可回收体积再执行） ──────────────────────────

  /**
   * 运行时缓存扫描（GET `/api/android/runtime-cache/scan`，只读）：
   * `{ok, reclaimableBytes, targets[], skipped[]}`。targets/skipped 原样透出（label 是
   * `$DSH_HOME/...` 形态，绝不含绝对路径）；读不到回落 `readable:false`（**不是 0**）。
   */
  fun runtimeCacheScan(context: Context): JSONObject =
    engineRequest(context, "GET", PATH_CACHE_SCAN, 15_000)

  /**
   * 执行运行时缓存清理（POST `/api/android/runtime-cache/execute`）：
   * `{ok, removed, failed, removedBytes, plannedBytes, items[]}`，逐项结果与原因码原样透出
   * （失败/跳过如实呈现，不粉饰成「已清干净」）。破坏性操作——UI 必须二次确认。
   */
  fun runtimeCacheExecute(context: Context): JSONObject =
    engineRequest(context, "POST", PATH_CACHE_EXECUTE, 60_000)

  // ── 引擎起停 / 控制台 ────────────────────────────────────────────────────

  /**
   * 重启引擎（阻塞：先停含 userShutdown 解锁，再起；预算内 HTTP 不就绪如实回执）。
   *
   * 调用方必须自行切 IO 线程 + busy 态；回执 `{ok, text}`（text 是人话结论，可直接展示）。
   */
  fun restartEngine(context: Context): JSONObject =
    dshAnswer("restart-engine") { DshmobileControl.restart(context) }

  /**
   * 停止引擎（阻塞：请求前台服务停机 + 停本进程持有的子进程；端口在预算内未释放时如实
   * 回执「可能是其它入口持有的引擎」，不按名字杀进程）。
   */
  fun stopEngine(context: Context): JSONObject =
    dshAnswer("stop-engine") { DshmobileControl.stop(context) }

  /** 打开内置控制台（快照 bash 交互终端；引擎没起来时也能用于诊断）。 */
  fun openConsole(context: Context): JSONObject = runCatching {
    val app = context.applicationContext
    app.startActivity(
      Intent(app, ConsoleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    JSONObject().put("ok", true)
  }.getOrElse { readableFailure("open-console-failed", it) }

  // ── 配置导入 / 导出（安全手改通道） ───────────────────────────────────────

  /**
   * 导出活动配置（私有 settings.yaml -> Documents/dshdata/exports/config/）。
   *
   * 引擎 DSH_HOME 在私有域（外部改共享目录副本无效），这是安全的手改通道：导出 -> 文件管理器
   * 编辑 -> 导入。回包 `{ok, path?, hint?}` / `{ok:false, error?}`；阻塞文件 IO，勿主线程调。
   */
  fun exportConfig(context: Context): JSONObject =
    answerOf("export-config") { DshmobileControl.manager(context).exportConfig() }

  /**
   * 导入配置（exports/config/settings.yaml -> 私有 DSH_HOME；引擎 chokidar 热加载）。
   *
   * 旧 settings.yaml 不会覆盖已迁移的 profile 补丁；回包 `{ok, path?, hint?}` /
   * `{ok:false, error?}`。阻塞文件 IO，勿主线程调。
   */
  fun importConfig(context: Context): JSONObject =
    answerOf("import-config") { DshmobileControl.manager(context).importConfig() }

  // ── 聚合读面（UI 单次轮询） ──────────────────────────────────────────────

  /**
   * 一次拉全部轻量状态（清理存储页 2s 轮询走这里；含一次引擎 HTTP 读，故**勿主线程调**）。
   *
   * 不含运行时缓存扫描（那是目录遍历，按需触发）：`{ok, devLog, overlay, allFiles,
   * engine:{running, auth, listening, ...}, incoming:{...}}`。incoming 读不到时其内部
   * 自带 readable:false，不拖垮整个快照。
   */
  fun snapshot(context: Context): JSONObject {
    val out = JSONObject().put("ok", true)
    out.put("devLog", devLogEnabled(context))
    out.put("overlay", overlayEnabled(context))
    out.put("allFiles", hasAllFilesAccess(context))
    out.put("engine", runCatching { EngineProbe.check(800) }
      .getOrElse { readableFailure("engine-probe-failed", it) })
    out.put("incoming", incomingUsage(context))
    return out
  }

  // ── 引擎 HTTP（鉴权双通道 + 401 重试一次 + 结构化回落） ───────────────────

  /** 一次引擎应答（code + body 文本；body 可能为空串）。 */
  private data class EngineReply(val code: Int, val body: String)

  /**
   * 调一次引擎端点（GET/POST，body 均不需要——两端点都不读请求体）。
   *
   * 401 ⇒ [EngineAuth.handleUnauthorized] 换 cookie 后重发一次（与 [DshmobileControl] 的
   * RPC 纪律一致；重发仍失败才回落）。非 2xx / 连接失败 / body 不可解析一律结构化回落，
   * 不抛异常。
   */
  private fun engineRequest(
    context: Context,
    method: String,
    path: String,
    readTimeoutMs: Int,
  ): JSONObject {
    var retriedAfterUnauthorized = false
    while (true) {
      val reply = runCatching { attempt(context, method, path, readTimeoutMs) }
        .getOrElse { return readableFailure("engine-request-failed", it) }
      if (reply.code == 401 && !retriedAfterUnauthorized) {
        retriedAfterUnauthorized = true
        Log.w(TAG, "engine route 401 ($path); refreshing cookie and retrying once")
        runCatching { EngineAuth.handleUnauthorized(context) }
        continue
      }
      if (reply.code in 200..299) {
        return runCatching { JSONObject(reply.body) }
          .getOrElse { bodyUnparsable(path, reply.body, it) }
      }
      return httpFailure(path, reply.code, reply.body)
    }
  }

  /** 单次尝试（不含重试）。Proxy.NO_PROXY + cookie + 控制令牌双通道鉴权。 */
  private fun attempt(
    context: Context,
    method: String,
    path: String,
    readTimeoutMs: Int,
  ): EngineReply {
    val conn = URL(EngineProbe.ENGINE_URL + path)
      .openConnection(Proxy.NO_PROXY) as HttpURLConnection
    try {
      conn.requestMethod = method
      conn.connectTimeout = 4_000
      conn.readTimeout = readTimeoutMs
      conn.setRequestProperty("cache-control", "no-store")
      if (method == "POST") conn.doOutput = true
      // connection-first：先带浏览器会话 cookie（authorizeMobileRoute 的 connection 分支），
      // 再带原生 loopback 控制令牌兜底（browser-session 401 时才轮到它）。
      EngineAuth.attach(context, conn)
      runCatching { DeviceControlService.token(context) }.getOrNull()?.let { token ->
        conn.setRequestProperty(HEADER_CONTROL_TOKEN, token)
      }
      if (method == "POST") {
        // 空 body（Content-Length: 0）：两个 POST 端点都不读请求体。
        conn.outputStream.use { }
      }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
      return EngineReply(code, body)
    } finally {
      conn.disconnect()
    }
  }

  // ── 内部工具 ─────────────────────────────────────────────────────────────

  /** 读路径失败的结构化回落：`readable:false` + code + guidance（绝不返回「看起来正常」的默认结构）。 */
  private fun readableFailure(code: String, error: Throwable): JSONObject =
    JSONObject().put("ok", false).put("readable", false).put("code", code)
      .put("guidance", error.javaClass.simpleName + ": " + (error.message ?: ""))

  /** HTTP 非 2xx 的结构化回落（code/http/path + 响应摘要，长度有界防灌屏）。 */
  private fun httpFailure(path: String, code: Int, body: String): JSONObject =
    JSONObject().put("ok", false).put("readable", false)
      .put("code", "engine-http-" + code)
      .put("http", code)
      .put("path", path)
      .put("guidance", body.take(300).ifBlank { "HTTP $code" })

  /** 2xx 但 body 不是预期 JSON 的结构化回落（不把脏数据当业务回包）。 */
  private fun bodyUnparsable(path: String, body: String, error: Throwable): JSONObject =
    JSONObject().put("ok", false).put("readable", false)
      .put("code", "engine-body-unparsable")
      .put("path", path)
      .put("guidance", error.javaClass.simpleName + ": " + (error.message ?: "") +
        " (body " + body.length + "B)")

  /** 把壳侧既有的「JSON 文本」回包解析成 JSONObject（解析失败同样结构化，不抛）。 */
  private fun answerOf(code: String, block: () -> String): JSONObject = runCatching {
    JSONObject(block())
  }.getOrElse { readableFailure(code, it) }

  /** 把 [DshmobileControl.DshResult] 映射成 `{ok, text}`（阻塞调用点自备 IO 线程）。 */
  private fun dshAnswer(code: String, block: () -> DshmobileControl.DshResult): JSONObject =
    runCatching {
      val r = block()
      JSONObject().put("ok", r.ok).put("text", r.text)
    }.getOrElse { readableFailure(code, it) }
}

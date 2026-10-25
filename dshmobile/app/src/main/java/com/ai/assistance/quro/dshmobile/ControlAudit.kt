package com.ai.assistance.quro.dshmobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 授权审计（原生侧写面，与 dsh-android-bridge 插件同路径同格式：
 * files/audit/audit.ndjson 换行分隔 JSON；ts=ISO8601 UTC + action + tool + args + result，
 * 不含任何凭据/配对码值）。
 *
 * 0.14.0：随内置 adb 退役从 AdbState 迁出（原 AdbAudit，含 transport/uid/op 字段）。
 *
 * ── G-7（2026-09-25）：result 不再恒为 ok ──────────────────────────────────────
 * 旧实现把 `result` 硬编码成 `"ok"`，而调用方把真值塞在 `args.ok` 里（ShellOps.kt 传 `"ok" to ok`）。
 * 后果是审计**无法回答「谁执行了什么、结果如何」**：失败的特权命令在 ndjson 里与成功逐字相同，
 * 出现 `result:"ok"` 与 `args.ok:false` 自相矛盾的记录，事后复盘不可信（审查 §5.3 / EXECUTION-MAP K04）。
 * 现 `result` 由**调用方显式传入**，取值三态且互斥：
 *   - [RESULT_OK]     执行成功；
 *   - [RESULT_FAILED] 特权通道**真的执行了但失败**（Shizuku 返回 ok=false）；
 *   - [RESULT_DENIED] 范围/授权门在**执行前**拒绝（如 screen-out-of-scope，命令从未下发）。
 * 后两者在旧实现里与成功不可辨，正是本次修的对象。取值与插件侧 `audit()` 的 ok/denied 口径一致
 * （plugins/dsh-android-bridge/src/index.ts 也写 `ok`/`denied`），多出的 `failed` 让「下发后失败」
 * 与「下发前拒绝」也能分开——这两件事在取证上完全不同（有没有真的碰过设备）。
 *
 * 落盘核心 [entry] / [appendTo] 只依赖 [File]，不依赖 Context：本项目测试面**没有 Mockito**
 * （见 BootFailLogTest 头注），抽出来后 JVM 单测可用 TemporaryFolder 直接驱动，
 * 从而对「result 反映真实结果」给出可证伪断言，而不是只能靠人读设备日志。
 */
object ControlAudit {

  /** 执行成功。 */
  const val RESULT_OK = "ok"

  /** 特权通道真的执行了，但返回失败（Shizuku 侧 ok=false）。 */
  const val RESULT_FAILED = "failed"

  /** 范围/授权门在执行**之前**拒绝——命令从未下发。 */
  const val RESULT_DENIED = "denied"

  /** 审计记录的工具名（与插件侧 `tool` 字段同面）。 */
  const val TOOL = "shell-native"

  private val TS = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
  }

  /**
   * 纯函数：构造一条审计记录（不落盘、不依赖 Context）。
   *
   * @param action 动作名（如 `shExec`）。
   * @param result 真实结果，取值必须是 [RESULT_OK] / [RESULT_FAILED] / [RESULT_DENIED] 之一。
   *   拒绝其余取值而不是静默接受：拼错的结果串会让这回退到「恒 ok」的老形态（本次修的缺陷）。
   * @param args 参数面（不含凭据/配对码；`detail` 已由调用方截断）。
   * @return 可直接序列化进 ndjson 的 JSON 对象。
   * @throws IllegalArgumentException result 不在三态之内。
   */
  fun entry(action: String, result: String, args: Map<String, Any?>): JSONObject {
    require(result == RESULT_OK || result == RESULT_FAILED || result == RESULT_DENIED) {
      "audit result 必须是 $RESULT_OK/$RESULT_FAILED/$RESULT_DENIED 之一，实得 '$result'"
    }
    return JSONObject()
      .put("ts", TS.format(Date()))
      .put("action", action)
      .put("tool", TOOL)
      .put("args", JSONObject(args as Map<*, *>))
      .put("result", result)
  }

  /** 纯函数：把一条记录追加到 `<auditDir>/audit.ndjson`（只依赖 [File]，供 JVM 单测直驱）。 */
  fun appendTo(auditDir: File, record: JSONObject) {
    auditDir.mkdirs()
    File(auditDir, "audit.ndjson").appendText(record.toString() + "\n")
  }

  /**
   * 落盘一条审计记录。审计失败不阻断授权（隐私优先，静默放弃）。
   *
   * @param context 用于解析 filesDir。
   * @param action 动作名。
   * @param result 真实结果（[RESULT_OK] / [RESULT_FAILED] / [RESULT_DENIED]）。
   * @param args 参数面。
   */
  fun log(context: Context, action: String, result: String, args: Map<String, Any?>) {
    try {
      appendTo(File(context.filesDir, "audit"), entry(action, result, args))
    } catch (_: Throwable) {
      /* 审计失败不阻断授权（隐私优先，静默放弃） */
    }
  }
}
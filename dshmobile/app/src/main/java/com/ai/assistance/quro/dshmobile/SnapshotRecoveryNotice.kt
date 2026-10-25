package com.ai.assistance.quro.dshmobile

/**
 * 快照恢复期「拒绝回滚」的用户可见上报（0.14.2-fx-2 缺口）。
 *
 * ── 缺陷现场 ────────────────────────────────────────────────────────────────
 * `applyRecovery` 的 ROLLBACK_FAILED 分支把结构化原因写进了 logcat 与引擎诊断面，
 * **但没有任何界面提示**：用户的数据被保护了（拒绝把空备份搬回去覆盖现场）却完全不知情。
 * 对照：`EngineStartFlow` 里 `refreshSnapshot` 返回 false 那条（同文件的 :552 参照）**有**
 * 完整上报路径（writeBootFail + 错误页 + 诊断包）⇒ 缺口只在恢复期这一支。
 *
 * ── 为什么抽成纯函数 ─────────────────────────────────────────────────────────
 * 「拒绝是否产生可见提示」必须可被判据钉住。若把文案直接写在 applyGuidePhase 调用里，
 * 测试只能做文本断言（本仓实锤：文本断言锁不住判据——把上报删掉，源码里仍有那句话）。
 * 抽成纯函数后，测试驱动真逻辑：给定 failures ⇒ 断言返回 notice 的 code/标题/原因。
 */
internal object SnapshotRecoveryNotice {

  /**
   * 稳定码（与 `snapshot-refresh-failed` 同族）。
   *
   * 为什么用码而不是在 UI 里硬编码英文：boot-fail.log 与诊断包的字段要能被 grep 归类，
   * 而用户可见文案必须走**壳侧中文**（两者不能是同一个字符串）。
   */
  internal const val CODE = "snapshot-recovery-rejected"

  /** 用户可见的上报内容。 */
  internal data class Notice(
    val code: String,
    val title: String,
    val hint: String,
  )

  /**
   * 由恢复失败明细构造上报内容。
   *
   * @param detail `EngineManager.pendingRecoveryFailure`（回滚未落地的条目明细）。
   *   null/空 = 本次恢复没有拒绝 ⇒ 返回 null（**不得**凭空造提示）。
   * @returns Notice；或 null 表示无需上报。
   *
   * 文案纪律（如实、不承诺）：
   *   · 说清「更新被拒绝」与「**你的数据没有被改动**」——这是拒绝的全部意义；
   *   · 给出去哪看（控制台/诊断包），但**不承诺**「重试一定能修好」——
   *     恢复失败可能来自半份备份、属主异常等，壳侧无从保证下一次收敛。
   */
  internal fun forRejection(detail: String?): Notice? {
    val d = detail?.trim().orEmpty()
    if (d.isEmpty()) return null
    return Notice(
      code = CODE,
      title = "运行时更新被拒绝",
      hint = "已保留现有运行时与你的数据，未做任何替换。原因：" + d +
        "。可打开控制台查看日志，或复制诊断包路径反馈；本轮不会自动重试替换。",
    )
  }

  /** An unresolved journal plus a recorded recovery refusal must never reach probe or spawn. */
  internal fun blocksRuntimeStart(detail: String?, transactionMarkerPresent: Boolean): Boolean =
    !detail.isNullOrBlank() && transactionMarkerPresent

  // ── 一次性标记：让提示能跨到 WebUI 可见面 ──────────────────────────────────

  /**
   * 待注入标记文件名（壳侧，`files/` 下）。
   *
   * 为什么要一个文件而不是内存字段：发出拒绝的是**启动流**，而提示要显示在**引擎 WebUI** 上
   * （引导页会被 `showWeb()` 盖掉）。两者之间隔着「引擎起没起、页面加载完没有」的时序，
   * 用文件才能在页面就绪后补发。
   */
  internal const val PENDING_FILE = ".recovery-rejected-notice"

  /**
   * 写入待注入标记（**只在 ROLLBACK_FAILED 时**调用）。
   *
   * @returns 写入的文本（成功）；null = 无需写（明细为空）。
   */
  internal fun markPending(dir: java.io.File, detail: String?): Boolean {
    val notice = forRejection(detail) ?: return false
    return try {
      // 标题与正文都要留：页面注入时两者分工不同（标题一行、正文可换行）。
      // 用换行分隔（标题不含换行，正文也不含）——解析见 [splitMarker]。
      java.io.File(dir, PENDING_FILE).writeText(notice.title + "\n" + notice.hint)
      true
    } catch (_: Throwable) {
      false
    }
  }

  /**
   * 拆分标记内容为 (标题, 正文)。
   *
   * 兼容只有一行的情况（历史/人工写入）：整行当正文，标题用默认标题。
   */
  internal fun splitMarker(raw: String): Pair<String, String> {
    val t = raw.trim()
    val nl = t.indexOf('\n')
    return if (nl < 0) DEFAULT_TITLE to t else t.substring(0, nl).trim() to t.substring(nl + 1).trim()
  }

  /** 默认标题（标记只有一行时的兜底）。 */
  internal const val DEFAULT_TITLE = "运行时更新被拒绝"

  /**
   * 读取待注入文案（**不删除**——删除由 [consume] 在注入成功后执行）。
   *
   * 为什么读与删分开：注入可能失败（WebView 未就绪、页面正在导航）。若「读到即删」，
   * 那次失败就把提示永久吞掉了；用户既没看到、也没有下一次机会。
   */
  internal fun pending(dir: java.io.File): String? = try {
    val f = java.io.File(dir, PENDING_FILE)
    if (f.isFile) f.readText().trim().takeIf { it.isNotEmpty() } else null
  } catch (_: Throwable) {
    null
  }

  /**
   * 注入**成功后**删除标记（一次性语义）。
   *
   * @returns true = 已删除或本就不存在。
   */
  internal fun consume(dir: java.io.File): Boolean = try {
    val f = java.io.File(dir, PENDING_FILE)
    if (!f.exists()) true else f.delete()
  } catch (_: Throwable) {
    false
  }

  /**
   * **本进程**曾上报过的提示文案（内存，进程内有效）。
   *
   * 为什么要它（lead 明确要求把「跨轮次存续」与「本轮存续」分开）：
   *   · 文件标记：注入成功即删 ⇒ 以后的启动不再唠叨（不狼来了）；
   *   · 内存文案：WebUI 页面重载（`onPageFinished`）后要**重新注入**，否则用户刷新一次就再也看不到。
   * 两者合起来才同时满足「看得到」与「不重复唠叨」。进程被杀即清空——那是正确语义：
   * 下次启动若事务仍未收敛，恢复流程会重新置位文件标记。
   */
  @Volatile
  internal var inProcessText: String? = null
    private set

  /** 记录本次进程要持续显示的提示（注入成功后调用）。 */
  internal fun remember(text: String) {
    inProcessText = text
  }

  /** 清除进程内提示（测试用 / 事务收敛后）。 */
  internal fun forget() {
    inProcessText = null
  }

  /** 注入到页面的容器 id（稳定，重注入时先移除旧的，避免叠字）。 */
  internal const val DOM_ID = "__dsh-recovery-notice"

  /**
   * 构造注入脚本（纯函数，可单测）。
   *
   * 设计约束（lead 四条硬要求第 3 条「注入不能伤页面」）：
   *   · `pointer-events:none` —— 不遮挡任何核心交互（用户点得到下面的东西）；
   *   · **不自动隐藏** —— 用户必须来得及读完（自动消失的提示在真机上等于没有）；
   *   · 固定 id，重复注入时**先删旧节点**再插 —— 否则 `onPageFinished` 补注会叠字；
   *   · 只写文本（`textContent`），不解析 HTML —— 明细来自文件，绝不能当 HTML 注入。
   *
   * @param title 标题（一行）。
   * @param body 正文（可换行）。
   * @returns 可直接交给 `evaluateJavascript` 的脚本。
   */
  internal fun injectionScript(title: String, body: String): String {
    val id = jsString(DOM_ID)
    val t = jsString(title)
    val b = jsString(body)
    return "(function(){" +
      "var old=document.getElementById(" + id + ");if(old&&old.parentNode)old.parentNode.removeChild(old);" +
      "var d=document.createElement('div');d.id=" + id + ";" +
      "d.style.cssText='position:fixed;left:8px;right:8px;bottom:8px;z-index:2147483647;" +
      "pointer-events:none;box-sizing:border-box;padding:10px 12px;border-radius:10px;" +
      "background:rgba(38,38,38,.94);color:#fff;font-size:13px;line-height:1.5;" +
      "white-space:pre-wrap;word-break:break-word;font-family:system-ui,sans-serif;';" +
      "var h=document.createElement('div');h.textContent=" + t + ";" +
      "h.style.cssText='font-weight:600;margin-bottom:4px;';" +
      "var p=document.createElement('div');p.textContent=" + b + ";" +
      "d.appendChild(h);d.appendChild(p);" +
      "(document.body||document.documentElement).appendChild(d);" +
      "return true;})()"
  }

  /**
   * JS 字符串字面量转义（只依赖 JSON 编码，避免手写转义漏洞）。
   *
   * 与 `AndroidBridge.kt` 的 jsString 同思想：明细来自文件/异常消息，可能含引号、反斜杠、换行。
   * 若直接拼进脚本会闭合字符串字面量 ⇒ 整段脚本语法错误 ⇒ 注入静默失败。
   */
  internal fun jsString(value: String): String {
    val sb = StringBuilder("\"")
    for (ch in value) {
      when (ch) {
        '\\' -> sb.append("\\\\")
        '"' -> sb.append("\\\"")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
      }
    }
    sb.append("\"")
    return sb.toString()
  }

  // ── 迁移收敛判定：pendingRecoveryFailure 的复位条件（纯函数，可单测）──────────

  /**
   * 该恢复结局是否代表「事务已收敛」（⇒ 应复位 `pendingRecoveryFailure`）。
   *
   * 为什么抽出来：复位点原本埋在 `EngineManager.applyRecovery` 的 when 分支里，
   * 要构造 EngineManager 才能测（需要 Context，本仓测试面无 Robolectric）。
   * 抽成纯函数后可直接断言 4 种收敛结局 + 1 种未收敛结局。
   *
   * @param outcome 恢复结局（用序号规避对 SnapshotTransaction 的编译期依赖，见调用方传值）。
   * @returns true = 已收敛（应清空陈旧明细）。
   */
  internal fun recoveryConverged(isRollbackFailed: Boolean): Boolean = !isRollbackFailed

  /**
   * 一次性消费的**纯判定**（可注入，供单测与调用方共用同一条逻辑）。
   *
   * 语义矩阵（lead 四条硬要求里的第 1、3 条）：
   *   · 无标记                    => NOTHING（不注入、不删）
   *   · 有标记 + 注入成功          => INJECT 且**删标记** + 记内存
   *   · 有标记 + 注入失败          => INJECT 但**不删标记**（下次补发）
   *
   * @param pending 待注入文案（null = 无标记）。
   * @param inject 注入动作；返回 true = 注入成功。
   * @param consumeOnSuccess 注入成功后的删除动作。
   * @returns true = 发生过一次注入尝试（成功与否）；false = 无标记。
   */
  internal fun deliver(pending: String?, inject: (String) -> Boolean, consumeOnSuccess: () -> Unit): Boolean {
    if (pending.isNullOrBlank()) return false
    val ok = try {
      inject(pending)
    } catch (_: Throwable) {
      false
    }
    if (ok) {
      remember(pending)
      try {
        consumeOnSuccess()
      } catch (_: Throwable) {
        // 删除失败不影响本次已成功的注入；下次启动会再发一次（可接受：宁可多一次，不可漏）。
      }
    }
    return true
  }
}

package com.ai.assistance.quro.dshmobile

/**
 * 快照刷新失败的**持久账本**与降级判据（纯逻辑，JVM 可直接测；不触碰 Android 类）。
 *
 * ── 为什么需要（详档 0.14.1-preview-DEFECT-BATCH-FIX-PLAN §1.3）────────────────
 * 冷启动路径是 `if (!snapshotFresh()) refreshSnapshot(...)`，失败即 `GuidePhase.Error` 并 return。
 * 这只记账在**内存**里（`EngineStartFlow.engineRetryCount` 是 #118 的**引擎启动**重试计数），
 * 与快照刷新无关，且进程一死即清零。于是「同一份指向上失败」的形态是：
 * **每次冷启动都重跑一次全量刷新、每次以同样原因失败**，用户被无限拦在引导页。
 *
 * ── 判据为什么是「live 树完整性」而不是「失败次数」 ─────────────────────────
 * refresh 失败的常见真因是快照缺失或解压不全——这时「放行」等于拉起一棵**不完整**的运行时，
 * 比拦在引导页更坏，且会以「引擎能起但插件缺」的形态静默劣化（幽灵缺陷的定义形态）。
 * issue 现场的真正特征是 **live 运行时完整可用、缺的只是提交文件（fingerprint）**。
 * 故降级的必要条件里必须有 `liveComplete`，失败次数只是「不值得再自动重试」的依据。
 *
 * 账本以 **fingerprint** 为键：换了快照（App 升级）即视为新问题，计数清零重来。
 */
internal object SnapshotRefreshPolicy {

  /** 连续失败到第几次起不再自动重试。**建议值，未经真机全面校准**（详档 §7 第 5 项）。 */
  const val FAILURE_THRESHOLD = 3

  /** 账本形态：单行 `<fingerprint>\t<consecutiveFailures>`（单行便于原子重写与人工查看）。 */
  private const val SEPARATOR = '\t'

  fun encode(fingerprint: String, failures: Int): String =
    fingerprint.trim() + SEPARATOR + failures.coerceAtLeast(0)

  /** 解析账本；空/畸形/负计数一律 null（**不得**把不可解析当成「已失败多次」）。 */
  fun parse(raw: String?): Pair<String, Int>? {
    if (raw == null) return null
    val line = raw.trim()
    if (line.isEmpty()) return null
    val at = line.lastIndexOf(SEPARATOR)
    if (at <= 0 || at == line.length - 1) return null
    val fp = line.substring(0, at).trim()
    val n = line.substring(at + 1).trim().toIntOrNull() ?: return null
    if (fp.isEmpty() || n < 0) return null
    return fp to n
  }

  /**
   * 记一次失败后的账本内容。**换 fingerprint 即清零重计**（同一份快照上失败才累积）。
   */
  fun afterFailure(raw: String?, fingerprint: String): String {
    val prev = parse(raw)
    val n = if (prev != null && prev.first == fingerprint.trim()) prev.second + 1 else 1
    return encode(fingerprint, n)
  }

  /**
   * 是否应当**降级**：跳过本次自动刷新、以现有运行时启动。
   *
   * 三个条件全中才降级：① live 树完整；② 账本存在；③ 账本指纹与当前内嵌快照一致且计数达阈。
   * 任一不满足一律**维持现状拦截**（宁可再失败一次，也不拉起一棵不完整的运行时）。
   */
  fun shouldDegrade(
    raw: String?,
    fingerprint: String,
    liveComplete: Boolean,
    threshold: Int = FAILURE_THRESHOLD,
  ): Boolean {
    if (!liveComplete) return false
    val fp = fingerprint.trim()
    if (fp.isEmpty()) return false
    val prev = parse(raw) ?: return false
    return prev.first == fp && prev.second >= threshold.coerceAtLeast(1)
  }
}

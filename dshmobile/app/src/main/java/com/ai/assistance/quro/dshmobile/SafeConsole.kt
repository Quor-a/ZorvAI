package com.ai.assistance.quro.dshmobile

/**
 * 控制台离线口令 `dsh safe` 的解析结果（缺陷 D/fx-2）。
 *
 * 为什么需要壳侧解析（而不是让 bash 去理解）：控制台在**引擎已死**时仍可用，而
 * `dsh safe` 要动的是 profile 装配清单——那是壳侧文件，bash 侧既没有权限语义也没有事务纪律。
 * 命中口令时**不落 bash**（见 ConsoleActivity.submit），因此解析必须是纯函数且判据收紧到
 * 「只有这几种写法才算命令」，否则用户敲的任何以 dsh 开头的东西都会被壳吞掉。
 */
internal enum class SafeAction { ON, OFF, STATUS }

/**
 * 把一行控制台输入解析成 [SafeAction]；**不是本口令一律返回 null**（原样交给 bash）。
 *
 * 判据（故意收紧）：
 *  - 首词必须是 `dsh`，次词必须是 `safe`；
 *  - 第三个词只能是 `on` / `off` / `status`，**或没有**（`dsh safe` 等价 `dsh safe on`，
 *    因为用户口径就是「按一下就以 safe 运行」）；
 *  - 多余词（`dsh safe on extra`）返回 null —— 宁可交给 bash 报错，也不猜用户想干什么；
 *  - 大小写不敏感、空白归一（`  DSH   SAFE  OFF ` 可解析）；
 *  - 反证锚点：`echo hi` / `dsh` / `dsh safe-mode` / 空串 全部必须返回 null。
 *
 * @param line 控制台输入的一行（可含前导/尾随空白）。
 * @returns 命中的动作；未命中返回 null。
 */
internal fun parseSafeCommand(line: String): SafeAction? {
  val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.lowercase() }
  if (parts.size !in 2..3) return null
  if (parts[0] != "dsh" || parts[1] != "safe") return null
  if (parts.size == 2) return SafeAction.ON
  return when (parts[2]) {
    "on" -> SafeAction.ON
    "off" -> SafeAction.OFF
    "status" -> SafeAction.STATUS
    else -> null
  }
}

/** 口令的回执文案（纯函数，便于逐条断言「说的是实话」）。 */
internal fun safeCommandUsage(): String =
  "safe 用法：dsh safe（进入安全模式） / dsh safe off（退出） / dsh safe status（查看）"

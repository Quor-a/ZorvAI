package com.ai.assistance.quro.dshmobile

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes

/**
 * Symbolic-link-safe filesystem primitives shared by the snapshot transaction and
 * the legacy user-data recovery. Every helper here is deliberately NOFOLLOW: a
 * dangling link inside the runtime tree is ordinary upgrade residue and must never
 * be resolved into the live tree, and a recursive delete must never escape through
 * a link into user data.
 */
internal object SnapshotFs {

  /** Existence without following a symbolic link. */
  fun exists(file: File): Boolean = Files.exists(file.toPath(), NOFOLLOW_LINKS)

  /** True when [file] is a symbolic link, dangling or not. */
  fun isSymbolicLink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

  /**
   * Deletes a file, directory or link without following links.
   *
   * 逐项容错（0.14.0 模拟器实锤）：**一个删不掉的条目曾让整个快照刷新永久卡死**。
   * 现象：模拟器异常掉线时解压中断，留下 `.snapshot-stage/home`；该目录的内部元数据损坏，
   * `ls` 看是空的、`rm -rf` 与 `rmdir` 都删不掉（\`Not a data message\` ／ \`Directory not empty\`）。
   * 而本方法是 refreshSnapshot 的第一步（清理上次残留），它一抛异常就：
   *   ① 本次刷新失败；② 回滚也走同一方法 → **回滚同样失败**（实测日志：
   *   \`snapshot refresh rollback failed; recovery marker retained\`）；
   *   ③ 残留永远存在 ⇒ **之后每次启动都失败**，用户只能清应用数据。
   *
   * 因此这里不能「遇到坏条目就整体失败」：能删的必须删掉，删不掉的**如实记下并继续**，
   * 由调用方决定是否致命。清理阶段的残余不影响后续解压到干净的 staging 目录——
   * 反过来，因一个残余就让整条升级链永久瘫痪，是远比残留更严重的问题。
   *
   * **容错契约的覆盖面（0.14.1 加固，issue #240 的剩余缺口）**：本方法同时是刷新的第一步
   * （`EngineManager` 清理残留）与回滚路径的公共原语（`SnapshotTransaction.rollbackEntry`），
   * 而此前这里只兜 `Exception`。issue #240 现场那个形状正是「`Error` 打穿整条链」：
   * `NoSuchMethodError`（`Stream.toList()` 在 API < 34 上的形态）是 `Error` 而非 `Exception`，
   * 它既绕过本方法的逐项容错，又绕过 `EngineManager` 紧随其后的「残渣改名挪开」兜底，
   * 一路打穿到刷新失败的 `catch (t: Throwable)` 去走回滚——而回滚走的是同一个原语。
   * 故判定交给 [isTolerableDeletionFailure]：**容忍 `Exception` + `LinkageError`，重抛 `VirtualMachineError`**。
   * 不写成裸 `catch (Throwable)` 是刻意的：`OutOfMemoryError` / `StackOverflowError` 在一个递归删除里
   * 被降级成「继续删」只会放大失败，那不是容错而是掩盖。
   *
   * @param onFailure 单条删除失败时的回调（收集诊断用）；不抛异常。签名收 `Throwable`——
   *   被上报的失败不再限于 `Exception`（见上）。**刻意放在最后一个参数**：本仓既有调用点
   *   一律写作 `deletePath(x) { f, e -> … }` 的尾随 lambda 形式（EngineManager 与各单测共 5 处），
   *   放在最后才能继续绑定到它、不逼着所有调用点改名传参。
   * @param listChildren 列目录的实现。默认即生产用法（`newDirectoryStream` + Kotlin stdlib 的
   *   `toList()`，**无 API 级别依赖**）。显式传参是为了让「列目录时抛 `Error`」这种场景能在
   *   JVM 单测里**行为对照**地判红，而不是只能靠静态扫描（本仓纪律：测试改为显式传参，
   *   生产面不留测试缝）。
   */
  fun deletePath(
    path: File,
    listChildren: (Path) -> List<Path> = { dir -> Files.newDirectoryStream(dir).use { it.toList() } },
    onFailure: (File, Throwable) -> Unit = { _, _ -> },
  ) {
    deleteRecursive(path, listChildren, onFailure)
  }

  /**
   * **严格**删除：删不净**必须抛**（issue #271 的起点）。
   *
   * 与容错的 [deletePath] 分工刻意划清：清理**可能损坏的历史残渣**用容错版（能删的删掉、
   * 删不掉的记账继续）；而**作为 rename 前置条件**的「目标必须为空」只能用严格版——
   * 容错版可能正常返回而目录**仍非空**，紧接着的 move 就抛
   * FileSystemException: … Directory not empty（用户实报的正是这条）。
   *
   * 真机现场（issue #271 作者实测）：挡住删除的不是普通孤儿，而是 root:root 属主残留——
   * 工厂快照自带的 usr/lib/python3.14 下的 __pycache__ 被打上 root:root，app uid 递归删时
   * 32 处 EACCES，root 删则立即成功。这类失败没有任何理由被静默吞掉。
   *
   * @throws SnapshotFsException code=snapshot-delete-residue，residue 含残留条目。
   */
  fun deletePathStrict(
    path: File,
    listChildren: (Path) -> List<Path> = { dir -> Files.newDirectoryStream(dir).use { it.toList() } },
  ) {
    val failures = mutableListOf<Pair<File, Throwable>>()
    deleteRecursive(path, listChildren) { file, t -> failures += file to t }
    // 复查 exists()：删不掉时可能既不抛也没真删（静默失败），「调用返回了」不等于「删干净了」。
    val residue = LinkedHashSet<File>()
    if (Files.exists(path.toPath(), NOFOLLOW_LINKS)) residue += path
    failures.forEach { residue += it.first }
    if (residue.isEmpty()) return
    throw SnapshotFsException(
      code = CODE_DELETE_RESIDUE,
      residue = residue.toList(),
      message = "快照事务无法清空目录（有条目删不掉）：" + deletionResidueSummary(path, failures)
        + "。最常见真因是目录内残留了非应用属主的条目（工厂快照里被打上 root:root 的 __pycache__ 等），"
        + "app uid unlink 会被 EACCES 拒绝——存在非应用属主残留，需先修正属主后重试。",
      cause = failures.firstOrNull()?.second,
    )
  }

  /**
   * 递归删除的**唯一实现**：容错版与严格版共用，避免两份递归各自漂移。
   * 逐项容错的语义只体现在调用方如何处理 [onFailure]。
   */
  private fun deleteRecursive(
    path: File,
    listChildren: (Path) -> List<Path>,
    onFailure: (File, Throwable) -> Unit,
  ) {
    val nioPath = path.toPath()
    try {
      if (!Files.exists(nioPath, NOFOLLOW_LINKS)) return
      val attrs = Files.readAttributes(nioPath, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
      if (attrs.isDirectory) {
        // 目录项本身读取失败（元数据损坏）也由下面的兜底接住：记下并跳过，不中断整棵树。
        // 注意 `Files.newDirectoryStream` 而非 `Files.list`：后者返回的 Stream 是 Java 16 /
        // Android API 34 才有的面，在 API < 34 上列目录本身就抛 `NoSuchMethodError`
        // （0.14.1 P0 真机实锤，本方法历史上正是崩在这里）。
        for (child in listChildren(nioPath)) deleteRecursive(child.toFile(), listChildren, onFailure)
      }
      Files.deleteIfExists(nioPath)
    } catch (t: Throwable) {
      // 非容忍类（VirtualMachineError / ThreadDeath / AssertionError …）必须原样抛出：
      // 把它们降级成「继续删」等于用一个更坏的失败掩盖当前失败。
      if (!isTolerableDeletionFailure(t)) throw t
      onFailure(path, t)
    }
  }

  /** 诊断摘要：最多点名 5 条残留 + 总数。 */
  private fun deletionResidueSummary(path: File, failures: List<Pair<File, Throwable>>): String {
    val named = failures.take(5).joinToString(", ") {
      it.first.absolutePath + " (" + it.second.javaClass.simpleName + ")"
    }
    val more = if (failures.size > 5) " 等共 " + failures.size + " 条" else ""
    return path.absolutePath + (if (named.isEmpty()) "" else " -> " + named + more)
  }

  /**
   * 列出 [root] 下**非本应用属主**的条目（issue #271 预检与失败文案共用）。
   *
   * 用途有二：① 事务前的可写性预检（判死「继续下去必然半搬」的现场）；
   * ② 删除失败时的归因——把「删不掉」直接翻成「存在非应用属主残留，需先修正属主」。
   * @param maxDepth 扫描深度上限（启动路径上必须廉价）。
   */
  fun foreignOwnedEntries(root: File, maxDepth: Int = 6): List<File> {
    if (!exists(root)) return emptyList()
    val out = mutableListOf<File>()
    fun walk(dir: File, depth: Int) {
      if (depth > maxDepth || !exists(dir)) return
      if (!ownedByApp(dir)) { out += dir; return }
      for (child in dir.listFiles() ?: return) walk(child, depth + 1)
    }
    walk(root, 0)
    return out
  }

  /**
   * Rename within one filesystem; falls back to a plain move when ATOMIC_MOVE is unsupported.
   *
   * **目标必须先清空**（issue #271 的主修）：`Files.move` 在目标为非空目录时抛
   * `FileSystemException: … Directory not empty`，而**`REPLACE_EXISTING` 对非空目录同样会失败**
   * ——所以「删净并断言」是必须的，加个选项解决不了问题。
   *
   * 旧实现没有任何前置断言：目标残留（历史孤儿树 / 一份 9 天前的 `.snapshot-previous/usr/lib`）
   * 直接让 `replaceEntry` 的 `move(staged, live)` 失败，而那一刻 `live` 已被搬走 ⇒ 运行时缺失
   * ⇒ `node: No such file or directory` ⇒ 引擎 90s 死亡并反复重启。
   *
   * 这里只做**自保**（删净 + 断言），不做「静默兜底改名」：调用方需要知道这件事发生了——
   * 事务层据此才能走回滚，而不是把一次没搬成功伪装成搬成功。
   *
   * @throws SnapshotFsException code=`snapshot-move-blocked`：目标删不净（多为非应用属主残留）。
   */
  fun move(
    source: File,
    destination: File,
    /**
     * 列目录实现（默认生产用法）。与 [deletePath] / [deletePathStrict] 同一惯例：
     * 「文件系统拒绝删除」这一设备侧事实在普通 JVM 上无法用真实权限复现（本仓已有实锤记录：
     * 删掉兜底后用例照样全绿），必须能显式注入才能行为对照地判红。生产面只开这一个参数。
     */
    listChildren: (Path) -> List<Path> = { dir -> Files.newDirectoryStream(dir).use { it.toList() } },
  ) {
    destination.parentFile?.let { Files.createDirectories(it.toPath()) }
    if (exists(destination)) {
      // 严格删除：删不净会抛 snapshot-delete-residue（含残留条目），绝不带着非空目标去 move。
      deletePathStrict(destination, listChildren)
    }
    if (exists(destination)) {
      // 双保险：即便删除路径被替换成容错实现，也不得把非空目标交给 Files.move。
      throw SnapshotFsException(
        code = CODE_MOVE_BLOCKED,
        residue = listOf(destination),
        message = "快照事务拒绝把条目搬到仍非空的目标：" + destination.absolutePath
          + "（存在非应用属主残留，需先修正属主）",
      )
    }
    try {
      Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(source.toPath(), destination.toPath())
    }
  }

  /**
   * 目录字节数（**不跟随符号链接**：快照树里有大量指向同树的链，跟随会把体积算成几倍）。
   * 用于交换前的空间断言（审查 §7.2-F-4）。不可读的条目按 0 计（宁可低估也不抛）。
   */
  fun sizeOf(dir: File): Long {
    if (!exists(dir)) return 0L
    if (isSymbolicLink(dir)) return 0L
    if (dir.isFile) return dir.length()
    var total = 0L
    val children = dir.listFiles() ?: return 0L
    for (child in children) total += sizeOf(child)
    return total
  }

  /**
   * 一棵树的清点结果（issue #273 ①）：条目数 / 符号链接数 / 字节数。
   *
   * 判据用途：备份与源树必须**逐项对账**。此前 `copyRecursivelyStrict` 直接跳过链接，
   * 备份里 0 个链接而源里有 N 个，事后无人发现——回滚把这份「合法但残缺」的备份当 live 搬回去，
   * 501 个链接（现网实测数）就此消失，pnpm 的 node_modules 结构崩掉、模块解析失败。
   * 对账**必须数链接**：只比条目总数会漏掉「少一个链接、多一个文件」这类等量替换。
   *
   * @param entries 文件 + 目录 + 链接的总数（不含 root 自身）
   * @param links 符号链接数、
   * @param bytes 常规文件字节合计（链接按 0 计，不跟随）
   */
  data class TreeStats(val entries: Long, val links: Long, val bytes: Long)

  /**
   * 清点 [dir]（NOFOLLOW：不跟随链接、不越出树）。不可读的条目按「已计入条目、0 字节」处理——
   * 这里只做对账，读不到内容不该让整次备份判失败（真正的删除/搬移失败另有严格路径）。
   */
  fun treeStats(dir: File): TreeStats {
    var entries = 0L
    var links = 0L
    var bytes = 0L
    // **不含 root 自身**（与文档一致，也是「空目录 = 0 条目」这一判据的前提：
    // verifyPreviousForRollback 靠它识别「半份/未完成残渣」。若把 root 计进去，
    // 空目录会报 1 条 ⇒ 那个判据永不触发——本轮实测踩过）。
    fun walk(file: File) {
      val attrs = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
      } catch (_: Throwable) {
        return
      }
      entries += 1
      when {
        attrs.isSymbolicLink -> links += 1
        attrs.isDirectory -> for (child in file.listFiles() ?: emptyArray()) walk(child)
        else -> bytes += attrs.size()
      }
    }
    if (!exists(dir)) return TreeStats(0L, 0L, 0L)
    if (isSymbolicLink(dir)) return TreeStats(1L, 1L, 0L)
    for (child in dir.listFiles() ?: emptyArray()) walk(child)
    return TreeStats(entries, links, bytes)
  }

  fun createDirectories(dir: File) {
    Files.createDirectories(dir.toPath())
  }

  /**
   * 该条目是否**归本应用所有**（issue #271 的预检判据）。
   *
   * 真机现场：工厂快照自带的 `usr/lib/python3.14/x/__pycache__` 被打上 `root:root`，app uid
   * `unlink` 直接 EACCES（用户对照实测：app uid 递归删 32 处 Permission denied，root `rm -rf` 立即成功）。
   * 属主不是本应用 = 大概率删不掉；这条判据能在**动任何东西之前**把该形态判死。
   *
   * 读不到属性时返回 true（不搞猜测性阻断）：真正的判据仍是紧接其后的 `deletePathStrict`——
   * 本函数只是「尽早失败、并给出可照做的文案」，不是权限的权威。
   */
  fun ownedByApp(file: File): Boolean = try {
    android.system.Os.lstat(file.absolutePath).st_uid == android.os.Process.myUid()
  } catch (_: Throwable) {
    true
  }
}

/**
 * 删除路径的容错边界：哪些 `Throwable` 可以「记下并继续」，哪些必须原样抛出。
 *
 * 策略只声明一处，便于单测逐条钉住（`SnapshotFsTest`）。**不写成裸 `catch (Throwable)`**：
 *
 *  - `Exception` —— 既有的容错面（元数据损坏的目录、EACCES、并发删除等），保持原语义。
 *  - `LinkageError` —— 0.14.1 加固的核心。缺 API 的类错误全在这一支：
 *    `NoSuchMethodError`（`Stream.toList()` 在 API < 34 上）、`NoClassDefFoundError`、
 *    `IncompatibleClassChangeError`。issue #240 现场正是被这一类打穿整条刷新+回滚链。
 *  - `VirtualMachineError` —— **必须重抛**。`OutOfMemoryError` / `StackOverflowError` 出现在一个
 *    递归删除里时，「继续删下一个」只会让已经耗尽的资源继续被消耗，把一次可诊断的失败
 *    放大成一片静默的坏状态。同理 `ThreadDeath` / `AssertionError` 等其余 `Error` 也一律重抛：
 *    非 `LinkageError` 的 `Error` 表示 JVM 层已经不可信，不是「某个条目删不掉」。
 */
internal fun isTolerableDeletionFailure(t: Throwable): Boolean = when (t) {
  is VirtualMachineError -> false
  is Exception -> true
  is LinkageError -> true
  else -> false
}

/** 快照路径删不净（issue #271）。 */
internal const val CODE_DELETE_RESIDUE = "snapshot-delete-residue"

/** 原子写失败（issue #274 ②）：写用户面清单时的 tmp+rename 或可解析性校验失败。 */
internal const val CODE_ATOMIC_WRITE = "snapshot-atomic-write"

/** Pre-overwrite preservation failed; the transaction must abort before replacing the live file. */
internal const val CODE_PRESERVE_FAILED = "snapshot-preserve-failed"

/** 写事务 journal（marker）失败（issue #273 ②）：恢复权威写不进去，事务必须中止。 */
internal const val CODE_MARKER_WRITE = "snapshot-marker-write"

/** profiles 备份与源树对账不齐（issue #273 ①）：残缺备份绝不搬回。 */
internal const val CODE_BACKUP_INCOMPLETE = "snapshot-backup-incomplete"

/** `move` 的目标仍非空（issue #271 主修）。 */
internal const val CODE_MOVE_BLOCKED = "snapshot-move-blocked"

/** 事务开始前发现 `.snapshot-previous` 下有非应用属主残留（issue #271 预检）。 */
internal const val CODE_FOREIGN_OWNER = "snapshot-foreign-owner"

/**
 * 快照文件系统层的**结构化失败**（issue #271）。
 *
 * 为什么需要专门类型：调用方（事务层 / EngineManager / boot-fail.log）要能对不同的失败给
 * **不同的、可直接照做的文案**，而不是把所有真因压成同一句「运行时更新失败」——
 * 那正是用户反馈里「报了一堆错但没有任何指向」的形态。`code` 是稳定判据（日志/诊断面按它归因），
 * `residue` 是现场条目（谁挡住的）。
 *
 * 继承 [IOException] 而不是 RuntimeException：这些失败都在既有的 `catch (t: Throwable)` /
 * `IOException` 处理面内（事务失败 → 回滚 → boot-fail 落盘），保持既有控制流不变。
 */
internal class SnapshotFsException(
  val code: String,
  val residue: List<File> = emptyList(),
  message: String,
  cause: Throwable? = null,
) : IOException("[" + code + "] " + message, cause) {
  /** 诊断一行：`code=… residue=…`（boot-fail.log 与日志共用同一口径）。 */
  fun diagnosticLine(): String {
    // 注意：单表达式函数体（`= …`）**不能**在下一行以 `+` 续写（会被解析成一元 + 而报
    // unaryPlus 错误），所以这里用块体。
    val names = residue.take(5).joinToString(",") { it.name }
    return "code=" + code + (if (names.isEmpty()) "" else " residue=" + names)
  }
}

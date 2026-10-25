package com.ai.assistance.quro.dshmobile

import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * Fixed root-only operation, loaded from ApplicationInfo.sourceDir (the installed signed APK):
 * CLASSPATH=<trusted-sourceDir> /system/bin/app_process /system/bin
 *   com.ai.assistance.quro.dshmobile.RootRepairMain <fullUid> <dataDir> <relativeSubpath> <cap> <timeoutMs>
 *
 * Exactly five positional arguments, no flags, shell snippets or arbitrary commands. An empty
 * relativeSubpath selects the anchor; '.' is not an alias. All fields are bounded and checked before
 * any filesystem access. The caller must quote each argument, drain with bounded ProcIo, reject
 * noise/partial/truncated output, parse ONE JSON object, and require both exitCode == 0 and ok == true.
 * This entrypoint does not authenticate an untrusted CLASSPATH: the native parent owns that trust
 * boundary and must never load this code from an app-writable copied dex/JAR.
 */
@Keep
object RootRepairMain {
  /** JVM-testable parser: no Android framework calls or root process; Android stubs may be loaded. */
  fun parse(args: Array<String>): OwnershipRepairCore.Request? {
    if (args.size != 5) return null
    if (args[0].length !in 1..10 || args[1].length !in 1..OwnershipRepairCore.MAX_PATH_BYTES ||
      args[2].length > OwnershipRepairCore.MAX_PATH_BYTES || args[3].length !in 1..6 ||
      args[4].length !in 1..6) return null
    fun decimal(text: String): Boolean = text.all { it in '0'..'9' } &&
      (text.length == 1 || text[0] != '0')
    if (!decimal(args[0]) || !decimal(args[3]) || !decimal(args[4])) return null
    val uid = args[0].toIntOrNull() ?: return null
    val cap = args[3].toIntOrNull() ?: return null
    val timeout = args[4].toLongOrNull() ?: return null
    if (!OwnershipRepairCore.isAppUid(uid) || cap !in 1..OwnershipRepairCore.MAX_ENTRIES ||
      timeout !in 1..OwnershipRepairCore.MAX_TIMEOUT_MS) return null
    if (!OwnershipRepair.isTrustedAppDataAnchor(args[1], uid)) return null
    val target = args[2]
    if (target.startsWith('/') || target.toByteArray(Charsets.UTF_8).size > OwnershipRepairCore.MAX_PATH_BYTES)
      return null
    if (target.isNotEmpty()) {
      val segments = target.split('/')
      if (segments.size > OwnershipRepairCore.MAX_DEPTH ||
        segments.any { !OwnershipRepairCore.isValidName(it) }) return null
    }
    return OwnershipRepairCore.Request(args[1], target, uid, cap, timeout)
  }

  @JvmStatic
  fun main(args: Array<String>) {
    val result = try {
      val request = parse(args)
      if (request == null) OwnershipRepairCore.rejected("invalid-repair-arguments")
      else OwnershipRepair.repair(request.dataDir, request.relativeTarget, request.uid,
        request.maxEntries, request.timeoutMs)
    } catch (_: Exception) {
      OwnershipRepairCore.rejected("root-repair-exception")
    }
    // No logging or command output on stdout: one bounded JSON object and one newline only.
    System.out.println(OwnershipRepair.toJson(result).toString())
    System.out.flush()
    exitProcess(if (result.ok) 0 else 2)
  }
}

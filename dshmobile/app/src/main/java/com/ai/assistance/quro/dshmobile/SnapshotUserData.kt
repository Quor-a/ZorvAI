package com.ai.assistance.quro.dshmobile

import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/**
 * Preserves and transfers the user configuration in DSH_HOME, and recovers legacy backups.
 * The legacy backup was left by a pre-transaction refresh (versions up to 0.13.2 copied `.dsh` aside, extracted over the live tree and
 * copied it back; a kill in the middle left `.dsh-backup` behind).
 *
 * The current refresh no longer creates that backup — [SnapshotTransaction] never
 * touches user-owned paths — so this restore is a one-time migration (only reachable
 * via a leftover `.dsh-backup` from ≤0.13.2).
 *
 * Boundary (review C14, comment corrected to match behavior): for the user-owned trees
 * the restore is deliberately additive — only missing entries and truncated regular
 * files are copied, so a stale backup can never roll back data the user created after
 * the interrupted refresh. The small singletons in [replacedSingletons] are the
 * exception: they are replaced wholesale, because the interrupted refresh could have
 * overwritten them with factory content during extraction, which makes the
 * pre-extraction backup the authoritative content for exactly those files.
 * Broken symbolic links are logged and skipped, and the live source tree is never
 * mutated to make a copy work.
 */
internal object SnapshotUserData {

  /** DSH_HOME paths that survive a factory snapshot replacement. */
  internal val preservedNames = listOf(
    "sessions", "storages", "attachments", "workspaces", "undo-snapshots", "llm-deepseek",
    ".credentials.yaml", "settings.yaml", "settings.yaml.imported", ".anonymous-user-id", ".private-layout",
    "models-store.json", ".node-compile-cache",
  )

  /**
   * Direct children of the current factory `home/.dsh` archive. Existing paths outside
   * this inventory are user or third-party data and must be preserved on collision.
   * Add a name only after verifying its ownership in the snapshot builder and documenting
   * why replacing an existing user path is required. The verified current archive
   * inventory is profiles/, settings.yaml, and .private-layout; recheck it when assets change.
   */
  internal val factorySnapshotNames = setOf("profiles", "settings.yaml", ".private-layout")

  /**
   * Small singletons that are restored wholesale. A leftover backup means the refresh
   * that could have overwritten them did not finish, and the backup copy predates that
   * extraction, so it is the authoritative content.
   */
  private val replacedSingletons = setOf(
    "settings.yaml", ".credentials.yaml", ".anonymous-user-id", ".private-layout", "models-store.json",
  )

  /**
   * The engine renames legacy settings before writing into the active profile. Absence of
   * settings.yaml with an .imported marker is deliberate, not a request for a factory seed.
   * Only the extracted stage is changed; live settings and profile patches stay transactional.
   */
  fun prepareStagedSnapshot(stagedDsh: File, liveDsh: File): List<String> {
    val notes = mutableListOf<String>()
    fun discard(staged: File, label: String) {
      if (!SnapshotFs.exists(staged)) return
      SnapshotFs.deletePathStrict(staged)
      notes += "省略出厂用户数据 " + label
    }
    val liveLegacy = File(liveDsh, "settings.yaml")
    val livePatch = File(liveDsh, "profiles/web/cordis.patch.yml")
    if (SnapshotFs.exists(File(liveDsh, "settings.yaml.imported")) ||
        (!SnapshotFs.exists(liveLegacy) && SnapshotFs.exists(livePatch))) {
      discard(File(stagedDsh, "settings.yaml"), "settings.yaml（已迁移到活动 profile）")
    }
    // An importer marker belongs to this installation, never to the factory archive.
    discard(File(stagedDsh, "settings.yaml.imported"), "settings.yaml.imported")
    val stagedProfiles = File(stagedDsh, "profiles")
    for (profile in stagedProfiles.listFiles() ?: emptyArray()) {
      if (Files.isSymbolicLink(profile.toPath()) || !profile.isDirectory) continue
      val liveProfile = File(File(liveDsh, "profiles"), profile.name)
      if (SnapshotFs.exists(File(liveProfile, "settings.yaml.imported"))) {
        discard(File(profile, "settings.yaml"), "profiles/" + profile.name + "/settings.yaml（已迁移）")
      }
      discard(File(profile, "settings.yaml.imported"), "profiles/" + profile.name + "/settings.yaml.imported")
      for (name in preservedNames) {
        if (SnapshotFs.exists(File(liveProfile, name))) discard(File(profile, name), "profiles/" + profile.name + "/" + name)
      }
    }
    return notes
  }

  /** Quarantines a known blank factory seed resurrected by an older upgrade; custom YAML is untouched. */
  fun retireReseededFactorySettings(dshRoot: File): File? {
    val legacy = File(dshRoot, "settings.yaml")
    if (!SnapshotFs.exists(File(dshRoot, "settings.yaml.imported")) ||
        !legacy.isFile || Files.isSymbolicLink(legacy.toPath())) return null
    val body = legacy.readText().lineSequence().map { it.substringBefore('#').trimEnd() }
      .filter { it.isNotBlank() }.joinToString("\n")
    if (body != "llm-deepseek: {}\nllm-pi-ai:\n  providers: {}") return null
    val backup = File.createTempFile(".settings-factory-reseed-", ".bak", dshRoot)
    try {
      Files.move(legacy.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } catch (t: Throwable) {
      Files.deleteIfExists(backup.toPath())
      throw t
    }
    return backup
  }

  /** The web engine persists settings in its profile patch; imported YAML is historical only. */
  fun configurationDocument(dshRoot: File, profileName: String = "web"): File {
    val profile = File(File(dshRoot, "profiles"), profileName)
    val patch = File(profile, "cordis.patch.yml")
    val migrated = SnapshotFs.exists(File(dshRoot, "settings.yaml.imported")) ||
      SnapshotFs.exists(File(profile, "settings.yaml.imported"))
    return if (migrated || SnapshotFs.exists(patch)) patch else File(dshRoot, "settings.yaml")
  }

  /** User-initiated export of the actual configuration, never the .imported historical document. */
  @Synchronized
  fun exportConfiguration(dshRoot: File, sharedRoot: File): File {
    val source = configurationDocument(dshRoot)
    require(source.isFile && !Files.isSymbolicLink(source.toPath())) { "活动配置尚未生成，请等待引擎初始化后再导出" }
    val directory = File(File(sharedRoot, "exports"), "config")
    SnapshotFs.createDirectories(directory)
    val target = File(directory, source.name)
    replaceConfiguration(source, target)
    return target
  }

  /** Imports the matching exported format; a legacy YAML cannot overwrite a migrated profile patch. */
  @Synchronized
  fun importConfiguration(dshRoot: File, sharedRoot: File): File {
    val target = configurationDocument(dshRoot)
    val source = File(File(File(sharedRoot, "exports"), "config"), target.name)
    require(source.isFile && !Files.isSymbolicLink(source.toPath())) {
      "未找到 exports/config/" + target.name + "；请先导出当前活动配置，再编辑并导入（旧 settings.yaml 不会覆盖已迁移的 profile）"
    }
    require(target.isFile && !Files.isSymbolicLink(target.toPath())) { "活动配置尚未生成，请等待引擎初始化后再导入" }
    replaceConfiguration(target, File(target.parentFile, target.name + ".import-backup"))
    replaceConfiguration(source, target)
    return target
  }

  private fun replaceConfiguration(source: File, target: File) {
    val temporary = File.createTempFile(".config-", ".tmp", target.parentFile)
    try {
      Files.copy(source.toPath(), temporary.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(temporary.toPath())
    }
  }

  internal data class CopyResult(
    val copiedEntries: Int,
    val skippedExisting: Int,
    val skippedBrokenLinks: Int,
  )

  fun restoreLegacyBackup(
    backupRoot: File,
    destinationRoot: File,
    onBrokenLink: (File) -> Unit,
  ): CopyResult {
    if (!SnapshotFs.exists(backupRoot)) return CopyResult(0, 0, 0)
    SnapshotFs.createDirectories(destinationRoot)
    var copied = 0
    var skipped = 0
    var broken = 0
    val migratedSettings = SnapshotFs.exists(File(destinationRoot, "settings.yaml.imported")) ||
      SnapshotFs.exists(File(backupRoot, "settings.yaml.imported"))
    for (name in preservedNames + "profiles") {
      // A pre-migration backup must not resurrect a legacy document after a completed import.
      if (name == "settings.yaml" && migratedSettings) { skipped++; continue }
      val source = File(backupRoot, name)
      if (!SnapshotFs.exists(source)) continue
      val destination = File(destinationRoot, name)
      val result = if (name in replacedSingletons) {
        replaceEntry(source, destination, onBrokenLink)
      } else {
        mergeMissing(source, destination, onBrokenLink, restoreTruncated = name != "profiles" && name != "settings.yaml.imported")
      }
      copied += result.copiedEntries
      skipped += result.skippedExisting
      broken += result.skippedBrokenLinks
    }
    return CopyResult(copied, skipped, broken)
  }

  private fun replaceEntry(source: File, destination: File, onBrokenLink: (File) -> Unit): CopyResult {
    SnapshotFs.deletePath(destination)
    return mergeMissing(source, destination, onBrokenLink)
  }

  /** Copies only what the destination lacks: missing entries and truncated regular files. */
  private fun mergeMissing(
    source: File,
    destination: File,
    onBrokenLink: (File) -> Unit,
    restoreTruncated: Boolean = true,
  ): CopyResult {
    val sourcePath = source.toPath()
    val attributes = Files.readAttributes(sourcePath, BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    return try {
      when {
        attributes.isSymbolicLink -> {
          if (!Files.exists(sourcePath)) {
            // Dangling link: disposable runtime residue, never recreated.
            onBrokenLink(source)
            CopyResult(0, 0, 1)
          } else if (SnapshotFs.exists(destination)) {
            CopyResult(0, 1, 0)
          } else {
            SnapshotFs.createDirectories(destination.parentFile ?: source.parentFile)
            Files.createSymbolicLink(destination.toPath(), Files.readSymbolicLink(sourcePath))
            CopyResult(1, 0, 0)
          }
        }
        attributes.isDirectory -> {
          SnapshotFs.createDirectories(destination)
          var copied = 0
          var skipped = 0
          var broken = 0
          Files.list(sourcePath).use { children ->
            children.forEach { child ->
              val name = child.fileName.toString()
              if (name == "settings.yaml" && (SnapshotFs.exists(File(source, "settings.yaml.imported")) ||
                  SnapshotFs.exists(File(destination, "settings.yaml.imported")))) {
                skipped++
                return@forEach
              }
              val result = mergeMissing(child.toFile(), File(destination, name), onBrokenLink,
                restoreTruncated && name != "settings.yaml.imported")
              copied += result.copiedEntries
              skipped += result.skippedExisting
              broken += result.skippedBrokenLinks
            }
          }
          CopyResult(copied, skipped, broken)
        }
        attributes.isRegularFile -> {
          val existing = if (SnapshotFs.exists(destination)) destination.length() else -1L
          if ((!restoreTruncated && existing >= 0) || existing >= attributes.size()) {
            CopyResult(0, 1, 0)
          } else {
            SnapshotFs.createDirectories(destination.parentFile ?: source.parentFile)
            SnapshotFs.deletePath(destination)
            Files.copy(sourcePath, destination.toPath())
            CopyResult(1, 0, 0)
          }
        }
        else -> throw IllegalStateException("unsupported user-data entry: " + source.absolutePath)
      }
    } catch (e: NoSuchFileException) {
      if (!attributes.isSymbolicLink) throw e
      onBrokenLink(source)
      CopyResult(0, 0, 1)
    }
  }
}

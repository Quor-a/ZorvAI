package com.ai.assistance.quro.dshmobile

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File-level reverse migration with an owned sibling stage, so a retry never mistakes a partial copy for user data. */
internal object ReverseMigration {
  private const val OWNER_MARKER = "dsh-reverse-migration-stage-v1"
  private const val STAGE_SUFFIX = ".reverse-migration-stage"
  private const val OWNER_SUFFIX = ".reverse-migration-owner"

  internal fun migrateDir(
    privateDir: File,
    publicDir: File,
    listFiles: (File) -> Array<File>? = { it.listFiles() },
    copyTree: (File, File) -> Unit = { src, dst -> copyTreeVerified(src, dst, listFiles) },
    rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    deleteTree: (File) -> Unit = { SnapshotFs.deletePath(it) },
    writeOwner: (File) -> Unit = ::writeOwnerMarker,
  ) {
    if (!privateDir.isAbsolute || !publicDir.isAbsolute) {
      throw IOException("reverse migration requires absolute paths")
    }
    val parent = privateDir.parentFile ?: throw IOException("private migration target has no parent: " + privateDir.path)
    if (publicDir.parentFile == null) throw IOException("public migration source has no parent: " + publicDir.path)
    val stage = File(parent, privateDir.name + STAGE_SUFFIX)
    val owner = File(parent, privateDir.name + OWNER_SUFFIX)

    // A previous process may have been killed after the atomic stage->live rename. The durable
    // owner marker proves this live tree came from this migration and lets us finish the source
    // cleanup. Without it, preserve the established private-wins conflict policy.
    if (SnapshotFs.exists(owner)) {
      requireOwned(owner)
      if (privateDir.isDirectory && !SnapshotFs.exists(stage)) {
        deleteTree(publicDir)
        if (SnapshotFs.exists(publicDir)) throw IOException("failed to delete public source " + publicDir.absolutePath)
        if (!owner.delete()) throw IOException("failed to clear migration owner " + owner.absolutePath)
        return
      }
      if (SnapshotFs.exists(stage)) {
        requireOwned(owner)
        deleteTree(stage)
        if (SnapshotFs.exists(stage)) throw IOException("failed to clear owned migration stage " + stage.absolutePath)
      }
      if (!owner.delete()) throw IOException("failed to clear migration owner " + owner.absolutePath)
    } else if (SnapshotFs.exists(stage)) {
      // Never infer ownership from a familiar name: an unrelated sibling may belong to the user.
      throw IOException("unowned reverse migration stage exists: " + stage.absolutePath)
    }

    if (!publicDir.isDirectory) return

    if (SnapshotFs.exists(privateDir)) {
      if (!privateDir.isDirectory) {
        throw IOException("private migration target is a non-directory; preserving it: " + privateDir.absolutePath)
      }
      val existingPrivateEntries = listFiles(privateDir)
        ?: throw IOException("could not list private migration target: " + privateDir.absolutePath)
      if (existingPrivateEntries.isNotEmpty()) {
        val backup = uniquePublicBackup(publicDir)
        if (!rename(publicDir, backup)) throw IOException("failed to backup public dir " + publicDir.absolutePath)
        return
      }
      deleteTree(privateDir)
      if (SnapshotFs.exists(privateDir)) throw IOException("failed to clear private target " + privateDir.absolutePath)
    }

    privateDir.parentFile?.mkdirs()
    writeOwner(owner)
    if (!stage.mkdir()) throw IOException("could not create reverse migration stage " + stage.absolutePath)
    try {
      copyTree(publicDir, stage)
      if (!rename(stage, privateDir)) throw IOException("failed to activate reverse migration stage " + stage.absolutePath)
      deleteTree(publicDir)
      if (SnapshotFs.exists(publicDir)) throw IOException("failed to delete public source " + publicDir.absolutePath)
      if (!owner.delete()) throw IOException("failed to clear migration owner " + owner.absolutePath)
    } catch (t: Throwable) {
      // Keep the marker and any partial stage. A retry can identify and discard exactly this
      // transaction; no unknown sibling or pre-existing private data is removed.
      throw t
    }
  }

  private fun requireOwned(owner: File) {
    if (Files.isSymbolicLink(owner.toPath()) || !owner.isFile || owner.readText() != OWNER_MARKER) {
      throw IOException("invalid reverse migration owner marker: " + owner.absolutePath)
    }
  }

  private fun uniquePublicBackup(publicDir: File): File {
    var candidate = File(publicDir.parentFile, publicDir.name + ".public-backup")
    var i = 1
    while (SnapshotFs.exists(candidate)) {
      candidate = File(publicDir.parentFile, publicDir.name + ".public-backup-" + i)
      i++
    }
    return candidate
  }

  private fun writeOwnerMarker(owner: File) {
    if (SnapshotFs.exists(owner)) throw IOException("migration owner already exists: " + owner.absolutePath)
    val parent = owner.parentFile ?: throw IOException("migration owner has no parent: " + owner.path)
    if (!owner.isAbsolute) throw IOException("migration owner must have an absolute path: " + owner.path)
    val temp = Files.createTempFile(parent.toPath(), owner.name + ".", ".tmp")
    try {
      FileOutputStream(temp.toFile()).use { out ->
        out.write(OWNER_MARKER.toByteArray(Charsets.UTF_8))
        out.fd.sync()
      }
      // Same-directory atomic rename publishes only a complete marker. A crash while writing leaves
      // an unreferenced random temp file; future runs ignore it and never delete unknown siblings.
      Files.move(temp, owner.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (t: Throwable) {
      // This invocation knows the unique temp path it created. Delete it only when its full marker
      // proves the expected contents; partial/empty files are left untouched as unknown residue.
      if (Files.exists(temp) && runCatching { String(Files.readAllBytes(temp), Charsets.UTF_8) == OWNER_MARKER }.getOrDefault(false)) {
        Files.deleteIfExists(temp)
      }
      throw t
    }
  }

  private fun copyTreeVerified(src: File, dst: File, listFiles: (File) -> Array<File>?) {
    if (!dst.isDirectory && !dst.mkdirs() && !dst.isDirectory) throw IOException("mkdirs failed: " + dst.absolutePath)
    val children = listFiles(src) ?: throw IOException("could not list migration source: " + src.absolutePath)
    children.forEach { f ->
      val target = File(dst, f.name)
      if (f.isDirectory) copyTreeVerified(f, target, listFiles) else f.copyTo(target, overwrite = true)
    }
    val srcFiles = src.walkBottomUp().filter { it.isFile }.toList()
    val dstFiles = dst.walkBottomUp().filter { it.isFile }.toList()
    val srcSize = srcFiles.sumOf { it.length() }
    val dstSize = dstFiles.sumOf { it.length() }
    if (srcFiles.size != dstFiles.size || srcSize != dstSize) {
      throw IOException("copy verification failed for " + src.absolutePath)
    }
  }
}

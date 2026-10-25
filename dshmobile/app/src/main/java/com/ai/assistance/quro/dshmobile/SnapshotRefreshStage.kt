package com.ai.assistance.quro.dshmobile

import java.io.File

/** The offline staging boundary used by snapshot refresh before any live-tree swap begins. */
internal object SnapshotRefreshStage {
  internal fun extractAndValidate(
    stage: File,
    extract: (File) -> Boolean,
    validate: (File) -> Boolean,
    discard: (File) -> Unit = { SnapshotFs.deletePath(it) },
  ): Boolean {
    val ready = try {
      extract(stage) && validate(stage)
    } catch (t: Throwable) {
      discard(stage)
      throw t
    }
    if (!ready) discard(stage)
    return ready
  }
}

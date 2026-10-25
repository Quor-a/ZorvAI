package com.ai.assistance.quro.dshmobile

/**
 * Determines whether an extracted regular file needs the executable bit. Snapshot
 * archives can lose their original mode bits on Windows/WSL mounts, so tar mode
 * alone is not trusted. Directly executable payloads are ELF binaries or scripts
 * with a shebang; ordinary JavaScript, JSON, configuration and data files remain
 * non-executable.
 */
internal object SnapshotFileMode {

  fun isDirectlyExecutable(prefix: ByteArray, length: Int): Boolean {
    if (length >= 4 &&
      prefix[0] == 0x7f.toByte() &&
      prefix[1] == 'E'.code.toByte() &&
      prefix[2] == 'L'.code.toByte() &&
      prefix[3] == 'F'.code.toByte()
    ) {
      return true
    }
    return length >= 2 && prefix[0] == '#'.code.toByte() && prefix[1] == '!'.code.toByte()
  }
}

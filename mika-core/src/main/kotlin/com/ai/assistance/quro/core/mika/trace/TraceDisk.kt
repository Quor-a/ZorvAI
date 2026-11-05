package com.ai.assistance.quro.core.mika.trace

import java.io.File
import java.io.IOException

internal class TraceDiskFull(val block: String) : IOException("Trace storage limit")

/** Accounts for committed file lengths, including spans and manifests, across all local runs. */
internal class TraceDisk(private val directory: File, private var limit: Long) {
    private val active = mutableSetOf<File>()
    private val readers = mutableMapOf<File, Int>()
    private var free = 0L

    @Synchronized fun open(dir: File): Boolean {
        if (dir.exists()) return false
        if (free < ROOT_RESERVE) reclaim(ROOT_RESERVE)
        if (free < ROOT_RESERVE) return false
        directory.mkdirs()
        if (!dir.mkdir()) return false
        active += dir
        free -= ROOT_RESERVE
        return true
    }

    @Synchronized fun close(dir: File) {
        if (active.remove(dir)) {
            free += rootReserve(dir)
            // A failed initialization can leave empty subdirectories, which have no bytes to rotate.
            if (!File(dir, "root.pb").isFile) dir.deleteRecursively()
        }
        if (free < 0) reclaim(0)
    }

    @Synchronized fun setLimit(bytes: Long) {
        require(bytes > 0)
        limit = bytes
        reclaim(0)
    }

    // An export holds the run until its last block has been read. Network I/O happens after release.
    fun <T> read(dir: File, block: () -> T): T {
        synchronized(this) {
            if (!dir.isDirectory) throw IOException("Trace no longer available")
            readers[dir] = (readers[dir] ?: 0) + 1
        }
        try {
            return block()
        } finally {
            synchronized(this) {
                val remaining = readers.getValue(dir) - 1
                if (remaining == 0) readers.remove(dir) else readers[dir] = remaining
                if (free < 0) reclaim(0)
            }
        }
    }

    @Synchronized fun write(dir: File, file: File, data: ByteArray, block: String) {
        // Reserve the root checkpoint up front so a full store can still explain why capture stopped.
        val floor = if (file == File(dir, "root.pb")) ROOT_RESERVE else 0L
        val delta = maxOf(floor, data.size.toLong()) - maxOf(floor, file.length())
        if (delta > 0 && delta > free) {
            reclaim(delta)
            if (delta > free) throw TraceDiskFull(block)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeBytes(data)
            if (!tmp.renameTo(file)) throw IOException("Trace write failed")
            free -= delta
        } finally {
            tmp.delete()
        }
    }

    private fun rootReserve(dir: File) = (ROOT_RESERVE - File(dir, "root.pb").length()).coerceAtLeast(0L)

    // Rescan on startup, quota changes or pressure. Normal writes only account for their own delta.
    private fun reclaim(needed: Long) {
        val runs = directory.listFiles().orEmpty().filter { it.isDirectory && RUN_ID.matches(it.name) }
        val sizes = runs.associateWith { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        free = limit - sizes.values.sum() - active.sumOf(::rootReserve)
        if (free >= needed) return
        for (dir in runs.filter { it !in active && it !in readers }.sortedBy { it.lastModified() }) {
            if (dir.deleteRecursively()) free += sizes.getValue(dir)
            if (free >= needed) break
        }
    }

    companion object {
        val RUN_ID = Regex("[a-zA-Z0-9_-]{1,80}")
        private const val ROOT_RESERVE = 1024L
    }
}

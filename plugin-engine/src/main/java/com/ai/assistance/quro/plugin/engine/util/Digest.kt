package com.ai.assistance.quro.plugin.engine.util

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal object Digest {
    fun md5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

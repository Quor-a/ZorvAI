package com.ai.assistance.quro.etalib.core

import java.io.File

/** 加密数据库必须由宿主连接读取，不能当作损坏或空表的普通 SQLite 文件处理。 */
internal fun isPlaintextColorOsMemoryDatabase(file: File): Boolean = file.inputStream().use { input ->
    val signature = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    input.readNBytes(signature.size).contentEquals(signature)
}

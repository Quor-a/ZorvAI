package com.ai.assistance.quro.core.tools

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 应用私有数据库只读查询工具。
 *
 * 对应用自身的 SQLite 数据库（databases 目录）做只读查询：先快照拷贝到 cache，
 * 再以 `SQLiteDatabase.openDatabase(OPEN_READONLY or NO_LOCALIZED_COLLATORS)` 打开、
 * 查询、最后删除快照，避免与应用自身写连接争锁。
 *
 * 仅允许 SELECT / PRAGMA / WITH / EXPLAIN 等只读语句，严格禁止任何写操作，确保只读不破坏应用数据。
 */
class QuroPrivateDbTool : QuroTool {
    override val name = "private_db"
    override val description = """对应用自身的 SQLite 数据库做只读查询（免 root，应用私有目录隔离）。

扫描应用 databases 目录下的 .db/.sqlite 文件；所有查询均为只读（仅允许 SELECT/PRAGMA/WITH/EXPLAIN），绝不写库。
动作（action）：
- db_list：列出可用数据库文件
- db_tables：列出某数据库的表（参数 db）
- db_schema：查看表结构（参数 db + table）
- db_query：执行只读 SQL（参数 db + sql，仅 SELECT/PRAGMA/WITH/EXPLAIN）

示例：
{"action":"db_list"}
{"action":"db_tables","db":"quro.db"}
{"action":"db_schema","db":"quro.db","table":"memory"}
{"action":"db_query","db":"quro.db","sql":"SELECT * FROM memory LIMIT 20"}"""
    override val parametersJson = """{
  "type":"object",
  "properties":{
    "action":{"type":"string","enum":["db_list","db_tables","db_schema","db_query"],"description":"数据库动作"},
    "db":{"type":"string","description":"数据库文件名（db_list 外都需要，来自 db_list 的结果）"},
    "table":{"type":"string","description":"表名（db_schema 用）"},
    "sql":{"type":"string","description":"只读 SQL（db_query 用，仅 SELECT/PRAGMA/WITH/EXPLAIN）"}
  },
  "required":["action"]
}"""

    override fun run(context: Context, arguments: String): String {
        val json = runCatching { JSONObject(arguments) }.getOrElse { return err("参数不是合法 JSON") }
        val action = json.optString("action", "")
        val dbDir = context.getDatabasePath("__probe__").parentFile ?: File(context.filesDir, "databases")
        if (!dbDir.exists()) dbDir.mkdirs()
        return try {
            when (action) {
                "db_list" -> doList(dbDir)
                "db_tables" -> doTables(context, dbDir, json.optString("db", ""))
                "db_schema" -> doSchema(context, dbDir, json.optString("db", ""), json.optString("table", ""))
                "db_query" -> doQuery(context, dbDir, json.optString("db", ""), json.optString("sql", ""))
                else -> err("未知 action: $action（可选 db_list/db_tables/db_schema/db_query）")
            }
        } catch (e: Exception) {
            err("执行失败: ${e.message}")
        }
    }

    /** 仅允许文件名（防路径穿越）：从入参中取最后一段，确保落在 databases 目录内。 */
    private fun dbFile(dbDir: File, db: String): File {
        if (db.isBlank()) throw IllegalArgumentException("缺少 db 参数")
        val name = db.substringAfterLast('/').substringAfterLast('\\')
        val f = File(dbDir, name)
        if (!f.isFile) throw IllegalArgumentException("数据库不存在: $name")
        return f
    }

    private fun doList(dbDir: File): String {
        val arr = JSONArray()
        dbDir.listFiles()
            ?.filter { f ->
                val n = f.name.lowercase()
                // 排除 wal/shm/journal 伴生文件；主库按扩展名或「存在 -wal 伴生文件」识别
                !(n.endsWith("-wal") || n.endsWith("-shm") || n.endsWith("-journal")) &&
                    (n.endsWith(".db") || n.endsWith(".sqlite") || n.endsWith(".sqlite3") ||
                        File(f.parentFile, f.name + "-wal").isFile)
            }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                arr.put(JSONObject().apply {
                    put("name", f.name)
                    put("size", f.length())
                })
            }
        return JSONObject().apply {
            put("ok", true)
            put("databases", arr)
            put("count", arr.length())
            if (arr.length() == 0) put("note", "应用当前没有 SQLite 数据库文件（数据可能以文件/SP 形式存储）")
        }.toString()
    }

    /** 快照拷贝到 cacheDir 后再只读打开，避免与应用自身写连接争锁。 */
    private fun withSnapshot(context: Context, dbDir: File, db: String, block: (File) -> String): String {
        val src = dbFile(dbDir, db)
        val snap = File(context.cacheDir, "privdb_snap_${System.nanoTime()}.db")
        FileInputStream(src).channel.use { inCh ->
            FileOutputStream(snap).channel.use { outCh -> outCh.transferFrom(inCh, 0, inCh.size()) }
        }
        // 一并拷贝可能的 -wal / -shm，保证读到最新已提交数据
        for (suffix in listOf("-wal", "-shm")) {
            val extra = File(src.parentFile, src.name + suffix)
            if (extra.isFile) {
                val dst = File(snap.parentFile, snap.name + suffix)
                FileInputStream(extra).channel.use { inCh ->
                    FileOutputStream(dst).channel.use { outCh -> outCh.transferFrom(inCh, 0, inCh.size()) }
                }
            }
        }
        return try {
            block(snap)
        } finally {
            snap.delete()
            File(snap.parentFile, snap.name + "-wal").delete()
            File(snap.parentFile, snap.name + "-shm").delete()
        }
    }

    private fun doTables(context: Context, dbDir: File, db: String): String {
        return withSnapshot(context, dbDir, db) { snap ->
            SQLiteDatabase.openDatabase(
                snap.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { d ->
                val arr = JSONArray()
                d.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name",
                    null
                ).use { c ->
                    while (c.moveToNext()) arr.put(c.getString(0))
                }
                JSONObject().apply { put("ok", true); put("db", db); put("tables", arr); put("count", arr.length()) }.toString()
            }
        }
    }

    private fun doSchema(context: Context, dbDir: File, db: String, table: String): String {
        if (table.isBlank()) return err("缺少 table 参数")
        return withSnapshot(context, dbDir, db) { snap ->
            SQLiteDatabase.openDatabase(
                snap.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { d ->
                val arr = JSONArray()
                d.rawQuery("PRAGMA table_info($table)", null).use { c ->
                    while (c.moveToNext()) {
                        arr.put(JSONObject().apply {
                            put("cid", c.getInt(0))
                            put("name", c.getString(1))
                            put("type", c.getString(2))
                            put("notnull", c.getInt(3))
                            put("pk", c.getInt(5))
                        })
                    }
                }
                JSONObject().apply { put("ok", true); put("db", db); put("table", table); put("columns", arr); put("count", arr.length()) }.toString()
            }
        }
    }

    private fun doQuery(context: Context, dbDir: File, db: String, sql: String): String {
        if (sql.isBlank()) return err("缺少 sql 参数")
        // 去掉前导注释/空白后再判断
        val trimmed = sql.trim().replace(Regex("(?m)^--.*$"), "").trim()
        val upper = trimmed.uppercase()
        val okPrefix = upper.startsWith("SELECT") || upper.startsWith("PRAGMA") ||
                upper.startsWith("WITH") || upper.startsWith("EXPLAIN")
        if (!okPrefix) return err("仅允许 SELECT/PRAGMA/WITH/EXPLAIN 只读语句")
        // 禁止任何写关键字（PRAGMA 自身允许，从禁用词中剔除）。
        // 先剥掉单引号字符串字面量再匹配：否则查询文本列里出现 'DELETE'/'CREATE' 等词
        // 的合法 SELECT 会被误杀（例如查聊天记录/日志表内容）。
        val writeWords = listOf(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "ATTACH",
            "REPLACE", "BEGIN", "COMMIT", "ROLLBACK", "VACUUM", "GRANT", "REVOKE"
        )
        val scrubbed = upper.replace(Regex("'(?:[^']|'')*'"), "''")
        val hit = writeWords.firstOrNull { Regex("""\b$it\b""").containsMatchIn(scrubbed) }
        if (hit != null) return err("检测到写操作关键字被拒绝: $hit")
        return withSnapshot(context, dbDir, db) { snap ->
            SQLiteDatabase.openDatabase(
                snap.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { d ->
                val rows = JSONArray()
                d.rawQuery(trimmed, null).use { c -> cursorToJson(c, rows) }
                JSONObject().apply { put("ok", true); put("db", db); put("rows", rows); put("count", rows.length()) }.toString()
            }
        }
    }

    private fun cursorToJson(c: Cursor, rows: JSONArray) {
        val cols = c.columnCount
        val maxField = 4000
        while (c.moveToNext()) {
            val o = JSONObject()
            for (i in 0 until cols) {
                val name = c.getColumnName(i)
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> o.put(name, JSONObject.NULL)
                    Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> o.put(name, c.getDouble(i))
                    Cursor.FIELD_TYPE_BLOB -> o.put(name, "<blob ${c.getBlob(i).size} bytes>")
                    else -> {
                        val s = c.getString(i) ?: ""
                        o.put(name, if (s.length > maxField) s.substring(0, maxField) + "...(truncated)" else s)
                    }
                }
            }
            rows.put(o)
        }
    }

    private fun err(msg: String): String =
        JSONObject().apply { put("ok", false); put("error", msg) }.toString()
}

package com.ai.assistance.quro.core.miniapp

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 结构化存储（SQLite）模块：native.db.*。
 * 移植自 MiniAppFramework（com.miniapp），去品牌化为 QuroAI 的 MiniAppBridgeModule 协议。
 * API：execSql / query / insert / update / delete。单线程池串行访问，避免并发访问 SQLiteDatabase。
 */
class SqlStorageModule(private val context: Context) : MiniAppBridgeModule {
    override val name: String = "db"
    private val dbHelper = AppDataDbHelper(context.applicationContext)
    private val executor = Executors.newSingleThreadExecutor()

    override fun invoke(method: String, params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        when (method) {
            "execSql" -> execSql(params, callback)
            "query" -> query(params, callback)
            "insert" -> insert(params, callback)
            "update" -> update(params, callback)
            "delete" -> delete(params, callback)
            else -> callback(-1, null, "method not found: $method")
        }
    }

    private fun execSql(params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        val sql = params.optString("sql", "")
        if (sql.isBlank()) { callback(-1, null, "empty sql"); return }
        executor.execute {
            runCatching {
                dbHelper.writableDatabase.execSQL(sql)
                callback(0, true, null)
            }.onFailure { callback(-1, null, it.message) }
        }
    }

    private fun query(params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        val sql = params.optString("sql", params.optString("query", ""))
        if (sql.isBlank()) { callback(-1, null, "empty sql"); return }
        executor.execute {
            runCatching {
                dbHelper.readableDatabase.rawQuery(sql, null).use { cursor ->
                    val rows = cursorToRows(cursor)
                    val arr = JSONArray().apply { rows.forEach { put(it) } }
                    callback(0, arr, null)
                }
            }.onFailure { callback(-1, null, it.message) }
        }
    }

    private fun insert(params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        val table = params.optString("table", "app_data")
        val values = params.optJSONObject("values") ?: params
        executor.execute {
            runCatching {
                val cv = ContentValues()
                val now = System.currentTimeMillis()
                // app_data 表的 created_at/updated_at 为 NOT NULL：调用方未传时自动补当前时间，避免 insert 因缺列报错
                if (table == "app_data") {
                    cv.put("created_at", values.optLong("created_at", now))
                    cv.put("updated_at", values.optLong("updated_at", now))
                }
                values.keys().forEach { k ->
                    if (table == "app_data" && (k == "created_at" || k == "updated_at")) return@forEach
                    cv.put(k, values.optString(k, null))
                }
                val id = dbHelper.writableDatabase.insert(table, null, cv)
                callback(0, JSONObject().put("insertId", id), null)
            }.onFailure { callback(-1, null, it.message) }
        }
    }

    private fun update(params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        val table = params.optString("table", "app_data")
        val values = params.optJSONObject("values") ?: JSONObject()
        val where = params.optString("where", "")
        executor.execute {
            runCatching {
                val cv = ContentValues()
                val now = System.currentTimeMillis()
                if (table == "app_data") cv.put("updated_at", values.optLong("updated_at", now))
                values.keys().forEach { k ->
                    if (table == "app_data" && k == "updated_at") return@forEach
                    cv.put(k, values.optString(k, null))
                }
                val count = if (where.isBlank()) dbHelper.writableDatabase.update(table, cv, null, null)
                else dbHelper.writableDatabase.update(table, cv, where, null)
                callback(0, JSONObject().put("changes", count), null)
            }.onFailure { callback(-1, null, it.message) }
        }
    }

    private fun delete(params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        val table = params.optString("table", "app_data")
        val where = params.optString("where", "")
        executor.execute {
            runCatching {
                val count = if (where.isBlank()) dbHelper.writableDatabase.delete(table, null, null)
                else dbHelper.writableDatabase.delete(table, where, null)
                callback(0, JSONObject().put("changes", count), null)
            }.onFailure { callback(-1, null, it.message) }
        }
    }

    private fun cursorToRows(cursor: Cursor): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        if (cursor.moveToFirst()) {
            val cols = cursor.columnNames
            do {
                val row = JSONObject()
                for (c in cols) {
                    val idx = cursor.getColumnIndexOrThrow(c)
                    row.put(c, when (cursor.getType(idx)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(idx)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(idx)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(idx)
                        else -> cursor.getString(idx)
                    })
                }
                rows.add(row)
            } while (cursor.moveToNext())
        }
        return rows
    }

    private class AppDataDbHelper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS app_data (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "key TEXT NOT NULL UNIQUE, " +
                    "value TEXT, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    companion object {
        private const val DB_NAME = "quro_miniapp.db"
        private const val DB_VERSION = 1
    }
}

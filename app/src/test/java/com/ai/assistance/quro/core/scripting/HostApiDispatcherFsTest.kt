package com.ai.assistance.quro.core.scripting

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * issue #11 问题 1/2 回归锁：脚本沙盒 `Tools.Files` 的宿主侧契约。
 *
 * 背景（用户 v1.1.4 实测）：
 *  ① 文件存在性判定恒 false——「写入 10 字节文本 → stat=10 ✓、read 有内容 ✓、存在性判定 false ✗」。
 *     根因在桥接层（prelude.js 拿宿主回的 `{exists:true}` 对象直接 `=== true`，对象恒不等于 true），
 *     不在本类；但 issue 的改善建议明确要求「判定改为真实文件系统查询 + 补两条最小单测」，
 *     本测试钉死宿主侧返回的 `{exists:bool}` 契约（存在→true / 不存在→false）。
 *  ② 读取接口返回形状与文档不一致（包装对象 vs 原始文本）——宿主回 `{content}`，
 *     由 prelude.js 的 Tools.Files.read 在调用点统一解包为原始文本（另见 prelude.js 注释）。
 *
 * 本测试逐条钉死：
 *  1. fs.exists：存在文件→true、不存在→false、目录→true、路径越界→{error}（桥接层判 false）；
 *  2. fs.write/fs.read 往返：10 字节文本 → {bytes:10} → {content:"0123456789"}；
 *  3. fs.stat：size/lastModified 正确；不存在→{error}；
 *  4. fs.read：不存在/是目录分别给可自解释的错误文案；
 *  5. fs.list：entries 数组含条目；fs.mkdir/fs.remove/fs.append 往返。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostApiDispatcherFsTest {

    private lateinit var context: Context
    private lateinit var sandboxRoot: File
    private lateinit var dispatcher: HostApiDispatcher

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        sandboxRoot = kotlin.io.path.createTempDirectory("quro-fs-test").toFile()
        dispatcher = HostApiDispatcher(context, sandboxRoot)
    }

    @After
    fun tearDown() {
        sandboxRoot.deleteRecursively()
    }

    private fun call(api: String, params: String): JSONObject =
        JSONObject(dispatcher.dispatch(api, params))

    /* ===================== 问题 1：存在性判定 ===================== */

    @Test
    fun fsExists_existingFile_true() {
        val f = File(sandboxRoot, "hello.txt").apply { writeText("0123456789") }
        assertTrue(f.length() == 10L)

        val r = call("fs.exists", """{"path":"hello.txt"}""")
        assertTrue("存在文件必须判 true（issue #11 问题1：旧实现连这条都不过）", r.optBoolean("exists", false))
        assertTrue(r.getBoolean("exists"))
    }

    @Test
    fun fsExists_missingFile_false() {
        val r = call("fs.exists", """{"path":"no-such-file.txt"}""")
        assertTrue(r.has("exists"))
        assertFalse(r.getBoolean("exists"))
    }

    @Test
    fun fsExists_directory_true() {
        File(sandboxRoot, "subdir").mkdirs()
        assertTrue(call("fs.exists", """{"path":"subdir"}""").getBoolean("exists"))
    }

    @Test
    fun fsExists_pathTraversal_false() {
        // 越界路径：resolve 返回 null → {exists:false}（不是 {error}）。
        // 桥接层 Tools.Files.exists 判 false，与「不存在」同效——
        // 分支/幂等/去重逻辑不会误判为「存在」，也不会因异常对象炸掉调用方。
        val r = call("fs.exists", """{"path":"../escape.txt"}""")
        assertFalse("越界路径不得判 true", r.optBoolean("exists", true))
        assertTrue(r.getBoolean("exists") == false)
        assertFalse(r.has("error"))
    }

    /* ===================== 问题 2：读取形状 ===================== */

    @Test
    fun fsWrite_thenRead_roundTrip_tenBytes() {
        // 完全复刻用户复测步骤：写入 10 字节文本 → stat=10 ✓、read 有内容 ✓
        val w = call("fs.write", """{"path":"data.txt","content":"0123456789"}""")
        assertEquals(10L, w.getLong("bytes"))

        val s = call("fs.stat", """{"path":"data.txt"}""")
        assertEquals(10L, s.getLong("size"))
        assertTrue(s.getLong("lastModified") > 0)

        val r = call("fs.read", """{"path":"data.txt"}""")
        assertEquals("0123456789", r.getString("content"))
    }

    @Test
    fun fsRead_missingFile_selfExplainingError() {
        val r = call("fs.read", """{"path":"ghost.txt"}""")
        assertTrue(r.has("error"))
        assertTrue(r.getString("error").contains("ghost.txt"))
    }

    @Test
    fun fsRead_directory_distinctError() {
        File(sandboxRoot, "adir").mkdirs()
        val r = call("fs.read", """{"path":"adir"}""")
        assertTrue(r.has("error"))
        // 必须与「不存在」区分开，避免误导排查方向
        assertTrue(r.getString("error").contains("目录"))
    }

    @Test
    fun fsStat_missingFile_error() {
        val r = call("fs.stat", """{"path":"ghost.txt"}""")
        assertTrue(r.has("error"))
        assertFalse("失败响应不得带 size 字段", r.has("size"))
    }

    /* ===================== fs 族其余方法 ===================== */

    @Test
    fun fsList_entriesArray() {
        File(sandboxRoot, "a.txt").writeText("aaa")
        File(sandboxRoot, "b.txt").writeText("bb")
        val r = call("fs.list", """{"path":"."}""")
        val entries = r.getJSONArray("entries")
        assertEquals(2, entries.length())
        val names = (0 until entries.length()).map { entries.getJSONObject(it).getString("name") }
        assertTrue(names.containsAll(listOf("a.txt", "b.txt")))
    }

    @Test
    fun fsMkdir_append_remove_roundTrip() {
        assertTrue(call("fs.mkdir", """{"path":"logs"}""").getBoolean("ok"))
        assertTrue(File(sandboxRoot, "logs").isDirectory)

        assertTrue(call("fs.append", """{"path":"logs/x.txt","content":"1"}""").getBoolean("ok"))
        assertTrue(call("fs.append", """{"path":"logs/x.txt","content":"2"}""").getBoolean("ok"))
        assertEquals("12", call("fs.read", """{"path":"logs/x.txt"}""").getString("content"))

        assertTrue(call("fs.remove", """{"path":"logs"}""").getBoolean("ok"))
        assertFalse(File(sandboxRoot, "logs").exists())
        assertFalse(call("fs.exists", """{"path":"logs"}""").getBoolean("exists"))
    }
}

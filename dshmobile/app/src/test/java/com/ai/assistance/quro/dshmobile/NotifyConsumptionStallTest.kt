package com.ai.assistance.quro.dshmobile

import android.os.FileObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.14.1 真机通知停摆（`dsh-mobile#238`）的**代码层判据**——四条断言对应修复计划的 §7 验收 ①②③④。
 *
 * 为什么这一组必须存在：该缺陷的现场是「文件在长、偏移不动、一条报告也没投」，
 * 而当时的全部测试都是**纯协议**测试（drainBytes / parseEntry 都对，所以全绿）——
 * 坏的是**驱动与推进**（谁触发消费、偏移怎么前进、重复怎么处理），不是解析。
 * 这四条断言里每一条在修复前**必然判红**：
 *  ① [监听位必须覆盖rename落盘与关闭写] / [消费事件白名单] —— 旧位集只有 MODIFY|CREATE
 *  ② [消费步进判定与偏移单调] —— 旧实现没有这两个函数，且 `offset + consumed` 无上界
 *  ③ [重复投递判定] —— 旧实现同 id 同内容 80 ms 内投 5 次
 *  ④ [尾部倒读只取最后一条report行] —— 旧实现内存槽为空即返回 null（面板只剩占位行）
 */
class NotifyConsumptionStallTest {

  // ── ① 驱动面：事件位与事件白名单（停摆候选成因 C2：rename 落盘永不命中）──────────

  @Test
  fun 监听位必须覆盖rename落盘与关闭写() {
    val mask = NotifyStore.WATCH_MASK
    // 位值用字面量写死（不引用 FileObserver 常量）：断言的是「这个位在集合里」，与平台常量取值无关。
    assertTrue("必须含 MOVED_TO(0x80)：引擎改成「临时文件 + rename」落盘时唯一的信号", mask and 0x80 != 0)
    assertTrue("必须含 CLOSE_WRITE(0x8)", mask and 0x8 != 0)
    assertTrue("必须含 MODIFY(0x2)：就地追加的主信号", mask and 0x2 != 0)
    assertTrue("必须含 CREATE(0x100)", mask and 0x100 != 0)
    assertTrue("必须含 DELETE(0x200)", mask and 0x200 != 0)
    assertTrue("必须含 MOVED_FROM(0x40)：轮转时源文件被移走", mask and 0x40 != 0)
  }

  @Test
  fun 消费事件白名单() {
    // 本文件的两个名字 + 目录自身（path=null）——三种都必须触发消费。
    assertTrue(NotifyStore.shouldConsumeOnEvent(FileObserver.MODIFY, NotifyStore.FILE_NAME))
    assertTrue(NotifyStore.shouldConsumeOnEvent(FileObserver.MOVED_TO, NotifyStore.FILE_NAME))
    assertTrue(NotifyStore.shouldConsumeOnEvent(FileObserver.CLOSE_WRITE, NotifyStore.FILE_NAME))
    assertTrue("轮转出的 .1 也必须消费", NotifyStore.shouldConsumeOnEvent(FileObserver.MOVED_TO, NotifyStore.ROTATED_NAME))
    assertTrue("path=null（被监视目录自身的事件）保守消费", NotifyStore.shouldConsumeOnEvent(FileObserver.MOVED_TO, null))
    // 反面：同一目录下的高频写者与无关位都不得触发（否则每写一行 live 就打一次盘）。
    assertFalse("无关文件不得触发消费", NotifyStore.shouldConsumeOnEvent(FileObserver.MODIFY, ".live.ndjson"))
    assertFalse("无关位（ACCESS）不得触发消费", NotifyStore.shouldConsumeOnEvent(FileObserver.ACCESS, NotifyStore.FILE_NAME))
  }

  // ── ② 推进面：步进判定 + 偏移单调（真机现场：offset=8810 / len=9402）────────────

  @Test
  fun 消费步进判定与偏移单调() {
    // 停摆现场那 592 B 必须被判为「要读」——判成 Skip 就是本缺陷本身。
    val step = NotifyStore.drainStep(8810L, 9402L)
    assertTrue("有新字节必须读", step is NotifyStore.DrainStep.Read)
    assertEquals(8810L, (step as NotifyStore.DrainStep.Read).from)
    assertEquals(592, step.bytes)
    assertEquals(NotifyStore.DrainStep.Skip, NotifyStore.drainStep(8810L, 8810L))
    assertEquals("len < offset = 轮转/重建", NotifyStore.DrainStep.Rotated, NotifyStore.drainStep(8810L, 4096L))
    assertEquals("负偏移 = 被重建", NotifyStore.DrainStep.Rotated, NotifyStore.drainStep(-1L, 100L))
    val capped = NotifyStore.drainStep(0L, NotifyStore.READ_CAP_BYTES.toLong() + 1000L)
    assertEquals(
      "单次读取必须有上限（溢出留到下一轮，不跳行）",
      NotifyStore.READ_CAP_BYTES, (capped as NotifyStore.DrainStep.Read).bytes,
    )

    assertEquals("无完整行不得推进", 8810L, NotifyStore.advanceOffset(8810L, 0L, 9402L))
    assertEquals("整批推进到末尾", 9402L, NotifyStore.advanceOffset(8810L, 592L, 9402L))
    assertEquals("越界 consumed 必须被夹住（否则此后每轮都误判轮转）", 9402L, NotifyStore.advanceOffset(8810L, 99999L, 9402L))

    // 单调性：一串「文件增长 / 轮转」序列里偏移永不回退，且不越过本次读到的长度。
    var off = 0L
    for (len in listOf(10L, 10L, 40L, 39L, 100L, 100L, 250L)) {
      val next = NotifyStore.advanceOffset(off, (len - off).coerceAtLeast(0L), len)
      assertTrue("offset 只能单调前进：$off -> $next (len=$len)", next >= off)
      assertTrue("offset 不得超过本次长度：$off -> $next (len=$len)", next <= maxOf(len, off))
      off = next
    }
    // 文件缩回（轮转）时偏移仍不回退——复位由 drainStep 判 Rotated 后显式归零，不靠 advanceOffset 猜。
    assertEquals("轮转期间 advanceOffset 不得回退", 40L, NotifyStore.advanceOffset(40L, 0L, 39L))
    assertEquals(NotifyStore.DrainStep.Rotated, NotifyStore.drainStep(40L, 39L))
  }

  // ── ④ 面板面：尾部倒读（内存槽为空时的回落）──────────────────────────────────

  @Test
  fun 尾部倒读只取最后一条report行() {
    val first = """{"ts":"2026-09-20T15:10:00.000Z","kind":"report","sessionId":"s1","summary":"第一轮"}"""
    val silent = """{"ts":"2026-09-20T15:11:00.000Z","kind":"silent","event":"watchdog","title":"引擎状态"}"""
    val second = """{"ts":"2026-09-20T15:12:00.000Z","kind":"report","sessionId":"s1","summary":"第二轮"}"""
    assertEquals("必须取最后一条（不是第一条）", second, NotifyStore.lastReportLineIn(first + "\n" + silent + "\n" + second + "\n", false))
    assertNull("没有 report 行 → null（面板有自己的占位文案）", NotifyStore.lastReportLineIn(silent + "\n", false))

    // 头部被截断：残缺首行必须丢弃——它可能恰好能被解析成一条假 report。
    val truncated = """{"kind":"report","summary":"残缺半行"}""" + "\n" + silent + "\n"
    assertNull("被截断的首行不得当作最近汇报", NotifyStore.lastReportLineIn(truncated, true))
    assertNotNull("同一段文本在未截断时是合法行", NotifyStore.lastReportLineIn(truncated, false))

    // 半行尾巴（无换行）不参与判定。
    assertEquals(first, NotifyStore.lastReportLineIn(first + "\n" + """{"kind":"report","summary":"未写完""", false))

    // 判定走 parseEntry 而不是文本包含：正文里出现 report 字样不得误判（旧写法会中招）。
    assertNull(NotifyStore.lastReportLineIn("""{"kind":"silent","text":"kind\":\"report\""}""" + "\n", false))
  }

  // ── ③ 投递面：同 id 同内容窗口内只投一次（真机：80 ms 内 5 次）──────────────────

  @Test
  fun 重复投递判定() {
    val a = NotifyStore.parseEntry("""{"kind":"report","sessionId":"s1","summary":"done","durationMs":2029}""")!!
    val sameContent = NotifyStore.parseEntry("""{"kind":"report","sessionId":"s1","summary":"done","durationMs":2029}""")!!
    val nextTurn = NotifyStore.parseEntry("""{"kind":"report","sessionId":"s1","summary":"done","durationMs":4200}""")!!
    val id = NotifyCenter.notificationId(a, NotifyCenter.Face.REPORT)
    assertNotEquals("同名同会话必须共用覆盖式 id", 0, id)
    assertEquals("同内容的两个条目指纹必须相同", NotifyCenter.contentSignature(a), NotifyCenter.contentSignature(sameContent))
    assertNotEquals("内容变了指纹必须变（否则新一轮汇报会被当重复吞掉）", NotifyCenter.contentSignature(a), NotifyCenter.contentSignature(nextTurn))

    val sig = NotifyCenter.contentSignature(a)
    assertTrue("同 id 同内容窗口内 = 重复", NotifyCenter.isDuplicatePost(id, sig, 1_000L, id, sig, 1_080L))
    assertFalse(
      "内容变了不得判重复",
      NotifyCenter.isDuplicatePost(id, sig, 1_000L, id, NotifyCenter.contentSignature(nextTurn), 1_080L),
    )
    assertFalse(
      "窗口外不得判重复",
      NotifyCenter.isDuplicatePost(id, sig, 1_000L, id, sig, 1_000L + NotifyCenter.DEDUP_WINDOW_MS + 1L),
    )
    assertFalse("不同通知身份不得互相吞", NotifyCenter.isDuplicatePost(id, sig, 1_000L, id + 7, sig, 1_080L))
    assertFalse("无历史投递记录 = 不重复", NotifyCenter.isDuplicatePost(0, null, 0L, id, sig, 1_080L))
  }
}

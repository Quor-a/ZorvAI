package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台抑制待投队列的纯逻辑回归（0.14.1 块J FIX-1）。
 *
 * 背景：旧实现「抑制 = 永久丢弃」（命中即 return 终态），用户体感「必须划到后台才推送」。
 * FIX-1 改为「抑制 = 延后」，立刻带来两个可预见的退化，本测试逐个钉住：
 *  ① 无 TTL ⇒ 退后台时把几十分钟前的陈旧汇报一次弹一堆；
 *  ② 无去重/无界 ⇒ 同一会话堆积多条（与 NotificationManager 的覆盖式 ID 语义自相矛盾）且队列无界。
 *
 * 全部是纯函数（不碰 Android/Looper），可在 JVM 上直接跑。
 */
class NotifySuppressQueueTest {

  @Test fun failedPersistenceKeepsPendingUntilSuccessfulSettlement() {
    NotifySuppressQueue.reset()
    val pending = listOf(NotifySuppressQueue.Pending("report:s", entry("event"), 1L))
    try {
      assertFalse(NotifySuppressQueue.commitJournal(pending, emptyMap()) { false })
      assertEquals(0, NotifySuppressQueue.pendingCount())
      var disk = ""
      assertTrue(NotifySuppressQueue.commitJournal(pending, emptyMap()) { disk = it; true })
      assertEquals(pending, NotifySuppressQueue.decodeJournal(disk).first)
      assertEquals(1, NotifySuppressQueue.pendingCount())
      assertFalse(NotifySuppressQueue.commitJournal(emptyList(), mapOf("event" to 2L)) { false })
      assertEquals("failed settlement must retain the original durable item", pending, NotifySuppressQueue.decodeJournal(disk).first)
      assertEquals(1, NotifySuppressQueue.pendingCount())
      assertTrue(NotifySuppressQueue.commitJournal(emptyList(), mapOf("event" to 2L)) { disk = it; true })
      assertEquals(emptyList<NotifySuppressQueue.Pending>(), NotifySuppressQueue.decodeJournal(disk).first)
      assertEquals(mapOf("event" to 2L), NotifySuppressQueue.decodeJournal(disk).second)
      assertEquals(0, NotifySuppressQueue.pendingCount())
    } finally { NotifySuppressQueue.reset() }
  }

  @Test fun journalRoundTripPreservesCompleteEntryAndOriginalDeadline() {
    val item = NotifyEntry(
      kind = "report", title = "title", text = "text", event = "done", dedupeKey = "custom", sessionId = "s",
      eventId = "event", count = 2, done = 3, total = 4, current = "current", outcome = "completed",
      outcomeLabel = "label", summary = "summary", body = "line1\nline2", durationMs = 42L,
      durationLabel = "duration", toolCount = 5, turn = 6, presentedFiles = listOf("file"), popup = false,
      toolName = "tool", reason = "reason", questions = listOf(NotifyQuestion("q", "header", "question", listOf("A", "B"))),
      target = "target",
    )
    val pending = listOf(NotifySuppressQueue.Pending("report:s", item, 1_000L))
    val settled = mapOf(NotifySuppressQueue.identity(item) to 1_200L)
    val recovered = NotifySuppressQueue.decodeJournal(NotifySuppressQueue.encodeJournal(pending, settled))
    assertEquals(pending, recovered.first)
    assertEquals(settled, recovered.second)
    assertTrue(NotifySuppressQueue.isExpired(recovered.first.single().enqueuedAt, 1_000L + NotifySuppressQueue.TTL_MS))
  }

  @Test fun journalSupportsOldEntriesWithNoEventIdOrTarget() {
    val item = NotifyEntry(kind = "report", sessionId = "old-session", summary = "old report")
    val recovered = NotifySuppressQueue.decodeJournal(NotifySuppressQueue.encodeJournal(
      listOf(NotifySuppressQueue.Pending("report:old-session", item, 1L)), emptyMap(),
    ))
    assertEquals(item, recovered.first.single().entry)
    assertEquals(NotifySuppressQueue.identity(item), NotifySuppressQueue.identity(recovered.first.single().entry))
  }

  @Test fun identityDistinguishesSeparateTurnsAndFullBody() {
    val item = NotifyEntry(kind = "report", sessionId = "s", summary = "same", turn = 1, body = "body")
    assertFalse(NotifySuppressQueue.identity(item) == NotifySuppressQueue.identity(item.copy(turn = 2)))
    assertFalse(NotifySuppressQueue.identity(item) == NotifySuppressQueue.identity(item.copy(body = "other body")))
  }

  @Test(expected = IllegalArgumentException::class)
  fun unknownJournalVersionIsRejectedWithoutDiscardingIt() {
    NotifySuppressQueue.decodeJournal("{\"version\":2,\"pending\":[],\"settled\":{}}")
  }

  @Test
  fun strictDecodeKeepsOversizeJournalForBackupAndSalvage() {
    val q = List(NotifySuppressQueue.MAX_PENDING + 1) { NotifySuppressQueue.Pending("key$it", entry("e$it"), 1L) }
    val raw = NotifySuppressQueue.encodeJournal(q, emptyMap())
    assertTrue(NotifySuppressQueue.readJournal(raw) is NotifySuppressQueue.JournalRead.Invalid)
    val salvaged = NotifySuppressQueue.salvageJournal(raw)
    assertEquals("all waiting records must remain recoverable", q.size, salvaged.pending.size)
    assertEquals(1, salvaged.oversized)
  }

  @Test
  fun damagedParentEnvelopeRetainsValidWaitingQuestions() {
    val item = NotifyEntry(kind = "question", eventId = "q-1", title = "还等你回答")
    val raw = "{\"version\":1,\"pending\":[{\"key\":\"retry:q-1\",\"at\":1,\"entry\":" +
      NotifySuppressQueue.encodeEntry(item).toString() + "}],\"settled\":[]}"
    val salvage = NotifySuppressQueue.salvageJournal(raw)
    assertEquals(1, salvage.pending.size)
    assertEquals(item, salvage.pending[0].entry)
    assertTrue(salvage.settled.isEmpty())
    assertTrue("the parent envelope still fails strict decode", NotifySuppressQueue.readJournal(raw) is NotifySuppressQueue.JournalRead.Invalid)
  }

  @Test
  fun backupKeyIsStableAndIndependentOfTheLiveJournalKey() {
    val raw = "{\"version\":2,\"pending\":[]}"
    assertEquals(NotifySuppressQueue.backupKey(raw), NotifySuppressQueue.backupKey(raw))
    assertTrue(NotifySuppressQueue.backupKey(raw).startsWith("notify.deferred.backup."))
    assertFalse("the original journal key must remain available for the backup", NotifySuppressQueue.backupKey(raw) == "notify.deferred.v1")
    var wroteKey = ""
    var wroteValue = ""
    assertTrue(NotifySuppressQueue.backupJournal(raw) { k, v -> wroteKey = k; wroteValue = v; true })
    assertEquals(NotifySuppressQueue.backupKey(raw), wroteKey)
    assertEquals(raw, wroteValue)
    assertFalse("failed backup must not unlock the journal", NotifySuppressQueue.backupJournal(raw) { _, _ -> false })
  }

  @Test
  fun permissionDeferredQuestionDoesNotExpireWithReportTtl() {
    val t0 = 1_000L
    val q = NotifyEntry(kind = "question", eventId = "q-1", title = "等待回答")
    val pending = listOf(NotifySuppressQueue.Pending("retry:q-1", q, t0))
    val later = t0 + NotifySuppressQueue.TTL_MS + 1
    assertFalse("交互事件不得被报告 TTL 清掉", NotifySuppressQueue.expiredAt(pending[0], later))
    val (deliver, expired) = NotifySuppressQueue.takeForFlush(pending, later)
    assertEquals(1, deliver.size)
    assertEquals(0, expired)
  }

  @Test
  fun anOldQuestionAndANewReportNeverDisplaceTheQuestion() {
    val q = NotifyEntry(kind = "question", eventId = "q-old", title = "仍等你回答")
    val old = listOf(NotifySuppressQueue.Pending("retry:q-old", q, 1_000L))
    val now = 1_000L + NotifySuppressQueue.TTL_MS * 2
    val next = NotifySuppressQueue.merge(old, entry("new-report"), "report:s1", now)
    assertTrue("an unexpired waiting question must survive report housekeeping", next.any { it.entry == q })
    assertEquals("the new report is also retained", 2, next.size)
  }

  @Test
  fun nineDistinctQuestionsAreAllRetained() {
    var q = emptyList<NotifySuppressQueue.Pending>()
    for (i in 0 until 9) {
      val e = NotifyEntry(kind = "question", eventId = "q$i", title = "question $i")
      q = NotifySuppressQueue.merge(q, e, "retry:q$i", 1_000L + i)
    }
    assertEquals("capacity must never evict waiting questions", 9, q.size)
    assertEquals(9, q.map { it.entry.eventId }.toSet().size)
  }

  @Test
  fun nineDistinctWaitingQuestionsSurviveStrictDecodeAfterRestart() {
    val q = List(9) { i ->
      NotifySuppressQueue.Pending("retry:q$i", NotifyEntry(kind = "question", eventId = "q$i", title = "question $i"), i.toLong())
    }
    val raw = NotifySuppressQueue.encodeJournal(q, emptyMap())
    val strict = NotifySuppressQueue.readJournal(raw)
    assertTrue("a legitimate interactive journal must not be treated as corrupt on restart", strict is NotifySuppressQueue.JournalRead.Valid)
    val (decoded, settled) = NotifySuppressQueue.decodeJournal(raw)
    assertEquals(9, decoded.size)
    assertEquals(9, decoded.map { it.entry.eventId }.toSet().size)
    assertTrue(settled.isEmpty())
  }

  @Test
  fun captureThenExpectedEventOrderUsesThePureRetirementPredicate() {
    val q = NotifyEntry(kind = "question", eventId = "q-done", title = "waiting")
    val queued = NotifySuppressQueue.Pending("retry:q-done", q, 1_000L)
    assertFalse("an answer still waiting must not be retired",
      NotifySuppressQueue.shouldRetire(queued, resolveEventId = "", answeredEventId = ""))
    assertTrue("engine resolve retires the matching durable item",
      NotifySuppressQueue.shouldRetire(queued, resolveEventId = "q-done", answeredEventId = ""))
    assertTrue("a locally accepted answer retires the same item",
      NotifySuppressQueue.shouldRetire(queued, resolveEventId = "", answeredEventId = "q-done"))
  }

  @Test
  fun aFullReportQueueEvictsOnlyReports() {
    var q = emptyList<NotifySuppressQueue.Pending>()
    for (i in 0 until NotifySuppressQueue.MAX_PENDING) {
      q = NotifySuppressQueue.merge(q, entry("r$i", "s$i"), "report:s$i", 1_000L + i)
    }
    val question = NotifyEntry(kind = "question", eventId = "critical", title = "answer me")
    q = NotifySuppressQueue.merge(q, question, "retry:critical", 2_000L)
    assertTrue("the question is retained", q.any { it.entry == question })
    assertEquals("report capacity is independent of waiting questions", NotifySuppressQueue.MAX_PENDING + 1, q.size)
    assertTrue("adding a question does not consume report capacity", q.any { it.entry.eventId == "r0" })
  }

  @Test fun mixedQueueKeepsReportsBoundedAndMergedAcrossRestart() {
    val questions = List(9) { i ->
      NotifySuppressQueue.Pending("retry:q$i", NotifyEntry(kind = "question", eventId = "q$i"), 1L)
    }
    var q = questions
    for (i in 0 until 20) {
      q = requireNotNull(NotifySuppressQueue.admit(q, entry("r$i", "s$i"), "report:s$i", 100L + i))
    }
    q = requireNotNull(NotifySuppressQueue.admit(q, entry("updated", "s19"), "report:s19", 200L))
    assertEquals(questions, q.filter { it.entry.kind == "question" })
    assertEquals(8, q.count { it.entry.kind == "report" })
    assertEquals(1, q.count { it.key == "report:s19" })
    assertEquals("updated", q.last().entry.eventId)
    assertEquals(q, NotifySuppressQueue.decodeJournal(NotifySuppressQueue.encodeJournal(q, emptyMap())).first)
  }

  @Test fun rescuedOversizeReportsAreNotEvictedByAdmission() {
    val rescued = List(12) { i -> NotifySuppressQueue.Pending("report:s$i", entry("r$i", "s$i"), 1L) }
    assertEquals(null, NotifySuppressQueue.admit(rescued, entry("new"), "report:new", 2L))
    val question = NotifyEntry(kind = "question", eventId = "waiting")
    val q = requireNotNull(NotifySuppressQueue.admit(rescued, question, "retry:waiting", 2L))
    assertEquals(rescued, q.take(rescued.size))
    assertEquals(question, q.last().entry)
  }

  @Test
  fun deferredRetryNeverSettlesTheDurableItem() {
    assertFalse(NotifySuppressQueue.isSettledResult(NotifyCenter.Result.DEFERRED_RETRY))
    assertFalse(NotifySuppressQueue.isSettledResult(NotifyCenter.Result.PERMISSION_DENIED))
    assertFalse(NotifySuppressQueue.isSettledResult(NotifyCenter.Result.ERROR))
    assertTrue(NotifySuppressQueue.isSettledResult(NotifyCenter.Result.POSTED))
    assertTrue(NotifySuppressQueue.isSettledResult(NotifyCenter.Result.RESOLVED))
  }

  @Test
  fun enqueueIsIdempotentForTheSameEntry() {
    val e = entry("event-id")
    val first = NotifySuppressQueue.merge(emptyList(), e, "retry:" + NotifySuppressQueue.identity(e), 1L)
    val again = NotifySuppressQueue.merge(first, e, "retry:" + NotifySuppressQueue.identity(e), 2L)
    assertEquals("replaying the same source event must not duplicate it", 1, again.size)
  }

  @Test
  fun interactiveOverflowIsValidAndDeliveredInBoundedBatches() {
    val q = List(NotifySuppressQueue.MAX_PENDING + 4) {
      NotifySuppressQueue.Pending("retry:$it", NotifyEntry(kind = "question", eventId = "q$it"), 1L)
    }
    val raw = NotifySuppressQueue.encodeSalvagedJournal(q, emptyMap())
    val strict = NotifySuppressQueue.readJournal(raw)
    assertTrue("waiting questions above the report bound remain valid", strict is NotifySuppressQueue.JournalRead.Valid)
    val decoded = NotifySuppressQueue.decodeJournal(raw).first
    assertEquals("no waiting question may be truncated away", q.size, decoded.size)
    val (batch, expired) = NotifySuppressQueue.takeForFlush(decoded, 2L)
    assertEquals("delivery remains bounded", NotifySuppressQueue.MAX_DELIVER_PER_FLUSH, batch.size)
    assertEquals(0, expired)
    assertEquals("the remainder stays queued", q.size - batch.size, decoded.size - batch.size)
  }

  @Test fun corruptOrUnknownJournalIsRetainedUntilBackupGate() {
    val malformed = "{not-json"
    val invalid = NotifySuppressQueue.readJournal(malformed) as NotifySuppressQueue.JournalRead.Invalid
    assertEquals(malformed, invalid.original)
    assertTrue(invalid.reason.isNotBlank())
    val unknown = NotifySuppressQueue.readJournal("{\"version\":2,\"pending\":[],\"settled\":{}}")
    assertTrue(unknown is NotifySuppressQueue.JournalRead.Invalid)
    assertTrue(NotifySuppressQueue.readJournal(null) is NotifySuppressQueue.JournalRead.Valid)

    var writes = 0
    val blocked = NotifySuppressQueue.commitJournalIfAllowed(
      listOf(NotifySuppressQueue.Pending("report:s", entry("e"), 1L)), emptyMap(), allowed = false,
    ) { writes++; true }
    assertFalse("a failed backup must keep the live journal untouched", blocked)
    assertEquals(0, writes)
  }

  private fun entry(id: String, session: String = "s1") = NotifyEntry(
    kind = "report", sessionId = session, eventId = id, title = "会话 " + session,
  )

  @Test
  fun 同会话后写覆盖前写且移队尾() {
    val t0 = 1_000_000L
    var q = NotifySuppressQueue.merge(emptyList(), entry("e1"), "report:s1", t0)
    q = NotifySuppressQueue.merge(q, entry("e2"), "report:s1", t0 + 100)
    assertEquals("同 key 必须合并为一条（覆盖式，不得堆两条）", 1, q.size)
    assertEquals("后写必须覆盖前写", "e2", q[0].entry.eventId)
  }

  @Test
  fun 不同会话互不覆盖() {
    val t0 = 1_000_000L
    var q = NotifySuppressQueue.merge(emptyList(), entry("e1", "s1"), "report:s1", t0)
    q = NotifySuppressQueue.merge(q, entry("e2", "s2"), "report:s2", t0)
    assertEquals(2, q.size)
  }

  @Test
  fun 过期条目在合并时被清掉() {
    val t0 = 1_000_000L
    val q = NotifySuppressQueue.merge(emptyList(), entry("old", "s1"), "report:s1", t0)
    // 距入队已超 TTL：合并新条目时旧条目必须被清掉（否则陈旧汇报会一直等着退后台补弹）。
    val later = t0 + NotifySuppressQueue.TTL_MS + 1
    val merged = NotifySuppressQueue.merge(q, entry("new", "s2"), "report:s2", later)
    assertEquals("过期项必须被清除，只剩新项", 1, merged.size)
    assertEquals("new", merged[0].entry.eventId)
  }

  @Test
  fun isExpired边界() {
    val t0 = 1_000L
    assertFalse("未到 TTL 不得判过期", NotifySuppressQueue.isExpired(t0, t0 + NotifySuppressQueue.TTL_MS - 1))
    assertTrue("到 TTL 即过期", NotifySuppressQueue.isExpired(t0, t0 + NotifySuppressQueue.TTL_MS))
  }

  @Test
  fun 队列有界_超容量保留最新() {
    val t0 = 1_000_000L
    var q = emptyList<NotifySuppressQueue.Pending>()
    val n = NotifySuppressQueue.MAX_PENDING + 5
    for (i in 0 until n) {
      q = NotifySuppressQueue.merge(q, entry("e$i", "s$i"), "report:s$i", t0 + i)
    }
    assertEquals("队列不得超过容量上限（防无界内存）", NotifySuppressQueue.MAX_PENDING, q.size)
    assertEquals("保留的必须是最新的那批", "e" + (n - 1), q.last().entry.eventId)
  }

  @Test
  fun 单次补投有上限_且最新优先() {
    val t0 = 1_000_000L
    var q = emptyList<NotifySuppressQueue.Pending>()
    for (i in 0 until NotifySuppressQueue.MAX_PENDING) {
      q = NotifySuppressQueue.merge(q, entry("e$i", "s$i"), "report:s$i", t0 + i)
    }
    val (deliver, expired) = NotifySuppressQueue.takeForFlush(q, t0 + 100)
    assertEquals("单次补投必须封顶（防退后台瞬间弹一串）", NotifySuppressQueue.MAX_DELIVER_PER_FLUSH, deliver.size)
    assertEquals(0, expired)
    assertEquals("补投必须取最新的（用户最关心刚做完的那一轮）",
      "e" + (NotifySuppressQueue.MAX_PENDING - 1), deliver.last().entry.eventId)
  }

  @Test
  fun 过期项不计入补投但被计入丢弃() {
    val t0 = 1_000_000L
    val q = listOf(
      NotifySuppressQueue.Pending("report:old", entry("old"), t0),
      NotifySuppressQueue.Pending("report:fresh", entry("fresh"), t0 + NotifySuppressQueue.TTL_MS),
    )
    val now = t0 + NotifySuppressQueue.TTL_MS + 1
    val (deliver, expired) = NotifySuppressQueue.takeForFlush(q, now)
    assertEquals("过期项不得补投", 1, deliver.size)
    assertEquals("fresh", deliver[0].entry.eventId)
    assertEquals("过期项必须被如实计数（丢弃要可观测，不得静默）", 1, expired)
  }

  @Test
  fun 覆盖式去重键与会话粒度一致() {
    // 必须与 notificationId 的 report 粒度（dsh-report:<sessionId>）同源，否则补投会与覆盖式 ID 冲突。
    assertEquals("report:s1", NotifyCenter.deferredKey(entry("e1", "s1")))
    assertEquals("report:s2", NotifyCenter.deferredKey(entry("e2", "s2")))
    // 会话为空时退化为事件身份（不得让不同事件互相吞掉）。
    assertEquals("report:e9", NotifyCenter.deferredKey(NotifyEntry(kind = "report", eventId = "e9")))
  }
}

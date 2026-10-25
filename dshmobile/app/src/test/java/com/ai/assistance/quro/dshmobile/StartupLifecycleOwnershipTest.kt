package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused source fixtures only: no root grant/ownership worker fixtures or Android power APIs. */
class StartupLifecycleOwnershipTest {
  @Test fun pendingReasonsCannotAuthorizeStartupReads() {
    assertTrue(startupOwnershipPending("root-maintenance-busy"))
    assertTrue(startupOwnershipPending("repair-result-unknown"))
    assertFalse(startupOwnershipPending("repair-complete"))
    assertFalse(startupOwnershipPending("root-unavailable"))
  }

  @Test fun duplicateBeginDoesNotAdvanceTheCurrentGeneration() {
    val ownership = StartupFlowOwnership()
    val first = ownership.begin()!!
    assertNull(ownership.begin())
    assertTrue(ownership.isCurrent(first.generation))
    ownership.finish(first)
    assertNotNull(ownership.begin())
  }

  @Test fun obsoleteFinallyCannotClearTheReplacementGuard() {
    val ownership = StartupFlowOwnership()
    val obsolete = ownership.begin()!!
    ownership.invalidate()
    val replacement = ownership.begin()!!
    ownership.finish(obsolete) // Late finally after explicit restart.
    assertFalse(ownership.isCurrent(obsolete.generation))
    assertTrue(ownership.isCurrent(replacement.generation))
    assertNull(ownership.begin())
    ownership.finish(replacement)
    assertNotNull(ownership.begin())
  }

  @Test fun destructionIsTerminalEvenIfAnOldCallerFinallyFinishes() {
    val ownership = StartupFlowOwnership()
    val old = ownership.begin()!!
    ownership.invalidate(destroy = true)
    ownership.finish(old)
    assertTrue(ownership.destroyed)
    assertFalse(ownership.isCurrent(old.generation))
    assertNull(ownership.begin())
  }

  @Test fun invalidationInterruptsOnlyTheRegisteredCallerNotASharedWorker() {
    val ownership = StartupFlowOwnership()
    val token = ownership.begin()!!
    val caller = Thread.currentThread()
    val sharedWorker = Thread { }
    token.attach(caller)
    try {
      ownership.invalidate()
      assertTrue(caller.isInterrupted)
      assertFalse(sharedWorker.isInterrupted)
    } finally {
      Thread.interrupted() // Never leave the test runner interrupted.
      token.detach(caller)
    }
  }

  @Test fun pendingRetryBudgetNeverResetsOrSpinsAfterExhaustion() {
    val retry = StartupOwnershipRetryBudget()
    assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(6) { retry.nextDelayMs() })
    repeat(20) { assertNull(retry.nextDelayMs()) }
  }

  @Test fun acquisitionReturningAfterTeardownIsReleasedAndCannotPublish() {
    val released = mutableListOf<String>()
    val owner = EpochResourceOwner<String> { released += it }
    assertFalse(owner.install(acquire = { owner.close(); "late-acquisition" }))
    assertEquals(listOf("late-acquisition"), released)
    var calls = 0
    assertFalse(owner.install(acquire = { calls++; "must-not-acquire" }))
    assertEquals(0, calls)
    owner.close()
    assertEquals(listOf("late-acquisition"), released)
  }

  @Test fun obsoleteCandidateCleanupDoesNotReleaseTheWinningAcquisition() {
    val released = mutableListOf<String>()
    val owner = EpochResourceOwner<String> { released += it }
    assertFalse(owner.install(acquire = {
      assertTrue(owner.install(acquire = { "replacement" }))
      "obsolete"
    }))
    assertEquals(listOf("obsolete"), released)
    owner.close()
    assertEquals(listOf("obsolete", "replacement"), released)
  }

  @Test fun staleServiceTeardownCannotReleaseAReplacementServiceOwner() {
    val released = mutableListOf<String>()
    val old = EpochResourceOwner<String> { released += it }
    val replacement = EpochResourceOwner<String> { released += it }
    assertTrue(old.install(acquire = { "old-service" }))
    assertTrue(replacement.install(acquire = { "new-service" }))
    old.close()
    old.close()
    assertEquals(listOf("old-service"), released)
    assertTrue(replacement.install(acquire = { error("already held") }, keepExisting = { true }))
    replacement.close()
    assertEquals(listOf("old-service", "new-service"), released)
  }

  @Test fun teardownDuringRenewalReleasesOldAndLateNewHandlesExactlyOnce() {
    val released = mutableListOf<String>()
    val owner = EpochResourceOwner<String> { released += it }
    assertTrue(owner.install(acquire = { "held" }))
    assertFalse(owner.install(acquire = { owner.close(); "renewal" }))
    owner.close()
    assertEquals(listOf("held", "renewal"), released)
  }
}

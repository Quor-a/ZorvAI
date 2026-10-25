package com.ai.assistance.quro.dshmobile

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real observation function is driven by a winner/loser handoff, never sleeps. */
class EngineBootContinuationTest {
  private fun CountDownLatch.reached() = assertTrue("worker reached the required phase", await(5, TimeUnit.SECONDS))

  @Test fun loserWaitsBeforeSpawnThenSeesPublishedChildAndActualExit() {
    val starting = AtomicBoolean(false)
    val alive = AtomicBoolean(false)
    val claimed = CountDownLatch(1)
    val publish = CountDownLatch(1)
    val published = CountDownLatch(1)
    val release = CountDownLatch(1)
    val winner = Thread {
      check(starting.compareAndSet(false, true))
      claimed.countDown()
      check(publish.await(5, TimeUnit.SECONDS))
      alive.set(true) // Production publishes its shared Process before clearing STARTING.
      published.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      starting.set(false)
    }
    winner.start()
    try {
      claimed.reached()
      assertFalse("loser does not own the spawn CAS", starting.compareAndSet(false, true))
      assertFalse("legacy process-only poll would falsely report DEAD", alive.get())
      assertTrue(observeBootContinuation(starting::get, alive::get))
      publish.countDown(); published.reached()
      assertTrue(observeBootContinuation(starting::get, alive::get))
      release.countDown(); winner.join(5_000)
      assertFalse(winner.isAlive)
      assertTrue(observeBootContinuation(starting::get, alive::get))
      alive.set(false)
      assertFalse("real exit must still stop waiting", observeBootContinuation(starting::get, alive::get))
    } finally { publish.countDown(); release.countDown(); winner.join(5_000) }
  }

  @Test fun failedWinnerReleasesGuardAndDoesNotKeepLoserWaiting() {
    val starting = AtomicBoolean(false)
    val claimed = CountDownLatch(1)
    val fail = CountDownLatch(1)
    val winner = Thread {
      check(starting.compareAndSet(false, true)); claimed.countDown()
      check(fail.await(5, TimeUnit.SECONDS))
      starting.set(false) // No process was ever published.
    }
    winner.start()
    try {
      claimed.reached()
      assertTrue(observeBootContinuation(starting::get, { false }))
      fail.countDown(); winner.join(5_000)
      assertFalse(winner.isAlive)
      assertFalse(observeBootContinuation(starting::get, { false }))
    } finally { fail.countDown(); winner.join(5_000) }
  }

  @Test fun publisherCompletingDuringStaleProcessReadCannotProduceFalseDead() {
    val starting = AtomicBoolean(false)
    val alive = AtomicBoolean(false)
    val observedBeforeSpawn = CountDownLatch(1)
    val completed = CountDownLatch(1)
    val winner = Thread {
      check(observedBeforeSpawn.await(5, TimeUnit.SECONDS))
      check(starting.compareAndSet(false, true))
      alive.set(true)
      starting.set(false)
      completed.countDown()
    }
    var processReads = 0
    winner.start()
    try {
      assertTrue(observeBootContinuation(starting::get) {
        if (processReads++ == 0) {
          val stale = alive.get() // First STARTING read and this read both precede the winner.
          observedBeforeSpawn.countDown(); completed.reached()
          stale // Guard is false again; only the final liveness read sees publication.
        } else alive.get()
      })
      assertTrue("a stale process read must trigger a fresh read", processReads == 2)
    } finally { observedBeforeSpawn.countDown(); winner.join(5_000) }
  }
}

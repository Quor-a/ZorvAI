package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPageRecoveryPolicyTest {
  @Test fun pausedCallbacksStayStaleAfterAQuickResume() {
    val policy = ForegroundPageRecoveryPolicy()
    policy.resume()
    val generation = policy.generation
    assertTrue(policy.accepts(generation))
    policy.pause()
    assertFalse(policy.accepts(generation))
    policy.resume()
    assertFalse(policy.accepts(generation))
    assertTrue(policy.accepts(policy.generation))
  }

  @Test fun repeatedBackgroundReloadRequestsProduceOneQuietResumeAction() {
    val policy = ForegroundPageRecoveryPolicy()
    policy.resume()
    policy.pause()
    repeat(10) { policy.requestReload() }
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.NONE, policy.takeRecovery())
    policy.resume()
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.RELOAD, policy.takeRecovery())
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.NONE, policy.takeRecovery())
  }

  @Test fun backgroundRendererEvictionDefersAndSupersedesQueuedReloads() {
    val policy = ForegroundPageRecoveryPolicy()
    policy.resume()
    val generation = policy.generation
    policy.pause()
    policy.requestReload()
    policy.rendererLost()
    repeat(10) { policy.requestReload() }
    assertTrue(policy.rendererGone)
    assertFalse(policy.accepts(generation))
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.NONE, policy.takeRecovery())
    policy.resume()
    assertFalse(policy.accepts(policy.generation))
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.RECREATE, policy.takeRecovery())
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.NONE, policy.takeRecovery())
  }

  @Test fun foregroundRendererLossRecreatesOnlyOnce() {
    val policy = ForegroundPageRecoveryPolicy()
    policy.resume()
    policy.rendererLost()
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.RECREATE, policy.takeRecovery())
    policy.requestReload()
    policy.rendererLost() // Duplicate callbacks cannot arm a second recreation.
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.NONE, policy.takeRecovery())
  }

  @Test fun automaticLoadErrorRetryIsBoundedAcrossPauseResumeUntilRealPageReadiness() {
    val policy = ForegroundPageRecoveryPolicy()
    assertFalse(policy.claimLoadErrorRetry())
    policy.resume()
    assertTrue(policy.claimLoadErrorRetry())
    repeat(10) { assertFalse(policy.claimLoadErrorRetry()) }
    policy.pause()
    policy.resume()
    assertFalse(policy.claimLoadErrorRetry())
    policy.pageReady()
    assertTrue(policy.claimLoadErrorRetry())
  }

  @Test fun readinessFromADeadRendererCannotRearmNavigation() {
    val policy = ForegroundPageRecoveryPolicy()
    policy.resume()
    policy.rendererLost()
    policy.pageReady()
    assertFalse(policy.claimLoadErrorRetry())
    policy.requestReload()
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.RECREATE, policy.takeRecovery())
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.NONE, policy.takeRecovery())
  }

  @Test fun explicitBackgroundRefreshDefersWithoutSpendingTheForegroundRetry() {
    val policy = ForegroundPageRecoveryPolicy()
    policy.resume()
    assertTrue(policy.claimLoadErrorRetry())
    policy.pause()
    policy.userReloadRequested()
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.NONE, policy.takeRecovery())
    policy.resume()
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.RELOAD, policy.takeRecovery())
    assertTrue(policy.claimLoadErrorRetry())
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.NONE, policy.takeRecovery())
  }

  @Test fun repeatedCrashesBeforeReadinessStopAtTheNativeGuideAcrossActivityRecreation() {
    val first = ForegroundPageRecoveryPolicy()
    first.resume()
    first.rendererLost()
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.RECREATE, first.takeRecovery())
    val recreated = ForegroundPageRecoveryPolicy()
    recreated.restoreRecreationBudget(first.rendererRecreationAttempted)
    recreated.resume()
    recreated.rendererLost()
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.NATIVE_ERROR, recreated.takeRecovery())
    recreated.requestReload()
    recreated.rendererLost()
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.NONE, recreated.takeRecovery())
    recreated.userReloadRequested()
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.RECREATE, recreated.takeRecovery())
  }

  @Test fun successfulRecreatedPageAllowsALaterIndependentRendererEvictionToRecover() {
    val policy = ForegroundPageRecoveryPolicy()
    policy.restoreRecreationBudget(true)
    policy.resume()
    policy.pageReady()
    assertFalse(policy.rendererRecreationAttempted)
    policy.pause()
    policy.rendererLost()
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.NONE, policy.takeRecovery())
    policy.resume()
    assertEquals(ForegroundPageRecoveryPolicy.Recovery.RECREATE, policy.takeRecovery())
  }
}

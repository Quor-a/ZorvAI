package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Multi-viewer surface arbitration: one Surface can never be silently attached to two viewers.
 *
 * The controller-level integration is exercised on device; this keeps the fail-closed rule itself
 * testable without an Android display.
 */
class VdisplayArbitrationTest {

  @Test
  fun freeTargetAttachesToFirstViewer() {
    assertEquals(
      VdisplayController.ViewerArbitration.Verdict.ATTACH,
      VdisplayController.ViewerArbitration.decide(null, "viewer-a"),
    )
  }

  @Test
  fun sameViewerMayRebindIdempotently() {
    assertEquals(
      VdisplayController.ViewerArbitration.Verdict.ATTACH,
      VdisplayController.ViewerArbitration.decide("viewer-a", "viewer-a"),
    )
  }

  @Test
  fun foreignViewerIsRefusedInsteadOfStealingTheSurface() {
    assertEquals(
      VdisplayController.ViewerArbitration.Verdict.OCCUPIED,
      VdisplayController.ViewerArbitration.decide("viewer-a", "viewer-b"),
    )
  }

  @Test
  fun releaseReturnsTargetToTheFreePool() {
    // Owner cleared -> next viewer (including the previous foreign one) attaches.
    assertEquals(
      VdisplayController.ViewerArbitration.Verdict.ATTACH,
      VdisplayController.ViewerArbitration.decide(null, "viewer-b"),
    )
  }
}

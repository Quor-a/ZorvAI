package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Test

/** Default and wire normalization must fail closed to the virtual-only product scope. */
class ScreenScopeTest {
  @Test
  fun knownWireValuesResolveExactly() {
    assertEquals(ScreenScope.VIRTUAL_ONLY, ScreenScope.fromWire("virtual-only"))
    assertEquals(ScreenScope.REAL_ONLY, ScreenScope.fromWire("real-only"))
    assertEquals(ScreenScope.ALL, ScreenScope.fromWire("all"))
  }

  @Test
  fun absentOrUnknownWireValueFailsClosedToVirtualOnly() {
    assertEquals(ScreenScope.VIRTUAL_ONLY, ScreenScope.fromWire(null))
    assertEquals(ScreenScope.VIRTUAL_ONLY, ScreenScope.fromWire("all-screens"))
    assertEquals(ScreenScope.VIRTUAL_ONLY, ScreenScope.fromWire(""))
  }

  @Test
  fun scopeAllowsOnlyItsDeclaredStableTargets() {
    assertEquals(true, ScreenScope.VIRTUAL_ONLY.allows(ScreenTargets.VIRTUAL))
    assertEquals(false, ScreenScope.VIRTUAL_ONLY.allows(ScreenTargets.REAL))
    assertEquals(true, ScreenScope.REAL_ONLY.allows(ScreenTargets.REAL))
    assertEquals(false, ScreenScope.REAL_ONLY.allows(ScreenTargets.VIRTUAL))
    assertEquals(true, ScreenScope.ALL.allows(ScreenTargets.REAL))
    assertEquals(true, ScreenScope.ALL.allows(ScreenTargets.VIRTUAL))
    assertEquals(false, ScreenScope.ALL.allows("display-1"))
  }

  @Test
  fun vdInputScopeGateDeniesVirtualInputInRealOnly() {
    assertEquals("screen-out-of-scope", ScreenScope.REAL_ONLY.inputDenial("virtual-1"))
    assertEquals(null, ScreenScope.VIRTUAL_ONLY.inputDenial("virtual-1"))
    assertEquals(null, ScreenScope.ALL.inputDenial("virtual-1"))
  }

  @Test
  fun onlyRealHasAStableAndroidDisplayId() {
    assertEquals(true, ScreenTargets.known(ScreenTargets.REAL))
    assertEquals(true, ScreenTargets.known(ScreenTargets.VIRTUAL))
    assertEquals(false, ScreenTargets.known("1"))
    assertEquals(0, ScreenTargets.REAL_DISPLAY_ID)
  }
}

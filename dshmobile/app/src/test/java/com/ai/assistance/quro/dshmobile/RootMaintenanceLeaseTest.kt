package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Epoch decisions and durable admission wiring; actual root/su settlement remains external validation. */
class RootMaintenanceLeaseTest {
  @Test fun missingEpochNeverProvesTermination() {
    for (epoch in listOf<String?>(null, "", "boot-id:one", "boot-count:7")) {
      assertFalse(RootMaintenanceLease.newBoot(epoch, null))
      assertFalse(RootMaintenanceLease.newBoot(null, epoch))
    }
  }
  @Test fun sameBootSourceNeverProvesTermination() {
    assertFalse(RootMaintenanceLease.newBoot("boot-id:one", "boot-id:one"))
    assertFalse(RootMaintenanceLease.newBoot("boot-count:7", "boot-count:7"))
  }
  @Test fun changingEpochSchemeCannotClearSameBootUncertainty() {
    assertFalse(RootMaintenanceLease.newBoot("boot-id:one", "boot-count:7"))
    assertFalse(RootMaintenanceLease.newBoot("boot-count:7", "boot-id:two"))
    assertFalse(RootMaintenanceLease.newBoot("unknown:one", "unknown:two"))
  }
  @Test fun knownNewBootPermitsEarlierProcessLeaseRetirement() {
    assertTrue(RootMaintenanceLease.newBoot("boot-id:one", "boot-id:two"))
    assertTrue(RootMaintenanceLease.newBoot("boot-count:7", "boot-count:8"))
  }
  private fun source(name: String): String = listOf(File("src/main/java/com/ai/assistance/quro/dshmobile/$name.kt"),
    File("app/src/main/java/com/ai/assistance/quro/dshmobile/$name.kt")).first { it.isFile }.readText()
  @Test fun durableLeasePrecedesSuHelperAndUnknownIsNotOrdinaryFailure() {
    val root = source("RootAccess")
    val begin = root.indexOf("""RootMaintenanceLease.begin(app, "su-ownership")""")
    val dispatch = root.indexOf("val transport = execPrivileged(app, command")
    assertTrue(begin >= 0 && dispatch > begin)
    assertTrue(root.contains("""markUnknown(app, "repair-transport-incomplete")"""))
    assertTrue(root.contains("cleanupIncomplete") && root.contains("readError"))
  }
  @Test fun unknownCannotBeClearedByLateFinishOrNewApplicationProcess() {
    val lease = source("RootMaintenanceLease")
    assertTrue(lease.contains("unknown || owner !== Thread.currentThread()"))
    assertTrue(lease.contains("unknown = pendingEpoch != null"))
    assertTrue(lease.contains("prefs.edit().clear().commit()"))
    assertTrue(source("RootExecutionFence").contains("read.tryLock(0, TimeUnit.MILLISECONDS)"))
    assertTrue(source("RootOwnershipJobs").contains("RootMaintenanceLease.outstanding(context)"))
  }
}

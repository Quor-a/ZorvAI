package com.ai.assistance.quro.dshmobile

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotMigrationPolicyTest {
  private fun put(root: File, name: String, content: String) = File(root, name).apply {
    parentFile!!.mkdirs()
    writeText(content)
  }

  @Test fun migratedUserKeepsImportedDocumentProfileConfigAndUserDataAcrossSwap() {
    val root = Files.createTempDirectory("migrated-snapshot").toFile()
    try {
      val live = File(root, "live")
      val stage = SnapshotTransaction.stageRoot(root)
      val liveDsh = File(live, "home/.dsh")
      val stagedDsh = File(stage, "home/.dsh")
      put(live, "usr/bin/node", "old-node")
      put(stage, "usr/bin/node", "new-node")
      put(liveDsh, "settings.yaml.imported", "original: user-config\n")
      put(stagedDsh, "settings.yaml", "factory: blank-provider\n")
      put(stagedDsh, "settings.yaml.imported", "factory-must-not-replace-marker")
      val userPatch = "- id: llm-pi-ai\n  config:\n    providers:\n      custom: user-value\n"
      put(liveDsh, "profiles/web/cordis.patch.yml", userPatch)
      put(stagedDsh, "profiles/web/cordis.patch.yml", "- id: llm-pi-ai\n  config:\n    providers: {}\n- id: factory-added\n  disabled: false\n")
      put(liveDsh, "profiles/web/cordis.yml", "old-factory")
      put(stagedDsh, "profiles/web/cordis.yml", "new-factory")
      put(liveDsh, "profiles/web/storages/settings.json", "user-storage")
      put(stagedDsh, "profiles/web/storages/settings.json", "factory-storage")
      put(liveDsh, "sessions/live.jsonl", "user-session")
      put(stagedDsh, "sessions/live.jsonl", "factory-session")

      val notes = SnapshotUserData.prepareStagedSnapshot(stagedDsh, liveDsh)
      assertTrue(notes.any { it.contains("已迁移") })
      assertFalse(File(stagedDsh, "settings.yaml").exists())
      assertFalse(File(stagedDsh, "settings.yaml.imported").exists())
      assertFalse(File(stagedDsh, "profiles/web/storages").exists())
      assertEquals(userPatch, File(liveDsh, "profiles/web/cordis.patch.yml").readText())
      assertNull(SnapshotTransaction.readMarker(root))

      SnapshotTransaction.swap(root, stage, File(live, "usr"), File(live, "home"),
        SnapshotUserData.preservedNames.toSet(), "0123456789abcdef".repeat(4), 1L)
      assertEquals("new-node", File(live, "usr/bin/node").readText())
      assertFalse(File(liveDsh, "settings.yaml").exists())
      assertEquals("original: user-config\n", File(liveDsh, "settings.yaml.imported").readText())
      val patch = File(liveDsh, "profiles/web/cordis.patch.yml").readText()
      assertTrue(patch.contains("custom: user-value"))
      assertTrue(patch.contains("factory-added"))
      assertEquals("new-factory", File(liveDsh, "profiles/web/cordis.yml").readText())
      assertEquals("user-storage", File(liveDsh, "profiles/web/storages/settings.json").readText())
      assertEquals("user-session", File(liveDsh, "sessions/live.jsonl").readText())
      SnapshotTransaction.finish(root)
      assertNull(SnapshotTransaction.readMarker(root))
    } finally { SnapshotFs.deletePath(root) }
  }

  @Test fun freshInstallationRetainsTheLegacySeedForItsFirstImport() {
    val root = Files.createTempDirectory("first-import").toFile()
    try {
      val stage = File(root, "stage")
      put(stage, "settings.yaml", "seed: first-install\n")
      SnapshotUserData.prepareStagedSnapshot(stage, File(root, "live"))
      assertEquals("seed: first-install\n", File(stage, "settings.yaml").readText())
    } finally { SnapshotFs.deletePath(root) }
  }

  @Test fun legacyBackupCannotResurrectMigratedSettingsOrReplaceCurrentProfilePatch() {
    val root = Files.createTempDirectory("legacy-import").toFile()
    try {
      val live = File(root, "live")
      val backup = File(root, "backup")
      put(live, "settings.yaml.imported", "current-import")
      put(backup, "settings.yaml", "obsolete-seed")
      put(backup, "settings.yaml.imported", "obsolete-import-with-a-longer-body")
      put(live, "profiles/web/cordis.patch.yml", "current")
      put(backup, "profiles/web/cordis.patch.yml", "older-profile-with-a-longer-body")
      put(backup, "profiles/custom/cordis.patch.yml", "missing-user-profile")
      SnapshotUserData.restoreLegacyBackup(backup, live) { }
      assertFalse(File(live, "settings.yaml").exists())
      assertEquals("current-import", File(live, "settings.yaml.imported").readText())
      assertEquals("current", File(live, "profiles/web/cordis.patch.yml").readText())
      assertEquals("missing-user-profile", File(live, "profiles/custom/cordis.patch.yml").readText())
    } finally { SnapshotFs.deletePath(root) }
  }

  @Test fun profileLocalImportMarkerSuppressesItsSeedAndLegacyBackupResurrection() {
    val root = Files.createTempDirectory("profile-local-import").toFile()
    try {
      val live = File(root, "live")
      val stage = File(root, "stage")
      val backup = File(root, "backup")
      put(live, "profiles/web/settings.yaml.imported", "user-import")
      put(live, "profiles/web/cordis.patch.yml", "user-patch")
      put(stage, "profiles/web/settings.yaml", "factory-seed")
      put(stage, "profiles/web/settings.yaml.imported", "factory-marker")
      put(stage, "profiles/web/cordis.patch.yml", "new-factory-patch")
      SnapshotUserData.prepareStagedSnapshot(stage, live)
      assertFalse(File(stage, "profiles/web/settings.yaml").exists())
      assertFalse(File(stage, "profiles/web/settings.yaml.imported").exists())
      assertEquals("new-factory-patch", File(stage, "profiles/web/cordis.patch.yml").readText())
      put(backup, "profiles/web/settings.yaml", "older-settings")
      put(backup, "profiles/web/settings.yaml.imported", "older-and-longer-import")
      SnapshotUserData.restoreLegacyBackup(backup, live) { }
      assertFalse(File(live, "profiles/web/settings.yaml").exists())
      assertEquals("user-import", File(live, "profiles/web/settings.yaml.imported").readText())
      assertEquals("user-patch", File(live, "profiles/web/cordis.patch.yml").readText())
    } finally { SnapshotFs.deletePath(root) }
  }

  @Test fun providerTransferUsesTheActivePatchEvenIfAnOldFactorySeedWasResurrected() {
    val root = Files.createTempDirectory("active-provider-config").toFile()
    try {
      val live = File(root, "live")
      val shared = File(root, "shared")
      put(live, "settings.yaml", "llm-pi-ai:\n  providers: {}\n")
      put(live, "settings.yaml.imported", "historical-user-settings")
      val current = "- id: llm-pi-ai\n  config:\n    providers:\n      custom: current-provider\n"
      val patch = put(live, "profiles/web/cordis.patch.yml", current)
      assertEquals(patch, SnapshotUserData.configurationDocument(live))
      val exported = SnapshotUserData.exportConfiguration(live, shared)
      assertEquals("cordis.patch.yml", exported.name)
      assertEquals(current, exported.readText())
      val updated = current.replace("current-provider", "edited-provider")
      exported.writeText(updated)
      assertEquals(patch, SnapshotUserData.importConfiguration(live, shared))
      assertEquals(updated, patch.readText())
      assertEquals(current, File(patch.parentFile, "cordis.patch.yml.import-backup").readText())
      assertEquals("historical-user-settings", File(live, "settings.yaml.imported").readText())
      assertEquals("llm-pi-ai:\n  providers: {}\n", File(live, "settings.yaml").readText())
    } finally { SnapshotFs.deletePath(root) }
  }

  @Test fun migratedConfigurationNeverFallsBackToHistoricalOrFactoryYamlWhenPatchIsMissing() {
    val root = Files.createTempDirectory("missing-migrated-patch").toFile()
    try {
      val live = File(root, "live")
      val shared = File(root, "shared")
      put(live, "settings.yaml.imported", "historical-provider")
      put(live, "settings.yaml", "factory-blank-provider")
      put(shared, "exports/config/settings.yaml", "old-exported-format")
      assertEquals(File(live, "profiles/web/cordis.patch.yml"), SnapshotUserData.configurationDocument(live))
      assertTrue(runCatching { SnapshotUserData.exportConfiguration(live, shared) }.isFailure)
      assertTrue(runCatching { SnapshotUserData.importConfiguration(live, shared) }.isFailure)
      assertEquals("factory-blank-provider", File(live, "settings.yaml").readText())
      assertEquals("historical-provider", File(live, "settings.yaml.imported").readText())
      assertFalse(File(live, "profiles/web/cordis.patch.yml").exists())
    } finally { SnapshotFs.deletePath(root) }
  }

  @Test fun oldSharedYamlCannotReplaceAnExistingMigratedProfile() {
    val root = Files.createTempDirectory("reject-legacy-config-import").toFile()
    try {
      val live = File(root, "live")
      val shared = File(root, "shared")
      val patch = put(live, "profiles/web/cordis.patch.yml", "current-provider-config")
      put(live, "settings.yaml.imported", "historical-import")
      put(shared, "exports/config/settings.yaml", "obsolete-provider-settings")
      assertTrue(runCatching { SnapshotUserData.importConfiguration(live, shared) }.isFailure)
      assertEquals("current-provider-config", patch.readText())
      assertFalse(File(live, "settings.yaml").exists())
    } finally { SnapshotFs.deletePath(root) }
  }

  @Test fun unmigratedStandaloneYamlTransferKeepsItsExistingFormat() {
    val root = Files.createTempDirectory("legacy-config-transfer").toFile()
    try {
      val live = File(root, "live")
      val shared = File(root, "shared")
      val legacy = put(live, "settings.yaml", "legacy: user-value\n")
      assertEquals(legacy, SnapshotUserData.configurationDocument(live))
      val exported = SnapshotUserData.exportConfiguration(live, shared)
      assertEquals("settings.yaml", exported.name)
      exported.writeText("legacy: edited-value\n")
      assertEquals(legacy, SnapshotUserData.importConfiguration(live, shared))
      assertEquals("legacy: edited-value\n", legacy.readText())
      assertEquals("legacy: user-value\n", File(live, "settings.yaml.import-backup").readText())
    } finally { SnapshotFs.deletePath(root) }
  }

  @Test fun rollbackKeepsImportedMarkerAndProviderPatchWhileRestoringFactoryTree() {
    val root = Files.createTempDirectory("migrated-profile-rollback").toFile()
    try {
      val live = File(root, "live")
      val stage = SnapshotTransaction.stageRoot(root)
      val liveDsh = File(live, "home/.dsh")
      val stagedDsh = File(stage, "home/.dsh")
      put(live, "usr/bin/node", "old-node")
      put(stage, "usr/bin/node", "new-node")
      put(liveDsh, "settings.yaml.imported", "historical-user-settings")
      put(stagedDsh, "settings.yaml", "blank-factory-seed")
      val patch = "- id: llm-pi-ai\n  config:\n    providers:\n      custom: user-provider\n"
      put(liveDsh, "profiles/web/cordis.patch.yml", patch)
      put(stagedDsh, "profiles/web/cordis.patch.yml", "- id: factory-added\n  disabled: false\n")
      SnapshotUserData.prepareStagedSnapshot(stagedDsh, liveDsh)
      val sha = "0123456789abcdef".repeat(4)
      SnapshotTransaction.swap(root, stage, File(live, "usr"), File(live, "home"),
        SnapshotUserData.preservedNames.toSet(), sha, 1L)
      val marker = SnapshotTransaction.readMarker(root)!!
      val rollback = SnapshotTransaction.rollback(root, stage, File(live, "usr"), File(live, "home"), marker)
      assertTrue(rollback.ok)
      assertEquals("old-node", File(live, "usr/bin/node").readText())
      assertEquals(patch, File(liveDsh, "profiles/web/cordis.patch.yml").readText())
      assertEquals("historical-user-settings", File(liveDsh, "settings.yaml.imported").readText())
      assertFalse(File(liveDsh, "settings.yaml").exists())
    } finally { SnapshotFs.deletePath(root) }
  }

  @Test fun factorySeederNeverKeepsDeviceYamlOrItsImporterMarker() {
    val script = listOf(
      File("scripts/build-snapshot-013.mjs"),
      File("../scripts/build-snapshot-013.mjs"),
    ).first { it.isFile }.canonicalFile
    val text = script.readText()
    val seeder = text.substringAfter("// Factory archives always replace device-derived YAML")
      .substringBefore("// 出厂 profile 清单体检")
    assertTrue(seeder.contains("SEED_SETTINGS.split"))
    assertTrue(seeder.contains("seedSettingsBody !== 'llm-deepseek: {}\\nllm-pi-ai:\\n  providers: {}'"))
    assertTrue(seeder.contains("throw new Error('Factory settings seed"))
    assertTrue(seeder.contains("['settings.yaml', 'settings.yaml.imported']"))
    val removal = seeder.indexOf("rmSync(join(DH, leaf)")
    val write = seeder.indexOf("writeFileSync(seedSettingsPath")
    assertTrue(removal >= 0 && write > removal)
    assertTrue(seeder.contains("if (!lstatSync(DH).isDirectory()) throw"))
    assertTrue(seeder.contains("{ flag: 'wx', mode: 0o600 }"))
    assertFalse(seeder.contains("readFileSync(seedSettingsPath"))
    assertFalse(seeder.contains("if (existsSync(seedSettingsPath"))
    // When both repositories are checked out, their production seeder regions must be identical.
    val counterpart = File(script.parentFile.parentFile.parentFile, "scripts/build-snapshot-013.mjs")
    if (counterpart.isFile && counterpart.canonicalFile != script) {
      val mirrored = counterpart.readText().substringAfter("// Factory archives always replace device-derived YAML")
        .substringBefore("// 出厂 profile 清单体检")
      assertEquals(seeder, mirrored)
    }
  }

  @Test fun initializedProfileWithALostHistoricalMarkerDoesNotReceiveANewFactorySeed() {
    val root = Files.createTempDirectory("lost-import-marker").toFile()
    try {
      val live = File(root, "live")
      val stage = File(root, "stage")
      put(live, "profiles/web/cordis.patch.yml", "- id: llm-pi-ai\n  config:\n    providers: user-config\n")
      put(stage, "settings.yaml", "llm-pi-ai:\n  providers: {}\n")
      SnapshotUserData.prepareStagedSnapshot(stage, live)
      assertFalse(File(stage, "settings.yaml").exists())
      assertFalse(File(live, "settings.yaml.imported").exists())
      assertTrue(File(live, "profiles/web/cordis.patch.yml").readText().contains("user-config"))
    } finally { SnapshotFs.deletePath(root) }
  }

  @Test fun preSpawnRepairQuarantinesOnlyTheKnownBlankFactoryReseedAfterMigration() {
    val root = Files.createTempDirectory("retire-old-factory-reseed").toFile()
    try {
      val seed = "# old factory seed\nllm-deepseek: {}\n\nllm-pi-ai:\n  providers: {}\n"
      val legacy = put(root, "settings.yaml", seed)
      assertNull(SnapshotUserData.retireReseededFactorySettings(root))
      assertEquals(seed, legacy.readText()) // First import on a fresh installation remains enabled.
      put(root, "settings.yaml.imported", "original-user-settings")
      val backup = SnapshotUserData.retireReseededFactorySettings(root)!!
      assertEquals(seed, backup.readText())
      assertFalse(legacy.exists())
      assertEquals("original-user-settings", File(root, "settings.yaml.imported").readText())
      assertNull(SnapshotUserData.retireReseededFactorySettings(root))
      val custom = "llm-pi-ai:\n  providers:\n    custom: user-provider\n"
      legacy.writeText(custom)
      assertNull(SnapshotUserData.retireReseededFactorySettings(root))
      assertEquals(custom, legacy.readText())
    } finally { SnapshotFs.deletePath(root) }
  }
}

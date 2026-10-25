package com.ai.assistance.quro.dshmobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfilePackageManifestTest {
  @Test fun userPinsAndUnknownFieldsWinWhileFactoryDependenciesAndBundlesAreAdded() {
    val user = """{"dependencies":{"@shared":"user-pin","@soft/plugin":"1.2"},"dsh":{"profile":{"bundles":["@user/custom"],"future":{"keep":true}}},"futureRoot":{"v":3}}"""
    val factory = """{"dependencies":{"@shared":"factory","@new/hard":"2.0"},"dsh":{"profile":{"bundles":["@factory/base","@user/custom"]}}}"""
    val merged = JSONObject(requireNotNull(ProfilePackageManifest.merge(user, factory)))
    val deps = merged.getJSONObject("dependencies")
    assertEquals("user-pin", deps.getString("@shared"))
    assertEquals("2.0", deps.getString("@new/hard"))
    assertEquals("1.2", deps.getString("@soft/plugin"))
    val profile = merged.getJSONObject("dsh").getJSONObject("profile")
    val bundles = profile.getJSONArray("bundles")
    assertEquals(listOf("@user/custom", "@factory/base"), (0 until bundles.length()).map { bundles.getString(it) })
    assertTrue(profile.getJSONObject("future").getBoolean("keep"))
    assertEquals(3, merged.getJSONObject("futureRoot").getInt("v"))
  }

  @Test fun malformedJsonIsRejectedAndBlankUserUsesFactoryDocument() {
    assertNull(ProfilePackageManifest.merge("not-json", "{}"))
    assertNull(ProfilePackageManifest.merge("{}", "not-json"))
    val factory = "{\"name\":\"factory\"}"
    assertEquals(factory, ProfilePackageManifest.merge("", factory))
  }
}

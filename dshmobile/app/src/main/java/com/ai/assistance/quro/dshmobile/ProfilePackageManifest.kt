package com.ai.assistance.quro.dshmobile

import org.json.JSONArray
import org.json.JSONObject

/** Shared pure merge for a profile-root package.json during snapshot refresh and user restore. */
internal object ProfilePackageManifest {
  /**
   * Keep [userText] as the document base (preserving pins and unknown keys), then add factory
   * dependencies and bundles that are missing. Returns null for invalid JSON so callers can
   * preserve their existing live document or reject an import before its commit.
   */
  fun merge(userText: String, factoryText: String): String? {
    if (userText.isBlank()) return factoryText.takeIf { runCatching { JSONObject(it) }.isSuccess }
    val user = runCatching { JSONObject(userText) }.getOrNull() ?: return null
    val factory = runCatching { JSONObject(factoryText) }.getOrNull() ?: return null
    val factoryDeps = factory.optJSONObject("dependencies") ?: JSONObject()
    if (factoryDeps.length() > 0) {
      val deps = user.optJSONObject("dependencies") ?: JSONObject().also { user.put("dependencies", it) }
      for (key in factoryDeps.keys()) if (!deps.has(key)) deps.put(key, factoryDeps.getString(key))
    }
    val factoryBundles = findBundles(factory)
    if (factoryBundles != null && factoryBundles.length() > 0) {
      val bundles = findBundles(user)
        ?: createBundles(user, nested = nestedBundles(factory) || user.optJSONObject("dsh") != null)
      val present = (0 until bundles.length()).map { bundles.optString(it) }.toHashSet()
      for (i in 0 until factoryBundles.length()) {
        val item = factoryBundles.optString(i)
        if (item.isNotEmpty() && item !in present) bundles.put(item)
      }
    }
    return user.toString(2)
  }

  private fun findBundles(root: JSONObject): JSONArray? =
    root.optJSONArray("dsh.profile.bundles")
      ?: root.optJSONObject("dsh")?.optJSONObject("profile")?.optJSONArray("bundles")

  private fun nestedBundles(root: JSONObject): Boolean = root.optJSONArray("dsh.profile.bundles") == null &&
    root.optJSONObject("dsh")?.optJSONObject("profile")?.optJSONArray("bundles") != null

  private fun createBundles(root: JSONObject, nested: Boolean): JSONArray {
    if (!nested) return JSONArray().also { root.put("dsh.profile.bundles", it) }
    val dsh = root.optJSONObject("dsh") ?: JSONObject().also { root.put("dsh", it) }
    val profile = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
    return JSONArray().also { profile.put("bundles", it) }
  }
}

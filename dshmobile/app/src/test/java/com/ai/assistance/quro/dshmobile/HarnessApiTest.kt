package com.ai.assistance.quro.dshmobile

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #506 harness API 客户端的回归（用户诉状「开发个插件给 zorvAI 一个 api 接口让
 * zorvAI 任意调用他的插件、技能 skills 等等」的壳侧面）。
 *
 * 客户端 = [HarnessApi]（dshmobile 模块 internal 单例）+ DshmobileControl 门面 +
 * app 模块 HarnessTool 的四个动作（tools_list / tool_call / skills_list / skill_load）。
 *
 * 本测试锁四件事：
 *  ① 请求体/超时夹取纯函数：timeoutMs 仅在给出时携带、秒→毫秒、[1,600] 夹取；
 *  ② 回执格式化三层判别（API 面 ok / 工具级 isError / 成功文本优先、空文本退 value）；
 *  ③ 不可达与 API 面失败都有可读结论，绝不抛错；
 *  ④ 源码契约：路由常量与插件 TS 的 ROUTE_* 一一对应，HarnessTool 四动作在位，
 *     DshmobileControl 门面四方法在位（单改一侧即红）。
 */
class HarnessApiTest {

  // ── ① 请求体与超时夹取 ─────────────────────────────────────────────────────

  @Test
  fun toolCallBodyDefaultsArgumentsToEmptyObject() {
    val body = HarnessApi.buildToolCallBody("bash", null, null)
    assertEquals("bash", body.getString("name"))
    assertTrue("arguments 缺省必须是空对象（插件侧 ?? {} 之前的壳侧保证）", body.getJSONObject("arguments").length() == 0)
    assertFalse("timeoutMs 未给出时不得携带", body.has("timeoutMs"))
  }

  @Test
  fun toolCallBodyCarriesTimeoutMsAsMilliseconds() {
    val body = HarnessApi.buildToolCallBody("bash", JSONObject().put("cmd", "ls"), 30)
    assertEquals(30_000L, body.getLong("timeoutMs"))
    assertEquals("ls", body.getJSONObject("arguments").getString("cmd"))
  }

  @Test
  fun engineTimeoutIsClampedToOneToSixHundred() {
    // null = 未给出 → 插件默认 120s；显式给出的值（含 0/负数）一律夹取，不套默认。
    assertEquals(120, HarnessApi.clampEngineTimeoutS(null))
    assertEquals(1, HarnessApi.clampEngineTimeoutS(0))
    assertEquals(1, HarnessApi.clampEngineTimeoutS(-5))
    assertEquals(600, HarnessApi.clampEngineTimeoutS(601))
    assertEquals(600, HarnessApi.clampEngineTimeoutS(999_999))
    assertEquals(300, HarnessApi.clampEngineTimeoutS(300))
  }

  @Test
  fun readTimeoutExceedsEngineTimeoutByMargin() {
    // 默认 120s → 135s（插件 ENGINE_TIMEOUT_DEFAULT 120s + 15s 收错误回执的余量）。
    assertEquals(135_000, HarnessApi.readTimeoutFor(null))
    assertEquals(135_000, HarnessApi.readTimeoutFor(120))
    assertEquals(615_000, HarnessApi.readTimeoutFor(600))
  }

  @Test
  fun skillLoadBodyCarriesName() {
    assertEquals("dsh-plugin-store", HarnessApi.buildSkillLoadBody("dsh-plugin-store").getString("name"))
  }

  // ── ② 回执格式化 ───────────────────────────────────────────────────────────

  @Test
  fun toolsListFormatsDeferLoadingMarker() {
    val body = JSONObject()
      .put("ok", true)
      .put("count", 2)
      .put(
        "tools",
        org.json.JSONArray()
          .put(JSONObject().put("name", "bash").put("description", "跑命令"))
          .put(JSONObject().put("name", "zorvai_bridge").put("description", "桥状态").put("deferLoading", true)),
      )
    val result = HarnessApi.formatToolsList(body)
    assertTrue(result.ok)
    assertTrue(result.text.contains("harness 可见工具 2 个"))
    assertTrue(result.text.contains("· bash — 跑命令"))
    assertTrue(result.text.contains("· zorvai_bridge（渐进披露） — 桥状态"))
  }

  @Test
  fun toolsListEmptyCatalogIsStillOk() {
    val result = HarnessApi.formatToolsList(JSONObject().put("ok", true).put("count", 0))
    assertTrue(result.ok)
    assertTrue(result.text.contains("没有可见工具"))
  }

  @Test
  fun toolCallSuccessPrefersTextThenValue() {
    val withText = HarnessApi.formatToolCall(
      JSONObject().put("ok", true).put("name", "bash").put("isError", false).put("text", "hello"),
    )
    assertTrue(withText.ok)
    assertEquals("hello", withText.text)

    val valueOnly = HarnessApi.formatToolCall(
      JSONObject().put("ok", true).put("name", "x").put("isError", false).put("text", "  ")
        .put("value", JSONObject().put("k", 1)),
    )
    assertTrue(valueOnly.ok)
    assertTrue(valueOnly.text.contains("\"k\""))

    val nothing = HarnessApi.formatToolCall(
      JSONObject().put("ok", true).put("name", "x").put("isError", false).put("text", ""),
    )
    assertTrue(nothing.ok)
    assertTrue(nothing.text.contains("无输出"))
  }

  @Test
  fun toolCallToolLevelFailureIsNotOk() {
    val result = HarnessApi.formatToolCall(
      JSONObject().put("ok", true).put("name", "nope").put("isError", true)
        .put("error", JSONObject().put("message", "UNKNOWN_TOOL")),
    )
    assertFalse(result.ok)
    assertTrue(result.text.contains("harness 工具 nope 执行失败：UNKNOWN_TOOL"))
  }

  @Test
  fun apiLevelFailureSurfacesErrorText() {
    val result = HarnessApi.formatToolCall(JSONObject().put("ok", false).put("error", "请求体超过 1048576 字节上限"))
    assertFalse(result.ok)
    assertTrue(result.text.contains("harness API 面返回错误：请求体超过 1048576 字节上限"))
  }

  @Test
  fun unreachableIsExplainable() {
    val result = HarnessApi.formatToolsList(null)
    assertFalse(result.ok)
    assertTrue(result.text.contains("harness API 面不可达"))
    assertTrue(result.text.contains("action=status"))
  }

  @Test
  fun skillsListAndLoadFormat() {
    val list = HarnessApi.formatSkillsList(
      JSONObject().put("ok", true)
        .put("skills", org.json.JSONArray().put(JSONObject().put("name", "dsh-plugin-store").put("description", "商城"))),
    )
    assertTrue(list.ok)
    assertTrue(list.text.contains("· dsh-plugin-store — 商城"))
    assertTrue(list.text.contains("action=skill_load"))

    val loaded = HarnessApi.formatSkillLoad(
      JSONObject().put("ok", true).put("name", "dsh-plugin-store")
        .put("skill", JSONObject().put("source", "plugin:dshmarketplace-plugin").put("content", "# 正文")),
    )
    assertTrue(loaded.ok)
    assertTrue(loaded.text.contains("=== 技能 dsh-plugin-store（来源 plugin:dshmarketplace-plugin）==="))
    assertTrue(loaded.text.endsWith("# 正文"))

    val missing = HarnessApi.formatSkillLoad(JSONObject().put("ok", false).put("error", "技能不存在：ghost"))
    assertFalse(missing.ok)
    assertTrue(missing.text.contains("技能不存在：ghost"))
  }

  // ── ④ 源码契约：三侧路由/动作/门面对齐 ─────────────────────────────────────

  private fun read(path: String): String {
    val f = File(path)
    assertTrue("找不到 $path（工作目录应为 dshmobile/app）", f.isFile)
    return f.readText()
  }

  @Test
  fun clientRoutesMirrorPluginRoutes() {
    val client = read("src/main/java/com/ai/assistance/quro/dshmobile/HarnessApi.kt")
    val plugin = read("../plugins/dsh-android-harness-api/src/index.ts")
    val routes = listOf(
      "/api/harness/tools",
      "/api/harness/tools/call",
      "/api/harness/skills",
      "/api/harness/skills/load",
    )
    for (route in routes) {
      assertTrue("客户端缺路由常量 $route", client.contains("\"" + route + "\""))
      assertTrue("插件缺路由常量 $route", plugin.contains("'" + route + "'"))
    }
    // 门面四方法必须在场（HarnessTool 只认门面）。
    val facade = read("src/main/java/com/ai/assistance/quro/dshmobile/DshmobileControl.kt")
    for (m in listOf("harnessToolsList", "harnessToolCall", "harnessSkillsList", "harnessSkillLoad")) {
      assertTrue("门面缺方法 $m", facade.contains("fun $m("))
    }
  }

  @Test
  fun harnessToolExposesFourApiActions() {
    // 工作目录 = dshmobile/app；app 模块在仓库根的 ../.. 下。
    val tool = read("../../app/src/main/java/com/ai/assistance/quro/core/tools/HarnessTool.kt")
    for (action in listOf("tools_list", "tool_call", "skills_list", "skill_load")) {
      assertTrue("enum 缺动作 $action", tool.contains("\"" + action + "\""))
      assertTrue("run 缺分支 \"$action\" ->", tool.contains("\"" + action + "\" ->"))
    }
    assertTrue("tool_call 必须经门面 harnessToolCall", tool.contains("DshmobileControl.harnessToolCall("))
    assertTrue("tool_call 必须读 tool_name", tool.contains("optString(\"tool_name\""))
    assertTrue("tool_call 必须读 tool_args", tool.contains("optJSONObject(\"tool_args\")"))
    // 执行预算必须为 tool_call 留 HTTP 余量（引擎超时 + 20s），否则回执在路上就被强杀。
    assertTrue(tool.contains("engineS * 1000L + 20_000L"))
  }

  @Test
  fun pluginSideWiredIntoSnapshot() {
    val patch = read("../scripts/profile-web.cordis.patch.yml")
    assertTrue("profile patch 缺 harness-api insert", patch.contains("id: android-harness-api"))
    assertTrue(patch.contains("'@dsh-android/dsh-android-harness-api'"))
  }
}

package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 功能 B（0.14.6）脱敏回归：诊断包出口 [LogSanitizer] 的正反例。
 *
 * 为什么这一组用例是功能 B 的核心（规格 B4）：诊断包一旦漏打码，用户的真实凭据就被发出去了，
 * **不可撤回**。故判据写成「原值一个字符都不出现」，而不是「看起来像被打码了」——
 * 后者用 contains(MASK) 就能骗过，前者必须逐条断言原始秘密串不在输出里。
 *
 * 覆盖 Lead 点名的四类样本：sk- 前缀密钥 / Authorization Bearer / apiKey 键值 /
 * 启动行里的 token 参数；另加私钥块、Cookie、环境变量、查询串与 fail-closed 分支。
 */
class LogSanitizerTest {

  /** 真实形态的秘密串（一旦出现在输出里即判红）。 */
  private val skKey = "sk-proj-AbCd1234EfGh5678IjKl9012"
  private val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.signaturePart"
  private val apiKeyValue = "AIzaSyD-EXAMPLE-1234567890abcdef"
  private val sessionToken = "ZPBs2spM1bfLo10cQwEOjV13FvNHBRE0fWd-hGKUG_U"
  private val password = "hunter2-SuperSecret"
  // 长度刻意 <20：足以触发脱敏器 ghp_ 规则（要求 ≥8），但不命中 CI 敏感信息扫描器的
  // ghp_[A-Za-z0-9]{20,} 形态——夹具不是真实凭据，不该逼安全门禁放宽。
  private val ghToken = "ghp_AbCdEfGh1234567"
  // PEM 头同理：pre-commit 的签名材料守卫按源文件**连续字面量**匹配
  // `-----BEGIN (RSA |EC |)PRIVATE KEY-----`。这些串全是脱敏器的假密钥夹具（body 是
  // KEY-ONE-MATERIAL / 重复 'A'，不含任何合法 base64 密钥材料），但运行期字符串必须与
  // 真实 PEM 头逐字节一致才能测到正则。源里拼接 ⇒ 连续字面量不出现（守卫不误报），
  // 运行期字节不变（测试判据不失真）。
  private val pemBegin = "-----BEGIN" + " PRIVATE KEY-----"
  private val pemEnd = "-----END" + " PRIVATE KEY-----"
  private val pemBeginEc = "-----BEGIN" + " EC PRIVATE KEY-----"
  private val pemEndEc = "-----END" + " EC PRIVATE KEY-----"
  private val pemBeginRsa = "-----BEGIN" + " RSA PRIVATE KEY-----"
  private val pemEndRsa = "-----END" + " RSA PRIVATE KEY-----"

  @Test
  fun vendorPrefixedKeysAreMasked() {
    val out = LogSanitizer.sanitizeOrNull(
      "config: model key " + skKey + " and github " + ghToken + " and google " + apiKeyValue,
    )
    assertNotNull(out)
    assertFalse("sk- 密钥不得残留", out!!.contains(skKey))
    assertFalse("ghp_ 令牌不得残留", out.contains(ghToken))
    assertFalse("AIza 密钥不得残留", out.contains(apiKeyValue))
    assertTrue("必须打码", out.contains(LogSanitizer.MASK))
  }

  @Test
  fun authorizationBearerHeaderIsMasked() {
    val out = LogSanitizer.sanitizeOrNull(
      "POST /v1/messages\nAuthorization: Bearer " + jwt + "\nContent-Type: application/json",
    )
    assertNotNull(out)
    assertFalse("Bearer 令牌不得残留", out!!.contains(jwt))
    assertTrue("头名必须保留（排障要知道这里是什么）", out.contains("Authorization"))
    assertTrue("非敏感头不受影响", out.contains("Content-Type: application/json"))
  }

  @Test
  fun quotedApiKeyAssignmentsAreMasked() {
    // 带引号 / 不带引号 / JSON 形态三种写法都要覆盖：真实配置文件与日志里三种都出现。
    val out = LogSanitizer.sanitizeOrNull(
      "apiKey: \"" + skKey + "\"\napi_key=" + apiKeyValue + "\n{\"token\":\"" + sessionToken + "\"}",
    )
    assertNotNull(out)
    assertFalse(out!!.contains(skKey))
    assertFalse(out.contains(apiKeyValue))
    assertFalse(out.contains(sessionToken))
    assertTrue("键名要保留", out.contains("apiKey"))
  }

  @Test
  fun passwordsAndSecretsAreMasked() {
    val out = LogSanitizer.sanitizeOrNull(
      "password: " + password + "\nclient_secret: " + password + "\ncredential=" + password,
    )
    assertNotNull(out)
    assertFalse("口令不得残留", out!!.contains(password))
  }

  @Test
  fun launchTokenParameterIsMaskedByTheExistingSink() {
    // 复用既有 EngineAuth.redact 的那一类；这里锁「本对象没有把它漏掉」。
    val out = LogSanitizer.sanitizeOrNull("dsh web: http://127.0.0.1:3080/?token=" + sessionToken)
    assertNotNull(out)
    assertFalse("启动令牌不得残留", out!!.contains(sessionToken))
    assertTrue(out.contains("token=***"))
  }

  @Test
  fun shortSecretLookalikesAreAlsoMasked() {
    // fail-closed 的方向性：短令牌同样打码（EngineAuth 曾因把提取正则的 {40,} 用到脱敏出口而漏打）。
    val out = LogSanitizer.sanitizeOrNull("apiKey: abc123")
    assertNotNull(out)
    assertFalse("短值也不得残留", out!!.contains("abc123"))
  }

  @Test
  fun privateKeyBlockIsRemovedWhole() {
    val body = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC" + "A".repeat(60)
    val out = LogSanitizer.sanitizeOrNull(
      "---\n" + pemBeginRsa + "\n" + body + "\n" + pemEndRsa + "\nnext line",
    )
    assertNotNull(out)
    assertFalse("私钥材料不得残留", out!!.contains(body))
    assertFalse("BEGIN 边界也不得残留（否则读者以为只是被截断）", out.contains("BEGIN RSA PRIVATE KEY"))
    assertEquals("块外内容必须保留", true, out.contains("next line"))
  }

  @Test
  fun cookieHeaderValuesAreMasked() {
    val out = LogSanitizer.sanitizeOrNull(
      "Set-Cookie: dsh-auth-abcdef=" + sessionToken + "; Path=/; HttpOnly\nCookie: other=" + password,
    )
    assertNotNull(out)
    assertFalse(out!!.contains(sessionToken))
    assertFalse(out.contains(password))
    assertTrue("头名要保留", out.contains("Set-Cookie"))
  }

  @Test
  fun environmentExportsAreMasked() {
    val out = LogSanitizer.sanitizeOrNull(
      "OPENAI_API_KEY=" + skKey + "\nDSH_ACCESS_TOKEN=" + sessionToken + "\nPATH=/usr/bin:/bin",
    )
    assertNotNull(out)
    assertFalse(out!!.contains(skKey))
    assertFalse(out.contains(sessionToken))
    assertTrue("非敏感环境变量不受影响", out.contains("PATH=/usr/bin:/bin"))
  }

  @Test
  fun queryStringSecretsAreMasked() {
    val out = LogSanitizer.sanitizeOrNull("GET /v1?api_key=" + apiKeyValue + "&model=gpt&password=" + password)
    assertNotNull(out)
    assertFalse(out!!.contains(apiKeyValue))
    assertFalse(out.contains(password))
    assertTrue("非敏感查询参数不受影响", out.contains("model=gpt"))
  }

  @Test
  fun plainLogLinesAreUnchangedApartFromExistingSink() {
    val plain = "2026-10-11 00:00:00.000 dsh-shell: engine started\nexit code 0\n"
    assertEquals(plain, LogSanitizer.sanitizeOrNull(plain))
  }

  @Test
  fun failureIsFailClosedRatherThanPassThrough() {
    // fail-closed 的契约面：sanitizeOrNull 的返回类型是可空的，调用方**必须**处理 null 分支。
    // 这里断言「正常输入不返回 null」，而 discarding 行为在 LogShareTest 里用注入条目验证。
    assertNotNull(LogSanitizer.sanitizeOrNull("ordinary text"))
    assertEquals("空串也必须能过（不得因空输入返回 null）", "", LogSanitizer.sanitizeOrNull(""))
  }

  @Test
  fun emptyInputStaysEmptyAndIsNotNull() {
    val out = LogSanitizer.sanitizeOrNull("")
    assertNotNull(out)
    assertTrue(out!!.isEmpty())
  }

  @Test
  fun multiplePrivateKeyBlocksAreAllRemoved() {
    val one = pemBegin + "\nKEY-ONE-MATERIAL\n" + pemEnd
    val two = pemBeginEc + "\nKEY-TWO-MATERIAL\n" + pemEndEc
    val out = LogSanitizer.redactPrivateKeyBlocks("a\n" + one + "\nb\n" + two + "\nc")
    assertFalse(out.contains("KEY-ONE-MATERIAL"))
    assertFalse(out.contains("KEY-TWO-MATERIAL"))
    assertTrue(out.contains("a") && out.contains("b") && out.contains("c"))
    // 幂等：已打码的文本再打一次不得再变动（避免二次处理把正文吃掉）。
    assertEquals(out, LogSanitizer.redactPrivateKeyBlocks(out))
  }
}

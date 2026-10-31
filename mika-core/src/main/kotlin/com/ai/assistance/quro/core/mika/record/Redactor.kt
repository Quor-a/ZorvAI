package com.ai.assistance.quro.core.mika.record

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.ai.assistance.quro.core.mika.tool.Verb

/**
 * 运行记录的脱敏:密码框里输入的字、形如 API Key 的串一律记为 `***`。
 * 调用方不确定目标是不是密码框时应按是处理(宁可少记)。
 */
object Redactor {
    const val MASK = "***"

    private val SK_KEY = Regex("""\bsk-[A-Za-z0-9_\-]{16,}""")
    private val BEARER = Regex("""\bBearer\s+[A-Za-z0-9._~+/=\-]{8,}""")
    private val GOOGLE_KEY = Regex("""\bAIza[0-9A-Za-z_\-]{30,}""")
    /** Google 新格式的认证 Key:`AQ.` 开头,后面一长串 base64url 字符。 */
    private val GOOGLE_AUTH_KEY = Regex("""\bAQ\.[0-9A-Za-z_\-]{20,}""")
    private val GITHUB_TOKEN = Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}""")

    /** [secretField]:端口报告这次输入的目标是密码框。 */
    /** [verb]:调用的工具做的动作(不认识的工具为 null)。 */
    fun args(verb: Verb?, argsJson: String, secretField: Boolean): String {
        if (verb == Verb.MemorySave) return maskFields(argsJson, MEMORY_ARGS)
        if (verb == Verb.Script) return script(argsJson)
        if (verb == Verb.PhoneCommand) return maskFields(argsJson, setOf("command"))
        if (verb == Verb.PhoneRun) return phoneRun(argsJson)
        if (verb != Verb.TypeText || !secretField) return text(argsJson)
        val o = try {
            Json.parseToJsonElement(argsJson) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return MASK // 解析不了就整段不记,宁可丢信息
        val masked = if ("text" in o) JsonObject(o + ("text" to JsonPrimitive(MASK))) else o
        return text(masked.toString())
    }

    /** 条件式手机命令里的输入内容可能是密码,整段按字段打码。 */
    private fun phoneRun(argsJson: String): String {
        val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return MASK
        val commands = o["commands"] as? JsonArray ?: return text(argsJson)
        val hidden = JsonArray(commands.map { command ->
            val item = command as? JsonObject ?: return@map command
            if ("text" !in item || item["op"] != JsonPrimitive("type")) item
            else JsonObject(item + ("text" to JsonPrimitive(MASK)))
        })
        return text(JsonObject(o + ("commands" to hidden)).toString())
    }

    /**
     * web_script 的源码:脚本里要输入的字可能写成任何形式(交给 quro.type、先存进变量、拼接),
     * 和 type_text 一样不知道是不是密码框,一律按是处理:源码里每个字符串字面量都打码,只留引号种类(模板字符串留 `${…}` 的位置)。
     */
    private fun script(argsJson: String): String {
        val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return MASK
        val code = (o["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return text(argsJson)
        val masked = STRING_LITERAL.replace(code) { m ->
            val q = m.value.first()
            val body = m.value.substring(1, m.value.length - 1)
            if (body.isEmpty()) m.value
            else if (q == '`') "`" + TEMPLATE_HOLE.findAll(body).joinToString("") { h -> MASK + STRING_LITERAL.replace(h.value) { "'$MASK'" } } + MASK + "`"
            else "$q$MASK$q"
        }
        return text(JsonObject(o + ("code" to JsonPrimitive(masked))).toString())
    }

    /** 工具结果进运行记录前:记忆的内容、用户交回时写的备注不记(编号、种类照记)。 */
    fun result(verb: Verb?, content: JsonObject): JsonObject = when (verb) {
        Verb.MemorySearch -> (content["memories"] as? JsonArray)?.let { arr ->
            JsonObject(content + ("memories" to JsonArray(arr.map { m ->
                (m as? JsonObject)?.let { if ("content" in it) JsonObject(it + ("content" to JsonPrimitive(MASK))) else it } ?: JsonPrimitive(MASK)
            })))
        } ?: content
        Verb.HandToUser -> if ("userNote" in content) JsonObject(content + ("userNote" to JsonPrimitive(MASK))) else content
        Verb.PhoneCommand -> if ("output" in content) JsonObject(content + ("output" to JsonPrimitive(MASK))) else content
        else -> content
    }

    private fun maskFields(argsJson: String, fields: Set<String>): String {
        val o = try {
            Json.parseToJsonElement(argsJson) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return MASK
        return text(JsonObject(o.mapValues { (k, v) -> if (k in fields) JsonPrimitive(MASK) else v }).toString())
    }

    private val STRING_LITERAL = Regex("""'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"|`(?:\\.|[^`\\])*`""")
    /** 模板字符串里的 ${…}:表达式不是字面量,位置留着,前后的字打码。 */
    private val TEMPLATE_HOLE = Regex("""\$\{[^}]*\}""")
    private val MEMORY_ARGS = setOf("content", "quote", "keywords")

    /** 一次性码一类的词。 */
    private val CODE_WORD = Regex("""验证|校验码|动态码|动态密码|确认码|安全码|短信|口令|(?i:\b(?:code|otp|passcode|pin|2fa)\b)""")
    /** 4–8 位数字,中间可以隔单个空格或连字符(「4 8 2 9 1 3」「482-913」)。 */
    private val CODE_DIGITS = Regex(
        """(?<![\d.¥$€£])(?<!\d,)(?<![¥$€£] )\d(?:[ \-]?\d){3,7}(?!\d)(?!\.\d)(?!,\d{3}(?!\d))""" +
            // 后面跟着单位、年份、货币的是量,不是码
            """(?!\s*(?:年|元|块|角|分钟|小时|秒|天|月|日|号|次|个|人|件|条|米|公里|千米|克|公斤|千克|斤|吨|度|℃|°|%|‰|[¥$€£]|(?i:km|kg|mb|gb|kb|ms|cm|mm|mah|rmb|usd|cny)\b))""",
    )
    /** 码和这类词之间至多隔几个字(哪一边都算)。 */
    private const val CODE_WINDOW = 24

    /**
     * 证据账本里的材料(读屏、网页、命令输出)进账前经它:[text] 认得的 Key 之外,一次性码也遮住——
     * 前后 [CODE_WINDOW] 个字以内有「验证」「短信」「code」一类词的 4–8 位数字。密码框的字本来就不进读屏文字。
     */
    fun evidence(s: String): String {
        val t = text(s)
        val words = CODE_WORD.findAll(t).map { it.range }.toList()
        if (words.isEmpty()) return t
        return CODE_DIGITS.replace(t) { m ->
            val near = words.any { w -> m.range.first - w.last in 1..CODE_WINDOW + 1 || w.first - m.range.last in 1..CODE_WINDOW + 1 }
            if (near) MASK else m.value
        }
    }

    fun text(s: String): String {
        var out = s
        for (re in listOf(SK_KEY, GOOGLE_KEY, GOOGLE_AUTH_KEY, GITHUB_TOKEN)) out = re.replace(out, MASK)
        return BEARER.replace(out, "Bearer $MASK")
    }

    // ---------- 网页网络数据 ----------

    data class Network(val url: String?, val body: String?, val headers: Map<String, String>?)

    /**
     * 网页网络数据(地址、响应正文、头)离开网络记录前一律经它:交给模型、进核查证据、进运行记录都是同一份。
     * 名字像凭据的查询参数、JSON/表单字段、头的值记为 `***`(按词判断:author、keyword 不算);
     * OAuth 回跳里的 code、state 一并遮住;此外 JWT、又长又杂(大小写字母加数字)的串、[text] 认得的 Key 也遮住。
     */
    fun network(url: String? = null, body: String? = null, headers: Map<String, String>? = null): Network = Network(
        url = url?.let { values(maskUrl(it), slash = false) },
        body = body?.let { values(maskBody(it), slash = true) },
        headers = headers?.mapValues { (k, v) -> if (secretName(k)) MASK else values(v, slash = true) },
    )

    /**
     * 读脚本、站点命令从页面上读出来的数据(JSON):和网络响应正文同一套规则(凭据名字的字段、JWT、又长又杂的串、Key),
     * 只是长串不跨斜杠认(结果里常有网址,路径各段分开看)。
     */
    fun pageData(s: String): String = values(maskBody(s), slash = false)

    private val SECRET_WORDS = setOf(
        "token", "tokens", "secret", "password", "passwd", "pwd", "passcode", "session", "sessionid", "sessid", "sid",
        "auth", "authorization", "csrf", "xsrf", "signature", "sig", "otp", "cookie", "credential", "credentials",
        "apikey", "jwt", "bearer", "pin", "privatekey",
    )
    private val SECRET_COMPOUNDS = setOf("apikey", "accesskey", "secretkey", "privatekey", "clientsecret", "sessionkey", "authkey")
    private val CASE_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
    private val WORD_SPLIT = Regex("[^A-Za-z0-9]+")

    /** 名字按词拆开(驼峰、下划线、连字符)后有像凭据的词。 */
    private fun secretName(name: String): Boolean {
        val words = name.split(WORD_SPLIT).flatMap { it.split(CASE_BOUNDARY) }.map { it.lowercase() }.filter { it.isNotEmpty() }
        return words.any { it in SECRET_WORDS } || words.joinToString("") in SECRET_COMPOUNDS
    }

    private val OAUTH_MARKERS = setOf("state", "redirect_uri", "client_id", "code_verifier")
    private val OAUTH_RESULTS = setOf("code", "state")

    /** 查询串或片段里的 a=b&c=d:凭据名字的值遮住;像 OAuth 回跳的(有 state 等,且带 code 或凭据)把 code、state 也遮住。[bareKey]:单个 key 也算凭据。 */
    private fun maskParams(q: String, bareKey: Boolean): String {
        val parts = q.split('&')
        val names = parts.map { it.substringBefore('=').lowercase() }
        val oauth = names.any { it in OAUTH_MARKERS } && (names.contains("code") || names.any { secretName(it) })
        return parts.joinToString("&") { p ->
            val eq = p.indexOf('=')
            if (eq <= 0) return@joinToString p
            val name = p.substring(0, eq)
            val n = name.lowercase()
            val hide = secretName(name) || (bareKey && n == "key") || (oauth && n in OAUTH_RESULTS)
            if (hide) "$name=$MASK" else p
        }
    }

    private fun maskUrl(u: String): String {
        val hash = u.indexOf('#')
        val beforeHash = if (hash >= 0) u.substring(0, hash) else u
        val fragment = if (hash >= 0) u.substring(hash + 1) else null
        val q = beforeHash.indexOf('?')
        val base = if (q >= 0) beforeHash.substring(0, q + 1) + maskParams(beforeHash.substring(q + 1), bareKey = true) else beforeHash
        return if (fragment == null) base else base + "#" + if ('=' in fragment) maskParams(fragment, bareKey = true) else fragment
    }

    private val JSON_PAIR = Regex(""""((?:[^"\\]|\\.){1,100})"(\s*:\s*)("(?:[^"\\]|\\.)*"|-?\d[\d.eE+\-]*|true|false|null)""")
    private val FORM = Regex("""^[^\s{}\[\]"<>]+=[^\s]*$""")

    private fun maskBody(b: String): String {
        if (FORM.matches(b)) return maskParams(b, bareKey = false)
        return JSON_PAIR.replace(b) { m ->
            if (secretName(m.groupValues[1])) "\"${m.groupValues[1]}\"${m.groupValues[2]}\"$MASK\"" else m.value
        }
    }

    private val JWT = Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}""")
    private val LONG_WITH_SLASH = Regex("""[A-Za-z0-9_\-+/=]{32,}""")
    private val LONG = Regex("""[A-Za-z0-9_\-+=]{32,}""")

    /** 值里的 JWT、又长又杂的串、已知格式的 Key。地址里不跨斜杠认(路径各段分开看)。 */
    private fun values(s: String, slash: Boolean): String {
        var out = JWT.replace(s, MASK)
        out = (if (slash) LONG_WITH_SLASH else LONG).replace(out) { m ->
            val v = m.value
            if (v.any(Char::isUpperCase) && v.any(Char::isLowerCase) && v.any(Char::isDigit)) MASK else v
        }
        return text(out)
    }
}

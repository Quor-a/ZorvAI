package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.QuroToolSpec
import java.security.MessageDigest

/**
 * 工具规格的**协议合法性护栏**（N5）。
 *
 * ## 为什么必须有这一层
 *
 * 下发到上游的 `tools` 数组是一段**整体** JSON：只要**任意一个**工具的 `name` 不合规，
 * OpenAI / DeepSeek 等严格上游会**整段拒收**（400），结果不是「少一个工具」而是
 * **全部工具调用一起失效** —— 用户看到的现象是「AI 突然不会用工具了」，
 * 而日志里只有一句上游 400，完全没有线索指向那个坏工具。
 *
 * 改造前这条链路上有三处真实缺口（全部是**静默**的）：
 *
 * 1. **全中文技能名坍缩成同一个工具名**。`QuroSkill.sanitizeToolName` 把每个非 ASCII 字符
 *    替换成 `-`、再折叠去首尾、空了回退 `"skill"`，于是「视频号账号诊断」「合同风险审查」
 *    「抖音热榜」**全部**变成 `skill__skill`；紧接着 `QuroTool.specs()` 的
 *    `distinctBy { it.name }` 把它们**去重掉只剩一个**。用户装了 N 个中文技能，
 *    真正能被 AI 调用的只有 1 个，且没有任何日志。
 *    修法：[sanitizeName] 在「净化发生了信息丢失」时追加**原名的稳定哈希尾缀**，
 *    既保持确定性（同一技能名永远得到同一工具名，反向查找依然成立），又保证唯一。
 *
 * 2. **工具名无长度上限**。OpenAI 规定 function name ≤ 64 字符，超了同样整段 400。
 *    修法：截断 + 哈希尾缀（截断本身是信息丢失，所以尾缀保证截断后仍不撞名）。
 *
 * 3. **`parameters` 未校验**。`QuroLlmClient` 直接 `JSONObject(spec.parametersJson)`，
 *    一个坏 schema 会抛异常让**整个请求**构造失败。修法：[normalizeParametersJson]
 *    与 `EMPTY_SCHEMA` 提供「退化但可用」的兜底。
 *
 * ## 两条使用铁律
 *
 * - **绝不在这里改名后直接下发**。[sanitizeName] 必须用在**注册/生产端**
 *   （`QuroSkill.toolNameOf`、导入工具入库），这样 `registry.get(name)` 与模型看到的
 *   名字是同一个。若在下发边界才改名，模型会调用一个注册表里不存在的名字 → 必然「未知工具」。
 * - **只对失败/异常路径做兜底，不对成功路径做「顺手清洗」**：静默改动成功数据是本仓
 *   反复出现过的「假实现」病灶。
 *
 * 纯 Kotlin + `java.security.MessageDigest`，不依赖 `org.json`，可被 JVM 单测穷举。
 */
object QuroToolSpecGuard {

    /**
     * 工具名长度上限（字符）。
     *
     * OpenAI function-calling 规定 `name` 最长 64；多数兼容网关沿用该限制。
     * 取 64 作为**总长**上限，调用方若自带前缀（如 `skill__`）必须自行扣减（见 `QuroSkill`）。
     */
    const val MAX_TOOL_NAME_LEN = 64

    /** 哈希尾缀的十六进制位数。8 位 = 32 bit，对「同一个人的技能集合」规模绰绰有余。 */
    private const val HASH_LEN = 8

    /** 参数缺失/非法时使用的**退化 schema**：空对象。让工具仍可被调用，而不是整轮请求失败。 */
    const val EMPTY_SCHEMA = """{"type":"object","properties":{}}"""

    /** 合法工具名字符集（与 OpenAI function-calling 一致）。 */
    private val LEGAL_FULL = Regex("^[A-Za-z0-9_-]+$")
    private val ILLEGAL = Regex("[^A-Za-z0-9_-]")
    private val MULTI_DASH = Regex("-+")

    /** 合法工具名：非空、不超长、字符集合规。 */
    fun isLegalName(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_TOOL_NAME_LEN && LEGAL_FULL.matches(name)

    /**
     * 把任意字符串净化为**合法、唯一、确定**的工具名。
     *
     * 算法（顺序即语义）：
     * 1. 已经是合法且不超长的名字 → **原样返回**。这一步同时保证了
     *    **幂等性**（`sanitizeName(sanitizeName(x)) == sanitizeName(x)`），
     *    因为净化结果必然合法且不超长，第二次调用会直接命中本分支。
     * 2. 非法字符（中文 / 空格 / 斜杠 / 点…）逐个替换为 `-`，折叠连续 `-`，去首尾 `-`。
     * 3. **丢失检测（必须看两种丢失）**：只要发生下列任一情况就追加
     *    `-<原名的 SHA-256 前 8 位>` 把唯一性找回来：
     *    - **字符丢失**：净化结果与原文不同（最极端的是全中文名 → 空 → `"tool"`，
     *      所有中文技能会撞在一起）；
     *    - **长度丢失**：原文超过 `maxLen`（纯 ASCII 超长名净化后与原文**相同**，
     *      只查字符会漏掉它，截断后两个共享长前缀的名字会直接撞名 —— 这是实测抓到的 bug）。
     *    ⚠️ 哈希取的是**净化前的原文（trim 后）**，所以「同一个技能名」永远得到
     *    「同一个工具名」，反向查找（`toolNameOf(skill.name) == call.name`）不会失效。
     * 4. 超长 → 截断到 `maxLen - 尾缀长度`，再拼尾缀；截断属于长度丢失，尾缀已经因此带上，
     *    所以截断后依然不撞名。
     *
     * @param raw 原始名字。
     * @param maxLen 结果允许的最大长度；调用方自带前缀时需扣掉前缀长度。
     */
    fun sanitizeName(raw: String, maxLen: Int = MAX_TOOL_NAME_LEN): String {
        val limit = maxLen.coerceAtLeast(HASH_LEN * 3)
        val base = raw.trim()
        if (base.isNotEmpty() && base.length <= limit && LEGAL_FULL.matches(base)) return base

        var body = ILLEGAL.replace(base) { "-" }
        body = MULTI_DASH.replace(body, "-").trim('-')
        if (body.isEmpty()) body = "tool"

        // 丢失检测必须同时看**两种**丢失：
        //  - 字符被替换：body != base（中文/空格/斜杠被换成 '-'）
        //  - 长度被截断：base.length > limit
        // 只查字符会漏掉「纯 ASCII 超长名」—— 那种名字 body == base，于是不带哈希尾缀，
        // 截断后两个共享长前缀的名字会**直接撞名**（实测抓到的 bug）。
        val lossy = body != base || base.length > limit
        val suffix = if (lossy) "-" + hash8(base) else ""
        val room = (limit - suffix.length).coerceAtLeast(1)
        if (body.length > room) {
            body = body.take(room).trimEnd('-').ifEmpty { "t" }
        }
        val out = body + suffix
        return if (out.length <= limit) out else out.take(limit)
    }

    /**
     * 名字的稳定哈希前 8 位（小写十六进制）。
     *
     * 用 SHA-256 而非 `String.hashCode()`：后者只有 32 bit 且 Java 规范虽规定算法、
     * 但设计上极易构造碰撞（且不利于跨语言比对）。这里的结果不能变，
     * 否则「存过工具名 → 重启后反查技能」会失配。
     */
    fun hash8(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(HASH_LEN)
        var i = 0
        while (sb.length < HASH_LEN && i < digest.size) {
            sb.append(String.format("%02x", digest[i]))
            i++
        }
        return sb.toString()
    }

    /**
     * 粗判「像不像一个 JSON 对象」。
     *
     * 刻意只做形状判断（首 `{` 尾 `}`）而不真的解析 —— 解析要 `org.json`，
     * 而 Android 单测里它是会抛 "not mocked" 的桩，把它拉进来会让本类不可测。
     * 真正的解析与兜底在 `QuroLlmClient` 用 `runCatching` 完成。
     */
    fun looksLikeJsonObject(s: String?): Boolean {
        val t = s?.trim() ?: return false
        return t.length >= 2 && t.startsWith("{") && t.endsWith("}")
    }

    /** 把 `parametersJson` 规整成**保证可用**的 JSON Schema 字符串；形状可疑时退化为 [EMPTY_SCHEMA]。 */
    fun normalizeParametersJson(s: String?): String =
        if (looksLikeJsonObject(s)) s!!.trim() else EMPTY_SCHEMA

    /** 去重结果：保留下来的规格 + 被丢弃的重复名（**必须记日志**，绝不静默丢工具）。 */
    data class DedupeResult(
        val specs: List<QuroToolSpec>,
        val droppedDuplicates: List<String>,
    ) {
        val hadDuplicates: Boolean get() = droppedDuplicates.isNotEmpty()
    }

    /**
     * 按名字去重，**保留先出现的那个**（内置工具优先于导入/插件/技能，调用方按该顺序传入）。
     *
     * 与裸 `distinctBy { it.name }` 的唯一区别是：这里会**把丢掉的名字报出来**。
     * 工具被静默去重正是「装了技能却用不了」的元凶，绝不能再让它无声发生。
     */
    fun dedupe(specs: List<QuroToolSpec>): DedupeResult {
        val seen = LinkedHashSet<String>(specs.size)
        val kept = ArrayList<QuroToolSpec>(specs.size)
        val dropped = ArrayList<String>()
        for (s in specs) {
            if (seen.add(s.name)) kept.add(s) else dropped.add(s.name)
        }
        return DedupeResult(kept, dropped)
    }
}

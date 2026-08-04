package com.ai.assistance.mnn

import java.io.File
import java.lang.reflect.Method
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 锁死 [MNNLlmSession] 注入给 MNN 引擎的采样配置**键名与结构**。
 *
 * 背景：这类键名拼写错误（例如把 `n_gram` 写成 `ngram`）编译器抓不到，引擎侧
 * `config_.value(key, default)` 找不到 key 就静默用默认值，表现为「配了但没生效」。
 * 本测试直接反射调用 `buildSamplerConfigs`，把键名与引擎 `llmconfig.hpp` 的读取键逐一对齐。
 *
 * 引擎侧真实键名（`transformers/llm/engine/src/llmconfig.hpp`，已人工核对）：
 * - `sampler_type`        line 440-441（默认 "mixed"）
 * - `mixed_samplers`      line 444-445（默认 [topK, tfs, typical, topP, min_p, temperature]，无 penalty）
 * - `repetition_penalty`  line 485-488（回退旧键 `penalty`，默认 1.0）
 * - `n_gram`              line 495-497（默认 8）—— **不是** `ngram`
 * - `ngram_factor`        line 499-501（默认 1.0，必须 > 1.0 才启用）
 * - `penalty_window`      line 511-513（默认 0 = 不限窗）
 */
class MnnSamplerConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 引擎 `llmconfig.hpp` 实际读取的键名，与上面的注释一一对应。 */
    private val engineKeys = listOf(
        "sampler_type",
        "mixed_samplers",
        "repetition_penalty",
        "penalty_window",
        "n_gram",
        "ngram_factor",
    )

    @Before
    fun sanityCheckJsonImplementation() {
        // mockable android.jar 里的 org.json 是桩（返回默认值），若它抢先生效则
        // quote() 会返回 null，后续断言全部失真。这里先做前置校验，避免误判。
        assertEquals(
            "测试 classpath 上的 org.json 是桩实现，不是真实实现——测试结果不可信",
            "\"penalty\"",
            JSONObject.quote("penalty"),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun buildConfigs(
        modelConfigJson: String,
        tuning: MnnSamplerTuning = MnnSamplerTuning(),
    ): List<String> {
        val f = tmp.newFile("llm_config_${System.nanoTime()}.json")
        f.writeText(modelConfigJson, Charsets.UTF_8)
        return invokeBuildSamplerConfigs(f, tuning)
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildSamplerConfigs(file: File, tuning: MnnSamplerTuning): List<String> {
        val m: Method = findBuildSamplerConfigs()
        m.isAccessible = true
        return m.invoke(MNNLlmSession.Companion, file, tuning) as List<String>
    }

    private fun findBuildSamplerConfigs(): Method {
        val candidates = listOf(
            MNNLlmSession.Companion::class.java,
            MNNLlmSession::class.java,
        )
        for (c in candidates) {
            c.declaredMethods.firstOrNull {
                it.name == "buildSamplerConfigs" && it.parameterCount == 2
            }?.let { return it }
        }
        throw AssertionError(
            "找不到 buildSamplerConfigs(File, MnnSamplerTuning)；" +
                "Companion methods=${MNNLlmSession.Companion::class.java.declaredMethods.map { it.name }}"
        )
    }

    /** 把 configs 列表合并成一个 key -> 原始 JSON 片段的映射，并校验每条都是合法 JSON 且只含 1 个 key。 */
    private fun toKeyMap(configs: List<String>): LinkedHashMap<String, Any> {
        val map = LinkedHashMap<String, Any>()
        configs.forEach { raw ->
            val o = JSONObject(raw) // 非法 JSON 会直接抛异常
            assertEquals("每条配置应只包含 1 个键：$raw", 1, o.length())
            val key = o.keys().next()
            assertFalse("配置键重复下发：$key", map.containsKey(key))
            map[key] = o.get(key)
        }
        return map
    }

    private fun chainOf(configs: List<String>): List<String> {
        val arr = toKeyMap(configs)["mixed_samplers"] as JSONArray
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // ------------------------------------------------------ P1：键名精确匹配

    /** 默认（模型 config 不含任何采样键）时，六个键必须全部下发且拼写精确。 */
    @Test
    fun emitsExactEngineKeyNames_onBareModelConfig() {
        val map = toKeyMap(buildConfigs("{}"))
        engineKeys.forEach { key ->
            assertTrue("缺少引擎键 \"$key\"，实际下发=${map.keys}", map.containsKey(key))
        }
        assertEquals("不应下发多余键", engineKeys.toSet(), map.keys)
    }

    /** 明确禁止历史踩坑过的错误拼写。 */
    @Test
    fun doesNotEmitMisspelledKeys() {
        val joined = buildConfigs("{}").joinToString("\n")
        listOf(
            "\"ngram\"",          // 正确应为 n_gram（llmconfig.hpp:496）
            "\"nGram\"",
            "\"n_gram_factor\"",  // 正确应为 ngram_factor（llmconfig.hpp:500）
            "\"repetitionPenalty\"",
            "\"penaltyWindow\"",
            "\"mixedSamplers\"",
            "\"samplerType\"",
            "\"repeat_penalty\"",
        ).forEach { bad ->
            assertFalse("下发了错误键名 $bad：\n$joined", joined.contains(bad))
        }
    }

    /** 键名与引擎源码交叉核对（`.cxx` 依赖存在时执行，缺失则跳过）。 */
    @Test
    fun keyNamesMatchEngineSourceLiterally() {
        val hpp = locateLlmConfigHpp()
        assumeTrue("未找到 llmconfig.hpp（.cxx 依赖未拉取），跳过交叉核对", hpp != null)
        val src = hpp!!.readText(Charsets.UTF_8)
        engineKeys.forEach { key ->
            assertTrue(
                "键 \"$key\" 在引擎 ${hpp.name} 里不存在，说明键名写错或引擎版本已变",
                src.contains("\"$key\""),
            )
        }
        // 反向确认：引擎读的是 n_gram，不存在 ngram 这个键
        assertFalse("引擎里出现了 \"ngram\" 键，需重新核对", src.contains("value(\"ngram\","))
        assertTrue(src.contains("value(\"n_gram\","))
    }

    private fun locateLlmConfigHpp(): File? {
        // Gradle Test 的 workingDir 在不同 AGP 版本下可能是模块目录或根工程目录，
        // 所以从当前目录逐级向上找 .cxx/quro_deps（含 llm/mnn 子路径）。
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile.normalize()
        repeat(6) {
            dir?.let {
                candidates += File(it, ".cxx/quro_deps")
                candidates += File(it, "llm/mnn/.cxx/quro_deps")
                dir = it.parentFile
            }
        }
        // quro_deps 下同时存在 mnn-<hash>-build 与 mnn-<hash>-src，只有 -src 里有引擎源码，
        // 因此必须遍历全部候选取第一个真实存在 llmconfig.hpp 的，不能取第一个 mnn-* 目录。
        return candidates.asSequence()
            .filter { it.isDirectory }
            .flatMap { (it.listFiles() ?: emptyArray()).asSequence() }
            .filter { it.isDirectory && it.name.startsWith("mnn-") }
            .map { File(it, "transformers/llm/engine/src/llmconfig.hpp") }
            .firstOrNull { it.isFile }
    }

    // --------------------------------------------- P1：penalty 必须在链首且不丢项

    /** 模型未配置 mixed_samplers 时，用引擎默认链并把 penalty 插到最前，原有项一个不丢。 */
    @Test
    fun prependsPenaltyToDefaultChain_withoutLosingEntries() {
        val chain = chainOf(buildConfigs("{}"))
        assertEquals("penalty 必须在链首", "penalty", chain.first())
        assertEquals(
            listOf("penalty", "topK", "tfs", "typical", "topP", "min_p", "temperature"),
            chain,
        )
        assertEquals("链中不允许出现重复项", chain.size, chain.toSet().size)
    }

    /** 模型自带 mixed_samplers 时，保留原顺序并只在最前补 penalty。 */
    @Test
    fun prependsPenaltyToModelChain_preservingOrder() {
        val model = """{"mixed_samplers":["topK","topP","temperature"]}"""
        val chain = chainOf(buildConfigs(model))
        assertEquals(listOf("penalty", "topK", "topP", "temperature"), chain)
    }

    /** 模型自带链里已含 penalty 时不得重复插入。 */
    @Test
    fun doesNotDuplicatePenaltyWhenAlreadyPresent() {
        val model = """{"mixed_samplers":["topK","penalty","temperature"]}"""
        val chain = chainOf(buildConfigs(model))
        assertEquals(1, chain.count { it == "penalty" })
        assertEquals(listOf("topK", "penalty", "temperature"), chain)
    }

    /** 模型给了空数组时退回引擎默认链 + penalty。 */
    @Test
    fun fallsBackToDefaultChainOnEmptyArray() {
        val chain = chainOf(buildConfigs("""{"mixed_samplers":[]}"""))
        assertEquals("penalty", chain.first())
        assertEquals(7, chain.size)
    }

    /** sampler_type 必须强制为 mixed（greedy 会让整条管线短路，sampler.cpp:239）。 */
    @Test
    fun forcesSamplerTypeMixed() {
        assertEquals("mixed", toKeyMap(buildConfigs("{}"))["sampler_type"])
        assertEquals(
            "模型配成 greedy 时也必须被强制改成 mixed",
            "mixed",
            toKeyMap(buildConfigs("""{"sampler_type":"greedy"}"""))["sampler_type"],
        )
    }

    // ------------------------------------------------------- 取值与尊重模型配置

    /** 默认兜底取值与 MnnSamplerTuning 一致，且 penalty > 1.0、ngram_factor > 1.0 才有效。 */
    @Test
    fun emitsEffectiveDefaultValues() {
        val map = toKeyMap(buildConfigs("{}"))
        assertEquals(1.1, (map["repetition_penalty"] as Number).toDouble(), 1e-6)
        assertEquals(256, (map["penalty_window"] as Number).toInt())
        assertEquals(8, (map["n_gram"] as Number).toInt())
        assertEquals(1.02, (map["ngram_factor"] as Number).toDouble(), 1e-6)

        assertTrue(
            "repetition_penalty 必须 > 1.0，否则 stepPenalty 直接 return（sampler.cpp:278）",
            (map["repetition_penalty"] as Number).toDouble() > 1.0,
        )
        assertTrue(
            "ngram_factor 必须 > 1.0 才会启用 n-gram 惩罚（sampler.cpp:277）",
            (map["ngram_factor"] as Number).toDouble() > 1.0,
        )
        assertTrue(
            "penalty_window 必须 > 0，否则会连 system prompt 的人设词一起惩罚",
            (map["penalty_window"] as Number).toInt() > 0,
        )
    }

    /** 模型显式配了更强的 repetition_penalty 时应尊重模型值。 */
    @Test
    fun respectsModelRepetitionPenaltyWhenStronger() {
        val map = toKeyMap(buildConfigs("""{"repetition_penalty":1.35}"""))
        assertEquals(1.35, (map["repetition_penalty"] as Number).toDouble(), 1e-5)
    }

    /** 模型配了失效值 1.0 时必须被兜底值顶掉。 */
    @Test
    fun overridesIneffectiveModelRepetitionPenalty() {
        val map = toKeyMap(buildConfigs("""{"repetition_penalty":1.0}"""))
        assertEquals(1.1, (map["repetition_penalty"] as Number).toDouble(), 1e-6)
    }

    /** 兼容引擎的旧键 `penalty`（llmconfig.hpp:488）。 */
    @Test
    fun respectsLegacyPenaltyKey() {
        val map = toKeyMap(buildConfigs("""{"penalty":1.25}"""))
        assertEquals(1.25, (map["repetition_penalty"] as Number).toDouble(), 1e-5)
    }

    /** 模型已配置窗口 / n-gram 时不得覆盖模型调优结果。 */
    @Test
    fun doesNotOverrideModelTunedFields() {
        val model = """{"penalty_window":512,"n_gram":4,"ngram_factor":1.5}"""
        val map = toKeyMap(buildConfigs(model))
        assertFalse("不应覆盖模型自带 penalty_window", map.containsKey("penalty_window"))
        assertFalse("不应覆盖模型自带 n_gram", map.containsKey("n_gram"))
        assertFalse("不应覆盖模型自带 ngram_factor", map.containsKey("ngram_factor"))
        // 但采样链和 penalty 仍必须注入
        assertTrue(map.containsKey("mixed_samplers"))
        assertTrue(map.containsKey("sampler_type"))
    }

    /** 可选覆盖项默认不下发；显式指定时才下发。 */
    @Test
    fun optionalOverridesAreOptional() {
        val bare = toKeyMap(buildConfigs("{}"))
        assertFalse(bare.containsKey("temperature"))
        assertFalse(bare.containsKey("topK"))
        assertFalse(bare.containsKey("topP"))

        val tuned = toKeyMap(
            buildConfigs("{}", MnnSamplerTuning(temperature = 0.7f, topK = 40, topP = 0.9f))
        )
        assertEquals(0.7, (tuned["temperature"] as Number).toDouble(), 1e-6)
        assertEquals(40, (tuned["topK"] as Number).toInt())
        assertEquals(0.9, (tuned["topP"] as Number).toDouble(), 1e-6)
    }

    /** enabled=false 时完全不注入（排障对照用）。 */
    @Test
    fun emitsNothingWhenDisabled() {
        assertTrue(buildConfigs("{}", MnnSamplerTuning(enabled = false)).isEmpty())
    }

    // ------------------------------------------------------------ 健壮性

    /** llm_config.json 损坏时不得抛异常，且仍要注入抗复读配置（降级为全兜底值）。 */
    @Test
    fun survivesMalformedModelConfig() {
        val configs = buildConfigs("{ this is not json ")
        val map = toKeyMap(configs)
        engineKeys.forEach { assertTrue("损坏配置下仍应下发 $it", map.containsKey(it)) }
        assertEquals("penalty", chainOf(configs).first())
    }

    /** 空文件同样不得崩。 */
    @Test
    fun survivesEmptyModelConfig() {
        val map = toKeyMap(buildConfigs(""))
        assertNotNull(map["mixed_samplers"])
    }

    /** 每条下发内容都必须是引擎 setConfig 能吃的合法单键 JSON（toKeyMap 内已校验）。 */
    @Test
    fun allEmittedConfigsAreValidSingleKeyJson() {
        val configs = buildConfigs("""{"mixed_samplers":["topK","temperature"],"repetition_penalty":1.2}""")
        assertTrue(configs.isNotEmpty())
        toKeyMap(configs)
        configs.forEach {
            assertTrue("配置必须是 JSON 对象字面量：$it", it.trim().startsWith("{") && it.trim().endsWith("}"))
        }
    }
}

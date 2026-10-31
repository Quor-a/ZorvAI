package com.ai.assistance.quro.core.mika.eval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class EvalCategory {
    @SerialName("read") Read,
    @SerialName("write") Write,
    @SerialName("irreversible") Irreversible,
    @SerialName("impossible") Impossible,
    @SerialName("barrier") Barrier,
}

@Serializable
enum class Expect {
    @SerialName("succeeded") Succeeded,
    @SerialName("failed") Failed,
    @SerialName("confirmation") Confirmation,
    @SerialName("takeover") Takeover,
}

/** 客观核对项;JSON 里以 "type" 区分。 */
@Serializable
sealed interface Check {
    @Serializable @SerialName("answer_contains")
    data class AnswerContains(val all: List<String>) : Check

    @Serializable @SerialName("answer_regex")
    data class AnswerRegex(val pattern: String) : Check

    /** 回答条目必须都出现在最后几次读屏文字中。 */
    @Serializable @SerialName("answer_grounded")
    data class AnswerGrounded(val minItems: Int = 1) : Check

    /** [packageName] 可写成 `{app:<能力>}`(见 [APP_CAPABILITIES]),跑的时候换成这台手机上的 App。 */
    @Serializable @SerialName("screen_has")
    data class ScreenHas(val packageName: String, val text: String, val deepLink: String? = null) : Check

    /** probe 必须是 [PROBE_ALLOWLIST] 里的名字。 */
    @Serializable @SerialName("probe_equals")
    data class ProbeEquals(val probe: String, val expected: String, val contains: Boolean = false) : Check

    /** 这次任务开头带上了至少 [min] 条记忆(代码记录的 memory_used)。记忆题的第二题用。 */
    @Serializable @SerialName("memory_used")
    data class MemoryUsed(val min: Int = 1) : Check

    /**
     * 这次任务里 Agent 有没有向用户追问(ask_user):[expected] 为 true 时须问过,false 时须一次没问。
     * 「该问还是该自己定」的题用:目标里缺了只有用户知道的信息就该问;只是没说偏好就该按常理定下并说明。
     */
    @Serializable @SerialName("asked_user")
    data class AskedUser(val expected: Boolean = true) : Check
    /** 任务结束后在新的网页标签里打开 [url] 读整页,要能找到 [text](忽略空白)。 */
    @Serializable @SerialName("web_has")
    data class WebHas(val url: String, val text: String) : Check

    @Serializable @SerialName("confirmation_shows")
    data class ConfirmationShows(
        val action: String? = null,
        val target: String? = null,
        val content: String? = null,
        val amount: String? = null,
    ) : Check
}

/**
 * 一道评测题。goal、marker、核对项里的字符串可写 `{run}` 和 `{probe:<名字>}`,跑前由 [EvalTasks.substitute] 替换;
 * `probesBefore` 是「占位名 → 白名单探针名」,跑任务前先执行这些探针得到期望值。
 * `apps` 和 screen_has 的包名可写 `{app:<能力>}`(手机自带的某类 App,见 [APP_CAPABILITIES]),由 [EvalTasks.resolveApps] 换成这台手机上的包名,
 * 题目不钉某个品牌的包名。
 *
 * `requires` 的写法:`installed:<包名>`;`app:<能力>`(这台手机上有处理这类事的 App);
 * `signedIn:<包名或网站>`(用户登录过:网站看设置「网站登录」里的登记,App 看人工标记;旧写法 `login:<包名>`、`site:<网站>` 照样认)。
 */
@Serializable
data class EvalTask(
    val id: String,
    val goal: String,
    val apps: List<String>,
    val category: EvalCategory,
    val requires: List<String> = emptyList(),
    val expect: Expect,
    val checks: List<Check>,
    val maxSteps: Int? = null,
    val timeoutSec: Int? = null,
    val marker: String? = null,
    val answers: Map<String, String> = emptyMap(),
    val probesBefore: Map<String, String> = emptyMap(),
    /** 给评审看的说明(这道题为什么这样出),评测器不用。 */
    val note: String? = null,
    /** 配对题:train(学技能的一轮跑)或 test(对比的一轮跑);评测器按 `-e split` 选。 */
    val split: String? = null,
    /** 同一 session 的题在同一个对话里按 id 顺序跑(记忆题:先告诉、再用上)。 */
    val session: String? = null,
    /** 回放评测用的变体:换说法或换参数的同一件事;`evalVariants on` 时先跑原题记下路线,再跑这些。 */
    val variants: List<Variant> = emptyList(),
    /** 强制渠道:"web" 或 "app";非空时目标前加「用网页」/「用 App」(两条路线对比用)。 */
    val channel: String? = null,
    /**
     * 题目分池:tune(调提示词、知识时看的题)或 holdout(只在决定发版时看结果,不拿来调);评测器按 `-e evalSplit` 选。
     * 和 [split](配对题的 train/test 两轮)是两回事。
     */
    val partition: String? = null,
    /**
     * 撞上这些障碍(屏上看得出来,见 [RunFacts.takeoverBarrier])后交给用户接管的,单列成「交给用户」([Scoring.handedOver]),
     * 不算办成也不算别的失败,算不算由人定。为空时按类别取默认([Scoring.takeoverKinds]):读类题是 login,其余不认。
     */
    val acceptTakeoverOn: List<String>? = null,
)

/**
 * 「手机自带的某类 App」按安卓标准 Intent 找:跑题前用这些 Intent 查出这台手机上能处理的 App(候选),
 * 跑完后优先取 Agent 实际打开过的那个候选。没有标准 Intent 的(备忘录)只能跑完后取 Agent 打开的 App。
 */
data class AppIntent(val action: String, val category: String? = null)

private const val MAIN = "android.intent.action.MAIN"

val APP_CAPABILITIES: Map<String, List<AppIntent>> = mapOf(
    "alarm" to listOf(AppIntent("android.intent.action.SET_ALARM"), AppIntent("android.intent.action.SHOW_ALARMS")),
    "calendar" to listOf(AppIntent(MAIN, "android.intent.category.APP_CALENDAR")),
    "calculator" to listOf(AppIntent(MAIN, "android.intent.category.APP_CALCULATOR")),
    "contacts" to listOf(AppIntent(MAIN, "android.intent.category.APP_CONTACTS")),
    "files" to listOf(AppIntent(MAIN, "android.intent.category.APP_FILES"), AppIntent("android.intent.action.OPEN_DOCUMENT_TREE")),
    "weather" to listOf(AppIntent(MAIN, "android.intent.category.APP_WEATHER")),
    "notes" to emptyList(),
)

object AppCapabilities {
    private val COMPONENT = Regex("^\\s*([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/\\S+")
    /** 系统的「选择用哪个 App」界面不是候选。 */
    private const val SYSTEM_PACKAGE = "android"

    /** `cmd package resolve-activity --brief` / `query-activities --brief` 输出里的包名,按出现顺序去重。 */
    fun packagesIn(output: String): List<String> =
        output.lines().mapNotNull { COMPONENT.find(it)?.groupValues?.get(1) }.filter { it != SYSTEM_PACKAGE }.distinct()

    /** 查某个 Intent 的 shell 命令(参数来自 [APP_CAPABILITIES],任务文件写不进去)。 */
    fun commands(i: AppIntent): List<String> {
        val args = "-a ${i.action}" + (i.category?.let { " -c $it" } ?: "")
        return listOf("cmd package resolve-activity --brief $args", "cmd package query-activities --brief $args")
    }

    /**
     * 跑完后 `{app:<能力>}` 取哪个包:Agent 打开过的候选优先(手机上可能有好几个日历),其次第一个候选。
     * 没有候选(没有标准 Intent 或这台手机上查不到)时,只认 Agent 打字时在前台的 App([written],见 [RunFacts.appsWrittenIn]),
     * 而且只有一个;说不准是哪个就不定(核对按没找到算),不拿「第一个打开的 App」凑数。[own] 是 ZorvAI 自己的包名。
     */
    fun choose(candidates: List<String>, launched: List<String>, own: String? = null, written: List<String> = emptyList()): String? {
        val used = launched.filter { it != own && it != SYSTEM_PACKAGE }
        candidates.firstOrNull { it in used }?.let { return it }
        candidates.firstOrNull()?.let { return it }
        return written.filter { it != own && it != SYSTEM_PACKAGE }.distinct().singleOrNull()
    }
}

/** [EvalTask.channel] 的取值与加在目标前的说法。 */
val CHANNEL_PREFIX = mapOf("web" to "用网页", "app" to "用 App")

/**
 * 原题的一个变体。[checks] 为空沿用原题的核对项。换参数时参数要在 [goal] 里逐字写出(回放只认原话里出现的槽值)。
 * 其余字段(App、类别、步数、时限、标记等)都沿用原题。
 */
@Serializable
data class Variant(val goal: String, val checks: List<Check>? = null)

/** 只读设备探针:名字 → shell 命令。评测器只执行这里列出的命令,任务文件不能写任意命令。 */
val PROBE_ALLOWLIST: Map<String, String> = mapOf(
    "android_release" to "getprop ro.build.version.release",
    "android_sdk" to "getprop ro.build.version.sdk",
    "device_model" to "getprop ro.product.model",
    "device_manufacturer" to "getprop ro.product.manufacturer",
    "timezone" to "getprop persist.sys.timezone",
    "date_today" to "date +%Y-%m-%d",
    "time_12_24" to "settings get system time_12_24",
    "font_scale" to "settings get system font_scale",
    "airplane_mode_on" to "settings get global airplane_mode_on",
    "wifi_on" to "settings get global wifi_on",
    "bluetooth_on" to "settings get global bluetooth_on",
    "auto_time" to "settings get global auto_time",
    "default_browser" to "cmd package resolve-activity --brief -a android.intent.action.VIEW -d https://example.com",
    "default_home" to "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
    /** 内部存储根目录里的文件和文件夹(建文件夹类题核对用,不依赖哪个文件管理 App)。 */
    "storage_root" to "ls -1 /sdcard/",
)

object EvalTasks {
    private val json = Json { explicitNulls = false }
    private val ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val SPLITS = setOf("train", "test")
    /** [EvalTask.partition] 的取值。 */
    val PARTITIONS = setOf("tune", "holdout")
    private val PROBE_REF = Regex("""\{probe:([^}]*)\}""")
    private val APP_REF = Regex("""\{app:([^}]*)\}""")
    private val REQUIREMENT_KINDS = setOf("installed", "login", "site", "signedIn", "app")

    /** 题目里用到的 `{app:<能力>}`。 */
    fun appCapabilities(t: EvalTask): Set<String> =
        APP_REF.findAll(json.encodeToString(EvalTask.serializer(), t)).map { it.groupValues[1] }.toSet()

    /** 把 `{app:<能力>}` 换成 [apps] 里的包名(能力 → 包名);[apps] 里没有的能力原样留着,`apps` 列表里没换上的去掉。 */
    fun resolveApps(t: EvalTask, apps: Map<String, String>): EvalTask {
        fun s(x: String) = APP_REF.replace(x) { m -> apps[m.groupValues[1]] ?: m.value }
        return t.copy(
            apps = t.apps.map(::s).filterNot { APP_REF.containsMatchIn(it) }.distinct(),
            checks = t.checks.map { c -> if (c is Check.ScreenHas) c.copy(packageName = s(c.packageName)) else c },
        )
    }

    /** 目标前加渠道说法([CHANNEL_PREFIX]);目标里已经这么说了(不计空白)就不再加。 */
    fun withChannel(goal: String, channel: String?): String {
        val prefix = channel?.let { CHANNEL_PREFIX[it] } ?: return goal
        fun squash(x: String) = x.filterNot(Char::isWhitespace)
        return if (squash(prefix) in squash(goal)) goal else prefix + goal
    }

    fun parse(json: String): EvalTask = this.json.decodeFromString(EvalTask.serializer(), json)

    /** 原题的各个变体,编号为 `<原题>~v<n>`(从 1 起);没有变体返回空。 */
    fun variantsOf(t: EvalTask): List<EvalTask> = t.variants.mapIndexed { i, v ->
        t.copy(id = "${t.id}~v${i + 1}", goal = v.goal, checks = v.checks ?: t.checks, variants = emptyList())
    }

    /** 单题合法性(含各变体);空列表 = 合法。 */
    fun validate(t: EvalTask): List<String> {
        val errs = mutableListOf<String>()
        // 变体按原题编号查,错误前面标上是第几个变体
        variantsOf(t).forEachIndexed { i, v -> validate(v.copy(id = t.id)).forEach { errs += "${t.id}: variant[${i + 1}]: $it" } }
        if (!ID.matches(t.id)) errs += "id「${t.id}」须为小写字母数字加连字符"
        if (t.goal.isBlank()) errs += "${t.id}: goal 为空"
        t.channel?.let { if (it !in CHANNEL_PREFIX) errs += "${t.id}: channel 只能是 ${CHANNEL_PREFIX.keys.joinToString("/")}" }
        t.maxSteps?.let { if (it <= 0) errs += "${t.id}: maxSteps 须为正数" }
        t.split?.let { if (it !in SPLITS) errs += "${t.id}: split 须为 train 或 test" }
        t.partition?.let { if (it !in PARTITIONS) errs += "${t.id}: partition 须为 tune 或 holdout" }
        t.session?.let { if (!ID.matches(it)) errs += "${t.id}: session「$it」须为小写字母数字加连字符" }
        t.timeoutSec?.let { if (it <= 0) errs += "${t.id}: timeoutSec 须为正数" }
        t.requires.forEach { r ->
            val kind = r.substringBefore(':')
            if (kind !in REQUIREMENT_KINDS || r.substringAfter(':', "").isBlank()) errs += "${t.id}: 不认识的前置条件「$r」"
            if (kind == "app" && r.substringAfter(':') !in APP_CAPABILITIES) errs += "${t.id}: 前置条件「$r」不是已知的 App 能力"
        }
        appCapabilities(t).forEach { if (it !in APP_CAPABILITIES) errs += "${t.id}: {app:$it} 不是已知的 App 能力" }
        t.probesBefore.forEach { (name, probe) ->
            if (probe !in PROBE_ALLOWLIST) errs += "${t.id}: probesBefore.$name 用了白名单外的探针「$probe」"
        }
        if (t.checks.isEmpty() && (t.expect == Expect.Succeeded || t.expect == Expect.Confirmation)) {
            errs += "${t.id}: expect=${t.expect} 至少要有一条核对项"
        }
        t.checks.forEachIndexed { i, c ->
            when (c) {
                is Check.ProbeEquals -> {
                    if (c.probe !in PROBE_ALLOWLIST) errs += "${t.id}: checks[$i] 用了白名单外的探针「${c.probe}」"
                    if (c.expected.isBlank()) errs += "${t.id}: checks[$i] expected 为空"
                }
                is Check.AnswerGrounded -> if (c.minItems < 1) errs += "${t.id}: checks[$i] minItems 须 ≥ 1"
                is Check.AnswerRegex ->
                    if (c.pattern.isEmpty()) errs += "${t.id}: checks[$i] pattern 为空"
                    else runCatching { Regex(c.pattern) }
                        .onFailure { errs += "${t.id}: checks[$i] 正则无效:${it.message}" }
                        // 能匹配空串的正则对任何回答都成立
                        .onSuccess { if (it.containsMatchIn("")) errs += "${t.id}: checks[$i] 正则能匹配空串,核对不了任何东西" }
                is Check.AnswerContains ->
                    if (c.all.isEmpty() || c.all.any { it.isBlank() }) errs += "${t.id}: checks[$i] all 为空或含空串"
                is Check.ConfirmationShows -> {
                    val fields = listOf(c.action, c.target, c.content, c.amount)
                    if (fields.all { it == null }) errs += "${t.id}: checks[$i] 没写任何要核对的字段"
                    if (fields.any { it != null && it.isBlank() }) errs += "${t.id}: checks[$i] 有字段为空白"
                }
                is Check.ScreenHas -> if (c.text.isBlank()) errs += "${t.id}: checks[$i] text 为空"
                is Check.MemoryUsed -> if (c.min < 1) errs += "${t.id}: checks[$i] min 须 ≥ 1"
                is Check.AskedUser -> Unit
                is Check.WebHas -> {
                    val u = c.url.trim().lowercase()
                    if (!u.startsWith("https://") && !u.startsWith("http://")) errs += "${t.id}: checks[$i] url 须是 http(s) 网址"
                    if (c.text.isBlank()) errs += "${t.id}: checks[$i] text 为空"
                }
            }
        }
        val referenced = PROBE_REF.findAll(json.encodeToString(EvalTask.serializer(), t)).map { it.groupValues[1] }.toSet()
        (referenced - t.probesBefore.keys).forEach { errs += "${t.id}: 占位符 {probe:$it} 没在 probesBefore 里声明" }
        return errs
    }

    /** 整个任务集:逐题校验,再查 id 重复。 */
    fun validateAll(ts: List<EvalTask>): List<String> =
        ts.flatMap { validate(it) } +
            ts.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.map { "id「$it」重复" }

    /** 替换 `{run}` 和 `{probe:<名字>}`;probes 是 probesBefore 的执行结果(占位名 → 输出)。 */
    fun substitute(t: EvalTask, run: String, probes: Map<String, String>): EvalTask {
        fun s(x: String): String = PROBE_REF.replace(x.replace("{run}", run)) { m ->
            probes[m.groupValues[1]]?.trim() ?: m.value
        }
        // 正则里插入的探针值按字面量处理
        fun re(x: String): String = PROBE_REF.replace(x.replace("{run}", Regex.escape(run))) { m ->
            probes[m.groupValues[1]]?.let { Regex.escape(it.trim()) } ?: Regex.escapeReplacement(m.value)
        }
        val checks = t.checks.map { c ->
            when (c) {
                is Check.AnswerContains -> c.copy(all = c.all.map(::s))
                is Check.AnswerRegex -> c.copy(pattern = re(c.pattern))
                is Check.AnswerGrounded -> c
                is Check.MemoryUsed -> c
                is Check.AskedUser -> c
                is Check.ScreenHas -> c.copy(text = s(c.text), deepLink = c.deepLink?.let(::s))
                is Check.WebHas -> c.copy(url = s(c.url), text = s(c.text))
                is Check.ProbeEquals -> c.copy(expected = s(c.expected))
                is Check.ConfirmationShows -> Check.ConfirmationShows(
                    c.action?.let(::s), c.target?.let(::s), c.content?.let(::s), c.amount?.let(::s),
                )
            }
        }
        return t.copy(
            goal = withChannel(s(t.goal), t.channel),
            marker = t.marker?.let(::s),
            checks = checks,
            answers = t.answers.mapValues { s(it.value) },
        )
    }
}

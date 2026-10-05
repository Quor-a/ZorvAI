package com.ai.assistance.quro.core.cards

import org.json.JSONObject

/**
 * 卡片交互的**结构化动作协议**。
 *
 * ## 为什么要做成协议
 *
 * 以前卡片交互是一串裸 command 字符串：渲染层拼 `"ai:${it.text}"`、
 * 宿主 `cmd.startsWith("run:")` 拆字符串，两边各写一套方言 ——
 * 换一个动词要同时改渲染层、宿主分支、工具下发文档七八个地方，
 * 还容易出现「拼出来的串宿主根本不认」的死交互（比如 `${card.id}:toggle:$i`
 * 在 [com.ai.assistance.quro.ui.ChatScreen.handleCardCommand] 里没有任何分支吃它）。
 *
 * 本文件把交互收敛成一条窄协议：
 *
 * ```
 *   CardAction(actionId = "ui:open_terminal", kind = UI, args = {target: "ui_open_terminal"})
 *
 *   CardAction.parse("ui_open_terminal")  →  上面那个；
 *   action.toCommand()                     →   "ui_open_terminal"（回落到裸串，老通道照旧能用）
 * ```
 *
 * ## 三条派发路径
 *
 * 1. [CardActionBus] —— 进程内按 `actionId` **定向联动**：一张卡发 `emit:<id>`，
 *    订阅同一 id 的其他卡 / 页面局部更新自己（A2UI 风格的局部更新），
 *    不经过 AI、不经过消息流，成本高可控。
 * 2. [CardActionRouter.dispatch] —— 先查总线，未命中再交回宿主
 *    （`ChatScreen.handleCardCommand`），宿主**一行不用改也能跑**。
 * 3. 裸 command 兜底 —— [CardAction.toCommand] 原样还原历史字符串，
 *    于是协议是「加在前面」的：现有 190 处 `onCommand("ai:...")` 一个都不用改。
 *
 * ## JSON 信封
 *
 * AI 也可以直接下发结构化动作（`keyvalue` 行的 `command` 字段写 JSON）：
 *
 * ```json
 * {"actionId":"poll:c1","kind":"emit","args":{"option":"2","target":"ui_open_terminal"}}
 * ```
 *
 * [CardAction.parse] 认这个信封（以 `{` 开头即走 JSON 分支），[CardAction.toJson]
 * 能把它写回去。
 */
object CardAction {

    /** 动作族。加新族时 [parse] 与 [toCommand] 要一起改 —— 那里是 exhaustive `when`。 */
    enum class Kind {
        /** 把一段文本回发给 AI（`reply:`） */
        REPLY,
        /** 等价于 REPLY，历史别名（`ai:`） */
        AI,
        /** 界面动作：打开界面 / 开关设置 / 弹层（`ui_open_*`、`ui_toggle_*`） */
        UI,
        /** 跑命令：打开终端并把命令喂进去（`run:`） */
        RUN,
        /** 外链 / geo / 文件（`open:`） */
        OPEN,
        /** 写剪贴板（`copy:`） */
        COPY,
        /** 走 UI 桥的屏幕动作（`screen:`） */
        SCREEN,
        /** 端侧环境安装（`linux:install`） */
        INSTALL,
        /** 进程内定向联动（本文件 [CardActionBus] 消费） */
        EMIT,
        /** 吐一个轻提示（`toast:`） */
        TOAST,
        /** 页面内导航（`navigate:`） */
        NAVIGATE,
        /** 认不出来的串：原样保留，别把动作吃掉 */
        UNKNOWN,
        ;

        companion object {
            /** 大小写不敏感；认不出来的当 UNKNOWN（按族处理总比崩掉强）。 */
            fun of(v: String?): Kind =
                values().firstOrNull { it.name.equals(v?.trim(), ignoreCase = true) } ?: UNKNOWN
        }
    }

    /** 一个动作实例。`args` 全部是 String —— 卡片 JSON 里不长这个结构，别引入第二套类型。 */
    data class Action(
        val actionId: String,
        val kind: Kind,
        val args: Map<String, String> = emptyMap(),
        /** 解析自裸串 / JSON 时记住原文，[toCommand] 才能原样还原（往返零漂移）。 */
        internal val raw: String? = null,
    ) {
        fun arg(k: String): String = args[k].orEmpty()
        fun has(k: String): Boolean = args.containsKey(k)

        /** 主参数：按族取最常用的那个键，省得每个调用点都写 `args["text"]`。 */
        fun payload(): String = when (kind) {
            Kind.REPLY, Kind.AI, Kind.TOAST, Kind.COPY -> arg("text")
            Kind.UI, Kind.NAVIGATE, Kind.SCREEN -> arg("target")
            Kind.RUN -> arg("cmd")
            Kind.OPEN -> arg("url")
            Kind.EMIT -> arg("data")
            Kind.INSTALL -> ""
            else -> arg("data").ifBlank { actionId }
        }

        fun withArg(k: String, v: String): Action = copy(args = HashMap(args).apply { put(k, v) })

        /** 归一成宿主现有 `handleCardCommand` 认的裸串；认不出来就原样吐回去。 */
        fun toCommand(): String {
            val rawHere = raw
            if (!rawHere.isNullOrBlank()) return rawHere
            val p = payload()
            return when (kind) {
                Kind.REPLY, Kind.AI -> "${kindOfWire()}:$p"
                Kind.UI -> if (p.startsWith("ui_")) p else "ui_$p"
                Kind.RUN -> "run:$p"
                Kind.OPEN -> "open:$p"
                Kind.COPY -> "copy:$p"
                Kind.SCREEN -> "screen:$p"
                Kind.TOAST -> "toast:$p"
                Kind.NAVIGATE -> "navigate:$p"
                Kind.INSTALL -> "linux:install"
                Kind.EMIT -> if (arg("data").isBlank()) actionId else "$actionId:${arg("data")}"
                Kind.UNKNOWN -> actionId
            }
        }

        /** 历史 wire 前缀：`AI` 这一族历史上写 `ai:`，不能跟着枚举名变成 `ai` 以外的东西。 */
        private fun kindOfWire(): String = when (kind) {
            Kind.AI -> "ai"
            else -> kind.name.lowercase()
        }

        /** 日志 / 诊断用的单行描述。 */
        fun describe(): String = "$kind:$actionId"

        fun toJson(): JSONObject = JSONObject().apply {
            put("actionId", actionId)
            put("kind", kind.name.lowercase())
            args.forEach { (k, v) -> put(k, v) }
        }

        companion object {
            /** 从解析出本对象的裸串重建，用于「解析后又不小心丢了 raw」的场合。 */
            fun restore(json: JSONObject): Action =
                Action(
                    actionId = json.optString("actionId").ifBlank { json.optString("id") },
                    kind = Kind.of(json.optString("kind")),
                    args = json.keys().let { ks ->
                        val m = LinkedHashMap<String, String>()
                        while (ks.hasNext()) {
                            val k = ks.next()
                            if (k != "actionId" && k != "kind" && k != "id") m[k] = json.optString(k)
                        }
                        m
                    },
                    raw = null,
                )
        }
    }

    /** 一次动作派发的完整上下文：谁发的（卡片 id）、重放用的 nonce。 */
    data class Event(
        val action: Action,
        /** 发起来源的卡片 id；用于「只更新发起来的那张卡」定向局部更新。 */
        val cardId: String? = null,
        val nonce: Long = 0L,
    )

    // ───────────── 构造糖 ─────────────

    fun reply(text: String) = Action("reply", Kind.REPLY, mapOf("text" to text))
    fun ai(text: String) = Action("ai", Kind.AI, mapOf("text" to text))
    fun ui(target: String) = Action("ui:$target", Kind.UI, mapOf("target" to target))
    fun run(cmd: String) = Action("run", Kind.RUN, mapOf("cmd" to cmd))
    fun open(url: String) = Action("open", Kind.OPEN, mapOf("url" to url))
    fun copy(text: String) = Action("copy", Kind.COPY, mapOf("text" to text))
    fun screen(target: String) = Action("screen:$target", Kind.SCREEN, mapOf("target" to target))
    fun install() = Action("linux:install", Kind.INSTALL)
    fun toast(text: String) = Action("toast", Kind.TOAST, mapOf("text" to text))
    fun navigate(target: String) = Action("navigate:$target", Kind.NAVIGATE, mapOf("target" to target))

    /**
     * 进程内定向联动动作。`event` 直接当 actionId 用（同 id 的订阅者一起被叫起来）。
     * `data` 是给订阅者的载荷，可以是任意串（JSON、下标、选项 id……）。
     */
    fun emit(event: String, data: String = ""): Action =
        Action(event, Kind.EMIT, if (data.isBlank()) emptyMap() else mapOf("data" to data))

    // ───────────── 解析：裸串 / JSON 信封 → Action ─────────────

    /**
     * 信封里的参数：顶层平铺的 key 全收，嵌套的 `args` / `data` 对象要**展开**而不是
     * 存成一段 JSON 文本 —— 否则 `a.arg("option")` 永远取不到东西。
     */
    private fun flatten(o: JSONObject): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val ks = o.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            if (k in JSON_META) continue
            if ((k == "args" || k == "data") && o.opt(k) is JSONObject) {
                (o.opt(k) as JSONObject).keys().let { ak ->
                    while (ak.hasNext()) {
                        val ak2 = ak.next()
                        m[ak2] = o.getJSONObject(k).opt(ak2).let { av ->
                            if (av is String) av else av.toString()
                        }
                    }
                }
                continue
            }
            m[k] = o.get(k).let { v -> if (v is String) v else v.toString() }
        }
        return m
    }

    /**
     * 信封里这些 key 是**身份/类别**元信息，不当参数收。
     * 🔴 `args` / `data` 不在这里 —— 它们是嵌套对象，[flatten] 会把里面的键值摊平；
     * 一旦被这里的 `continue` 跳掉，`a.arg("target")` 永远取不到东西。
     */
    private val JSON_META = setOf("actionId", "id", "action", "name", "kind")

    /**
     * 唯一解析入口。认不出族的串归 [Kind.UNKNOWN] 但**保留原文**，
     * 绝不能返回 null —— 宿主那边 `startsWith` 链靠原文兜底。
     */
    fun parse(rawIn: String?): Action {
        val raw = rawIn ?: return Action("", Kind.UNKNOWN, emptyMap(), "")
        val s = raw.trim()
        if (s.isEmpty()) return Action("", Kind.UNKNOWN, emptyMap(), s)
        // 🔴 信封不记原文：toCommand() 要吐归一后的**裸串**给老宿主，
        //    原样吐回一整段 JSON 的话，老 `startsWith("reply:")` 那条链路照样接不住。
        if (s.startsWith("{")) return try {
            fromJson(s)
        } catch (t: Throwable) {
            // AI 手写的 JSON 经常少个引号；这时候别让整条动作链路 500
            Action(s, Kind.UNKNOWN, mapOf("raw" to s), raw)
        }

        val c0 = s.indexOf(':')
        val head: String
        val tail: String
        if (c0 > 0 && c0 < s.length - 1) {
            head = s.substring(0, c0)
            tail = s.substring(c0 + 1)
        } else {
            head = s
            tail = ""
        }

        return when (head.lowercase()) {
            "reply" -> Action("reply", Kind.REPLY, mapOf("text" to tail), raw)
            "ai" -> Action("ai", Kind.AI, mapOf("text" to tail), raw)
            "ui" -> Action("ui:$tail", Kind.UI, mapOf("target" to normalizeUiTarget(tail)), raw)
            "run" -> Action("run", Kind.RUN, mapOf("cmd" to tail), raw)
            "open" -> Action("open", Kind.OPEN, mapOf("url" to tail), raw)
            "copy" -> Action("copy", Kind.COPY, mapOf("text" to tail), raw)
            "screen" -> Action("screen:$tail", Kind.SCREEN, mapOf("target" to tail), raw)
            "install", "linux" -> Action("linux:install", Kind.INSTALL, emptyMap(), raw)
            "toast" -> Action("toast", Kind.TOAST, mapOf("text" to tail), raw)
            "nav", "navigate" -> Action("navigate:$tail", Kind.NAVIGATE, mapOf("target" to tail), raw)
            "emit" -> emitFrom(tail, raw)
            // 没有冒号的裸 `ui_xxx` / `uiToggle` 之类，历史上也全是界面动作
            else -> when {
                head.equals("linux", ignoreCase = true) -> Action("linux:install", Kind.INSTALL, emptyMap(), raw)
                head.startsWith("ui_", ignoreCase = true) -> Action(head, Kind.UI, mapOf("target" to head), raw)
                head.equals("emit", ignoreCase = true) -> emitFrom(tail, raw)
                head.equals("install", ignoreCase = true) -> Action("linux:install", Kind.INSTALL, emptyMap(), raw)
                else -> Action(s, Kind.UNKNOWN, mapOf("raw" to s), raw)
            }
        }
    }

    /**
     * `emit:<event>[:<data>]`：event 是订阅 id，data 是给订阅者的载荷。
     * 🔴 必须切分 —— 否则 `emit:poll:2` 会把 actionId 拼成 `poll:2`，
     * 渲染侧 `CardActionBus.subscribe("poll")` 永远等不到，联动静默失效。
     */
    private fun emitFrom(tail: String, raw: String): Action {
        val c = tail.indexOf(':')
        if (c <= 0) return Action(tail, Kind.EMIT, emptyMap(), raw)
        return Action(tail.substring(0, c), Kind.EMIT, mapOf("data" to tail.substring(c + 1)), raw)
    }

    /**
     * `ui:` 与 `ui_` 两种写法要归到**同一个** target，否则 `toCommand()`
     * 拼出来的串宿主不认（`handleCardCommand` 只吃 `startsWith("ui_")`）。
     */
    private fun normalizeUiTarget(t: String): String {
        val v = t.trim()
        if (v.isEmpty()) return v
        if (v.startsWith("ui_", ignoreCase = true)) return v
        if (v.startsWith("ui.") || v.startsWith("ui/")) return "ui_" + v.substring(2)
        return "ui_$v"
    }

    /** AI 下发的结构化信封：`{"actionId":..,"kind":..,"args":{..}}`（args 也可以平铺）。 */
    fun fromJson(s: String): Action {
        val o = org.json.JSONObject(s)
        val id = o.optString("actionId").ifBlank { o.optString("id").ifBlank { o.optString("action") }
            .ifBlank { o.optString("name") } }
        val kind = Kind.of(o.optString("kind"))
        val m = flatten(o)
        val base = Action(id, kind, m)
        // 信封是标准形态时按族归一，保证 toCommand() 仍能被老宿主吃下
        return when (kind) {
            Kind.UI -> if (m.containsKey("target")) base else base.withArg("target", normalizeUiTarget(id))
            Kind.RUN -> if (m.containsKey("cmd")) base else base.withArg("cmd", m["data"].orEmpty())
            Kind.OPEN -> if (m.containsKey("url")) base else base.withArg("url", m["text"].orEmpty())
            Kind.REPLY, Kind.AI, Kind.TOAST -> if (m.containsKey("text")) base else base.withArg("text", m["data"].orEmpty())
            else -> base
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  进程内定向联动总线 + 统一派发路由
// ══════════════════════════════════════════════════════════════════

/**
 * 按 `actionId` 订阅 / 派发的**进程内**事件总线。
 *
 * 解决的是历史上一件做不到的事：**一张卡自己发个动作，同屏其他卡跟着局部更新**。
 * 过去只能把状态塞进 AI 会话里绕一圈（发一句 `ai:` → 模型回一段 JSON → 再解析），
 * 一问一答几百毫秒，纯属浪费。现在渲染侧 `DisposableEffect` 里注册订阅者，
 * 卡片点一下就同帧更新：
 *
 * ```
 *   DisposableEffect(card.id) {
 *       val off = CardActionBus.subscribe(card.id) { a -> vote.value = a.arg("option"); true }
 *       onDispose { off() }
 *   }
 * ```
 *
 * 没有任何订阅者时 [dispatch] 返回 `false`，调用方就把动作交回宿主
 * （[CardActionRouter] 已经这么做了），所以**注册是可选项**：协议能渐进落地。
 */
object CardActionBus {

    private val lock = Any()
    private val subs = LinkedHashMap<String, (CardAction.Action) -> Boolean>()

    /** 订阅 `id`；返回值是退订函数，直接塞 `onDispose` 就行。 */
    fun subscribe(id: String, handler: (CardAction.Action) -> Boolean): () -> Unit {
        synchronized(lock) { subs[id] = handler }
        return { unsubscribe(id, handler) }
    }

    fun unsubscribe(id: String, handler: (CardAction.Action) -> Boolean = { false }) {
        synchronized(lock) {
            val cur = subs[id]
            if (cur === null || cur === handler) subs.remove(id) else subs[id] = cur
        }
    }

    fun has(id: String): Boolean = synchronized(lock) { subs.containsKey(id) }

    fun size(): Int = synchronized(lock) { subs.size }

    fun ids(): List<String> = synchronized(lock) { subs.keys.toList() }

    /**
     * 派发给同 id 的订阅者；命中一个就停（先注册者优先? 不：后注册者优先，
     * 因为渲染侧重入时 `subscribe` 会覆盖，最新一帧的视图优先级最高）。
     * 没订阅者 / 订阅者抛异常都返回 false，交给宿主兜底。
     */
    fun dispatch(action: CardAction.Action): Boolean {
        val h = synchronized(lock) { subs[action.actionId] } ?: return false
        return try {
            h(action)
        } catch (t: Throwable) {
            false
        }
    }

    fun clear() = synchronized(lock) { subs.clear() }
}

/**
 * 派发总口子：**先本地联动，再宿主**。
 *
 * 宿主是 `ChatScreen` 里的 `handleCardCommand`（收裸串）。传进去的是
 * `(CardAction.Action) -> Boolean`（返回 false 表示「我处理不了」），
 * 于是这层桥接不依赖任何 Android 类型，单测里也能摆一个假宿主验证。
 */
object CardActionRouter {

    /** 裸 command / JSON 信封 → 结构化动作。 */
    fun parse(raw: String?): CardAction.Action = CardAction.parse(raw)

    /**
     * 派发顺序：
     * 1. [EMIT] 先查 [CardActionBus]（纯进程内联动，不该打到 AI / 界面层）；
     * 2. 其余族交给宿主；宿主返回 false（不认识）时退回 [CardAction.Action.toCommand] 的裸串，
     *    让 `startsWith` 那条老链路再兜一次 —— **双保险，等价于加协议前的行为**。
     */
    fun dispatch(action: CardAction.Action, host: (CardAction.Action) -> Boolean): Boolean {
        if (action.kind == CardAction.Kind.EMIT && CardActionBus.dispatch(action)) return true
        if (host(action)) return true
        return host(CardAction.parse(action.toCommand()))
    }

    /** 只问「本地有没有人接」，用于诊断 / 避免无谓打日志。 */
    fun locallyHandled(action: CardAction.Action): Boolean =
        action.kind == CardAction.Kind.EMIT && CardActionBus.has(action.actionId)
}

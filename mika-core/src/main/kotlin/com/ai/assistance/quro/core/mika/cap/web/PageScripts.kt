package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.script.JsSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 页面脚本(web_script)里 `quro.*` 的调用交给 ZorvAI 的入口。端口只管传话:
 * 每个调用原样交给 [call],返回值(`{ok, value}` 或 `{ok:false, error, message}`)原样交回脚本。
 * 读页面的调用(query、queryAll、text)返回 `{ok:true, read:{…}}`:参数已校验、已计数,由端口在读取世界里照它读。
 */
interface ScriptBridge {
    /** 抛 [ScriptAbort] 结束整个脚本(超上限、要确认、被守卫叫停、页面交给了用户)。 */
    suspend fun call(op: String, args: JsonObject): JsonObject
    /** `quro.log`:不等回复。 */
    fun log(text: String)
}

class ScriptAbort(val error: String, message: String) : Exception(message)

sealed interface ScriptRun {
    /** 跑完了;[valueJson] 是返回值的 JSON(没有返回值为 null)。 */
    data class Done(val valueJson: String?) : ScriptRun
    /** 整页跳走,脚本停在这里。 */
    data object Navigated : ScriptRun
    /** [error]:script_error | timeout | devtools_unavailable | no_tab_open,或 [ScriptAbort] 的错误码。[line] 是脚本里的行号(从 1 起)。 */
    data class Failed(val error: String, val message: String, val line: Int? = null) : ScriptRun
}

/** 页面脚本里一次 `quro.*` 调用。[id] 为 0 表示不等回复(log)。 */
data class ScriptCall(val id: Int, val op: String, val args: JsonObject)

/**
 * 页面脚本的做法:模型代码拿不到任何页面对象(按构造,不靠禁用清单)。
 * - 脚本世界:一个新的隔离世界,前置脚本只做两件事——用事先存下的 AsyncFunction 编译模型代码,把这个世界全局对象上
 *   (含原型链)所有名字都当成参数遮住、传 undefined,只放行 [ALLOWED_GLOBALS](纯 ECMAScript 内置,碰不到 DOM);
 *   再把能把字符串当代码跑、能拿到全局对象的路(Function 构造器家族、eval、调用栈 API)封掉。模型代码能用的只有 `quro`,
 *   `quro` 的每个调用都是发给 ZorvAI 的 JSON 消息,回来的也只是 JSON。
 * - 读取世界:另一个隔离世界,只装固定的 [READER](ZorvAI 自己写的),按校验过的参数读 DOM、返回纯数据(密码一类的值打码);
 *   模型代码和它不在一个世界,拿不到它手里的任何对象。
 */
object PageScriptProtocol {
    const val SOURCE_URL = "quro-script.js"
    const val PRELUDE_URL = "quro-prelude.js"
    /** 单次 quro.wait 的上限(毫秒)。 */
    const val MAX_WAIT_MS = 10_000
    const val WORLD_PREFIX = "quro-script-"
    const val READER_WORLD_PREFIX = "quro-reader-"
    /** 脚本读到密码一类输入框的值时得到的东西(和运行记录的打码一致)。 */
    const val SECRET_MASK = "***"

    /**
     * 不读值的输入框:和页面读屏脚本(page.js)同一条规则——密码框、隐藏字段,
     * 以及 autocomplete 标着密码、银行卡、一次性验证码的。
     */
    val SECRET_AUTOCOMPLETE = listOf("current-password", "new-password", "cc-number", "cc-csc", "cc-exp", "cc-exp-month", "cc-exp-year", "one-time-code")
    val SECRET_INPUT_TYPES = listOf("password", "hidden")

    /** 模型代码里能用的全局名:只有 ECMAScript 本身的值和函数,没有一个能通到 DOM 或全局对象。 */
    val ALLOWED_GLOBALS = listOf(
        "undefined", "NaN", "Infinity", "Object", "Array", "Number", "Boolean", "String", "Symbol", "BigInt", "Date", "RegExp", "Promise",
        "Map", "Set", "WeakMap", "WeakSet", "WeakRef", "JSON", "Math", "Intl", "Reflect", "Proxy",
        "Error", "AggregateError", "EvalError", "RangeError", "ReferenceError", "SyntaxError", "TypeError", "URIError",
        "ArrayBuffer", "DataView", "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array", "Uint32Array",
        "Float32Array", "Float64Array", "BigInt64Array", "BigUint64Array",
        "parseInt", "parseFloat", "isNaN", "isFinite", "encodeURI", "encodeURIComponent", "decodeURI", "decodeURIComponent", "Iterator",
    )

    /** 不能当参数名的(严格模式的保留字);eval 另外封掉。 */
    internal val RESERVED = listOf(
        "eval", "arguments", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "enum",
        "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "null", "return", "super",
        "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while", "with", "yield", "let", "static", "implements",
        "interface", "package", "private", "protected", "public", "await", "async", "quro",
    )

    /** 模型代码的第 1 行前面,编译后的源码还有几行(`(async function anonymous(…`、`) {`、`'use strict';`、`return (async () => {`)。 */
    private const val LINE_OFFSET = 4

    private val json = Json { ignoreUnknownKeys = true }

    private fun js(v: List<String>): String = v.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }

    /**
     * 装进脚本世界的前置脚本:存下要用的原件,封掉把字符串当代码跑的路,算出要遮住的名字,定义 `__quroRun`。
     * 每个 quro 调用带编号放进发件箱,Kotlin 一直挂着一个 [TAKE](等到下一条就交回),结果用 `__quroSettle(id, r)` 回传;
     * `__quroEnd()` 让之后的调用都失败,挂着的 [TAKE] 交回 null。不用 DevTools 的绑定:绑定要打开 Runtime,开着它页面察觉得到调试器。
     */
    val PRELUDE: String = """
(() => {
  'use strict';
  const G = globalThis, O = Object;
  if (O.prototype.hasOwnProperty.call(G, '__quroRun')) return;
  const AsyncFunction = O.getPrototypeOf(async function () {}).constructor;
  const stringify = JSON.stringify, apply = Reflect.apply, timer = G.setTimeout, freeze = O.freeze;
  const fixed = (o, k, v) => { try { O.defineProperty(o, k, { value: v, writable: false, enumerable: false, configurable: false }); } catch (e) {} };
  const unavailable = function () { throw new TypeError('not available in web_script: the script has no access to the page; use quro.*'); };
  // 把字符串当代码跑(会在全局作用域里跑,拿得到全局对象)、调用栈 API(拿得到函数和 this)
  for (const f of [function () {}, async function () {}, function* () {}, async function* () {}]) fixed(O.getPrototypeOf(f), 'constructor', unavailable);
  fixed(G, 'eval', unavailable);
  fixed(G, 'Function', unavailable);
  fixed(G.Error, 'prepareStackTrace', undefined);

  const pending = new Map();
  let seq = 0, ended = false;
  // 发件箱:Kotlin 挂着的 __quroTake 等在 takers 里,来一条交一条
  const outbox = [], takers = [];
  const post = (s) => { const t = takers.shift(); if (t) t(s); else outbox.push(s); };
  fixed(G, '__quroTake', () => new Promise((r) => {
    if (outbox.length) r(outbox.shift()); else if (ended) r(null); else takers.push(r);
  }));
  const call = (op, args) => new Promise((resolve, reject) => {
    if (ended) { reject(new Error('script_ended')); return; }
    const id = ++seq;
    pending.set(id, { resolve, reject });
    post(stringify({ id, op, args: args || {} }));
  });
  fixed(G, '__quroSettle', (id, r) => {
    const p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    if (r && r.ok) { p.resolve(r.value); return; }
    const e = new Error(((r && r.error) || 'failed') + (r && r.message ? ': ' + r.message : ''));
    e.code = r && r.error;
    p.reject(e);
  });
  fixed(G, '__quroEnd', () => {
    ended = true;
    for (const p of pending.values()) p.reject(new Error('script_ended'));
    pending.clear();
    for (const t of takers.splice(0)) t(null);
  });
  const sleep = (ms) => new Promise((r) => timer(r, ms));
  const refOf = (r) => (r && typeof r === 'object') ? r.ref : r;
  const str = (x) => { if (typeof x === 'string') return x; try { return stringify(x); } catch (e) { return String(x); } };
  const quro = freeze({
    snapshot: () => call('snapshot'),
    read: (opts) => call('read', opts || {}),
    query: (selector, opts) => call('query', { selector, ...(opts || {}) }),
    queryAll: (selector, opts) => call('queryAll', { selector, ...(opts || {}) }),
    text: (selector, opts) => call('text', { selector: selector || 'body', ...(opts || {}) }),
    tap: (ref) => call('tap', { ref: refOf(ref) }),
    type: (ref, text, opts) => call('type', { ref: refOf(ref), text, enter: !!(opts && opts.enter) }),
    scroll: (direction, opts) => call('scroll', { direction, ...(opts || {}) }),
    navigate: (url) => call('navigate', { url }),
    wait: async (w) => {
      if (typeof w === 'number') { await sleep(Math.max(0, Math.min(w, $MAX_WAIT_MS))); return true; }
      if (!w || typeof w.text !== 'string' || !w.text) throw new Error('invalid_arguments: quro.wait takes milliseconds or {text, timeoutMs}');
      return call('waitText', { text: w.text, timeoutMs: w.timeoutMs });
    },
    log: (...xs) => { if (!ended) post(stringify({ id: 0, op: 'log', args: { text: xs.map(str).join(' ') } })); },
  });

  // 这个世界全局对象上(含原型链)的每个名字都遮住,只放行纯 ECMAScript 内置
  const ALLOWED = new Set(${js(ALLOWED_GLOBALS)});
  const RESERVED = new Set(${js(RESERVED)});
  const names = new Set(['globalThis', 'window', 'self', 'document', 'location', 'top', 'parent', 'frames', 'opener']);
  for (let o = G; o; o = O.getPrototypeOf(o)) for (const k of O.getOwnPropertyNames(o)) {
    if (/^[A-Za-z_$][\w$]*$/.test(k) && !ALLOWED.has(k) && !RESERVED.has(k)) names.add(k);
  }
  names.add('__quroRun');
  const shadow = [...names];
  const blanks = shadow.map(() => undefined);
  fixed(G, '__quroRun', (code) => {
    // 模型代码放在里层的箭头函数里:它声明的名字(哪怕和遮住的同名)在自己的作用域里,不和参数冲突
    const fn = new AsyncFunction(...shadow, 'quro', "'use strict';\nreturn (async () => {\n" + code + '\n})();\n//# sourceURL=$SOURCE_URL');
    return apply(fn, undefined, [...blanks, quro]).then((r) => (r === undefined ? null : stringify(r)));
  });
})();
//# sourceURL=$PRELUDE_URL
""".trimStart()

    /** 跑模型代码的表达式(在脚本世界里求值,等 Promise)。 */
    fun run(code: String): String = "globalThis.__quroRun(${JsonPrimitive(code)})"

    /**
     * 模型代码里不许有 import(按词法查,字符串、注释、正则之外出现就拦):动态 import 在全局作用域里跑模块,拿得到全局对象。
     * 返回问题说明,没问题为 null。
     */
    fun codeProblem(code: String): String? =
        if (JsSource.usesImport(code)) "import is not available in page scripts (write the word only inside strings or comments)" else null

    /**
     * 读取世界里固定的读取器:按 `{mode, selector, fields, limit, maxChars}` 读 DOM,只返回纯数据。
     * mode:one(第一个)、all(列表与总数)、count(个数)、text(合起来的可见文字)、has(页面文字里有没有 [selector] 这段字)。
     * 密码一类的值和 value 属性打码,on* 属性不给。
     */
    val READER: String = """
(() => {
  'use strict';
  if (Object.prototype.hasOwnProperty.call(globalThis, '__quroRead')) return;
  const MASK = '$SECRET_MASK';
  const SECRET_AC = new Set(${js(SECRET_AUTOCOMPLETE)});
  const SECRET_TYPES = new Set(${js(SECRET_INPUT_TYPES)});
  const secret = (el) => {
    if (el instanceof HTMLInputElement && SECRET_TYPES.has(String(el.type).toLowerCase())) return true;
    if (!(el instanceof HTMLInputElement) && !(el instanceof HTMLTextAreaElement)) return false;
    const ac = el.getAttribute('autocomplete');
    return !!ac && ac.toLowerCase().split(/\s+/).some((t) => SECRET_AC.has(t));
  };
  const clip = (s, n) => (s.length > n ? s.slice(0, n) : s);
  const textOf = (el) => (secret(el) ? '' : String(el.innerText ?? el.textContent ?? '').trim());
  const item = (el, f, n) => {
    const o = { tag: el.tagName.toLowerCase() };
    if (f.includes('text')) o.text = clip(textOf(el), n);
    if (f.includes('attrs')) {
      o.attrs = {};
      for (const a of el.attributes) {
        const k = a.name.toLowerCase();
        if (k.startsWith('on')) continue;
        o.attrs[k] = k === 'value' && secret(el) ? (a.value ? MASK : '') : clip(a.value, n);
      }
    }
    if (f.includes('value')) {
      o.value = 'value' in el ? (secret(el) ? (el.value ? MASK : '') : clip(String(el.value), n)) : null;
      if (el instanceof HTMLInputElement && (el.type === 'checkbox' || el.type === 'radio')) o.checked = el.checked;
      if (el instanceof HTMLSelectElement) o.selected = Array.from(el.selectedOptions, (x) => clip(x.text, n));
    }
    if (f.includes('box')) {
      const r = el.getBoundingClientRect(), w = innerWidth || 1, h = innerHeight || 1;
      o.box = { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height),
        center: { x: Math.round(Math.min(999, Math.max(0, (r.x + r.width / 2) / w * 999))), y: Math.round(Math.min(999, Math.max(0, (r.y + r.height / 2) / h * 999)) ) },
        visible: r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < h && r.left < w };
    }
    return o;
  };
  Object.defineProperty(globalThis, '__quroRead', { value: (q) => {
    if (q.mode === 'has') return { ok: true, value: String(document.body ? document.body.innerText : '').includes(q.selector) };
    let list;
    try { list = document.querySelectorAll(q.selector); } catch (e) { return { ok: false, error: 'invalid_selector', message: String(e.message) }; }
    if (q.mode === 'count') return { ok: true, value: list.length };
    if (q.mode === 'text') {
      let t = '';
      for (const el of list) { const s = textOf(el); if (s) t += (t ? '\n' : '') + s; if (t.length >= q.maxChars) break; }
      return { ok: true, value: clip(t, q.maxChars) };
    }
    const items = [];
    for (let i = 0; i < list.length && items.length < q.limit; i++) items.push(item(list[i], q.fields, q.maxChars));
    return { ok: true, value: q.mode === 'one' ? (items[0] || null) : { items, total: list.length } };
  } });
})();
""".trimStart()

    /** 在读取世界里照 [spec] 读的表达式。 */
    fun read(spec: JsonObject): String = "globalThis.__quroRead($spec)"

    /**
     * 兜底:脚本还是发出了请求时,`Network.requestWillBeSent` 的发起调用栈里会有模型脚本或前置脚本。
     * 看整条栈(含异步的 parent 链)。
     */
    fun requestFromScript(params: JsonObject): Boolean {
        var stack = (params["initiator"] as? JsonObject)?.get("stack") as? JsonObject
        var depth = 0
        while (stack != null && depth++ < 32) {
            val frames = stack["callFrames"] as? JsonArray
            if (frames != null && frames.any { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull in SCRIPT_URLS }) return true
            stack = stack["parent"] as? JsonObject
        }
        return false
    }

    /** 兜底:脚本跑着、又没有 quro 动作在做时,主框架要跳转——不是 quro 动作带来的,一律拦下。 */
    fun navigationOutsideAction(params: JsonObject, mainFrameId: String, inAction: Boolean): Boolean =
        !inAction && (params["frameId"] as? JsonPrimitive)?.contentOrNull == mainFrameId

    private val SCRIPT_URLS = setOf(SOURCE_URL, PRELUDE_URL)

    /** 取发件箱里下一条消息的表达式(在脚本世界里求值,等 Promise;收尾后交回 null)。 */
    const val TAKE = "globalThis.__quroTake()"

    /** 发件箱里的一条消息;不是合法的调用为 null。 */
    fun parseCall(payload: String): ScriptCall? {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val id = (o["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 } ?: return null
        val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val args = o["args"] as? JsonObject ?: JsonObject(emptyMap())
        return ScriptCall(id, op, args)
    }

    /** 回传第 [id] 个调用的结果。结果是 JSON 对象字面量,直接嵌进表达式。 */
    fun settle(id: Int, result: JsonObject): String = "globalThis.__quroSettle && globalThis.__quroSettle($id, $result)"

    const val END = "globalThis.__quroEnd && globalThis.__quroEnd()"

    private val STACK_LINE = Regex(Regex.escape(SOURCE_URL) + ":(\\d+):\\d+")

    /**
     * `Runtime.evaluate` 的 exceptionDetails → (说明, 脚本里的行号)。行号取调用栈里模型脚本的位置,换算成模型写的第几行(从 1 起);
     * 语法错没有这样的栈,不报行号。
     */
    fun error(details: JsonObject): Pair<String, Int?> {
        val exception = details["exception"] as? JsonObject
        val description = (exception?.get("description") as? JsonPrimitive)?.contentOrNull
            ?: (exception?.get("value") as? JsonPrimitive)?.contentOrNull
        val text = (details["text"] as? JsonPrimitive)?.contentOrNull
        val message = description?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: text ?: "script failed"
        val line = description?.let { STACK_LINE.find(it) }?.groupValues?.get(1)?.toIntOrNull()?.let { it - LINE_OFFSET }?.takeIf { it >= 1 }
        return message to line
    }
}

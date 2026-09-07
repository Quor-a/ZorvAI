/*
 * ZorvAI SandboxPackage 运行时垫片（prelude.js）
 * =====================================================
 * 在 QuickJS 脚本运行时（createScriptRuntime）中注入，提供：
 *
 *  1. console.*        —— 输出重定向到宿主（Kotlin 侧收集后返回给 AI / 渲染到对话框）
 *  2. CommonJS 模块系统 —— require / module.exports / exports；同步加载（hostCallApi fs.read）
 *     · 相对路径：require("./utils") → 工作区内解析 .js / .ts（.ts 自动经 TsTranspiler 转译——由宿主在 fs.read 时完成）
 *     · 内置模块："lodash" / "_"（Lodash-lite）、"datautils"（数据工具）、"tools"（Tools 宿主 API）
 *  3. Tools.*          —— 宿主 API 网关：
 *     · Tools.Files  : read / write / append / list / exists / remove / mkdir / stat
 *     · Tools.Net    : fetch(url, options) —— GET/POST/PUT/DELETE，返回 {status, headers, body}
 *     · Tools.System : info() / now() / env() / clipboard(text?) / notify(title, msg)
 *     · Tools.calc   : eval(expr) —— 安全数学表达式求值（sin/cos/sqrt/pow/log…）
 *     · Tools.Media  : state() / screenshot(opts) —— MediaProjection 屏幕捕获
 *                     （仅用户在对话控制条长按「看懂屏幕」授权后可用）
 *     · Tools.Git    : init/status/add/commit/log/branchList/branchCreate/checkout —— 本地 Git 仓库（JGit）
 *  4. _ (Lodash-lite)  —— map/filter/reduce/each/find/groupBy/sortBy/uniq/flatten/cloneDeep/merge/omit/pick/chunk/range...
 *  5. dataUtils        —— JSON/CSV/统计工具：jsonParse/jsonStringify/csvParse/csvStringify/stats/summarize
 *
 * 安全模型：所有 fs/net 访问经 hostCallApi 网关到 Kotlin 侧，由 HostApiDispatcher 限制在
 * 当前工作区根目录内（路径穿越拒绝）；网络请求强制超时；无 os/exec 入口。
 */

/* ===================== console ===================== */
var console = (function () {
  function fmt(args) {
    var a = [];
    for (var i = 0; i < args.length; i++) {
      var v = args[i];
      try { a.push(typeof v === "string" ? v : JSON.stringify(v)); }
      catch (e) { a.push(String(v)); }
    }
    return a.join(" ");
  }
  function send(level, args) { hostSetData("__log", fmt(args)); }
  return {
    log: function () { send("log", arguments); },
    info: function () { send("info", arguments); },
    warn: function () { send("warn", arguments); },
    error: function () { send("error", arguments); },
    debug: function () { send("debug", arguments); },
    table: function (v) { try { hostSetData("__log", JSON.stringify(v, null, 2)); } catch (e) { hostSetData("__log", String(v)); } },
  };
})();

/* ===================== 工具函数 ===================== */
function __hostCall(api, params) {
  var res = hostCallApi(api, JSON.stringify(params || {}));
  if (res === undefined || res === null) return null;
  if (typeof res === "string") {
    try { return JSON.parse(res); } catch (e) { return res; }
  }
  return res;
}

/* ===================== Tools：宿主 API ===================== */
var Tools = {
  Files: {
    /** 读文件（文本）。.ts 文件由宿主自动转译为 JS 后返回。 */
    read: function (path) { return __hostCall("fs.read", { path: path }); },
    /** 写文件（整体覆盖），返回写入字节数。 */
    write: function (path, content) { return __hostCall("fs.write", { path: path, content: String(content) }); },
    /** 追加写入。 */
    append: function (path, content) { return __hostCall("fs.append", { path: path, content: String(content) }); },
    /** 列目录，返回 {name, isDir, size}[]。 */
    list: function (path) { return __hostCall("fs.list", { path: path }) || []; },
    exists: function (path) { return __hostCall("fs.exists", { path: path }) === true; },
    remove: function (path) { return __hostCall("fs.remove", { path: path }); },
    mkdir: function (path) { return __hostCall("fs.mkdir", { path: path }); },
    /** 文件/目录元信息 {name, isDir, size, lastModified}。 */
    stat: function (path) { return __hostCall("fs.stat", { path: path }); },
  },
  Net: {
    /** HTTP 请求。options: {method, headers, body, timeoutMs}。返回 {ok, status, body, headers}。 */
    fetch: function (url, options) { return __hostCall("net.fetch", Object.assign({ url: url }, options || {})); },
    get: function (url, headers) { return __hostCall("net.fetch", { url: url, method: "GET", headers: headers }); },
    post: function (url, body, headers) { return __hostCall("net.fetch", { url: url, method: "POST", body: typeof body === "string" ? body : JSON.stringify(body), headers: headers }); },
  },
  System: {
    /** 设备/运行时信息 {device, androidVersion, sdk, app, screen, battery, network}。 */
    info: function () { return __hostCall("system.info", {}); },
    /** 当前毫秒时间戳。 */
    now: function () { return Date.now(); },
    /** 工作区根目录与可用环境变量。 */
    env: function () { return __hostCall("system.env", {}); },
    /** 读写剪贴板：无参读、有参写。 */
    clipboard: function (text) { return __hostCall("system.clipboard", text === undefined ? {} : { text: String(text) }); },
    /** 系统通知（需通知权限）。 */
    notify: function (title, msg) { return __hostCall("system.notify", { title: String(title), message: String(msg) }); },
  },
  calc: {
    /** 数学表达式求值："sin(0.5)^2 + sqrt(16)"。 */
    eval: function (expr) { return __hostCall("calc.eval", { expr: String(expr) }); },
  },
  Media: {
    /** 屏幕捕获状态：{attached, running, state, lastError}
     *  （attached = 用户已在对话控制条长按「看懂屏幕」完成 MediaProjection 系统授权）。 */
    state: function () { return __hostCall("media.state", {}); },
    /** 截取当前屏幕（需先完成 MediaProjection 授权，见 state()）。
     *  options: {maxEdge=1280, quality=80, filename="shot_时间戳.jpg"}
     *  返回 {ok, path(工作区相对路径), file(绝对路径), width, height, bytes}。 */
    screenshot: function (options) { return __hostCall("media.screenshot", options || {}); },
  },
  Git: {
    /** 初始化本地仓库（已存在则幂等）。返回 {ok, repo} 或 {ok, reinitialized}。 */
    init: function (path) { return __hostCall("git.init", { path: path || "." }); },
    /** 工作区状态：{branch, clean, staged[], modified[], removed[], untracked[]}。 */
    status: function (path) { return __hostCall("git.status", { path: path || "." }); },
    /** 暂存：files 为相对路径数组，["."] 表示全部（含未跟踪新文件）。 */
    add: function (path, files) { return __hostCall("git.add", { path: path || ".", files: files || ["."] }); },
    /** 提交暂存区：{path, message, opts:{name, email}}。返回 {ok, commit, message, branch}。 */
    commit: function (path, message, opts) {
      opts = opts || {};
      return __hostCall("git.commit", { path: path || ".", message: String(message), name: opts.name || "", email: opts.email || "" });
    },
    /** 提交历史（倒序）：{commits:[{id, message, author, time}], count}。 */
    log: function (path, max) { return __hostCall("git.log", { path: path || ".", max: max || 20 }); },
    /** 分支列表：{branches:[{name, current}], current}。 */
    branchList: function (path) { return __hostCall("git.branchList", { path: path || "." }); },
    /** 建分支（opts.checkout=true 时切过去）。 */
    branchCreate: function (path, name, checkout) { return __hostCall("git.branchCreate", { path: path || ".", name: name, checkout: !!checkout }); },
    /** 切分支。 */
    checkout: function (path, name) { return __hostCall("git.checkout", { path: path || ".", name: name }); },
  },
};

/* ===================== Lodash-lite（_） ===================== */
var _ = (function () {
  var ArrayProto = Array.prototype;
  function isObj(v) { return v !== null && typeof v === "object"; }
  function isArr(v) { return Array.isArray(v); }
  function isFn(v) { return typeof v === "function"; }

  function each(coll, iter) {
    if (coll === null || coll === undefined) return coll;
    if (isArr(coll)) { for (var i = 0; i < coll.length; i++) iter(coll[i], i, coll); }
    else { var ks = Object.keys(coll); for (var j = 0; j < ks.length; j++) iter(coll[ks[j]], ks[j], coll); }
    return coll;
  }
  function map(coll, iter) {
    if (coll === null || coll === undefined) return [];
    if (isArr(coll)) { var r = []; for (var i = 0; i < coll.length; i++) r.push(iter(coll[i], i, coll)); return r; }
    var o = {}, ks2 = Object.keys(coll);
    for (var j = 0; j < ks2.length; j++) o[ks2[j]] = iter(coll[ks2[j]], ks2[j], coll);
    return o;
  }
  function reduce(coll, iter, acc) {
    var hasInit = arguments.length > 2, first = true;
    each(coll, function (v, k) {
      if (first && !hasInit) { acc = v; first = false; return; }
      acc = iter(acc, v, k);
    });
    return acc;
  }
  function filter(coll, pred) {
    var r = [];
    each(coll, function (v, k) { if (pred(v, k, coll)) r.push(v); });
    return r;
  }
  function find(coll, pred) {
    var found;
    each(coll, function (v, k) { if (pred(v, k, coll)) { found = v; return false; } });
    return found;
  }
  function findIndex(arr, pred) {
    for (var i = 0; i < (arr || []).length; i++) if (pred(arr[i], i, arr)) return i;
    return -1;
  }
  function some(coll, pred) { return findIndex(coll, pred) !== -1; }
  function every(coll, pred) {
    for (var i = 0; i < (coll || []).length; i++) if (!pred(coll[i], i, coll)) return false;
    return true;
  }
  function groupByInner(coll, iter) {
    var out = {};
    each(coll, function (v, k) {
      var key = isFn(iter) ? iter(v, k) : v[iter];
      (out[key] || (out[key] = [])).push(v);
    });
    return out;
  }
  function countBy(coll, iter) {
    var out = {};
    each(coll, function (v, k) {
      var key = isFn(iter) ? iter(v, k) : v[iter];
      out[key] = (out[key] || 0) + 1;
    });
    return out;
  }
  function sortBy(coll, iter) {
    var arr = (coll || []).slice();
    arr.sort(function (a, b) {
      var ka = isFn(iter) ? iter(a) : a[iter], kb = isFn(iter) ? iter(b) : b[iter];
      return ka < kb ? -1 : ka > kb ? 1 : 0;
    });
    return arr;
  }
  function uniq(arr) {
    var seen = {}, out = [];
    each(arr || [], function (v) { var k = JSON.stringify(v); if (!seen[k]) { seen[k] = 1; out.push(v); } });
    return out;
  }
  function uniqBy(arr, iter) {
    var seen = {}, out = [];
    each(arr || [], function (v) {
      var k = isFn(iter) ? iter(v) : v[iter];
      if (!seen[k]) { seen[k] = 1; out.push(v); }
    });
    return out;
  }
  function flatten(arr) { return reduce(arr || [], function (a, b) { return a.concat(isArr(b) ? flatten(b) : b); }, []); }
  function flattenDeep(arr) { return reduce(arr || [], function (a, b) { return a.concat(isArr(b) ? flattenDeep(b) : b); }, []); }
  function chunk(arr, n) {
    var out = [];
    for (var i = 0; i < (arr || []).length; i += n) out.push(arr.slice(i, i + n));
    return out;
  }
  function zip() {
    var args = ArrayProto.slice.call(arguments), out = [], max = 0;
    each(args, function (a) { max = Math.max(max, (a || []).length); });
    for (var i = 0; i < max; i++) { var row = []; each(args, function (a) { row.push(a ? a[i] : undefined); }); out.push(row); }
    return out;
  }
  function range(start, end, step) {
    if (end === undefined) { end = start; start = 0; }
    step = step || 1;
    var out = [];
    if (step > 0) for (var i = start; i < end; i += step) out.push(i);
    else for (var j = start; j > end; j += step) out.push(j);
    return out;
  }
  function clone(v) { if (!isObj(v)) return v; return isArr(v) ? v.slice() : Object.assign({}, v); }
  function cloneDeep(v) {
    if (!isObj(v)) return v;
    if (isArr(v)) return map(v, cloneDeep);
    var o = {};
    each(v, function (x, k) { o[k] = cloneDeep(x); });
    return o;
  }
  function merge(dst) {
    for (var i = 1; i < arguments.length; i++) {
      var src = arguments[i];
      if (!isObj(src)) continue;
      each(src, function (v, k) {
        if (isObj(v) && isObj(dst[k])) merge(dst[k], v);
        else dst[k] = cloneDeep(v);
      });
    }
    return dst;
  }
  function pick(obj, keys) {
    var out = {};
    each(keys || [], function (k) { if (obj && obj[k] !== undefined) out[k] = obj[k]; });
    return out;
  }
  function omit(obj, keys) {
    var out = {}, drop = {};
    each(keys || [], function (k) { drop[k] = 1; });
    each(obj || {}, function (v, k) { if (!drop[k]) out[k] = v; });
    return out;
  }
  function keys(o) { return Object.keys(o || {}); }
  function values(o) { return Object.keys(o || {}).map(function (k) { return o[k]; }); }
  function entries(o) { return Object.keys(o || {}).map(function (k) { return [k, o[k]]; }); }
  function fromEntries(ps) {
    var o = {};
    each(ps || [], function (p) { o[p[0]] = p[1]; });
    return o;
  }
  function invert(o) {
    var r = {};
    each(o || {}, function (v, k) { r[v] = k; });
    return r;
  }
  function get(obj, path, def) {
    var parts = String(path).split("."), cur = obj;
    for (var i = 0; i < parts.length; i++) {
      if (cur === null || cur === undefined) return def;
      cur = cur[parts[i]];
    }
    return cur === undefined ? def : cur;
  }
  function set(obj, path, val) {
    var parts = String(path).split("."), cur = obj;
    for (var i = 0; i < parts.length - 1; i++) {
      if (!isObj(cur[parts[i]])) cur[parts[i]] = {};
      cur = cur[parts[i]];
    }
    cur[parts[parts.length - 1]] = val;
    return obj;
  }
  function has(obj, path) { return get(obj, path) !== undefined; }
  function min(arr) { return reduce(arr, function (a, b) { return b < a ? b : a; }, Infinity); }
  function max(arr) { return reduce(arr, function (a, b) { return b > a ? b : a; }, -Infinity); }
  function sum(arr) { return reduce(arr || [], function (a, b) { return a + (Number(b) || 0); }, 0); }
  function mean(arr) { var a = arr || []; return a.length ? sum(a) / a.length : NaN; }
  function random(lower, upper, floating) {
    if (lower === undefined) { lower = 0; upper = 1; }
    if (upper === undefined) { upper = lower; lower = 0; }
    var r = lower + Math.random() * (upper - lower);
    return floating ? r : Math.floor(r);
  }
  function shuffle(arr) {
    var a = (arr || []).slice();
    for (var i = a.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)); var t = a[i]; a[i] = a[j]; a[j] = t; }
    return a;
  }
  function sample(arr) { return arr[Math.floor(Math.random() * (arr || []).length)]; }
  function capitalize(s) { s = String(s); return s ? s[0].toUpperCase() + s.slice(1) : s; }
  function camelCase(s) {
    return String(s).replace(/[-_.\s]+(.)?/g, function (_, c) { return c ? c.toUpperCase() : ""; });
  }
  function kebabCase(s) {
    return String(s).replace(/([a-z\d])([A-Z])/g, "$1-$2").replace(/[-_\s]+/g, "-").toLowerCase();
  }
  function snakeCase(s) { return kebabCase(s).replace(/-/g, "_"); }
  function padStart(s, len, ch) { s = String(s); ch = ch || " "; while (s.length < len) s = ch + s; return s; }
  function padEnd(s, len, ch) { s = String(s); ch = ch || " "; while (s.length < len) s = s + ch; return s; }
  function truncate(s, n) { s = String(s); return s.length > n ? s.slice(0, n - 1) + "…" : s; }
  function startsWith(s, p) { return String(s).indexOf(p) === 0; }
  function endsWith(s, p) { s = String(s); return s.indexOf(p, s.length - p.length) !== -1; }
  function template(tpl) {
    return function (data) {
      return String(tpl).replace(/<%=([\s\S]+?)%>/g, function (_, e) { return String(evalExpr(e, data)); })
        .replace(/<%-([\s\S]+?)%>/g, function (_, e) { return escapeHtml(String(evalExpr(e, data))); });
    };
    function evalExpr(e, d) { with (d || {}) { try { return eval("(" + e + ")"); } catch (ex) { return ""; } } }
    function escapeHtml(s) { return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }
  }
  function debounce(fn, wait) {
    var t = null;
    var wrapped = function () {
      var args = arguments, self = this;
      if (t) clearTimeout(t);
      t = setTimeout(function () { fn.apply(self, args); }, wait);
    };
    wrapped.cancel = function () { if (t) clearTimeout(t); t = null; };
    return wrapped;
  }
  function throttle(fn, wait) {
    var last = 0, t = null;
    return function () {
      var now = Date.now(), args = arguments, self = this;
      if (now - last >= wait) { last = now; fn.apply(self, args); }
      else if (!t) { t = setTimeout(function () { last = Date.now(); t = null; fn.apply(self, args); }, wait - (now - last)); }
    };
  }
  function once(fn) {
    var done = false, res;
    return function () { if (!done) { done = true; res = fn.apply(this, arguments); } return res; };
  }
  function isEmpty(v) {
    if (v === null || v === undefined) return true;
    if (isArr(v) || typeof v === "string") return v.length === 0;
    return Object.keys(v).length === 0;
  }
  function isArray(v) { return Array.isArray(v); }
  function isObject(v) { return v !== null && typeof v === "object" && !Array.isArray(v); }
  function isString(v) { return typeof v === "string"; }
  function isNumber(v) { return typeof v === "number"; }
  function isBoolean(v) { return typeof v === "boolean"; }
  function isFunction(v) { return typeof v === "function"; }
  function isNull(v) { return v === null; }
  function isNil(v) { return v === null || v === undefined; }
  function isUndefined(v) { return v === undefined; }

  var api = {
    each: each, forEach: each, map: map, collect: map, reduce: reduce, foldl: reduce, inject: reduce,
    filter: filter, select: filter, find: find, detect: find, findIndex: findIndex,
    some: some, any: some, every: every, all: every,
    groupBy: groupByInner, countBy: countBy, sortBy: sortBy, indexBy: function (c, k) { return groupByInner(c, function (v) { return v[k]; }); },
    uniq: uniq, unique: uniq, uniqBy: uniqBy, flatten: flatten, flattenDeep: flattenDeep,
    chunk: chunk, zip: zip, range: range, shuffle: shuffle, sample: sample,
    clone: clone, cloneDeep: cloneDeep, merge: merge, pick: pick, omit: omit,
    keys: keys, values: values, entries: entries, toPairs: entries, fromPairs: fromEntries, invert: invert,
    get: get, set: set, has: has, property: function (path) { return function (o) { return get(o, path); }; },
    min: min, max: max, sum: sum, mean: mean, random: random,
    capitalize: capitalize, camelCase: camelCase, kebabCase: kebabCase, snakeCase: snakeCase,
    padStart: padStart, padEnd: padEnd, truncate: truncate, startsWith: startsWith, endsWith: endsWith,
    template: template, debounce: debounce, throttle: throttle, once: once, isEmpty: isEmpty,
    isArray: isArray, isObject: isObject, isString: isString, isNumber: isNumber,
    isBoolean: isBoolean, isFunction: isFunction, isNull: isNull, isNil: isNil, isUndefined: isUndefined,
    identity: function (v) { return v; }, noop: function () {},
    times: function (n, iter) { var r = []; for (var i = 0; i < n; i++) r.push(iter ? iter(i) : i); return r; },
    keyBy: function (c, k) { var o = {}; each(c, function (v) { o[isFn(k) ? k(v) : v[k]] = v; }); return o; },
    partition: function (c, pred) { return [filter(c, pred), filter(c, function (v, i) { return !pred(v, i, c); })]; },
    orderBy: function (c, iters, orders) {
      iters = isArr(iters) ? iters : [iters]; orders = isArr(orders) ? orders : [orders];
      return sortBy(c, function (v) { return iters.map(function (it, i) { return (orders[i] === "desc" ? -1 : 1) * (isFn(it) ? it(v) : v[it]); }); });
    },
  };
  return api;
})();

/* ===================== dataUtils ===================== */
var dataUtils = {
  jsonParse: function (s) { return JSON.parse(s); },
  jsonStringify: function (v, pretty) { return JSON.stringify(v, null, pretty ? 2 : 0); },
  /** CSV 文本 → 对象数组（首行表头）。 */
  csvParse: function (text, delimiter) {
    var d = delimiter || ",", lines = String(text).trim().split(/\r?\n/);
    if (!lines.length) return [];
    var parseLine = function (line) {
      var out = [], cur = "", inQ = false;
      for (var i = 0; i < line.length; i++) {
        var c = line[i];
        if (inQ) {
          if (c === '"' && line[i + 1] === '"') { cur += '"'; i++; }
          else if (c === '"') inQ = false;
          else cur += c;
        } else {
          if (c === '"') inQ = true;
          else if (c === d) { out.push(cur); cur = ""; }
          else cur += c;
        }
      }
      out.push(cur);
      return out;
    };
    var headers = parseLine(lines[0]);
    return lines.slice(1).map(function (l) {
      var cells = parseLine(l), row = {};
      headers.forEach(function (h, i) { row[h] = cells[i]; });
      return row;
    });
  },
  /** 对象数组 → CSV 文本。 */
  csvStringify: function (rows, delimiter) {
    if (!rows || !rows.length) return "";
    var d = delimiter || ",", headers = Object.keys(rows[0]);
    var esc = function (v) {
      var s = v === null || v === undefined ? "" : String(v);
      return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
    };
    return [headers.join(d)].concat(rows.map(function (r) {
      return headers.map(function (h) { return esc(r[h]); }).join(d);
    })).join("\n");
  },
  /** 数值统计：{count, sum, mean, min, max, median, stdev}。 */
  stats: function (nums) {
    var a = (nums || []).map(Number).filter(function (v) { return !isNaN(v); }).sort(function (x, y) { return x - y; });
    if (!a.length) return { count: 0, sum: 0, mean: NaN, min: NaN, max: NaN, median: NaN, stdev: NaN };
    var s = _.sum(a), n = a.length, m = s / n;
    var varr = _.sum(a.map(function (v) { return (v - m) * (v - m); })) / n;
    return { count: n, sum: s, mean: m, min: a[0], max: a[n - 1], median: n % 2 ? a[(n - 1) / 2] : (a[n / 2 - 1] + a[n / 2]) / 2, stdev: Math.sqrt(varr) };
  },
  /** 表格摘要：每列类型/缺失数/唯一值数（数值列附统计）。 */
  summarize: function (rows) {
    if (!rows || !rows.length) return {};
    var headers = Object.keys(rows[0]), out = {};
    headers.forEach(function (h) {
      var vals = rows.map(function (r) { return r[h]; });
      var missing = vals.filter(function (v) { return v === null || v === undefined || v === ""; }).length;
      var uniq = _.uniq(vals).length;
      var allNum = vals.every(function (v) { return v === "" || v === null || v === undefined || !isNaN(Number(v)); });
      out[h] = allNum ? Object.assign({ missing: missing, unique: uniq, type: "number" }, _.stats(vals.filter(function (v) { return v !== "" && v !== null; })))
                      : { missing: missing, unique: uniq, type: "string", samples: _.uniq(vals).slice(0, 5) };
    });
    return out;
  },
};

/* ===================== CommonJS 模块系统 ===================== */
var __moduleCache = {};
var __moduleFactories = {}; /* 宿主预注入的模块工厂（内置模块走这里） */

function __resolvePath(base, req) {
  if (req.charAt(0) !== ".") return req; /* 内置模块名或裸包名（内置白名单外报错） */
  var stack = base ? base.split("/") : [];
  var parts = req.split("/");
  for (var i = 0; i < parts.length; i++) {
    var p = parts[i];
    if (p === "." || p === "") continue;
    if (p === "..") { if (stack.length) stack.pop(); }
    else stack.push(p);
  }
  return stack.join("/");
}

function require(req) {
  /* 1. 内置模块（Lodash / dataUtils / Tools / host 通道） */
  if (req === "lodash" || req === "_") return _;
  if (req === "datautils" || req === "dataUtils") return dataUtils;
  if (req === "tools" || req === "Tools") return Tools;

  /* 2. 相对路径模块：工作区内解析 */
  var resolved = __resolvePath(__dirname, req);
  if (!resolved) throw new Error("require 失败：非法路径 '" + req + "'");

  if (__moduleCache[resolved]) return __moduleCache[resolved].exports;

  /* 3. 读源码（宿主 fs.read：自动尝试 .js / .ts / package.json main；.ts 自动转译） */
  var src = __hostCall("fs.readModule", { path: resolved });
  if (src === null || src === undefined || (typeof src === "object" && src.error)) {
    throw new Error("require 失败：找不到模块 '" + req + "'（已解析为 '" + resolved + "'）" + (src && src.error ? "：" + src.error : ""));
  }
  var code = typeof src === "object" ? src.code : src;

  /* 4. 模块包装执行（CommonJS） */
  var module = { exports: {} };
  __moduleCache[resolved] = module;
  var dirname = resolved.indexOf("/") >= 0 ? resolved.slice(0, resolved.lastIndexOf("/")) : "";
  var fn = new Function("exports", "require", "module", "__filename", "__dirname",
    code + "\n//# sourceURL=" + resolved + ".js");
  fn(module.exports, require, module, resolved, dirname);
  return module.exports;
}

/* 顶层脚本的 __dirname 为工作区根（空串），由宿主在 eval 前按需覆盖 */
var __dirname = "";
var __filename = "";

/* ===================== 脚本入口协议 ===================== */
/* code_runner / ToolPkg 约定：脚本最后一行表达式的值或 module.exports.main(...) 的返回值
 * 作为结果回传。为简化：宿主把用户代码包进 (function(){ ... })() 后 eval，
 * 结果经 hostSetData("__result", ...) 回传。 */

/* zxwidget 全布局渲染巡检：把每个契约的 sample 喂给运行时编译器 compile()，
   用最小 DOM stub 跑真实渲染路径（resolveLayout -> LAYOUTS[fn]），
   标记 NaN / undefined / 渲染失败 / 空白（无子节点）三类缺陷。
   仅用于本地回归，不进 release 产物。 */
const fs = require("fs");
const path = require("path");

const ROOT = "D:/Calw OS-project/QuroAI";
const HTML = path.join(ROOT, "app/src/main/assets/zx_card_runtime.html");
const CONTRACTS = path.join(ROOT, "app/src/main/assets/zx_contracts.json");

// ---- 1. 抽取编译器脚本（截到宿主桥之前，避免顶层 document.getElementById 崩溃）----
let html = fs.readFileSync(HTML, "utf8");
const sTag = html.indexOf("<script>", html.indexOf('id="zx-contracts"'));
const eTag = html.indexOf("</script>", sTag);
let script = html.slice(sTag + "<script>".length, eTag);
const cut = script.indexOf("var state={component");
if (cut >= 0) script = script.slice(0, cut);

// ---- 2. 最小 DOM stub ----
function makeNode(tag) {
  const n = {
    tag, children: [], className: "", _attrs: {}, _text: "", _html: "",
    setAttribute(k, v) { this._attrs[k] = v; },
    getAttribute(k) { return this._attrs[k]; },
    appendChild(c) { this.children.push(c); return c; },
    addEventListener() {},
    classList: { add() {}, remove() {}, toggle() {} },
    style: {},
  };
  Object.defineProperty(n, "innerHTML", { get() { return this._html; }, set(v) { this._html = String(v); } });
  Object.defineProperty(n, "textContent", { get() { return this._text; }, set(v) { this._text = String(v); } });
  return n;
}
global.document = {
  createElement: (t) => makeNode(t),
  createElementNS: (_ns, t) => makeNode(t),
  getElementById: () => makeNode("div"),
  documentElement: { classList: { toggle() {} } },
};

// ---- 3. 装载编译器（屏蔽其自带 ZX_CONTRACTS 声明，稍后覆盖）----
const loader = new Function(script + "\n;return {compile:compile, resolveLayout:resolveLayout, get ZX_CONTRACTS(){return ZX_CONTRACTS;}, set ZX_CONTRACTS(v){ZX_CONTRACTS=v;}};");
const api = loader();
const compile = api.compile;
const resolveLayout = api.resolveLayout;

// ---- 4. 用真实契约覆盖 ZX_CONTRACTS ----
const json = JSON.parse(fs.readFileSync(CONTRACTS, "utf8"));
api.ZX_CONTRACTS = json.contracts;

// ---- 5. 巡检 ----
const ctx = { interact() {} };
const flags = { NaN: [], undefined: [], RENDER_ERR: [], EMPTY: [], THROW: [] };
const detail = [];
let covered = 0;

function walk(n, acc) {
  if (!n) return;
  acc.count++;
  // 真正的渲染失败由编译器吞异常后产出 className==="err-box" 的节点标记
  if (n.className === "err-box") acc.err = true;
  const s = (n._html || "") + (n._text || "");
  if (s.indexOf("NaN") >= 0) acc.nan = true;
  if (s.indexOf("undefined") >= 0) acc.undef = true;
  if (n._attrs) for (const k in n._attrs) {
    const v = String(n._attrs[k]);
    if (v.indexOf("NaN") >= 0) acc.nan = true;
    if (v.indexOf("undefined") >= 0) acc.undef = true;
  }
  (n.children || []).forEach((c) => walk(c, acc));
}

const comps = json.components || [];
for (const name of comps) {
  const ct = (json.contracts && json.contracts[name]) || {};
  const sample = ct.sample;
  if (!sample) { detail.push({ name, layout: "?", status: "no-sample" }); continue; }
  covered++;
  const layout = resolveLayout(name, sample);
  try {
    const node = compile(name, sample, ctx);
    const acc = { count: 0, nan: false, undef: false, err: false };
    walk(node, acc);
    let status = "ok";
    if (acc.err) { status = "RENDER_ERR"; flags.RENDER_ERR.push(name); }
    else if (acc.nan) { status = "NaN"; flags.NaN.push(name); }
    else if (acc.undef) { status = "undefined"; flags.undefined.push(name); }
    else if (acc.count <= 1) { status = "EMPTY"; flags.EMPTY.push(name); }
    detail.push({ name, layout, status, nodes: acc.count });
  } catch (e) {
    status = "THROW"; flags.THROW.push(name);
    detail.push({ name, layout, status, nodes: 0, err: String(e && e.message || e) });
  }
}

// ---- 6. 汇总 ----
const bad = flags.NaN.length + flags.undefined.length + flags.RENDER_ERR.length + flags.EMPTY.length + flags.THROW.length;
console.log("=== ZXWIDGET 全布局巡检 ===");
console.log("契约总数:", comps.length, " 有 sample 且巡检:", covered);
console.log("缺陷总计:", bad);
for (const k of ["RENDER_ERR", "THROW", "EMPTY", "NaN", "undefined"]) {
  const arr = flags[k];
  console.log(`  ${k}: ${arr.length}` + (arr.length ? "  -> " + arr.join(", ") : ""));
}
// 列出每个 EMPTY / NaN / undefined / RENDER_ERR 的命中布局，便于定位
console.log("\n--- 缺陷明细（name | layout | status | nodes）---");
for (const d of detail) {
  if (d.status === "ok" || d.status === "no-sample") continue;
  console.log(`  ${d.name} | ${d.layout} | ${d.status} | nodes=${d.nodes}` + (d.err ? " | " + d.err : ""));
}
process.exit(bad > 0 ? 1 : 0);

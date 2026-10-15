/* zxwidget 退化输入探针（全量）

   为什么要有这个脚本：
   现有三支巡检（sweep / blankcheck / audit）都拿**契约自带 sample** 去喂编译器，
   而 sample 是「契约作者写的、结构完全正确」的那一版 —— 于是永远绿。
   真机上用户看到的破图（空白卡 / 「暂无节点」/「没有网格数据」/ 界面上只剩一个 dead）
   全部来自**模型写的、结构走样的** DSL。本脚本专打这一态。

   做法：对每个带 sample 的组件，把 sample 按「模型真实会犯的 8 种走样」退化后
   走真实渲染路径（compile），再用 DOM 树统计可见文本与 SVG 几何图形，判三类缺陷：

     BLANK    渲染出来既无可见文本、也无任何几何图形 → 用户看到一块空白。
     DEAD     输入里**明明有数据**，输出却只有空态占位（「暂无节点」「没有网格数据」…）
              → 数据被吞，用户看到空壳。
     RAWKEY   输入的字段名（cells/nodes/dead/items…）被当成界面内容原样摆出来
              → 用户看到英文内部键名。

   退出码 0 = 全绿；1 = 有缺陷（打印明细）。
   用法：node scripts/zx/zxwidget_degrade_probe.js [--comp=zx_bento] [--all]
*/
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..', '..');
const ASSETS = path.join(ROOT, 'app/src/main/assets');
const html = fs.readFileSync(path.join(ASSETS, 'zx_card_runtime.html'), 'utf8');
const code = [...html.matchAll(/<script(?![^>]*application\/json)[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1])[0];
const contracts = JSON.parse(fs.readFileSync(path.join(ASSETS, 'zx_contracts.json'), 'utf8'));

/* ---------- 最小 DOM stub（与 render_path 探针同款） ---------- */
function mk(tag) {
  const n = {
    tagName: tag, children: [], attrs: {}, style: {}, _html: '', _text: '', className: '', id: '', dataset: {},
    classList: { toggle() {}, add() {}, remove() {} },
    appendChild(c) { this.children.push(c); return c; },
    insertBefore(c) { this.children.unshift(c); return c; },
    remove() {}, setAttribute(k, v) { this.attrs[k] = v; }, getAttribute(k) { return this.attrs[k]; },
    addEventListener() {}, querySelector() { return null; },
    set innerHTML(v) { this._html = String(v); if (v === '') this.children = []; }, get innerHTML() { return this._html; },
    set textContent(v) { this._text = String(v); }, get textContent() { return this._text; },
    get nextSibling() { return null; }, get firstChild() { return this.children[0] || null },
  };
  return n;
}
const byId = {};
['stream', 'inp', 'send', 'dot', 'title', 'model', 'cnt', 'btnRaw', 'btnNew', 'body', 'c', 't', 'rawBtn', 'zx-contracts', 'app']
  .forEach((i) => { byId[i] = mk('div'); byId[i].id = i; });
byId['zx-contracts'].textContent = JSON.stringify(contracts);
const sb = {
  document: {
    createElement: mk, createElementNS: (_ns, t) => mk(t), getElementById: (i) => byId[i] || null,
    documentElement: mk('html'), addEventListener() {}, querySelector() { return null; }, body: mk('body'),
  },
  window: {}, navigator: {}, requestAnimationFrame: (f) => f(),
  JSON, Math, Object, Array, String, Number, Boolean, RegExp, Error, parseInt, parseFloat, isFinite, isNaN, console, setTimeout, Date,
};
vm.createContext(sb);
vm.runInContext(code, sb);

/* ---------- 树分析 ---------- */
const SHAPE_TAGS = ['rect', 'circle', 'line', 'path', 'polygon', 'polyline', 'ellipse', 'text', 'img', 'canvas', 'iframe'];
function analyze(n, acc) {
  if (!n) return acc;
  if (n.className === 'err-box') acc.err = true;
  const tag = String(n.tagName || '').toLowerCase();
  if (SHAPE_TAGS.indexOf(tag) >= 0) acc.shapes++;
  const raw = String(n._html || '') + ' ' + String(n._text || '');
  acc.text += ' ' + raw.replace(/<[^>]*>/g, ' ');
  (n.children || []).forEach((c) => analyze(c, acc));
  return acc;
}
const EMPTY_PHRASES = ['暂无节点', '没有网格数据', '没有数值序列', '该组件没有可渲染字段', '暂无结构化数据', '没有数据', '暂无数据'];

/* ---------- 退化变体 ---------- */
const ARR_KEYS = ['items', 'rows', 'nodes', 'pts', 'steps', 'v', 'edges', 'cols', 'columns', 'options', 'days', 'cells',
  'slots', 'groups', 'blocks', 'marks', 'stages', 'fields', 'links', 'opts', 'cards', 'levels', 'series', 'people',
  'labels', 'tags', 'tasks', 'children', 'kids', 'lines', 'data', 'values', 'points', 'metrics', 'tiles', 'bars', 'arms',
  'rings', 'bins', 'rows'];
const RENAME = { n: 'title', t: 'text', v: 'value', d: 'desc', k: 'key', s: 'start', e: 'end', who: 'owner', at: 'time', st: 'status' };

function stripType(o) { const c = {}; Object.keys(o).forEach((k) => { if (k !== 'type') c[k] = o[k]; }); return c; }
function firstArrKey(o) {
  for (const k of Object.keys(o)) if (Array.isArray(o[k]) && o[k].length) return k;
  for (const k of Object.keys(o)) if (Array.isArray(o[k])) return k;
  return null;
}
function mapKeysDeep(o, fn) {
  if (Array.isArray(o)) return o.map((x) => mapKeysDeep(x, fn));
  if (o && typeof o === 'object') { const c = {}; Object.keys(o).forEach((k) => { c[fn(k)] = mapKeysDeep(o[k], fn); }); return c; }
  return o;
}
/* 输入里到底有没有「真实数据」——用于判定 DEAD 是否属于「数据被吞」 */
function hasRealData(dsl) {
  let found = false;
  (function walk(v, key) {
    if (found) return;
    if (Array.isArray(v)) { v.forEach((x) => walk(x, key)); return; }
    if (v && typeof v === 'object') { Object.keys(v).forEach((k) => { if (k !== 'type') walk(v[k], k); }); return; }
    if (v === undefined || v === null || v === '') return;
    found = true;
  })(dsl, null);
  return found;
}

function variants(comp, sample) {
  const out = [];
  const base = stripType(sample);
  out.push(['V0 原样 sample', sample]);

  const ak = firstArrKey(base);
  if (ak && ak !== 'items') {
    const v = stripType(sample); v.items = v[ak]; delete v[ak];
    out.push(['V1 主数组改名 items', Object.assign({ type: comp }, v)]);
  }
  if (ak) {
    const v = stripType(sample); v[ak] = [];
    out.push(['V2 主数组清空', Object.assign({ type: comp }, v)]);

    const v3 = stripType(sample);
    v3[ak] = (sample[ak] || []).map(() => ({}));
    out.push(['V3 条目变空对象', Object.assign({ type: comp }, v3)]);

    const v4 = stripType(sample);
    v4[ak] = (sample[ak] || []).map((x, i) => (x && typeof x === 'object' ? { v: i + 1 } : String(i + 1)));
    out.push(['V4 条目只留一个裸值', Object.assign({ type: comp }, v4)]);

    const v5 = stripType(sample);
    v5[ak] = (sample[ak] || []).map((x) => (x && typeof x === 'object' ? mapKeysDeep(x, (k) => RENAME[k] || k) : x));
    out.push(['V5 字段名换成语义词', Object.assign({ type: comp }, v5)]);

    const v6 = stripType(sample); delete v6[ak];
    out.push(['V6 剥掉主数组只留标量', Object.assign({ type: comp }, v6)]);
  }
  out.push(['V7 只发组件壳', { type: comp }]);
  out.push(['V8 整包套进 data', { type: comp, data: base }]);
  return out;
}

/* ---------- 主循环 ---------- */
const argv = process.argv.slice(2);
const only = (argv.find((a) => a.startsWith('--comp=')) || '').split('=')[1];
const comps = only ? [only] : (contracts.components || []);
const ctx = { interact() {} };

const stats = { total: 0, BLANK: [], DEAD: [], RAWKEY: [], THROW: [] };
const perComp = [];

for (const comp of comps) {
  const ct = (contracts.contracts && contracts.contracts[comp]) || {};
  const sample = ct.sample;
  if (!sample) continue;
  for (const [label, dsl] of variants(comp, sample)) {
    stats.total++;
    let acc;
    try {
      const node = sb.window.ZorvAI ? null : null;
      const body = byId['body'];
      body.children = []; body._html = ''; body._text = '';
      const n = sb.compile(comp, dsl, ctx);
      acc = analyze(n, { shapes: 0, text: '', err: false });
    } catch (e) {
      stats.THROW.push({ comp, label, msg: String((e && e.message) || e) });
      continue;
    }
    const vis = acc.text.replace(/\s+/g, ' ').trim();
    const visLen = vis.length;
    const bad = [];

    if (acc.err) bad.push('ERRBOX');
    if (acc.shapes === 0 && visLen < 2) bad.push('BLANK');
    const deadHit = EMPTY_PHRASES.find((p) => vis.indexOf(p) >= 0);
    if (deadHit && hasRealData(dsl)) bad.push('DEAD(' + deadHit + ')');
    /* RAWKEY：输入里的字段名原样出现在可见文本里 */
    const keys = new Set(Object.keys(flattenKeys(dsl)));
    const leaked = [...keys].filter((k) => k.length >= 3 && vis.split(/\W+/).indexOf(k) >= 0);
    if (leaked.length) bad.push('RAWKEY(' + leaked.slice(0, 4).join(',') + ')');

    if (bad.length) {
      let layout = '?';
      try { layout = sb.resolveLayout(comp, dsl); } catch (e) { layout = 'ERR'; }
      const rec = { comp, label, layout, bad: bad.join(' | '), vis: vis.slice(0, 60) };
      perComp.push(rec);
      if (bad.some((b) => b.startsWith('THROW'))) stats.THROW.push(rec);
      if (bad.some((b) => b === 'BLANK' || b === 'ERRBOX')) stats.BLANK.push(rec);
      if (bad.some((b) => b.startsWith('DEAD'))) stats.DEAD.push(rec);
      if (bad.some((b) => b.startsWith('RAWKEY'))) stats.RAWKEY.push(rec);
    }
  }
}

function flattenKeys(o, out) {
  out = out || {};
  if (Array.isArray(o)) { o.forEach((x) => flattenKeys(x, out)); return out; }
  if (o && typeof o === 'object') { Object.keys(o).forEach((k) => { out[k] = 1; flattenKeys(o[k], out); }); }
  return out;
}

console.log('=== zxwidget 退化输入探针 ===');
console.log('巡检用例:', stats.total, ' 组件:', comps.length);
console.log('');
console.log('BLANK  空白/渲染错误 :', stats.BLANK.length);
console.log('DEAD   有数据却打空态 :', stats.DEAD.length);
console.log('RAWKEY 键名当内容     :', stats.RAWKEY.length);
console.log('THROW  抛异常         :', stats.THROW.length);
console.log('');
const byClass = { BLANK: stats.BLANK, DEAD: stats.DEAD, RAWKEY: stats.RAWKEY };
for (const k of ['BLANK', 'DEAD', 'RAWKEY']) {
  const arr = byClass[k];
  if (!arr.length) continue;
  console.log('--- ' + k + ' 明细（前 40 条）---');
  arr.slice(0, 40).forEach((r) => console.log('  ' + r.comp.padEnd(16) + r.label.padEnd(22) + String(r.layout).padEnd(12) + r.bad + '   text="' + r.vis + '"'));
  if (arr.length > 40) console.log('  … 其余 ' + (arr.length - 40) + ' 条');
  console.log('');
}
/* 按组件聚合：哪些组件最脆弱 */
const cnt = {};
perComp.forEach((r) => { cnt[r.comp] = (cnt[r.comp] || 0) + 1; });
const top = Object.keys(cnt).sort((a, b) => cnt[b] - cnt[a]).slice(0, 25);
if (top.length) {
  console.log('--- 最脆弱组件（缺陷变体数）---');
  top.forEach((c) => console.log('  ' + c.padEnd(18) + cnt[c]));
}
const total = stats.BLANK.length + stats.DEAD.length + stats.RAWKEY.length;
process.exit(total ? 1 : 0);

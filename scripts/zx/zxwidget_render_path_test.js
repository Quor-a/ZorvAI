// zxwidget 挂载路径探针：用桩 DOM 加载完整运行时（含 render/state/$t），
// 直接调 window.ZorvAI.render(p)，检查 body 是否真的被填充。
// 这补的是现有冒烟脚本的盲区——它们只调 compile()，从不走 render() 挂载。
// 用法：node scripts/zx/zxwidget_render_path_test.js
const path = require('path');
const fs = require('fs'), vm = require('vm');
const ASSETS = path.resolve(__dirname, '../../app/src/main/assets') + '/';
const RUNTIME = 'zx_card_runtime.html';
const html = fs.readFileSync(ASSETS + RUNTIME, 'utf8');
const code = [...html.matchAll(/<script(?![^>]*application\/json)[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1])[0];
const contracts = JSON.parse(fs.readFileSync(ASSETS + 'zx_contracts.json', 'utf8'));

function mk(tag) {
  const n = { tagName: tag, children: [], attrs: {}, style: {}, _html: '', _text: '', className: '', id: '', dataset: {},
    classList: { toggle() {}, add() {}, remove() {} },
    appendChild(c) { this.children.push(c); return c; }, insertBefore(c) { this.children.unshift(c); return c; },
    remove() {}, setAttribute(k, v) { this.attrs[k] = v; }, getAttribute(k) { return this.attrs[k]; },
    addEventListener() {}, querySelector() { return null; },
    set innerHTML(v) { this._html = v; if (v === '') this.children = []; }, get innerHTML() { return this._html; },
    set textContent(v) { this._text = v; }, get textContent() { return this._text; },
    get nextSibling() { return null; }, get firstChild() { return this.children[0] || null } };
  return n;
}
const byId = {};
['stream','inp','send','dot','title','model','cnt','btnRaw','btnNew','body','c','t','rawBtn','zx-contracts','app']
  .forEach(i => { byId[i] = mk('div'); byId[i].id = i; });
byId['zx-contracts'].textContent = JSON.stringify(contracts);
byId['inp'].style = {};

const sb = { document: { createElement: mk, createElementNS: (ns, t) => mk(t), getElementById: i => byId[i] || null,
    documentElement: mk('html'), addEventListener() {} },
  window: {}, navigator: {}, requestAnimationFrame: f => f(), JSON, Math, Object, Array, String, Number, Boolean, RegExp, Error, parseInt, parseFloat, isFinite, console, setTimeout };
vm.createContext(sb);
vm.runInContext(code, sb);

function deepCount(n) { let c = 1; (n.children || []).forEach(x => { c += deepCount(x); }); return c; }
function collectText(node, out) {
  if (!node) return out;
  if (node._html) out.push(String(node._html).replace(/<[^>]*>/g, ' '));
  if (node._text) out.push(String(node._text));
  (node.children || []).forEach(c => collectText(c, out));
  return out;
}

const CASES = [
  ['zx_table',   { title: '测试表', columns: ['项目','金额'], rows: [['服务器','1200'],['域名','80']] }],
  ['zx_list',    { items: [{ n: '任务一', v: 12 }, { n: '任务二', v: 34 }] }],
  ['zx_metrics', { items: [{ n: '指标A', v: 42 }, { n: '指标B', v: 7 }] }],
  ['zx_compare', { items: [{ n: '方案A', v: 92, d: '成本低' }, { n: '方案B', v: 78, d: '扩展好' }] }],
  ['zx_chart',   { series: [{ name: 'CPU', data: [10,20,30,40] }] }],
  ['zx_gantt',   { tasks: [{ title: '部署', s: 0, e: 4 }, { title: '联调', s: 4, e: 8 }] }],
];

let fails = 0;
console.log('=== ZorvAI.render 挂载路径探针 ===');
for (const [comp, dsl] of CASES) {
  try {
    const body = byId['body'];
    body.children = []; body._html = ''; body._text = '';
    const p = { component: comp, title: '探针', dsl, dark: false };
    sb.window.ZorvAI.render(p);
    const cnt = deepCount(body);
    const txt = collectText(body, []).join(' ').replace(/\s+/g, ' ').trim();
    const ok = cnt > 1 && txt.length > 0;
    console.log(`${ok ? 'OK ' : 'FAIL'} ${comp}  body_nodes=${cnt}  textLen=${txt.length}  sample="${txt.slice(0, 40)}"`);
    if (!ok) fails++;
  } catch (e) {
    console.log(`THROW ${comp} -> ${e.message}`);
    fails++;
  }
}
console.log(fails ? `\nFAIL ${fails} 个组件 render 挂载失败` : '\nPASS render 挂载路径全通');
process.exit(fails ? 1 : 0);

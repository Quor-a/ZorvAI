// zxwidget 脏输入探针：用「模型真实会产出的不规范 DSL」打 ZorvAI.render，
// 看哪些组件画空/画崩。现有冒烟脚本用的是 zx_contracts.json 干净 sample，
// 给的是假绿；本脚本专打「AI 写的不规范」那一态。
// 用法：node scripts/zx/zxwidget_messy_input_test.js
const path = require('path');
const fs = require('fs'), vm = require('vm');
const ASSETS = path.resolve(__dirname, '../../app/src/main/assets') + '/';
const html = fs.readFileSync(ASSETS + 'zx_card_runtime.html', 'utf8');
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

// 模型真实会产出的「不规范」输入
const MESSY = [
  // 数据多包一层 data:
  ['zx_list', { data: { items: [{ n: '任务一', v: 12 }, { n: '任务二', v: 34 }] } }, ['任务一','任务二']],
  // 字段名全错（业务词），靠 harvest
  ['zx_metrics', { items: [{ city: '汕头', temp: 29 }, { city: '广州', temp: 31 }] }, ['汕头','29','广州','31']],
  // dsl 是字符串而非对象（模型常把整段 JSON 当字符串传）
  ['zx_table', JSON.stringify({ title:'预算表', columns:['项目','金额'], rows:[['服务器','1200'],['域名','80']] }), ['服务器','1200']],
  // 主数组嵌套错位：items 藏在 content 里
  ['zx_list', { content: { items: [{ n:'甲',v:1 },{ n:'乙',v:2 }] } }, ['甲','乙']],
  // items 是对象而不是数组
  ['zx_list', { items: { '0':{n:'甲',v:1}, '1':{n:'乙',v:2} } }, ['甲','乙']],
  // 只有表头没有数据行（半截输出）
  ['zx_table', { columns:['项目','金额'] }, []],
  // 组件名大小写/前缀错（模型幻觉 zx_Line）
  ['zx_Line', { items:[{n:'a',v:1}] }, []],
  // 完全空 dsl
  ['zx_list', {}, []],
  // 数组元素不是对象而是字符串
  ['zx_list', { items:['任务一','任务二'] }, ['任务一','任务二']],
  // 多系列图表字段名错（series→lines, data→points）
  // 注：图表数值渲染为 SVG 几何（折线+圆点+坐标轴刻度），不会出现在文本里，
  // 所以这里只断言 series 名出现（修复后 lines→series 映射生效，图例可见 CPU）。
  ['zx_chart', { lines:[{ name:'CPU', points:[10,20,30] }] }, ['CPU']],
];

let fails = 0;
console.log('=== zxwidget 脏输入探针（模型不规范输出）===');
for (const [comp, dsl, want] of MESSY) {
  try {
    const body = byId['body'];
    body.children = []; body._html = ''; body._text = '';
    // ZxWebCard.buildHtml 在原生侧把 dsl 规一成 JSONObject；这里模拟：对象原样、字符串解析
    let dslObj = dsl;
    if (typeof dsl === 'string') { try { dslObj = JSON.parse(dsl); } catch(e) { dslObj = { _raw: dsl }; } }
    const p = { component: comp, title: '探针', dsl: dslObj, dark: false };
    sb.window.ZorvAI.render(p);
    const cnt = deepCount(body);
    const txt = collectText(body, []).join(' ').replace(/\s+/g, ' ').trim();
    // 期望数据词必须出现，否则算「画了外壳没画数据」= 用户看到的那一态
    const miss = (want || []).filter(w => txt.indexOf(String(w)) < 0);
    const blank = cnt <= 1 || txt.length < 2;
    const bad = blank || miss.length > 0;
    let note = blank ? '空白/空壳' : (miss.length ? '缺数据词 ' + JSON.stringify(miss) : 'OK');
    console.log(`${bad ? 'FAIL' : 'ok  '} ${comp.padEnd(11)} nodes=${cnt} textLen=${txt.length} -> ${note}`);
    if (bad) fails++;
  } catch (e) {
    console.log(`THROW ${comp} -> ${e.message}`);
    fails++;
  }
}
console.log(fails ? `\nFAIL ${fails} 个脏输入态渲染失败/画空` : '\nPASS 脏输入也都能兜住');
process.exit(fails ? 1 : 0);

// zx 运行时编译器冒烟（全 477 组件）
// // 对 477 个 zx 组件逐个编译渲染：有示例 DSL 用示例，没有的按契约合成数据。
// 校验「深度非空」（递归数后代元素），避免外层盒子有内容、里面其实是空的。
//
// 用法（在仓库根目录执行）：
//   node scripts/zx/zx_runtime_smoke.js [运行时文件名]
// 例：
//   node scripts/zx/zx_runtime_smoke.js                       # 默认测 zx_chat_runtime.html（第三个对话框）
//   node scripts/zx/zx_runtime_smoke.js zx_card_runtime.html  # 测卡片运行时
//
// 依赖：仅 node 内置模块（用桩 DOM 在 node 里跑运行时的 JS，不需要 WebView/浏览器）。
const path = require('path');
const ASSETS = path.resolve(__dirname, '../../app/src/main/assets') + '/';

// 冒烟 v2：有 sample 用 sample；无 sample 按契约合成数据，覆盖全部 477 个组件的真实渲染路径
const fs = require('fs'), vm = require('vm');
const RUNTIME = process.argv[2] || 'zx_chat_runtime.html';
const html = fs.readFileSync(ASSETS + RUNTIME, 'utf8');
const code = [...html.matchAll(/<script(?![^>]*application\/json)[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1])[0];
const contracts = JSON.parse(fs.readFileSync(ASSETS + 'zx_contracts.json', 'utf8'));
function mk(tag) { const n = { tagName: tag, children: [], attrs: {}, style: {}, _html: '', _text: '', className: '', id: '', dataset: {},
  classList: { toggle() {}, add() {}, remove() {} }, appendChild(c) { this.children.push(c); return c; }, insertBefore(c) { this.children.unshift(c); return c; },
  remove() {}, setAttribute(k, v) { this.attrs[k] = v; }, getAttribute(k) { return this.attrs[k] }, addEventListener() {}, querySelector() { return null; },
  set innerHTML(v) { this._html = v; }, get innerHTML() { return this._html; }, set textContent(v) { this._text = v; }, get textContent() { return this._text; },
  get nextSibling() { return null; }, get firstChild() { return this.children[0] || null } }; return n; }
const byId = {};
/* 两个运行时用到的元素 id 取并集——多给的桩无害（getElementById 只返回桩对象） */
['stream','inp','send','dot','title','model','cnt','btnRaw','btnNew','body','c','t','rawBtn','zx-contracts','app']
  .forEach(i => { byId[i] = mk('div'); byId[i].id = i; });
byId['zx-contracts'].textContent = JSON.stringify(contracts); byId['inp'].style = {};
const sb = { document: { createElement: mk, createElementNS: (ns, t) => mk(t), getElementById: i => byId[i] || null, documentElement: mk('html'), addEventListener() {} },
  window: {}, navigator: {}, requestAnimationFrame: f => f(), JSON, Math, Object, Array, String, Number, Boolean, RegExp, Error, parseInt, parseFloat, isFinite, console, setTimeout };
vm.createContext(sb); vm.runInContext(code, sb);

/* ── 按契约合成 DSL ── */
const TXT = ['n','t','k','label','title','name','text','q','head','cap','caption','l','f'];
const NUM = ['v','value','val','cnt','count','num','amount','score','p','max','min','sec','dead','ratio','total','cost'];
function synthVal(key, i) {
  if (TXT.includes(key)) return '项目' + (i + 1);
  if (NUM.includes(key)) return (i + 1) * 10;
  if (['d','desc','detail','why','note','body','content','msg','summary','sub'].includes(key)) return '这是说明文字';
  if (['s','from'].includes(key)) return i;
  if (['e','to'].includes(key)) return i + 4;
  if (['at','time','when','ts','date','due','last','next'].includes(key)) return '今天 10:0' + i;
  if (['st','status','state','phase','level','lv'].includes(key)) return ['done','run','todo'][i % 3];
  if (['who','by','user','owner','author','assignee'].includes(key)) return '张三';
  if (['hex','color'].includes(key)) return '#5046E4';
  if (['unit','u'].includes(key)) return '%';
  if (['open','auto','on','must','me','done','checked','onDone'].includes(key)) return true;
  if (['id','kind','type'].includes(key)) return ['a','b','c'][i % 3];
  if (['e','icon','emoji','i'].includes(key)) return '★';
  return '值' + (i + 1);
}
function synthItem(fields, i) {
  const it = {};
  const cands = ['n','t','k','v','d','s','e','at','st','who','u','label','cnt','p','max','id','kind','l'];
  cands.forEach(k => { if (!fields.length || fields.includes(k)) it[k] = synthVal(k, i); });
  return it;
}
const CONT = ['zx_tabs','zx_accordion','zx_split','zx_carousel','zx_grid','zx_master','zx_stagger','zx_stack'];
function synthDsl(c) {
  const ct = contracts.contracts[c] || {}, fields = ct.fields || [], arrays = ct.arrays || [];
  const dsl = { type: c };
  arrays.forEach(a => {
    if (a === 'children' || a === 'kids') {
      dsl[a] = [{ type: 'zx_list', items: [synthItem([], 0), synthItem([], 1)] }, { type: 'zx_metrics', items: [{ k: '指标', v: 42 }] }];
    } else if (a === 'fields') {
      dsl[a] = [{ k: '姓名', t: 'text' }, { k: '年龄', t: 'number' }];
    } else if (a === 'columns') {
      dsl[a] = ['列A', '列B', '列C'];
    } else if (a === 'cols') {
      dsl[a] = [{ n: '待办', items: [{ n: '任务一' }] }, { n: '进行中', items: [{ n: '任务二' }] }];
    } else if (a === 'nodes' || a === 'people' || a === 'levels') {
      dsl[a] = [{ n: '根节点', children: [{ n: '子节点' }] }, { n: '另一个' }];
    } else {
      dsl[a] = [synthItem(fields, 0), synthItem(fields, 1), synthItem(fields, 2)];
    }
  });
  if (CONT.includes(c) && !dsl.items) dsl.items = [{ type: 'zx_list', items: [{ n: '子项' }] }];
  fields.forEach(f => {
    if (dsl[f] !== undefined || ['n','t','k','v'].includes(f) === false && arrays.includes(f)) return;
    if (arrays.includes(f)) return;
    if (dsl[f] === undefined) dsl[f] = synthVal(f, 0);
  });
  return dsl;
}

let ok = 0, empty = 0, fail = 0, synth = 0;
const failures = [], dist = {};
for (const c of contracts.components) {
  const ct = contracts.contracts[c] || {};
  const hasSample = !!ct.sample;
  const dsl = hasSample ? ct.sample : (synth++, synthDsl(c));
  try {
    const out = vm.runInContext('compile(' + JSON.stringify(c) + ',' + JSON.stringify(dsl) + ',{interact:function(){}})', sb);
    /* 深度非空：递归统计后代元素数，避免「外层盒子有、内容空」 */
    function deepCount(n) { let c = 1; (n.children || []).forEach(x => { c += deepCount(x); }); return c; }
    const cnt = deepCount(out);
    const l = vm.runInContext('resolveLayout(' + JSON.stringify(c) + ',' + JSON.stringify(dsl) + ')', sb);
    dist[l] = (dist[l] || 0) + 1;
    if (cnt > 1) ok++; else { empty++; failures.push(c + ' [' + l + '] 深度空 (sample=' + (hasSample ? 'Y' : 'N') + ')'); }
  } catch (e) { fail++; failures.push(c + ' -> ' + e.message); }
}
console.log('=== 冒烟 v2（含契约合成数据）===');
console.log('组件总数: ' + contracts.components.length + '（其中无 sample、走合成: ' + synth + '）');
console.log('渲染有内容: ' + ok);
console.log('深度为空: ' + empty);
console.log('抛错: ' + fail);
if (failures.length) console.log('问题组件:\n  ' + failures.join('\n  '));
console.log('\n=== 布局分布 ===');
Object.keys(dist).sort((a, b) => dist[b] - dist[a]).forEach(k => console.log('  ' + k + ': ' + dist[k]));

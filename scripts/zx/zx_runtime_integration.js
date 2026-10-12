// zx 运行时桥接与交互集成测试
// // 验证宿主桥（ZorvAI.chat.* 的 reset/load/user/beginAi/delta/reasoning/tool/endAi）
// 与交互回传（点击渲染出的元素 → ZorvAIBridge.interact 的结构化载荷）。
//
// 用法（在仓库根目录执行）：
//   node scripts/zx/zx_runtime_integration.js [运行时文件名]
// 例：
//   node scripts/zx/zx_runtime_integration.js                       # 默认测 zx_chat_runtime.html（第三个对话框）
//   node scripts/zx/zx_runtime_integration.js zx_card_runtime.html  # 测卡片运行时
//
// 依赖：仅 node 内置模块（用桩 DOM 在 node 里跑运行时的 JS，不需要 WebView/浏览器）。
const path = require('path');
const ASSETS = path.resolve(__dirname, '../../app/src/main/assets') + '/';

// 集成测试：宿主桥投影 + zx 卡片渲染 + 点击交互回传
const fs = require('fs'), vm = require('vm');
const RUNTIME = process.argv[2] || 'zx_chat_runtime.html';
const html = fs.readFileSync(ASSETS + RUNTIME, 'utf8');
const code = [...html.matchAll(/<script(?![^>]*application\/json)[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1])[0];
const contracts = JSON.parse(fs.readFileSync(ASSETS + 'zx_contracts.json', 'utf8'));

/* 记录型桩 DOM：能触发 click 监听 */
function mk(tag) {
  const n = { tagName: tag, children: [], attrs: {}, style: {}, _html: '', _text: '', className: '', id: '', dataset: {},
    _ls: {}, classList: { toggle() {}, add() {}, remove() {} },
    appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
    insertBefore(c) { c.parentNode = this; this.children.unshift(c); return c; },
    remove() {}, setAttribute(k, v) { this.attrs[k] = v; }, getAttribute(k) { return this.attrs[k] },
    addEventListener(t, f) { (this._ls[t] = this._ls[t] || []).push(f); },
    querySelector() { return null; }, querySelectorAll() { return []; },
    set innerHTML(v) { this._html = v; this.children = []; }, get innerHTML() { return this._html; },
    set textContent(v) { this._text = v; }, get textContent() { return this._text; },
    get nextSibling() { return null; }, get firstChild() { return this.children[0] || null },
    /* 事件冒泡：从自身向上逐级触发 click 监听，和浏览器一致 */
    click() {
      let n = this;
      while (n) { (n._ls.click || []).forEach(f => f({ stopPropagation() { n = null; } })); n = n.parentNode; }
    } };
  /* 真实 DOM 的 parentNode 不可枚举；这里对齐，否则 JSON.stringify 会循环引用 */
  Object.defineProperty(n, 'parentNode', { value: null, writable: true, enumerable: false, configurable: true });
  n.classList = {
    toggle(c, force) {
      const has = (n.className || '').split(/\s+/).includes(c);
      const want = force === undefined ? !has : !!force;
      if (want && !has) n.className = (n.className ? n.className + ' ' : '') + c;
      if (!want && has) n.className = (n.className || '').split(/\s+/).filter(x => x && x !== c).join(' ');
      return want;
    }, add(c) { n.classList.toggle(c, true); }, remove(c) { n.classList.toggle(c, false); },
    contains(c) { return (n.className || '').split(/\s+/).includes(c); }
  };
  return n;
}
const byId = {};
['stream','inp','send','dot','title','model','cnt','btnRaw','btnNew','zx-contracts','app'].forEach(i => { byId[i] = mk('div'); byId[i].id = i; });
byId['zx-contracts'].textContent = JSON.stringify(contracts);
const bridgeCalls = [];
const sb = { document: { createElement: mk, createElementNS: (ns, t) => mk(t), getElementById: i => byId[i] || null, documentElement: mk('html'), addEventListener() {} },
  window: {}, navigator: {}, requestAnimationFrame: f => f(), JSON, Math, Object, Array, String, Number, Boolean, RegExp, Error, parseInt, parseFloat, isFinite, console, setTimeout };
vm.createContext(sb);
sb.window = sb;   /* 浏览器里 window 即全局；沙箱里手动对齐，window.ZorvAI 才能当裸标识符用 */
vm.runInContext(code, sb);
vm.runInContext('window.ZorvAIBridge={send:function(t){},stop:function(){},newChat:function(){},interact:function(j){ window.__interact=j; }}', sb);

const R = (expr) => vm.runInContext(expr, sb);
function assert(cond, msg) { console.log((cond ? '  ✓ ' : '  ✗ ') + msg); if (!cond) process.exitCode = 1; }

console.log('=== 1. 宿主桥 API 投影 ===');
R('ZorvAI.chat.reset()');
R('ZorvAI.chat.title("测试会话")');
R('ZorvAI.chat.model("gpt-5")');
assert(byId['title']._text === '测试会话', '标题投影到顶栏: ' + byId['title']._text);
assert(byId['model']._text === 'gpt-5', '模型名投影到顶栏: ' + byId['model']._text);

console.log('\n=== 2. 消息流 + 流式 + 思考过程 ===');
R('ZorvAI.chat.user("帮我看看CPU")');
R('ZorvAI.chat.beginAi("m1")');
R('ZorvAI.chat.reasoning("m1","先查监控数据")');
R('ZorvAI.chat.delta("m1","## 报告\\n\\n**CPU 72%**，见下方卡片。")');
R('ZorvAI.chat.tool("m1","zx_web_card",\'{"component":"zx_metrics","dsl":{"items":[{"k":"CPU","v":"72%"},{"k":"内存","v":"88%"}]}}\',"ok","已渲染 2 项指标",1200)');
R('ZorvAI.chat.endAi("m1")');
const streamKids = byId['stream'].children.length;
assert(streamKids >= 2, '消息流入流: ' + streamKids + ' 条');
const allHtml = JSON.stringify(byId['stream']);
assert(allHtml.indexOf('CPU 72%') >= 0, 'Markdown 粗体渲染进流');
assert(allHtml.indexOf('先查监控数据') >= 0, '思考过程渲染进流');
const zxWraps = findAll(byId['stream'], 'zx-wrap', []);
assert(zxWraps.length === 1, 'zx_web_card 渲染出 1 个动态交互界面（而非 JSON 行）');
assert(zxWraps.length && findAll(zxWraps[0], 'met', []).length === 2, '界面里渲染出 2 个指标块');

console.log('\n=== 3. zx 卡片点击 → 宿主交互回传 ===');
/* 找到流里第一个 .met 元素（metrics 卡片的指标块）并点击 */
/* SVG 元素没有 className，按 tagName 匹配；普通元素按 className 匹配 */
function findAll(node, cls, out) {
  out = out || [];
  (node.children || []).forEach(c => {
    const byCls = (c.className || '').split(/\s+/).includes(cls);
    const byTag = c.tagName === cls;
    if (byCls || byTag) out.push(c);
    findAll(c, cls, out);
  });
  return out;
}
const mets = findAll(byId['stream'], 'met', []);
assert(mets.length === 2, '渲染出 2 个指标块: ' + mets.length);
mets[0].click();
const payload = R('window.__interact');
assert(!!payload, '点击指标块触发 ZorvAIBridge.interact');
const pj = payload ? JSON.parse(payload) : {};
assert(pj.component === 'zx_metrics', '回传带 component: ' + pj.component);
assert(pj.action === 'metric', '回传带 action: ' + pj.action);
assert(pj.key === 'CPU' && pj.value === '72%', '回传带 key/value: ' + pj.key + '=' + pj.value);
assert(pj.source === 'chat', '回传带 source=chat');
console.log('  回传全文: ' + payload);

console.log('\n=== 4. 更多布局的点击回传 ===');
const cases = [
  ['zx_list', { items: [{ n: '任务A', d: '详情' }] }, 'li', 'item'],
  ['zx_choice', { options: ['同意', '拒绝'] }, 'btn', 'choose'],
  ['zx_gauge', { v: 62, max: 100 }, 'pb', 'gauge'],
  ['zx_dial', { v: 62, max: 100, label: '音量' }, 'hero', 'value'],
  ['zx_tree', { nodes: [{ n: '根' }] }, 'tn', 'node'],
  ['zx_histogram', { bins: [{ n: '0-2', v: 12 }, { n: '2-4', v: 31 }] }, 'rect', 'bar'],
];
for (const [comp, dsl, cls, wantAction] of cases) {
  R('window.__interact=null');
  const node = R('compile(' + JSON.stringify(comp) + ',' + JSON.stringify(dsl) + ',{interact:function(a,p){window.ZorvAIBridge.interact(JSON.stringify(Object.assign({action:a,component:' + JSON.stringify(comp) + ',source:"chat"},p||{})))}})');
  const targets = findAll(node, cls, []);
  if (!targets.length) { console.log('  ✗ ' + comp + ' 找不到 .' + cls + ' 可点元素'); process.exitCode = 1; continue; }
  targets[0].click();
  const got = R('window.__interact');
  const okp = got && JSON.parse(got).action === wantAction;
  console.log((okp ? '  ✓ ' : '  ✗ ') + comp + ' → .' + cls + ' 点击回传 action=' + (got ? JSON.parse(got).action : '无'));
  if (!okp) process.exitCode = 1;
}

console.log('\n=== 5. 忙态 / 停止 / 新对话 ===');
R('ZorvAI.chat.setBusy(true)');
assert(byId['send'].classList.contains('stop'), '忙态发送按钮加 stop 类');
assert(byId['send']._text === '■', '忙态按钮图标变 ■: ' + byId['send']._text);
R('ZorvAI.chat.setBusy(false)');
assert(!byId['send'].classList.contains('stop'), '取消忙态移除 stop 类');
R('ZorvAI.chat.error("网络错误")');
assert(JSON.stringify(byId['stream']).indexOf('网络错误') >= 0, '错误消息入流');
R('ZorvAI.chat.reset()');
const afterReset = findAll(byId['stream'], 'msg', []);
assert(afterReset.length === 0, 'reset 清空全部消息节点（剩余欢迎占位）: ' + afterReset.length);
console.log('\n完成。exitCode=' + (process.exitCode || 0));

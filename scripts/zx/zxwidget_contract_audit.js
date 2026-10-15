/*
 * zxwidget_contract_audit.js
 * 全面排查：477 个契约的「声明 fn → 实际布局实现 → 推断布局 → 真实渲染」覆盖矩阵。
 * 用真实运行时编译器（vm + DOM stub）跑契约自带 sample，判定每一类缺陷。
 * 用法：node scripts/zx/zxwidget_contract_audit.js [--comp=zx_xxx] [--all]
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..', '..');
const HTML = path.join(ROOT, 'app/src/main/assets/zx_card_runtime.html');
const CONTRACTS = path.join(ROOT, 'app/src/main/assets/zx_contracts.json');

/* ---- 最小 DOM stub（与 degrade_probe 同构） ---- */
function mk(tag){
  const n = {
    tagName: tag, children: [], attrs: {}, style: {},
    _html: '', _text: '', className: '', id: '', dataset: {},
    classList: { toggle(){}, add(){}, remove(){}, contains(){ return false; } },
    appendChild(c){ this.children.push(c); return c; },
    insertBefore(c){ this.children.unshift(c); return c; },
    remove(){},
    setAttribute(k,v){ this.attrs[k]=v; },
    getAttribute(k){ return this.attrs[k]; },
    addEventListener(){},
    querySelector(){ return null; },
    querySelectorAll(){ return []; },
    set innerHTML(v){ this._html = String(v); if(v==='') this.children=[]; },
    get innerHTML(){ return this._html; },
    set textContent(v){ this._text = String(v); },
    get textContent(){ return this._text; },
    get nextSibling(){ return null; },
    get firstChild(){ return this.children[0]||null; }
  };
  return n;
}
function buildSandbox(){
  const byId = {};
  ['body','c','t','rawBtn','zx-contracts'].forEach(i=>{ byId[i]=mk('div'); byId[i].id=i; });
  // 注入契约 JSON 到 zx-contracts 节点
  const contractsRaw = fs.readFileSync(CONTRACTS,'utf8');
  byId['zx-contracts']._text = contractsRaw;
  const sb = {
    document: {
      createElement: mk,
      createElementNS: (_n,t)=>mk(t),
      getElementById: i=>byId[i]||null,
      documentElement: mk('html'),
      addEventListener(){}, querySelector(){ return null; }, body: mk('body')
    },
    window: {}, navigator: {},
    requestAnimationFrame: f=>f(),
    JSON, Math, Object, Array, String, Number, Boolean, RegExp, Error,
    parseInt, parseFloat, isFinite, isNaN, console, setTimeout, Date
  };
  return sb;
}

/* ---- 可见文本 + 几何图形提取（形状感知，避免把 SVG 渲染误判为空白） ---- */
const SHAPE_TAGS = ['svg','rect','circle','line','path','polygon','polyline','ellipse','text','img','canvas','iframe'];
function analyze(n, acc){
  if(!n) return acc;
  const tag = String(n.tagName||'').toLowerCase();
  if(SHAPE_TAGS.indexOf(tag)>=0) acc.shapes++;
  const raw = String(n._html||'') + ' ' + String(n._text||'');
  acc.text += ' ' + raw.replace(/<[^>]*>/g,' ');
  (n.children||[]).forEach(c=>analyze(c,acc));
  return acc;
}
function visibleText(n){
  let s = (n._html||'') + ' ' + (n._text||'');
  (n.children||[]).forEach(c=>{ s += ' ' + visibleText(c); });
  return s;
}
function flattenKeys(n, acc){
  acc = acc||{};
  const t = (n._html||'');
  // 抽取 kv-k / tn-t 类标签里的单词
  const m = t.match(/kv-k[^>]*>([^<]*)</g);
  if(m) m.forEach(x=>{ const w=(x.replace(/.*>([^<]*)</,'$1')).trim(); if(w) acc[w]=1; });
  (n.children||[]).forEach(c=>flattenKeys(c,acc));
  return acc;
}
function hasRealData(dsl){
  let found=false;
  (function walk(o){
    if(found) return;
    if(Array.isArray(o)){ if(o.length) found=true; o.forEach(walk); }
    else if(o && typeof o==='object'){ for(const k in o){ if(k==='type') continue; const v=o[k]; if(typeof v==='string'&&v.trim()) found=true; else if(typeof v==='number') found=true; else if(typeof v==='object') walk(v); } }
  })(dsl);
  return found;
}
// 明确空态占位短语（注意：不能用单字「空」，否则误伤「清空会话」「空气滤芯」）
const EMPTY_PHRASES = ['没有网格数据','暂无节点','没有数值序列','没有矩阵数据','没有节点',
  '该组件没有可渲染字段','没有可渲染字段','没有数据','暂无数据','没有列表项','容器','没有子节点'];
function classify(comp, dsl, node, threw){
  if(threw) return 'THROW';
  const a = analyze(node, {shapes:0, text:''});
  const text = a.text.replace(/\s+/g,' ').trim();
  const realData = hasRealData(dsl);
  const deadHit = EMPTY_PHRASES.find(p=>text.indexOf(p)>=0);
  if(deadHit && realData) return 'DEAD('+deadHit+')';
  // RAWKEY：输入字段名原样出现在可见文本里（仅当确有真实数据、且不是合法中文标签）
  const inKeys = Object.keys(flattenKeys(node,{}));
  const leaked = inKeys.filter(k=>k.length>=3 && /^[A-Za-z][A-Za-z0-9_]{2,}$/.test(k)
    && text.split(/\W+/).indexOf(k)>=0);
  if(leaked.length && realData) return 'RAWKEY('+leaked.slice(0,4).join(',')+')';
  if(a.shapes===0 && text.length < 2 && realData) return 'BLANK';
  if(a.shapes===0 && text.length < 2 && !realData) return 'EMPTY_OK';
  return 'OK';
}

/* ---- 运行 ---- */
const ALL = process.argv.includes('--all');
const compArg = (process.argv.find(a=>a.startsWith('--comp='))||'').replace('--comp=','');

const html = fs.readFileSync(HTML,'utf8');
// 取第一个非 JSON script（编译器段）
const scripts = [...html.matchAll(/<script(?![^>]*application\/json)[^>]*>([\s\S]*?)<\/script>/g)].map(m=>m[1]);
const code = scripts[0];
const sb = buildSandbox();
vm.createContext(sb);
vm.runInContext(code, sb);
const runtimeLayouts = Object.keys(sb.LAYOUTS||{});
const contracts = JSON.parse(fs.readFileSync(CONTRACTS,'utf8'));

const list = contracts.contracts ? Object.keys(contracts.contracts).map(k=>({name:k, ...contracts.contracts[k]}))
                                 : Object.keys(contracts).map(k=>({name:k, ...contracts[k]}));

let rows = [];
let noImpl = 0, fnImplemented = 0;
for(const ct of list){
  if(compArg && ct.name!==compArg) continue;
  const fn = ct.fn;
  const fnImpl = (sb.LAYOUTS && sb.LAYOUTS[fn]) ? true : false;
  fnImpl ? fnImplemented++ : noImpl++;
  let inferred = 'NO_SAMPLE', threw=false, sampleClass='NO_SAMPLE', samplePresent = !!ct.sample;
  if(samplePresent){
    let node=null;
    try { node = sb.compile(ct.name, JSON.parse(JSON.stringify(ct.sample)), {interact(){}}); }
    catch(e){ threw=true; }
    inferred = sb.resolveLayout(ct.name, ct.sample);
    sampleClass = threw ? 'THROW' : classify(ct.name, ct.sample, node, threw);
  }
  rows.push({ name: ct.name, fn, fnImpl, inferred, samplePresent, sampleClass, ct });
}

/* ---- 输出 ---- */
console.log('=== zxwidget 契约全面审计 ===');
console.log('组件总数:', list.length, '| 运行时布局实现数:', runtimeLayouts.length);
console.log('声明 fn 有实现:', fnImplemented, '| 无实现(靠推断):', noImpl);
console.log('运行时布局原语:', runtimeLayouts.sort().join(', '));
console.log('');

// 缺陷在「正确 sample」上的分布（真实风险）
const bad = rows.filter(r=>r.samplePresent && ['DEAD','RAWKEY','BLANK','THROW'].some(x=>r.sampleClass.startsWith(x)));
console.log('--- 正确 sample 上仍出缺陷（真实 bug）:', bad.length, '---');
bad.forEach(r=>console.log('  '+r.name+'  fn='+r.fn+'  impl='+(r.fnImpl?'Y':'N')+'  inferred='+r.inferred+'  => '+r.sampleClass));

// 无实现且推断布局与声明 fn 不一致的（推断兜底风险面，仅统计有 sample 的）
const mismatch = rows.filter(r=>!r.fnImpl && r.samplePresent);
console.log('');
console.log('--- 声明 fn 无实现、靠 resolveLayout 推断（有 sample 可验证）:', mismatch.length, '---');
// 按推断布局分组统计无实现组件数
const byInfer = {};
mismatch.forEach(r=>{ byInfer[r.inferred]=(byInfer[r.inferred]||0)+1; });
Object.keys(byInfer).sort((a,b)=>byInfer[b]-byInfer[a]).forEach(k=>console.log('  inferred='+k+' : '+byInfer[k]+' 个组件'));

// 无 sample 的组件（无法校验渲染，且模型无示例可学 → 高风险盲区）
const noSample = rows.filter(r=>!r.samplePresent);
console.log('');
console.log('--- 无 sample 无法静态校验渲染（模型无示例，高风险盲区）:', noSample.length, '---');
noSample.forEach(r=>console.log('  '+r.name+'  fn='+(r.fn||'(空)')+'  impl='+(r.fnImpl?'Y':'N')+'  arrays='+JSON.stringify((r.ct&&r.ct.arrays)||[])));

/* ---- 合成输入压力测试：对无 sample 组件，按其 fields/arrays 合成典型输入，跑真实编译 ---- */
console.log('');
console.log('--- 无 sample 组件「合成典型输入」压力测试（验证不崩、不空白） ---');
const SCALAR_VAL = { n:'示例', t:'标题', v:42, k:'键', s:'起', e:'止', label:'标签', name:'名称',
  title:'标题', desc:'描述', status:'状态', time:'时间', value:'值', count:3, total:10,
  amount:99.5, price:19.9, ratio:0.5, src:'来源', date:'2026-10-16', level:'中', cur:100 };
function synth(comp){
  const ct = (contracts.contracts&&contracts.contracts[comp])||{};
  const dsl = { type: comp };
  (ct.fields||[]).forEach(f=>{ if(f==='type') return; dsl[f] = (SCALAR_VAL[f]!==undefined)?SCALAR_VAL[f]:'值'; });
  (ct.arrays||[]).forEach(a=>{
    if(a==='type') return;
    if(['edges','flows','links','b'].indexOf(a)>=0){ dsl[a]=[{from:0,to:1}]; return; }
    if(['steps','stages','stops','turns','msgs','trans','states','opts','sides','looks','acts','segs','themes','qs','recs','advice','open','docs','ev'].indexOf(a)>=0){ dsl[a]=[{n:'项1',v:1},{n:'项2',v:2}]; return; }
    dsl[a]=[{n:'项1',v:1},{n:'项2',v:2}];
  });
  // children 容器类
  if(/_tabs|_accordion|_split|_carousel|_grid|_stack|_master|_stagger/.test(comp) || ct.arrays.indexOf('children')>=0 || ct.arrays.indexOf('cards')>=0){
    dsl.children=[{type:'zx_note',title:'子卡片A'},{type:'zx_list',items:[{n:'甲',v:1}]}];
  }
  return dsl;
}
const synthBad = [];
noSample.forEach(r=>{
  const dsl = synth(r.name);
  let threw=false, node=null;
  try { node = sb.compile(r.name, dsl, {interact(){}}); }
  catch(e){ threw=true; }
  const a = threw ? {shapes:0,text:''} : analyze(node, {shapes:0,text:''});
  const text = a.text.replace(/\s+/g,' ').trim();
  const deadHit = EMPTY_PHRASES.find(p=>text.indexOf(p)>=0);
  let status = threw ? 'THROW' : (deadHit ? 'DEAD' : (a.shapes===0 && text.length<2 ? 'BLANK' : 'OK'));
  if(status!=='OK') synthBad.push({name:r.name, status, txt:text.slice(0,30)});
});
console.log('  合成测试异常数:', synthBad.length, '/', noSample.length);
synthBad.forEach(b=>console.log('  !! '+b.name+' => '+b.status+'  "'+b.txt+'"'));
if(!synthBad.length) console.log('  （全部合成输入均正常渲染，无崩/无空白）');

// 详细表
console.log('');
console.log('--- 全量明细 (name | fn | impl | inferred | sample | class) ---');
rows.forEach(r=>console.log([r.name,r.fn,r.fnImpl?'Y':'N',r.inferred,r.samplePresent?'Y':'-',r.sampleClass].join(' | ')));

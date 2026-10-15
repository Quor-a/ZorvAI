/*
 * zxwidget_contract_enrich.js
 * 全面排查后的加固：
 *  ① 给 104 个「无 sample」组件补形状感知的示例（模型无示例可学是渲染破图的根因）；
 *  ② 把死 fn（无对应 LAYOUTS 实现）锁成显式布局映射（resolveLayout 现已优先采用真实 fn）。
 * 每个写回的 sample 都先用真实运行时编译验证「不抛异常、有内容」，再落盘。
 * 用法：node scripts/zx/zxwidget_contract_enrich.js [--write]
 *  默认 dry-run 只打印计划；--write 才真正改写 zx_contracts.json。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..', '..');
const HTML = path.join(ROOT, 'app/src/main/assets/zx_card_runtime.html');
const CONTRACTS = path.join(ROOT, 'app/src/main/assets/zx_contracts.json');

/* ---- 最小 DOM stub ---- */
function mk(tag){
  const n = { tagName: tag, children: [], attrs: {}, style: {},
    _html: '', _text: '', className: '', id: '', dataset: {},
    classList: { toggle(){}, add(){}, remove(){}, contains(){ return false; } },
    appendChild(c){ this.children.push(c); return c; },
    insertBefore(c){ this.children.unshift(c); return c; },
    remove(){}, setAttribute(k,v){ this.attrs[k]=v; }, getAttribute(k){ return this.attrs[k]; },
    addEventListener(){}, querySelector(){ return null; }, querySelectorAll(){ return []; },
    set innerHTML(v){ this._html = String(v); if(v==='') this.children=[]; },
    get innerHTML(){ return this._html; },
    set textContent(v){ this._text = String(v); }, get textContent(){ return this._text; },
    get nextSibling(){ return null; }, get firstChild(){ return this.children[0]||null; } };
  return n;
}
function buildSandbox(){
  const byId = {};
  ['body','c','t','rawBtn','zx-contracts'].forEach(i=>{ byId[i]=mk('div'); byId[i].id=i; });
  byId['zx-contracts']._text = fs.readFileSync(CONTRACTS,'utf8');
  return {
    document: { createElement: mk, createElementNS:(_n,t)=>mk(t), getElementById:i=>byId[i]||null,
      documentElement: mk('html'), addEventListener(){}, querySelector(){ return null; }, body: mk('body') },
    window: {}, navigator: {}, requestAnimationFrame: f=>f(),
    JSON, Math, Object, Array, String, Number, Boolean, RegExp, Error,
    parseInt, parseFloat, isFinite, isNaN, console, setTimeout, Date
  };
}

/* ---- 形状感知合成器：按数组名生成「该布局期望的形态」 ---- */
const SCALAR_VAL = { title:'标题', name:'名称', label:'标签', content:'内容', desc:'描述', text:'文本',
  value:42, v:42, k:'键', s:'起', e:'止', count:3, total:10, num:3, amount:99.5, price:19.9,
  ratio:0.5, rate:0.85, percent:85, score:88, status:'正常', state:'启用', date:'2026-10-16',
  time:'09:30', datetime:'2026-10-16 09:30', url:'https://example.com', link:'https://example.com',
  level:'中', unit:'个', author:'作者', owner:'负责人', from:'起点', to:'终点', src:'来源', dst:'目标',
  remark:'备注', note:'说明', summary:'摘要', onPick:'', bind:'', n:3, cur:100, type:'类型' };

function numSeries(){ return [3,5,2,8,4,6,7,1]; }
function objItems(){ return [{name:'项一',value:10},{name:'项二',value:20}]; }
function nodeTree(){
  return [{id:'n1',label:'节点一',children:[{id:'n1a',label:'子节点'},{id:'n1b',label:'子节点二'}]},
          {id:'n2',label:'节点二'}];
}
function synthArray(a){
  switch(a){
    case 'series': case 'pts': case 'points': case 'data': case 'values':
    case 'line': case 'lines': case 'y': case 'ys': case 'vals': case 'trend':
    case 'history': case 'spectrum': case 'wave': case 'curve': case 'dist':
    case 'density': case 'bins': case 'hist': case 'x': case 'xs':
      return numSeries();
    case 'nodes': case 'people':
      return nodeTree();
    case 'edges': case 'flows': case 'links':
      return [{from:'n1',to:'n2'}];
    case 'tasks':
      return [{s:'09:00',e:'10:00',t:'任务一'},{s:'10:00',e:'11:30',t:'任务二'}];
    case 'days':
      return {'2026-10-16':['事项A'],'2026-10-17':['事项B']};
    case 'columns':
      return ['列A','列B'];
    case 'rows':
      return [{k:'行一',v:['低','高']},{k:'行二',v:['中','低']}];
    case 'options': case 'opts':
      return [{label:'选项一',value:'a'},{label:'选项二',value:'b'}];
    case 'steps': case 'stages': case 'stops': case 'turns': case 'trans':
    case 'states': case 'sides': case 'looks': case 'acts': case 'segs': case 'themes':
    case 'qs': case 'recs': case 'advice': case 'docs': case 'ev': case 'msgs':
      return [{title:'步骤一',at:'09:00'},{title:'步骤二',at:'10:00'}];
    case 'fields':
      return [{id:'f1',label:'字段一',type:'text'},{id:'f2',label:'字段二',type:'number'}];
    case 'cols': case 'columns2':
      return [{title:'待办',cards:[{title:'任务',desc:'描述'}]}];
    case 'cells':
      return [{x:0,y:0,v:'A'},{x:1,y:0,v:'B'},{x:0,y:1,v:'C'}];
    case 'children': case 'cards': case 'kids':
      return [{type:'zx_note',title:'子卡片A'},{type:'zx_list',items:[{name:'甲',value:1}]}];
    case 'tags': case 'labels': case 'marks':
      return ['标签一','标签二','标签三'];
    case 'levels':
      return [{name:'一级',value:5},{name:'二级',value:3}];
    case 'blocks': case 'groups': case 'slots':
      return [{name:'分组一',value:8},{name:'分组二',value:4}];
    case 'subs': case 'dials': case 'metrics':
      return [{name:'指标一',value:90},{name:'指标二',value:75}];
    case 'items': case 'entries': case 'records': case 'list': case 'subs2':
      return objItems();
    default:
      return objItems();
  }
}
function synthSample(name, ct){
  const dsl = { type: name };
  (ct.fields||[]).forEach(f=>{
    if(f==='type') return;
    dsl[f] = (SCALAR_VAL[f]!==undefined) ? SCALAR_VAL[f] : '示例';
  });
  (ct.arrays||[]).forEach(a=>{
    if(a==='type') return;
    dsl[a] = synthArray(a);
  });
  // 容器类：补 children 让 master/stagger/tabs/accordion 等能递归渲染
  if(/_tabs|_accordion|_split|_carousel|_grid|_stack|_master|_stagger|_board/.test(name)
     || (ct.arrays||[]).indexOf('children')>=0 || (ct.arrays||[]).indexOf('cards')>=0){
    dsl.children = [{type:'zx_note',title:'子卡片A'},{type:'zx_list',items:[{name:'甲',value:1}]}];
  }
  return dsl;
}

/* ---- 渲染验证（综合「子树元素/文本/SVG 形状」判定，避免把 div 网格误判空白） ---- */
const EMPTY_PHRASES = ['没有网格数据','暂无节点','没有数值序列','没有矩阵数据','没有节点',
  '该组件没有可渲染字段','没有可渲染字段'];
function analyze(node){
  let shapes=0, text='', elems=0;
  (function walk(n){
    if(!n) return;
    const h = n._html||'';
    const t = n._text||'';
    text += ' ' + t + ' ' + h.replace(/<[^>]+>/g,' ');
    shapes += (h.match(/<(rect|circle|line|path|polygon|polyline|ellipse|svg)\b/gi)||[]).length;
    (n.children||[]).forEach(function(c){ elems++; walk(c); });
  })(node);
  return { shapes, text: text.replace(/<[^>]+>/g,' ').replace(/\s+/g,' ').trim(), elems };
}

function main(){
  const html = fs.readFileSync(HTML,'utf8');
  const code = [...html.matchAll(/<script(?![^>]*application\/json)[^>]*>([\s\S]*?)<\/script>/g)].map(m=>m[1])[0];
  const sb = buildSandbox();
  vm.createContext(sb);
  vm.runInContext(code, sb);
  const validLayouts = Object.keys(sb.LAYOUTS||{});
  const data = JSON.parse(fs.readFileSync(CONTRACTS,'utf8'));
  const contracts = data.contracts;

  const addedSamples = [], pinnedFn = [], skippedBad = [];
  Object.keys(contracts).forEach(name=>{
    const ct = contracts[name];
    // ① 补 sample
    if(!ct.sample){
      const s = synthSample(name, ct);
      const a = (function(){ try{ const node = sb.compile(name, JSON.parse(JSON.stringify(s)), {interact(){}}); return analyze(node); }catch(e){ return {shapes:0,text:'',err:e.message}; } })();
      const deadHit = EMPTY_PHRASES.find(function(p){ return a.text.indexOf(p)>=0; });
      if(a.err || deadHit || (a.shapes===0 && a.text.length<2 && a.elems===0)){
        skippedBad.push({name, status:a.err?('THROW:'+a.err):'BLANK', txt:(a.text||'').slice(0,30)});
        return; // 合成出来仍是空白/抛错，不写（留给人工补真实 sample）
      }
      ct.sample = s;
      addedSamples.push(name);
    }
    // ② 锁 fn（仅当当前 fn 是死的，且 sample 推断出的布局是「强布局」；
    //    排除 list/chips/generic/note 这些兜底布局——强行锁死反而会把正确 DSL 拽进弱布局）
    const WEAK = ['list','chips','generic','note'];
    const inferred = sb.resolveLayout(name, ct.sample);
    const curFn = ct.fn;
    const curValid = curFn && validLayouts.indexOf(curFn)>=0;
    if(!curValid && inferred && validLayouts.indexOf(inferred)>=0 && WEAK.indexOf(inferred)<0){
      ct.fn = inferred;
      pinnedFn.push({name, from:curFn||'(空)', to:inferred});
    }
  });

  console.log('有效布局数:', validLayouts.length);
  console.log('新增 sample 组件数:', addedSamples.length, '/ 原无 sample 的 104');
  console.log('锁 fn 组件数:', pinnedFn.length);
  console.log('合成仍不佳、跳过（需人工补真实 sample）:', skippedBad.length);
  skippedBad.forEach(b=>console.log('  !! '+b.name+' => '+b.status+'  "'+b.txt+'"'));
  if(pinnedFn.length){
    console.log('');
    console.log('--- 锁 fn 样例（前 40）---');
    pinnedFn.slice(0,40).forEach(p=>console.log('  '+p.name+': '+(p.from)+' -> '+p.to));
  }

  if(process.argv.indexOf('--write')>=0){
    // 原子写：先写临时文件再 rename
    const tmp = CONTRACTS + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(data, null, 2));
    fs.renameSync(tmp, CONTRACTS);
    console.log('');
    console.log('已写入 zx_contracts.json（--write）');
  } else {
    console.log('');
    console.log('（dry-run，未写入。加 --write 真正改写）');
  }
}
main();

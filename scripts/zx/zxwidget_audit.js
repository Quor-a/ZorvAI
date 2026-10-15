/* zxwidget 路由 + 数据覆盖审计：
   1) 对 477 个契约组件，比较合约声明的目标布局 fn 与 resolveLayout 实际选中的布局；
      若 fn 是合法 LAYOUTS 键且不一致 → MISROUTE（OVERRIDE 缺口）。
   2) 对带 sample 且主数组>=3 的组件，测「主数组标签渲染覆盖率」，
      覆盖率<0.6 → DATA_LOSS（数据被吞/路由错导致主数组没画出来）。
   3) 对解析到 generic/note/objtree 但仍含>=3 对象主数组的 → SUSPECT_ROUTING（人工复核）。
   仅本地回归用。 */
const fs = require("fs");
const path = require("path");
const ROOT = "D:/Calw OS-project/QuroAI";
const HTML = path.join(ROOT, "app/src/main/assets/zx_card_runtime.html");
const CONTRACTS = path.join(ROOT, "app/src/main/assets/zx_contracts.json");

let html = fs.readFileSync(HTML, "utf8");
const sTag = html.indexOf("<script>", html.indexOf('id="zx-contracts"'));
const eTag = html.indexOf("</script>", sTag);
let script = html.slice(sTag + "<script>".length, eTag);
const cut = script.indexOf("var state={component");
if (cut >= 0) script = script.slice(0, cut);

function mk(t){const n={tag:t,children:[],className:"",_a:{},_t:"",_h:"",setAttribute(k,v){this._a[k]=v},getAttribute(k){return this._a[k]},appendChild(c){this.children.push(c);return c},addEventListener(){},classList:{add(){},remove(){},toggle(){}},style:{}};Object.defineProperty(n,"innerHTML",{get(){return this._h},set(v){this._h=String(v)}});Object.defineProperty(n,"textContent",{get(){return this._t},set(v){this._t=String(v)}});return n;}
global.document={createElement:mk,createElementNS:(_n,t)=>mk(t),getElementById:()=>mk("div"),documentElement:{classList:{toggle(){}}}};
const api=new Function(script+"\n;return {compile:compile,resolveLayout:resolveLayout,mainArr:mainArr,txt:txt,LAYOUTS:LAYOUTS,get ZX_CONTRACTS(){return ZX_CONTRACTS},set ZX_CONTRACTS(v){ZX_CONTRACTS=v}};")();
const json=JSON.parse(fs.readFileSync(CONTRACTS,"utf8"));
api.ZX_CONTRACTS=json.contracts;
const ctx={interact(){}};
const LAYOUT_KEYS=Object.keys(api.LAYOUTS);

function collectText(n,out){ if(!n)return; out.push(n._h||""); out.push(n._t||""); (n.children||[]).forEach(c=>collectText(c,out)); }

/* 这些布局是「视觉/图形」型，主数组并不以文本标签逐条枚举
   （graph 画节点不画边、calendar 显示日数字而非 mark、hero 显示单个数值、
    chart/gauge 画几何、media/qr/barcode/rule 画图形或纯装饰）。
   对它们跑「主数组标签文本覆盖率」必然误报，故跳过，交给 blankcheck 守「能否渲染」。 */
const NON_ENUM=new Set(["graph","calendar","hero","chart","gauge","qr","barcode","media","rule"]);

const misroute=[], dataloss=[], suspect=[];
const comps=json.components||[];
for(const comp of comps){
  const ct=(json.contracts&&json.contracts[comp])||{};
  const fn=ct.fn, sample=ct.sample, arrays=ct.arrays||[];
  const resolved = api.resolveLayout(comp, sample||{});
  // 1) MISROUTE: fn 是合法布局且不等于解析结果
  if(fn && LAYOUT_KEYS.indexOf(fn)>=0 && fn!==resolved){
    misroute.push({comp, fn, resolved});
  }
  // 2) 数据覆盖（仅对「逐条枚举主数组为文本」的布局有意义；视觉型布局跳过，见 NON_ENUM）
  if(sample && !NON_ENUM.has(resolved)){
    const primary=api.mainArr(sample, comp);
    if(primary.length>=3 && isObjItems(primary)){
      const labels=primary.map(it=>api.txt(it)).filter(Boolean);
      if(labels.length>=3){
        let node; try{ node=api.compile(comp, sample, ctx); }catch(e){ labels.length=0; }
        if(node){
          const _t=[]; collectText(node,_t); const txtAll=_t.join(" ");
          const covered=labels.filter(l=>txtAll.indexOf(l)>=0).length;
          const cov=covered/labels.length;
          if(cov<0.6) dataloss.push({comp, resolved, primaryLen:primary.length, covered, cov:+cov.toFixed(2)});
        }
      }
    }
    // 3) SUSPECT: 解析到 generic/note/objtree 但主数组>=3 对象
    if(["generic","note","objtree"].indexOf(resolved)>=0){
      const primary=api.mainArr(sample, comp);
      if(primary.length>=3 && isObjItems(primary)) suspect.push({comp, resolved, primaryLen:primary.length});
    }
  }
}
function isObjItems(a){ return a.length && a.every(x=>typeof x==="object"&&x!==null); }

console.log("=== ZXWIDGET 路由/覆盖审计 ===");
console.log("组件总数:",comps.length);
console.log("\n[MISROUTE] fn 合法布局但与 resolveLayout 不一致（OVERRIDE 缺口）:", misroute.length);
misroute.forEach(m=>console.log("  "+m.comp+"  fn="+m.fn+"  ->  resolved="+m.resolved));
console.log("\n[DATA_LOSS] 主数组>=3 且标签覆盖率<0.6:", dataloss.length);
dataloss.forEach(d=>console.log("  "+d.comp+"  resolved="+d.resolved+"  items="+d.primaryLen+"  covered="+d.covered+"  cov="+d.cov));
console.log("\n[SUSPECT_ROUTING] 解析到 generic/note/objtree 但含>=3 对象主数组:", suspect.length);
suspect.forEach(s=>console.log("  "+s.comp+"  resolved="+s.resolved+"  items="+s.primaryLen));

/* 逐个审查候选组件：打印 sample（截断）、解析布局、渲染文本（截断），
   用于人工判断是「真·数据丢失」还是「度量假阳性」。 */
const fs = require("fs");
const path = require("path");
const ROOT = "D:/Calw OS-project/QuroAI";
const HTML = path.join(ROOT, "app/src/main/assets/zx_card_runtime.html");
const CONTRACTS = path.join(ROOT, "app/src/main/assets/zx_contracts.json");
let html = fs.readFileSync(HTML, "utf8");
const sTag = html.indexOf("<script>", html.indexOf('id="zx-contracts"'));
const eTag = html.indexOf("</script>", sTag);
let script = html.slice(sTag + "<script>".length, eTag);
const cut = script.indexOf("var state={component"); if (cut >= 0) script = script.slice(0, cut);
function mk(t){const n={tag:t,children:[],className:"",_a:{},_t:"",_h:"",setAttribute(k,v){this._a[k]=v},getAttribute(k){return this._a[k]},appendChild(c){this.children.push(c);return c},addEventListener(){},classList:{add(){},remove(){},toggle(){}},style:{}};Object.defineProperty(n,"innerHTML",{get(){return this._h},set(v){this._h=String(v)}});Object.defineProperty(n,"textContent",{get(){return this._t},set(v){this._t=String(v)}});return n;}
global.document={createElement:mk,createElementNS:(_n,t)=>mk(t),getElementById:()=>mk("div"),documentElement:{classList:{toggle(){}}}};
const api=new Function(script+"\n;return {compile:compile,resolveLayout:resolveLayout,mainArr:mainArr,get ZX_CONTRACTS(){return ZX_CONTRACTS},set ZX_CONTRACTS(v){ZX_CONTRACTS=v}};")();
const json=JSON.parse(fs.readFileSync(CONTRACTS,"utf8"));
api.ZX_CONTRACTS=json.contracts;
const ctx={interact(){}};
function collectText(n,out){ if(!n)return; out.push(n._h||""); out.push(n._t||""); (n.children||[]).forEach(c=>collectText(c,out)); }
const names = process.argv.slice(2);
const comps = names.length?names:json.components;
for(const comp of comps){
  const ct=(json.contracts&&json.contracts[comp])||{};
  const sample=ct.sample; if(!sample){ console.log("\n### "+comp+"  (no sample)"); continue; }
  const resolved=api.resolveLayout(comp,sample);
  let node; try{ node=api.compile(comp,sample,ctx); }catch(e){ node=null; }
  const _t=[]; if(node) collectText(node,_t); const txtAll=_t.join(" ").replace(/\s+/g," ").trim();
  const primary=api.mainArr(sample,comp);
  console.log("\n### "+comp+"  fn="+ct.fn+"  resolved="+resolved+"  primaryLen="+primary.length);
  console.log("  sample: "+JSON.stringify(sample).slice(0,260));
  console.log("  rendered("+txtAll.length+"ch): "+txtAll.slice(0,200));
}

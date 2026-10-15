/* zxwidget 敏感空白检测器：编译每个契约 sample，用最小 DOM stub 跑真实渲染，
   统计 SVG 几何图形（rect/circle/line/path/polygon/ellipse/polyline）与可见文本，
   判定「真空白」：err-box 或（无几何图形 且 可见文本 < 2 字符）。
   CSS 纯装饰布局（rule）单独标记 CSSVISUAL，不判空白。仅本地回归用。 */
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

const SHAPE_TAGS=["rect","circle","line","path","polygon","polyline","ellipse"];
function analyze(n,acc){
  if(!n) return;
  if(n.className==="err-box") acc.err=true;
  const tag=String(n.tag||"").toLowerCase();
  if(SHAPE_TAGS.indexOf(tag)>=0) acc.shapes++;
  if(tag==="text") acc.shapes++; // SVG 文本也算可见图形
  if(tag==="img") acc.shapes++;  // 图片是真实视觉内容（容器嵌套 media 时不算空白）
  const s=(n._h||"")+(n._t||"");
  // 从 _html 抽可见文本（剥标签）
  const htmlText = (n._h||"").replace(/<[^>]*>/g," ");
  const vis = (n._t||"") + " " + htmlText;
  acc.text += vis + " ";
  (n.children||[]).forEach(c=>analyze(c,acc));
}

const comps=json.components||[];
const blanks=[], cssVisual=[], ok=[];
const LAYOUT_KEYS=Object.keys(api.LAYOUTS);
const CSS_ONLY=new Set(["rule"]); // 仅 CSS 装饰、无 SVG/文本也属正常

for(const name of comps){
  const ct=(json.contracts&&json.contracts[name])||{};
  const sample=ct.sample;
  if(!sample){ continue; }
  const layout=api.resolveLayout(name, sample);
  let node;
  try{ node=api.compile(name, sample, ctx); }
  catch(e){ blanks.push({name,layout,reason:"THROW:"+(e&&e.message||e)}); continue; }
  const acc={shapes:0,text:"",err:false};
  analyze(node,acc);
  const visLen=acc.text.replace(/\s+/g," ").trim().length;
  if(acc.err){ blanks.push({name,layout,reason:"err-box"}); continue; }
  if(CSS_ONLY.has(layout)){ cssVisual.push({name,layout,shapes:acc.shapes,text:visLen}); continue; }
  if(acc.shapes===0 && visLen<2){ blanks.push({name,layout,reason:"no-shape-no-text"}); continue; }
  ok.push({name,layout,shapes:acc.shapes,text:visLen});
}

console.log("=== ZXWIDGET 敏感空白检测 ===");
console.log("有 sample 组件:", comps.filter(n=>{const c=json.contracts[n];return c&&c.sample;}).length);
console.log("正常渲染:", ok.length);
console.log("CSS 装饰(免检):", cssVisual.length, "->", cssVisual.map(x=>x.name).join(", "));
console.log("空白缺陷:", blanks.length);
blanks.forEach(b=>console.log("  BLANK "+b.name+" | layout="+b.layout+" | "+b.reason));
process.exit(blanks.length>0?1:0);

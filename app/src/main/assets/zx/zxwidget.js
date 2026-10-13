/* ═══════════════════════════════════════════════════════════════════════
   zxwidget —— 动态交互界面组件库
   ═══════════════════════════════════════════════════════════════════════

   定位：zxwidget 是「动态交互界面」，本身既不是 ZorvAI 对话框也不是 Web UI。
   它是一套**可被任意界面复用的组件编译器**——ZorvAI 原生对话框、Web UI 对话框
   都只是「支持它」。所以它必须待在自己的文件里，不掺进任何一个对话框的实现。

   用法：
     zxInstallContracts(json)                  // 注入 477 组件的字段契约
     var node = compile(component, dsl, ctx)   // 编译成一个可插入的 DOM 节点
     ctx = { interact: function(action, payload){ ... } }   // 用户操作回传

   契约词汇（高规则，所以能通用推断）：
     n=名/标签 v=数值 d/desc=描述 t=文本 k=键 s/e=起止 at/when=时间
     st/status=状态 who=人 u/unit=单位 hex=颜色 e/icon=图标
     数组容器 items/rows/nodes/pts/steps/cols/fields/...
   ═══════════════════════════════════════════════════════════════════════ */

/* ═══════════════════════════════════════════════════════════════
   1. zxwidget 组件契约 + 编译器（与卡片运行时同源）
   ═══════════════════════════════════════════════════════════════ */
/* ── 组件契约来源：由 install() 注入，不再内联在页面里 ── */
var ZX_CONTRACTS = {};
function zxInstallContracts(raw){
  var ct = raw;
  /* 宿主可能整包下发 {components, contracts}，这里统一解包到内层映射 */
  if(raw && typeof raw === "object" && raw.contracts && typeof raw.contracts === "object") ct = raw.contracts;
  ZX_CONTRACTS = (ct && typeof ct === "object") ? ct : {};
  return ZX_CONTRACTS;
}


var TXT_KEYS=["n","t","k","label","title","name","text","q","head","cap","caption"];
var VAL_KEYS=["v","value","val","cnt","count","num","amount","score","p"];
var SUB_KEYS=["d","desc","detail","why","note","body","content","msg","summary","sub"];
var WHO_KEYS=["who","by","user","owner","author","assignee"];
var AT_KEYS=["at","time","when","ts","date","due"];
var ST_KEYS=["st","status","state","phase","level","lv"];
var COLOR_KEYS=["hex","color","c","tint"];
var UNIT_KEYS=["unit","u"];
var ICON_KEYS=["e","icon","emoji","i"];

function normKey(k){return String(k).toLowerCase().replace(/[^a-z0-9]/g,"").replace(/[0-9]+$/,"");}
/* 取槽位：先精确命中（保持既有行为与优先级），再归一命中。
   🔴 只做精确比对是不够的：模型常给字段加后缀或换大小写
      （title2 / owner_name / theCity），精确比对会全部落空，
      于是每个对象里只有 harvest 捞到的第一个标量显示得出来 ——
      表现就是「外壳照画、格子里空的」。
   ⚠️ 本块与 zx_card_runtime.html 的那份必须逐 token 相同（parity 脚本按去空白后的 sha1 比对），
      所以排版可以不同，**token 顺序一个字都不能差**。 */
function pick(o, keys){
  var i, k, v;
  for(i=0;i<keys.length;i++){ v=o[keys[i]]; if(v!==undefined&&v!==null&&v!=="") return v; }
  var nk={}, ks=Object.keys(o);
  for(i=0;i<ks.length;i++) nk[ks[i]]=normKey(ks[i]);
  for(i=0;i<keys.length;i++){
    var w=normKey(keys[i]);
    if(!w) continue;
    for(k in o){
      v=o[k];
      if(v===undefined||v===null||v==="") continue;
      if(typeof v==="object") continue;
      var n=nk[k];
      if(n===w) return v;
      if(w.length>=3 && n.indexOf(w)>=0) return v;
    }
  }
  return "";
}
function isObj(v){return v!==null&&typeof v==="object"&&!Array.isArray(v);}
function isArr(v){return Array.isArray(v);}
function esc(s){return String(s===undefined||s===null?"":s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;");}
/* 兜底采集时要跳过的键：结构键、已在别的槽位取过的视觉键、布局标记。
   不跳的话 txt 会把 "#5046E4" 当成标题显示出来。 */
var HARVEST_SKIP=["items","rows","nodes","pts","steps","cols","columns","options","days","cells",
"slots","groups","blocks","marks","stages","fields","links","opts","cards","levels","series",
"people","labels","tags","tasks","children","kids","panels","sections","v","edges",
"hex","color","c","tint","e","icon","emoji","i","unit","u","x","y","w","h","pct","p",
"type","id","kind","k","_raw","ref","on","auto","must","me","done","checked"];
/* 自造字段名的兜底采集：契约词汇（n/v/t/k…）之外的字段名是常态——模型按业务起名
   （temp/high/low/city/weather/day），契约里没有这些词。此前这些数据一个都不显示：
   组件外壳照画（格子、表头都在），格子里全是空的 ——
   用户看到的正是「zxwidget 根本渲染不了 / 画出来是空的」。
   数值槽取「第一个像数字的标量」，文本槽取「第一个不是数字的标量」，
   两者取值域互补，同一个值不会被两个槽位重复显示。
   ⚠ 本文件当前**没有任何代码加载**（对话框走 zx_card_runtime.html 内联的那份拷贝），
     保留它是为了与那份拷贝逐块比对（scripts/zx/zxwidget_parity_check.js）。
     改动必须两边同步，否则漂移面会继续扩大。 */
function harvest(o,numOnly){
  for(var k in o){
    if(HARVEST_SKIP.indexOf(k)>=0)continue;
    var v=o[k];
    if(v===undefined||v===null||v==="")continue;
    if(typeof v==="object")continue;
    var sv=String(v);
    var isNum=/^-?[0-9]+(\.[0-9]+)?$/.test(sv);
    if(numOnly?!isNum:isNum)continue;
    return sv;
  }
  return "";
}
function txt(o){var v=pick(o,TXT_KEYS);if(v!=="")return String(v);return harvest(o,false);}
function val(o){var v=pick(o,VAL_KEYS);if(v!=="")return String(v);return harvest(o,true);}
function sub(o){var v=pick(o,SUB_KEYS);return v===""?"":String(v);}
function who(o){var v=pick(o,WHO_KEYS);return v===""?"":String(v);}
function at(o){var v=pick(o,AT_KEYS);return v===""?"":String(v);}
function stOf(o){var v=pick(o,ST_KEYS);return v===""?"":String(v);}
function colorOf(o){var v=pick(o,COLOR_KEYS);return (typeof v==="string"&&/^#[0-9a-f]{3,8}$/i.test(v))?v:"";}
function iconOf(o){var v=pick(o,ICON_KEYS);return (typeof v==="string"&&v.length<=4)?v:"";}
function num(v,d){var n=parseFloat(v);return isFinite(n)?n:(d||0);}

function mainArr(dsl,comp){
  var ct=ZX_CONTRACTS[comp]||{}, arrs=ct.arrays||[];
  for(var i=0;i<arrs.length;i++){if(isArr(dsl[arrs[i]])&&dsl[arrs[i]].length)return dsl[arrs[i]];}
  for(var i=0;i<arrs.length;i++){if(isArr(dsl[arrs[i]]))return dsl[arrs[i]];}
  var cand=["items","rows","nodes","pts","steps","v","edges","cols","options","days","cells","slots","groups","blocks","marks","stages","fields","links","opts","cards","levels","series","people","columns","labels","tags","tasks","children","kids"];
  for(var j=0;j<cand.length;j++){if(isArr(dsl[cand[j]])&&dsl[cand[j]].length)return dsl[cand[j]];}
  return [];
}
function childArr(dsl){
  var cand=["children","kids","items","blocks","panels","sections","groups"];
  for(var i=0;i<cand.length;i++){if(isArr(dsl[cand[i]]))return dsl[cand[i]];}
  return [];
}
function el(tag,cls,html){var n=document.createElement(tag);if(cls)n.className=cls;if(html!==undefined&&html!==null)n.innerHTML=html;return n;}
function svg(tag,attrs){var n=document.createElementNS("http://www.w3.org/2000/svg",tag);for(var k in attrs)n.setAttribute(k,attrs[k]);return n;}

/* ── 布局原语 ── */
var LAYOUTS={};

LAYOUTS.table=function(dsl,comp,ctx){
  var cols=isArr(dsl.columns)?dsl.columns:(isArr(dsl.cols)?dsl.cols:[]), rows=isArr(dsl.rows)?dsl.rows:mainArr(dsl,comp);
  var box=el("div","sec"),wrap=el("div","tw"),tb=el("table"),thead=el("thead"),tr=el("tr");
  if(!cols.length&&!rows.length){box.appendChild(el("div","empty","暂无表格数据"));return box;}
  function colKey(c){return isObj(c)?(pick(c,["key","k","field","id"])||txt(c)||String(c)):String(c);}
  cols.forEach(function(c){tr.appendChild(el("th",null,esc(isObj(c)?(txt(c)||colKey(c)):String(c))));});
  if(!cols.length&&rows.length&&isObj(rows[0]))Object.keys(rows[0]).forEach(function(k){tr.appendChild(el("th",null,esc(k)));});
  thead.appendChild(tr);tb.appendChild(thead);
  var tbody=el("tbody");
  rows.forEach(function(r,ri){
    var tr2=el("tr"),cells;
    if(isArr(r)){var n=cols.length||r.length;cells=[];for(var ci0=0;ci0<n;ci0++)cells.push(r[ci0]);}
    else if(isObj(r)){
      if(cols.length){
        var ks=Object.keys(r);
        cells=cols.map(function(c,ci){var key=colKey(c);if(r[key]!==undefined)return r[key];var hit;ks.forEach(function(o2){if(hit===undefined&&normKey(key)&&String(normKey(o2)).indexOf(normKey(key))>=0)hit=r[o2];});if(hit!==undefined)return hit;return ci<ks.length?r[ks[ci]]:"";});
      } else { cells=Object.keys(r).map(function(k){return r[k];}); }
    } else { cells=[r]; }
    cells.forEach(function(cv,ci){
      var td=el("td");
      td.innerHTML=isObj(cv)?esc(txt(cv)||JSON.stringify(cv)):esc(cv===null?"—":(cv===undefined?"":cv));
      td.addEventListener("click",function(e){e.stopPropagation();ctx.interact("cell",{row:ri,col:ci,key:cols[ci]?colKey(cols[ci]):ci,value:cv});});
      tr2.appendChild(td);
    });
    tr2.addEventListener("click",function(){ctx.interact("row",{row:ri,data:r});});
    tbody.appendChild(tr2);
  });
  tb.appendChild(tbody);wrap.appendChild(tb);box.appendChild(wrap);return box;
};
LAYOUTS.metrics=function(dsl,comp,ctx){
  var items=mainArr(dsl,comp),box=el("div","sec"),g=el("div","mgrid");
  if(!items.length){box.appendChild(el("div","empty","暂无指标数据"));return box;}
  items.forEach(function(it,i){
    if(!isObj(it))return;var m=el("div","met");
    var k=txt(it)||pick(it,["k","label","name"])||"",v=val(it);
    m.appendChild(el("div","met-k",esc(k)));
    m.appendChild(el("div","met-v",esc(v||"—")));
    var d=sub(it);if(d)m.appendChild(el("div","met-d",esc(d)));
    var meta=[];var w=who(it);if(w)meta.push(esc(w));var a=at(it);if(a)meta.push(esc(a));
    if(meta.length)m.appendChild(el("div","met-d",meta.join(" · ")));
    m.addEventListener("click",function(){ctx.interact("metric",{index:i,key:k,value:v});});
    g.appendChild(m);
  });
  box.appendChild(g);return box;
};
LAYOUTS.list=function(dsl,comp,ctx){
  var items=mainArr(dsl,comp),box=el("div","sec"),l=el("div","lst");
  if(!items.length){l.appendChild(el("div","empty","暂无条目"));}
  items.forEach(function(it,i){
    var b=el("button","li"),ic=iconOf(it);
    b.appendChild(el("div","idx",ic?esc(ic):String(i+1)));
    var inner=el("div","li-i"),t=isObj(it)?(txt(it)||val(it)):String(it);
    inner.appendChild(el("div","li-t",esc(t)));
    var d=isObj(it)?sub(it):"";if(d)inner.appendChild(el("div","li-d",esc(d)));
    var meta=[];var w=who(it);if(w)meta.push(esc(w));var a=at(it);if(a)meta.push(esc(a));var s=stOf(it);if(s)meta.push(esc(s));
    if(meta.length)inner.appendChild(el("div","li-m",meta.join(" · ")));
    b.appendChild(inner);
    b.addEventListener("click",function(){ctx.interact("item",{index:i,data:isObj(it)?it:{value:it}});});
    l.appendChild(b);
  });
  box.appendChild(l);return box;
};
LAYOUTS.stepper=function(dsl,comp,ctx){
  var steps=mainArr(dsl,comp),cur=dsl.cur!==undefined?num(dsl.cur,-1):-1;
  var box=el("div","sec"),l=el("div","steps");
  if(!steps.length){l.appendChild(el("div","empty","暂无步骤"));}
  steps.forEach(function(s,i){
    var row=el("div","stp"+(i===cur?" cur":"")+(i<cur?" done":""));
    row.appendChild(el("div","stp-dot"));
    var b=el("div","stp-b");
    var t=isObj(s)?(txt(s)||("步骤 "+(i+1))):String(s);
    b.appendChild(el("div","stp-t",esc(t)));
    var d=isObj(s)?sub(s):"";if(d)b.appendChild(el("div","stp-d",esc(d)));
    var a=isObj(s)?at(s):"";if(a)b.appendChild(el("div","stp-m",esc(a)));
    row.appendChild(b);
    row.addEventListener("click",function(){ctx.interact("step",{index:i,current:cur});});
    l.appendChild(row);
  });
  box.appendChild(l);return box;
};
LAYOUTS.form=function(dsl,comp,ctx){
  var fields=isArr(dsl.fields)?dsl.fields:mainArr(dsl,comp);
  var box=el("div","sec"),f=el("form","fm");
  if(!fields.length){box.appendChild(el("div","empty","暂无表单字段"));return box;}
  f.addEventListener("submit",function(e){e.preventDefault();});
  fields.forEach(function(fd,i){
    if(!isObj(fd))return;
    var id=pick(fd,["id","key","k","name"])||("f"+i);
    var kind=(pick(fd,["kind","type","t"])||"text").toLowerCase();
    var label=txt(fd)||id;
    var row=el("div","fld");
    row.appendChild(el("div","fld-l",esc(label)));
    var inp;
    if(kind==="select"||kind==="dropdown"||kind==="choice"){
      inp=el("select");
      (isArr(fd.options)?fd.options:[]).forEach(function(o){
        var ov=isObj(o)?(val(o)||txt(o)):o, ot=isObj(o)?(txt(o)||val(o)):o;
        var op=el("option",null,esc(ot));op.value=String(ov);inp.appendChild(op);
      });
    }else if(kind==="textarea"||kind==="multiline"||kind==="long"){inp=el("textarea");}
    else if(kind==="switch"||kind==="toggle"||kind==="bool"){inp=el("input");inp.type="checkbox";inp.style.width="22px";inp.style.height="22px";inp.style.minHeight="0";}
    else if(kind==="slider"||kind==="range"){inp=el("input");inp.type="range";if(fd.min!==undefined)inp.min=fd.min;if(fd.max!==undefined)inp.max=fd.max;}
    else if(kind==="number"){inp=el("input");inp.type="number";if(fd.min!==undefined)inp.min=fd.min;if(fd.max!==undefined)inp.max=fd.max;}
    else if(kind==="date"||kind==="time"){inp=el("input");inp.type=kind;}
    else{inp=el("input");inp.type="text";}
    inp.id="zx_f_"+id;inp.name=id;
    if(fd.placeholder)inp.placeholder=fd.placeholder;
    if(fd.default!==undefined||fd.v!==undefined)inp.value=fd.default!==undefined?fd.default:fd.v;
    row.appendChild(inp);f.appendChild(row);
  });
  var btn=el("button","btn btn-p btn-w",esc(dsl.submit||dsl.action||"提交"));btn.type="submit";
  btn.addEventListener("click",function(){
    var out={};
    fields.forEach(function(fd,i){
      if(!isObj(fd))return;
      var id=pick(fd,["id","key","k","name"])||("f"+i);
      var node=document.getElementById("zx_f_"+id);if(!node)return;
      out[id]=node.type==="checkbox"?node.checked:node.value;
    });
    ctx.interact("submit",{values:out});
  });
  f.appendChild(btn);box.appendChild(f);return box;
};
LAYOUTS.choice=function(dsl,comp,ctx){
  var opts=isArr(dsl.options)?dsl.options:(isArr(dsl.opts)?dsl.opts:mainArr(dsl,comp));
  var box=el("div","sec"),row=el("div","opt-row");
  if(!opts.length){box.appendChild(el("div","empty","暂无选项"));return box;}
  opts.forEach(function(o,i){
    var label=isObj(o)?(txt(o)||val(o)):String(o);
    var b=el("button","btn",esc(label));
    b.addEventListener("click",function(){ctx.interact("choose",{index:i,option:label});});
    row.appendChild(b);
  });
  box.appendChild(row);return box;
};
LAYOUTS.chips=function(dsl,comp,ctx){
  var tags=isArr(dsl.tags)?dsl.tags:(isArr(dsl.labels)?dsl.labels:mainArr(dsl,comp));
  var box=el("div","sec"),row=el("div","chips");
  if(!tags.length){box.appendChild(el("div","empty","暂无标签"));return box;}
  tags.forEach(function(t,i){
    var label=isObj(t)?(txt(t)||val(t)):String(t);
    var c=el("button","chip",esc(label));
    c.addEventListener("click",function(){ctx.interact("tag",{index:i,tag:label});});
    row.appendChild(c);
  });
  box.appendChild(row);return box;
};
LAYOUTS.chart=function(dsl,comp,ctx){
  var box=el("div","sec");
  var pts=isArr(dsl.pts)?dsl.pts:(isArr(dsl.series)?dsl.series:mainArr(dsl,comp));
  /* ── 多系列：每项自带数值数组（{n:"乐观",v:[100,128,…]}） ── */
  var sk=null;
  if(pts.length&&isObj(pts[0])){
    Object.keys(pts[0]).forEach(function(k){
      var v=pts[0][k];
      if(!sk&&isArr(v)&&v.length&&typeof v[0]==="number")sk=k;
    });
  }
  if(sk&&pts.length>1){
    var mW=660,mH=200,mP=30,mAll=[];
    pts.forEach(function(p){p[sk].forEach(function(x){mAll.push(num(x));});});
    var Mx=Math.max.apply(null,mAll.concat([1])),Mn=Math.min.apply(null,mAll.concat([0]));
    if(Mx===Mn)Mx=Mn+1;
    var ms=svg("svg",{viewBox:"0 0 "+mW+" "+mH,class:"chart",preserveAspectRatio:"xMidYMid meet"});
    for(var mg=0;mg<=4;mg++){
      var mgy=mP+(mH-mP*2)*mg/4;
      ms.appendChild(svg("line",{x1:mP,y1:mgy,x2:mW-mP,y2:mgy,stroke:"var(--line)","stroke-width":"1"}));
      var mtv=el("text",null,esc((Mx-(Mx-Mn)*mg/4).toFixed(Mx>10?0:1)));
      mtv.setAttribute("x",2);mtv.setAttribute("y",mgy+4);mtv.setAttribute("fill","var(--fg-3)");mtv.setAttribute("font-size","9.5");
      ms.appendChild(mtv);
    }
    var mCols=["var(--accent)","#e8590c","#1c7ed6","#0ca678","#ae3ec9","#f03e9c"];
    var mn=pts[0][sk].length,mbw=(mW-mP*2)/Math.max(mn,1);
    pts.forEach(function(p,si){
      var col=mCols[si%mCols.length],d="";
      p[sk].forEach(function(x,i){
        var y=mH-mP-(num(x)-Mn)/(Mx-Mn)*(mH-mP*2);
        d+=(i?"L":"M")+(mP+mbw*i+mbw/2).toFixed(1)+" "+y.toFixed(1);
      });
      ms.appendChild(svg("path",{d:d,fill:"none",stroke:col,"stroke-width":"2","stroke-linejoin":"round","stroke-linecap":"round",opacity:.9}));
      p[sk].forEach(function(x,i){
        var cx=mP+mbw*i+mbw/2,cy=mH-mP-(num(x)-Mn)/(Mx-Mn)*(mH-mP*2);
        var c=svg("circle",{cx:cx,cy:cy,r:3.2,fill:"var(--surface)",stroke:col,"stroke-width":"2"});
        c.style.cursor="pointer";
        c.addEventListener("click",function(){ctx.interact("point",{series:txt(p)||("系列"+(si+1)),index:i,value:num(x)});});
        ms.appendChild(c);
      });
    });
    box.appendChild(ms);
    var mlg=el("div","legend");
    pts.forEach(function(p,si){
      mlg.appendChild(el("span",null,"<i style='display:inline-block;width:9px;height:9px;border-radius:50%;background:"+mCols[si%mCols.length]+";margin-right:5px'></i>"+esc(txt(p)||("系列"+(si+1)))));
    });
    box.appendChild(mlg);
    return box;
  }
  var W=660,H=200,P=30;
  var s=svg("svg",{viewBox:"0 0 "+W+" "+H,class:"chart",preserveAspectRatio:"xMidYMid meet"});
  var nums=pts.map(function(p){return isObj(p)?num(pick(p,VAL_KEYS)||p.v||p.y||0):num(p);});
  var labels=pts.map(function(p,i){return isObj(p)?(txt(p)||pick(p,["x","label","n","t"])||""):String(i+1);});
  var mx=Math.max.apply(null,nums.concat([1])),mn=Math.min.apply(null,nums.concat([0]));
  if(mx===mn)mx=mn+1;
  var bw=(W-P*2)/Math.max(nums.length,1);
  for(var g=0;g<=4;g++){
    var gy=P+(H-P*2)*g/4;
    s.appendChild(svg("line",{x1:P,y1:gy,x2:W-P,y2:gy,stroke:"var(--line)","stroke-width":"1"}));
    var tv=el("text",null,esc((mx-(mx-mn)*g/4).toFixed(mx>10?0:1)));
    tv.setAttribute("x",2);tv.setAttribute("y",gy+4);tv.setAttribute("fill","var(--fg-3)");tv.setAttribute("font-size","9.5");
    s.appendChild(tv);
  }
  var isBar=comp.indexOf("bar")>=0||comp.indexOf("column")>=0||comp.indexOf("hist")>=0;
  nums.forEach(function(v,i){
    var h=(v-mn)/(mx-mn)*(H-P*2),x=P+bw*i+bw*0.18,w=bw*0.64,y=H-P-h;
    var lb=el("text",null,esc(labels[i]));
    lb.setAttribute("x",P+bw*i+bw/2);lb.setAttribute("y",H-P+13);lb.setAttribute("text-anchor","middle");
    lb.setAttribute("fill","var(--fg-3)");lb.setAttribute("font-size","9.5");s.appendChild(lb);
    if(isBar){
      var r=svg("rect",{x:x,y:y,width:w,height:Math.max(h,1),rx:3,fill:"var(--accent)",opacity:.85});
      r.style.cursor="pointer";
      r.addEventListener("click",function(){ctx.interact("bar",{index:i,label:labels[i],value:v});});
      s.appendChild(r);
    }
  });
  if(!isBar&&nums.length>1){
    var d="";nums.forEach(function(v,i){var x=P+bw*i+bw/2,y=H-P-(v-mn)/(mx-mn)*(H-P*2);d+=(i?"L":"M")+x.toFixed(1)+" "+y.toFixed(1);});
    s.appendChild(svg("path",{d:d,fill:"none",stroke:"var(--accent)","stroke-width":"2.5","stroke-linejoin":"round","stroke-linecap":"round"}));
    nums.forEach(function(v,i){
      var x=P+bw*i+bw/2,y=H-P-(v-mn)/(mx-mn)*(H-P*2);
      var c=svg("circle",{cx:x,cy:y,r:4,fill:"var(--surface)",stroke:"var(--accent)","stroke-width":"2.5"});
      c.style.cursor="pointer";
      c.addEventListener("click",function(){ctx.interact("point",{index:i,label:labels[i],value:v});});
      s.appendChild(c);
    });
  }
  box.appendChild(s);return box;
};
LAYOUTS.gantt=function(dsl,comp,ctx){
  var tasks=isArr(dsl.tasks)?dsl.tasks:mainArr(dsl,comp);
  var box=el("div","sec"),wrap=el("div","sec-b");
  if(!tasks.length){box.appendChild(el("div","empty","暂无任务"));return box;}
  var lo=Infinity,hi=-Infinity;
  tasks.forEach(function(t){var s=num(isObj(t)?(t.s!==undefined?t.s:t.from):0),e=num(isObj(t)?(t.e!==undefined?t.e:t.to):0);if(s<lo)lo=s;if(e>hi)hi=e;});
  if(!isFinite(lo)){lo=0;hi=1;}if(hi<=lo)hi=lo+1;
  var W=660,ROW=32;
  var s=svg("svg",{viewBox:"0 0 "+W+" "+(ROW*tasks.length+6),class:"chart",preserveAspectRatio:"xMidYMid meet"});
  tasks.forEach(function(t,i){
    var y=i*ROW+3;
    var sv=num(isObj(t)?(t.s!==undefined?t.s:t.from):0),ev=num(isObj(t)?(t.e!==undefined?t.e:t.to):0);
    var x1=(sv-lo)/(hi-lo)*(W-140)+140,x2=(ev-lo)/(hi-lo)*(W-140)+140;
    var nm=isObj(t)?(txt(t)||("任务"+(i+1))):String(t);
    var lb=el("text",null,esc(nm.length>11?nm.slice(0,11)+"…":nm));
    lb.setAttribute("x",0);lb.setAttribute("y",y+17);lb.setAttribute("fill","var(--fg-2)");lb.setAttribute("font-size","11");
    s.appendChild(lb);
    var col=colorOf(t)||"var(--accent)";
    var r=svg("rect",{x:x1,y:y+6,width:Math.max(x2-x1,3),height:15,rx:5,fill:col,opacity:.9});
    r.style.cursor="pointer";
    r.addEventListener("click",function(){ctx.interact("task",{index:i,name:nm,start:sv,end:ev});});
    s.appendChild(r);
    s.appendChild(svg("line",{x1:140,y1:y+2,x2:140,y2:y+26,stroke:"var(--line)","stroke-width":"1"}));
  });
  wrap.appendChild(s);box.appendChild(wrap);return box;
};
LAYOUTS.kanban=function(dsl,comp,ctx){
  var cols=isArr(dsl.cols)?dsl.cols:mainArr(dsl,comp);
  var box=el("div","sec"),kb=el("div","kb");
  if(!cols.length){box.appendChild(el("div","empty","暂无看板列"));return box;}
  cols.forEach(function(c,ci){
    var col=el("div","kb-c");
    var name=isObj(c)?(txt(c)||("列 "+(ci+1))):String(c);
    var cards=isObj(c)?(isArr(c.cards)?c.cards:(isArr(c.items)?c.items:[])):[];
    col.appendChild(el("div","kb-h","<span>"+esc(name)+"</span><span>"+cards.length+"</span>"));
    cards.forEach(function(cd,ki){
      var it=el("div","kb-i",esc(isObj(cd)?(txt(cd)||val(cd)):String(cd)));
      it.addEventListener("click",function(){ctx.interact("card",{col:ci,card:ki,data:isObj(cd)?cd:{value:cd}});});
      col.appendChild(it);
    });
    kb.appendChild(col);
  });
  box.appendChild(kb);return box;
};
LAYOUTS.calendar=function(dsl,comp,ctx){
  var box=el("div","sec"),cal=el("div","cal");
  var days=isArr(dsl.days)?dsl.days:(isArr(dsl.cells)?dsl.cells:mainArr(dsl,comp));
  if(!days.length){box.appendChild(el("div","empty","暂无日期数据"));return box;}
  var hd=el("div","cal-h");
  ["一","二","三","四","五","六","日"].forEach(function(w){hd.appendChild(el("div",null,esc(w)));});
  cal.appendChild(hd);
  var g=el("div","cal-g");
  days.forEach(function(d,i){
    var cell=el("div","cd"),label=isObj(d)?(val(d)||txt(d)):d;
    cell.innerHTML=esc(label);
    if(isObj(d)&&d.on)cell.className="cd on";
    else if(isObj(d)&&(d.hot||(d.v!==undefined&&num(d.v)>0)))cell.className="cd hot";
    cell.addEventListener("click",function(){ctx.interact("day",{index:i,data:isObj(d)?d:{value:d}});});
    g.appendChild(cell);
  });
  cal.appendChild(g);box.appendChild(cal);return box;
};
LAYOUTS.tree=function(dsl,comp,ctx){
  var nodes=isArr(dsl.nodes)?dsl.nodes:(isArr(dsl.people)?dsl.people:mainArr(dsl,comp));
  var box=el("div","sec"),wrap=el("div","tree");
  if(!nodes.length){box.appendChild(el("div","empty","暂无节点"));return box;}
  function draw(list,depth){
    var holder=el("div");
    (list||[]).forEach(function(n,i){
      if(!isObj(n))return;
      var row=el("div","tn");
      var tw=el("div","tn-tw",(isArr(n.children)&&n.children.length)?"▸":"");
      tw.addEventListener("click",function(e){
        e.stopPropagation();
        var sib=row.nextSibling;
        if(sib&&sib.className==="tn-k"){sib.style.display=sib.style.display==="none"?"":"none";tw.innerHTML=sib.style.display==="none"?"▾":"▸";}
      });
      row.appendChild(tw);row.appendChild(el("div","tn-t",esc(txt(n)||val(n))));
      var s=stOf(n);if(s)row.appendChild(el("span","st st-info",esc(s)));
      row.addEventListener("click",function(){ctx.interact("node",{depth:depth,index:i,data:n});});
      holder.appendChild(row);
      if(isArr(n.children)&&n.children.length){
        var k=el("div","tn-k");k.style.display="none";k.appendChild(draw(n.children,depth+1));
        holder.appendChild(k);
      }
    });
    return holder;
  }
  wrap.appendChild(draw(nodes,0));box.appendChild(wrap);return box;
};
LAYOUTS.note=function(dsl,comp,ctx){
  var lvl=(stOf(dsl)||"").toLowerCase();
  var cls="note"+(lvl==="ok"||lvl==="success"||lvl==="done"?" ok":(lvl==="warn"||lvl==="warning"?" warn":(lvl==="err"||lvl==="error"?" err":"")));
  var box=el("div",cls);
  var ic=lvl==="ok"||lvl==="success"?"✓":(lvl==="warn"?"!":(lvl==="err"||lvl==="error"?"✕":"i"));
  box.appendChild(el("div","note-i",esc(ic)));
  var b=el("div",null);
  var t=txt(dsl)||dsl.title||"";if(t)b.appendChild(el("div",null,"<b>"+esc(t)+"</b>"));
  var d=sub(dsl)||dsl.content||dsl.body||"";if(d)b.appendChild(el("div",null,esc(d)));
  box.appendChild(b);
  box.addEventListener("click",function(){ctx.interact("note",{});});
  return box;
};
LAYOUTS.terminal=function(dsl,comp,ctx){
  var lines=isArr(dsl.lines)?dsl.lines:mainArr(dsl,comp);
  var box=el("div","sec"),t=el("div","term");
  if(!lines.length){box.appendChild(el("div","empty","暂无终端输出"));return box;}
  lines.forEach(function(l){
    var s=isObj(l)?(txt(l)||val(l)):String(l),d=el("div");
    if(/^\s*[$#>]/.test(s))d.className="p";
    else if(/error|fail|exception/i.test(s))d.className="e";
    else if(/warn/i.test(s))d.className="w";
    d.textContent=s;t.appendChild(d);
  });
  box.appendChild(t);return box;
};
LAYOUTS.diff=function(dsl,comp,ctx){
  var hunks=isArr(dsl.hunks)?dsl.hunks:mainArr(dsl,comp);
  var box=el("div","sec"),d=el("div","diff");
  if(!hunks.length){box.appendChild(el("div","empty","暂无差异内容"));return box;}
  hunks.forEach(function(h){
    if(isObj(h)){
      d.appendChild(el("div","dl hunk","<span class='g'>@@</span>"+esc(h.at||h.n||"")));
      (isArr(h.lines)?h.lines:[]).forEach(function(l){
        var s=isObj(l)?(txt(l)||val(l)):String(l);
        var cls=/^\+/.test(s)?"add":(/^-/.test(s)?"del":"");
        d.appendChild(el("div","dl "+cls,"<span class='g'></span>"+esc(s)));
      });
    }else{
      var s2=String(h),cls2=/^\+/.test(s2)?"add":(/^-/.test(s2)?"del":"");
      d.appendChild(el("div","dl "+cls2,"<span class='g'></span>"+esc(s2)));
    }
  });
  box.appendChild(d);return box;
};
LAYOUTS.rating=function(dsl,comp,ctx){
  var box=el("div","sec"),row=el("div","rate");
  var max=num(dsl.max,5),v=num(dsl.v||dsl.value,0);
  for(var i=1;i<=max;i++){
    var s=el("div","rate-s"+(i<=v?" on":""),"★");
    (function(idx){s.addEventListener("click",function(){ctx.interact("rate",{value:idx,max:max});});})(i);
    row.appendChild(s);
  }
  box.appendChild(row);
  if(sub(dsl))box.appendChild(el("div","hint",esc(sub(dsl))));
  return box;
};
LAYOUTS.gauge=function(dsl,comp,ctx){
  var items=mainArr(dsl,comp),box=el("div","sec");
  if(!items.length&&dsl.v!==undefined){
    var row=el("div","gauge-row");
    var pct=Math.max(0,Math.min(100,num(dsl.v)/num(dsl.max,100)*100));
    row.appendChild(el("div","pb","<div class='pb-f' style='width:"+pct.toFixed(1)+"%'></div>"));
    row.appendChild(el("div",null,esc(dsl.v+""+(dsl.unit||""))));
    row.addEventListener("click",function(){ctx.interact("gauge",{value:num(dsl.v),max:num(dsl.max,100)});});
    box.appendChild(row);return box;
  }
  items.forEach(function(it,i){
    var row=el("div","gauge-row");
    var label=isObj(it)?(txt(it)||""):"",v=isObj(it)?val(it):it,mx=isObj(it)?num(it.max,100):100;
    var pct=Math.max(0,Math.min(100,num(v)/mx*100));
    var cls=pct>=80?"ok":(pct>=40?"":"warn");
    row.appendChild(el("div",null,"<div style='font-size:12.5px;color:var(--fg-2);flex:0 0 32%'>"+esc(label)+"</div>"));
    row.appendChild(el("div","pb","<div class='pb-f "+cls+"' style='width:"+pct.toFixed(1)+"%'></div>"));
    row.appendChild(el("div",null,"<span style='font-family:var(--mono);font-size:12px'>"+esc(v)+"</span>"));
    row.addEventListener("click",function(){ctx.interact("gauge",{index:i,label:label,value:v});});
    box.appendChild(row);
  });
  return box;
};
LAYOUTS.media=function(dsl,comp,ctx){
  var box=el("div","sec"),m=el("div","media");
  var src=dsl.src||dsl.url||dsl.v||"",kind=(dsl.kind||dsl.type||"").toLowerCase();
  if(/\.(mp4|webm|mov)/i.test(src)||kind==="video"){var v=el("video");v.src=src;v.controls=true;m.appendChild(v);}
  else if(src){var im=el("img");im.src=src;im.loading="lazy";m.appendChild(im);}
  if(sub(dsl)||dsl.caption)m.appendChild(el("div","hint",esc(sub(dsl)||dsl.caption)));
  box.appendChild(m);return box;
};
LAYOUTS.qr=function(dsl,comp,ctx){
  var box=el("div","sec"),wrap=el("div",null);
  wrap.style.padding="15px";wrap.style.textAlign="center";
  wrap.appendChild(el("div",null,"<div style='font-family:var(--mono);font-size:12px;color:var(--fg-3);word-break:break-all'>"+esc(dsl.text||dsl.v||"")+"</div>"));
  if(sub(dsl)||dsl.caption)wrap.appendChild(el("div","hint",esc(sub(dsl)||dsl.caption)));
  box.appendChild(wrap);return box;
};
LAYOUTS.keyvalue=function(dsl,comp,ctx){
  var box=el("div","sec"),dl=el("dl","kv");
  Object.keys(dsl).forEach(function(k){
    if(k==="type")return;var v=dsl[k];if(isObj(v)||isArr(v))return;
    dl.appendChild(el("div","kv-r","<div class='kv-k'>"+esc(k)+"</div><div class='kv-v'>"+esc(v)+"</div>"));
  });
  box.appendChild(dl);return box;
};
LAYOUTS.container=function(dsl,comp,ctx){
  var kids=childArr(dsl),box=el("div");
  box.style.display="flex";box.style.flexDirection="column";box.style.gap="10px";
  if(!kids.length){box.appendChild(el("div","empty","容器 "+esc(comp)+" 没有子节点"));return box;}
  kids.forEach(function(k){if(!isObj(k))return;var s=compile(k.type||k.component||"",k,ctx);if(s)box.appendChild(s);});
  return box;
};
LAYOUTS.generic=function(dsl,comp,ctx){
  var ct=ZX_CONTRACTS[comp]||{},arrs=ct.arrays||[];
  var box=el("div","sec"),scalars=[];
  Object.keys(dsl).forEach(function(k){
    if(k==="type")return;var v=dsl[k];if(isObj(v)||isArr(v))return;scalars.push([k,v]);
  });
  if(scalars.length>=1){
    var dl=el("dl","kv");
    scalars.forEach(function(p){dl.appendChild(el("div","kv-r","<div class='kv-k'>"+esc(p[0])+"</div><div class='kv-v'>"+esc(p[1])+"</div>"));});
    box.appendChild(dl);
  }
  arrs.forEach(function(a){
    if(!isArr(dsl[a])||!dsl[a].length)return;
    var items=dsl[a];
    if(isObj(items[0])){
      var hasKV=Object.keys(items[0]).indexOf("k")>=0&&Object.keys(items[0]).indexOf("v")>=0;
      var g=el("div","mgrid");g.style.padding="11px 13px";
      items.forEach(function(it,i){
        if(!isObj(it))return;var m=el("div","met");
        m.appendChild(el("div","met-k",esc(hasKV?txt(it):(txt(it)||a+"["+i+"]"))));
        m.appendChild(el("div","met-v",esc(hasKV?val(it):JSON.stringify(it).slice(0,60))));
        m.addEventListener("click",function(){ctx.interact(a,{index:i,data:it});});
        g.appendChild(m);
      });
      box.appendChild(el("div","sec-t",esc(a)));box.appendChild(g);
    }else{
      var row=el("div","chips");
      items.forEach(function(it,i){
        var c=el("button","chip",esc(isObj(it)?JSON.stringify(it):it));
        c.addEventListener("click",function(){ctx.interact(a,{index:i,value:it});});
        row.appendChild(c);
      });
      box.appendChild(el("div","sec-t",esc(a)));box.appendChild(row);
    }
  });
  if(!scalars.length&&!arrs.length)box.appendChild(el("div","empty","该组件没有可渲染字段"));
  return box;
};
LAYOUTS.spark=function(dsl,comp,ctx){
  var box=el("div","sec"),series=[];
  Object.keys(dsl).forEach(function(k){
    if(k==="type")return;var v=dsl[k];
    if(isArr(v)&&v.length&&typeof v[0]==="number")series.push([k,v]);
  });
  if(!series.length){box.appendChild(el("div","empty","没有数值序列"));return box;}
  var W=660,H=180,P=28,all=[];
  series.forEach(function(s){s[1].forEach(function(x){all.push(num(x));});});
  var mx=Math.max.apply(null,all.concat([1])),mn=Math.min.apply(null,all.concat([0]));
  if(mx===mn)mx=mn+1;
  var s=svg("svg",{viewBox:"0 0 "+W+" "+H,class:"chart",preserveAspectRatio:"xMidYMid meet"});
  for(var g=0;g<=3;g++){
    var gy=P+(H-P*2)*g/3;
    s.appendChild(svg("line",{x1:P,y1:gy,x2:W-P,y2:gy,stroke:"var(--line)","stroke-width":"1"}));
    var tv=el("text",null,esc((mx-(mx-mn)*g/3).toFixed(mx>10?0:1)));
    tv.setAttribute("x",2);tv.setAttribute("y",gy+4);tv.setAttribute("fill","var(--fg-3)");tv.setAttribute("font-size","9.5");
    s.appendChild(tv);
  }
  var cols=["var(--accent)","#e8590c","#1c7ed6","#0ca678","#ae3ec9"];
  var n=series[0][1].length,bw=(W-P*2)/Math.max(n,1);
  series.forEach(function(sr,si){
    var col=cols[si%cols.length],d="",ly=0;
    sr[1].forEach(function(x,i){
      var y=H-P-(num(x)-mn)/(mx-mn)*(H-P*2);
      d+=(i?"L":"M")+(P+bw*i+bw/2).toFixed(1)+" "+y.toFixed(1);ly=y;
    });
    s.appendChild(svg("path",{d:d,fill:"none",stroke:col,"stroke-width":"2.2","stroke-linejoin":"round","stroke-linecap":"round"}));
    s.appendChild(svg("circle",{cx:P+bw*(n-1)+bw/2,cy:ly,r:3.5,fill:col}));
  });
  box.appendChild(s);
  var lg=el("div","legend");
  series.forEach(function(sr,si){
    lg.appendChild(el("span",null,"<i style='display:inline-block;width:9px;height:9px;border-radius:50%;background:"+cols[si%cols.length]+";margin-right:5px'></i>"+esc(sr[0])+"（"+esc(sr[1].length)+" 点）"));
  });
  box.appendChild(lg);return box;
};
LAYOUTS.objtree=function(dsl,comp,ctx){
  var box=el("div","sec"),wrap=el("div","tree");
  var topKeys=Object.keys(dsl).filter(function(k){return k!=="type";});
  if(!topKeys.length){box.appendChild(el("div","empty","暂无结构化数据"));return box;}
  function draw(val,key){
    var holder=el("div");
    if(isArr(val)){
      val.forEach(function(v,i){holder.appendChild(draw(v,key+"["+i+"]"));});
      return holder;
    }
    if(isObj(val)){
      var ks=Object.keys(val);
      var row=el("div","tn");
      row.appendChild(el("div","tn-tw",ks.length?"▸":""));
      row.appendChild(el("div","tn-t",esc(key)+(ks.length?"（"+ks.length+" 项）":"")));
      row.addEventListener("click",function(){ctx.interact("node",{key:key,data:val});});
      holder.appendChild(row);
      if(ks.length){
        var kids=el("div","tn-k");kids.style.display="none";
        ks.forEach(function(k){kids.appendChild(draw(val[k],k));});
        holder.appendChild(kids);
      }
      return holder;
    }
    var leaf=el("div","tn");
    leaf.appendChild(el("div","tn-tw",""));
    leaf.appendChild(el("div","tn-t","<b>"+esc(key)+"</b> = "+esc(String(val))));
    leaf.addEventListener("click",function(){ctx.interact("leaf",{key:key,value:val});});
    holder.appendChild(leaf);return holder;
  }
  Object.keys(dsl).forEach(function(k){
    if(k==="type")return;var v=dsl[k];
    if(isObj(v)||isArr(v))wrap.appendChild(draw(v,k));
    else wrap.appendChild(el("div","tn","<div class='tn-tw'></div><div class='tn-t'><b>"+esc(k)+"</b> = "+esc(String(v))+"</div>"));
  });
  box.appendChild(wrap);return box;
};
LAYOUTS.matrix=function(dsl,comp,ctx){
  var box=el("div","sec"),m=null,hd=isArr(dsl.cols)?dsl.cols:(isArr(dsl.names)?dsl.names:(isArr(dsl.labels)?dsl.labels:[]));
  Object.keys(dsl).forEach(function(k){
    if(k==="type"||k==="cols"||k==="names"||k==="labels")return;var v=dsl[k];
    if(isArr(v)&&v.length&&isArr(v[0])&&v[0].length&&!m)m=v;
  });
  if(!m){box.appendChild(el("div","empty","没有矩阵数据"));return box;}
  var flat=[];m.forEach(function(r){r.forEach(function(x){flat.push(isObj(x)?num(val(x)):num(x));});});
  var mx=Math.max.apply(null,flat.concat([1])),mn=Math.min.apply(null,flat.concat([0]));
  if(mx===mn)mx=mn+1;
  var tb=el("table","mtx");
  if(hd.length){
    var hr=el("tr");hr.appendChild(el("th",null,""));
    hd.forEach(function(c){hr.appendChild(el("th",null,esc(isObj(c)?txt(c):String(c))));});
    tb.appendChild(hr);
  }
  m.forEach(function(r,ri){
    var tr=el("tr");
    tr.appendChild(el("th",null,esc(hd[ri]!==undefined?(isObj(hd[ri])?txt(hd[ri]):String(hd[ri])):"")));
    r.forEach(function(c,ci){
      var v=isObj(c)?num(val(c)):num(c),t=(v-mn)/(mx-mn);
      var td=el("td",t<0.34?"lo":(t<0.67?"md":"hi"),esc(isObj(c)?(txt(c)||val(c)||JSON.stringify(c)):String(c)));
      td.addEventListener("click",function(){ctx.interact("cell",{row:ri,col:ci,data:isObj(c)?c:{value:c}});});
      tr.appendChild(td);
    });
    tb.appendChild(tr);
  });
  box.appendChild(tb);return box;
};
LAYOUTS.rule=function(dsl,comp,ctx){
  var box=el("div","sec"),r=el("div","rule");
  var t=txt(dsl)||dsl.label||"";
  if(t)r.appendChild(el("span","rule-t",esc(t)));
  box.appendChild(r);return box;
};
LAYOUTS.hero=function(dsl,comp,ctx){
  var box=el("div","sec"),h=el("div","hero");
  var raw=dsl.v!==undefined?dsl.v:(dsl.num!==undefined?dsl.num:(dsl.value!==undefined?dsl.value:dsl.sec));
  var u=dsl.u||dsl.unit||"",shown=raw;
  if(comp==="zx_countdown"&&raw!==undefined){
    var s=Math.max(0,Math.round(num(raw)));
    shown=Math.floor(s/60)+":"+("0"+(s%60)).slice(-2);
  }
  var vv=el("div","hero-v");
  vv.innerHTML=esc(shown===undefined||shown===null?"—":shown)+(u?"<span class='hero-u'>"+esc(u)+"</span>":"");
  h.appendChild(vv);
  var lb=txt(dsl)||dsl.label||"";
  if(lb)h.appendChild(el("div","hero-s",esc(lb)));
  var d=sub(dsl)||dsl.desc||"";
  if(d)h.appendChild(el("div","hero-s",esc(d)));
  if(dsl.max!==undefined&&raw!==undefined&&isFinite(num(raw))){
    var pct=Math.max(0,Math.min(100,num(raw)/num(dsl.max,100)*100));
    var pb=el("div","pb","<div class='pb-f' style='width:"+pct.toFixed(1)+"%'></div>");
    pb.style.margin="13px auto 0";pb.style.maxWidth="210px";
    h.appendChild(pb);
  }
  h.addEventListener("click",function(){ctx.interact("value",{value:shown,unit:u,label:lb});});
  box.appendChild(h);return box;
};

var CONTAINERS=["zx_tabs","zx_accordion","zx_split","zx_carousel","zx_grid","zx_master","zx_stagger","zx_stack"];
var OVERRIDE={
  zx_compare:"table",zx_table:"table",zx_datatable:"table",zx_matrix:"table",zx_pivot:"table",
  zx_metrics:"metrics",zx_stat:"metrics",zx_stats:"metrics",zx_kpi:"metrics",zx_counter:"metrics",
  zx_list:"list",zx_feed:"list",zx_log:"list",zx_search:"list",zx_results:"list",zx_tasklist:"list",
  zx_timeline:"stepper",zx_steps:"stepper",zx_stepper:"stepper",zx_stages:"stepper",zx_process:"stepper",
  zx_form:"form",zx_survey:"form",zx_gridform:"form",
  zx_choice:"choice",zx_options:"choice",zx_pick:"choice",zx_vote:"choice",
  zx_tags:"chips",zx_chips:"chips",zx_labels:"chips",zx_marks:"chips",
  zx_chart:"chart",zx_line:"chart",zx_bar:"chart",zx_spark:"chart",zx_trend:"chart",
  zx_gantt:"gantt",zx_plan:"gantt",zx_kanban:"kanban",zx_board:"kanban",
  zx_calendar:"calendar",zx_heatmap:"calendar",zx_calheat:"calendar",
  zx_tree:"tree",zx_org:"tree",zx_orgchart:"tree",zx_mindmap:"tree",zx_depgraph:"tree",
  zx_alert:"note",zx_notice:"note",zx_callout:"note",zx_banner:"note",zx_quote:"note",zx_hint:"note",
  zx_divider:"rule",zx_stoprule:"rule",
  zx_dial:"hero",zx_dial2:"hero",zx_odometer:"hero",zx_countdown:"hero",zx_flipnum:"hero",zx_dialpad:"hero",
  zx_dial3:"gauge",zx_ring2:"tree",zx_radial:"tree",zx_sign:"keyvalue",zx_joystick:"keyvalue",
  zx_corr:"matrix",zx_missing:"matrix",zx_seat:"matrix",
  zx_sparkline:"spark",zx_bland:"spark",zx_lorenz:"spark",zx_optline:"spark",zx_perm:"spark",zx_qq:"spark",
  zx_ast:"objtree",zx_confront:"objtree",zx_formula:"objtree",
  zx_terminal:"terminal",zx_console:"terminal",zx_shell:"terminal",
  zx_diff:"diff",zx_patch:"diff",zx_changes:"diff",
  zx_rating:"rating",zx_scale:"rating",zx_stars:"rating",
  zx_gauge:"gauge",zx_progress:"gauge",zx_meter:"gauge",
  zx_media:"media",zx_image:"media",zx_video:"media",zx_audio:"media",
  zx_qr:"qr",zx_barcode:"qr",zx_keyvalue:"keyvalue",zx_kv:"keyvalue",zx_detail:"keyvalue",zx_summary:"keyvalue"
};
function resolveLayout(comp,dsl){
  if(OVERRIDE[comp])return OVERRIDE[comp];
  if(CONTAINERS.indexOf(comp)>=0)return "container";
  var ct=ZX_CONTRACTS[comp]||{},arrs=ct.arrays||[],fds=ct.fields||[];
  if(arrs.indexOf("columns")>=0&&arrs.indexOf("rows")>=0)return "table";
  if(arrs.indexOf("fields")>=0)return "form";
  if(arrs.indexOf("cols")>=0)return "kanban";
  /* tasks → 甘特图必须要有起止证据：LAYOUTS.gantt 只读 s/e（或 from/to），
     没有起止字段时每根条都塌成 0 宽，画出来是一条空轴 —— 等于「画不出来」。
     全仓只有 3 个组件声明 arrays 含 tasks：zx_gantt 有 s/e；
     zx_tasklist 只有 t/dep/pri（一份清单）；zx_critical 只有 d/n。 */
  if(arrs.indexOf("tasks")>=0){
    var tf=ct.fields||[], tl=mainArr(dsl, comp);
    var head=isObj(tl[0])?tl[0]:null;
    var hasSE=(tf.indexOf("s")>=0&&tf.indexOf("e")>=0)||(head&&(head.s!==undefined||head.from!==undefined)&&(head.e!==undefined||head.to!==undefined));
    if(hasSE) return "gantt";
  }
  if(arrs.indexOf("options")>=0||arrs.indexOf("opts")>=0)return "choice";
  if(arrs.indexOf("nodes")>=0||arrs.indexOf("people")>=0||arrs.indexOf("levels")>=0)return "tree";
  if(arrs.indexOf("pts")>=0||arrs.indexOf("series")>=0)return "chart";
  if(arrs.indexOf("steps")>=0||arrs.indexOf("stages")>=0)return "stepper";
  if(arrs.indexOf("days")>=0||arrs.indexOf("cells")>=0)return "calendar";
  if(arrs.indexOf("tags")>=0||arrs.indexOf("labels")>=0||arrs.indexOf("marks")>=0)return "chips";
  /* 契约驱动：主数组名 + 声明的标量字段组合，先于数据推断 */
  if(arrs.length&&fds.indexOf("k")>=0&&fds.indexOf("v")>=0&&
     ["items","rows","entries","records","list","dials","subs","metrics"].indexOf(arrs[0])>=0)return "metrics";
  if(arrs.indexOf("bins")>=0)return "chart";
  var items=mainArr(dsl,comp);
  if(items.length&&isObj(items[0])){
    var keys=Object.keys(items[0]);
    if(keys.indexOf("k")>=0&&keys.indexOf("v")>=0)return "metrics";
    if(keys.indexOf("s")>=0&&keys.indexOf("e")>=0)return "gantt";
    if(keys.indexOf("id")>=0&&(keys.indexOf("kind")>=0||keys.indexOf("type")>=0))return "form";
    if(keys.indexOf("children")>=0)return "tree";
    if(keys.indexOf("ys")>=0||keys.indexOf("vals")>=0)return "chart";
    /* 每项 = 标签 + 单个数值数组 → 多系列折线（scenario/ridgeline/stream/lane/cohort…）；
       真正的矩阵/热图除外，它们走 matrix */
    if(!/matrix|heat/.test(comp)){
      var ak=0,lab=false;
      for(var kj=0;kj<keys.length;kj++){
        var av=items[0][keys[kj]];
        if(isArr(av)&&av.length&&typeof av[0]==="number")ak++;
        else if(keys[kj]==="n"||keys[kj]==="t"||keys[kj]==="k"||keys[kj]==="label")lab=true;
      }
      if(ak===1&&lab)return "chart";
    }
    for(var ki=0;ki<keys.length;ki++){if(isArr(items[0][keys[ki]])&&items[0][keys[ki]].length)return "tree";}
    if(keys.indexOf("at")>=0||keys.indexOf("time")>=0)return "stepper";
    if(keys.indexOf("v")>=0&&keys.indexOf("max")>=0)return "gauge";
    if(keys.indexOf("f")>=0&&keys.indexOf("t")>=0)return "gantt";
    return "list";
  }
  if(items.length&&typeof items[0]==="number"&&
     (items.length>=5||/spark|line|trend|curve|hist|lorenz|bland|qq|perm|spectrum|series/.test(comp)))return "spark";
  if(items.length&&typeof items[0]!=="object")return "chips";
  /* 没有可识别的数组数据：按顶层值形态分流 */
  var hasObj=false,hasNum=false,hasMtx=false;
  Object.keys(dsl).forEach(function(k){
    if(k==="type")return;var v=dsl[k];
    if(isObj(v)&&Object.keys(v).length)hasObj=true;
    if(isArr(v)&&v.length&&typeof v[0]==="number")hasNum=true;
    if(isArr(v)&&v.length&&isArr(v[0])&&v[0].length)hasMtx=true;
  });
  if(hasObj)return "objtree";
  if(hasMtx)return "matrix";
  if(hasNum)return "spark";
  if(Object.keys(dsl).length<=1)return "note";
  return "generic";
}
function compile(comp,dsl,ctx){
  var layout=resolveLayout(comp,dsl),fn=LAYOUTS[layout];
  if(!fn){layout="generic";fn=LAYOUTS.generic;}
  try{return fn(dsl,comp,ctx);}catch(e){
    var b=el("div","sec");b.appendChild(el("div","empty",esc(comp+" 渲染失败："+(e&&e.message||e))));return b;
  }
}


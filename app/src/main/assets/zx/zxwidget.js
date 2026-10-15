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


var TXT_KEYS  = ["n","t","k","label","title","name","text","q","head","cap","caption"];
var VAL_KEYS  = ["v","value","val","cnt","count","num","amount","score","p"];
var SUB_KEYS  = ["d","desc","detail","why","note","body","content","msg","summary","sub"];
var WHO_KEYS  = ["who","by","user","owner","author","assignee"];
var AT_KEYS   = ["at","time","when","ts","date","due"];
var ST_KEYS   = ["st","status","state","phase","level","lv"];
var COLOR_KEYS= ["hex","color","c","tint"];
var UNIT_KEYS = ["unit","u"];
var ICON_KEYS = ["e","icon","emoji","i"];

/* 归一化键名：小写、只留字母数字、去掉尾部的编号。
   title2 / title_1 / TITLE → title，用来兜住模型自造的命名。 */
function normKey(k){ return String(k).toLowerCase().replace(/[^a-z0-9]/g,"").replace(/[0-9]+$/,""); }
/* 取槽位：先精确命中（保持既有行为与优先级），再归一命中。
   🔴 只做精确比对是不够的：模型常给字段加后缀或换大小写
      （title2 / owner_name / theCity），精确比对会全部落空，
      于是每个对象里只有 harvest 捞到的第一个标量显示得出来 ——
      表现就是「外壳照画、格子里空的」。 */
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
      /* 长键（>=3）允许包含匹配：owner_name / theCity */
      if(w.length>=3 && n.indexOf(w)>=0) return v;
    }
  }
  return "";
}
/* 兜底采集时要跳过的键：结构键（数组/子节点）、已在别的槽位取过的视觉键、
   以及布局标记。不跳的话 txt 会把 "#5046E4" 当标题显示出来。 */
var HARVEST_SKIP = ["items","rows","nodes","pts","steps","cols","columns","options","days","cells",
  "slots","groups","blocks","marks","stages","fields","links","opts","cards","levels","series",
  "people","labels","tags","tasks","children","kids","panels","sections","v","edges",
  "hex","color","c","tint","e","icon","emoji","i","unit","u","x","y","w","h","pct","p",
  "type","id","kind","k","_raw","ref","on","auto","must","me","done","checked"];
/* 🔴 自造字段名的兜底采集。
   契约词汇（n/v/t/k…）之外的字段名是**常态**——模型按业务起名（temp/high/low/city/
   weather/day），而契约里没有这些词。此前这些数据**一个都不显示**：
   组件外壳照画（格子、表头都在），格子里全是空的，
   于是用户看到的就是「zxwidget 根本渲染不了 / 画出来是空的」。
   现在补最后一跳：数值槽取「第一个像数字的标量」，文本槽取「第一个不是数字的标量」。
   两者取值域互补，所以同一个值不会被两个槽位重复显示。 */
function harvest(o, numOnly){
  for(var k in o){
    if(HARVEST_SKIP.indexOf(k)>=0) continue;
    var v=o[k];
    if(v===undefined||v===null||v==="") continue;
    if(typeof v==="object") continue;
    var sv=String(v);
    var isNum=/^-?[0-9]+(\.[0-9]+)?$/.test(sv);
    if(numOnly ? !isNum : isNum) continue;
    return sv;
  }
  return "";
}
function isObj(v){ return v!==null&&typeof v==="object"&&!Array.isArray(v); }
function isArr(v){ return Array.isArray(v); }
function esc(s){
  return String(s===undefined||s===null?"":s)
    .replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;");
}
function txt(o){ var v=pick(o,TXT_KEYS); if(v!=="") return String(v); return harvest(o,false); }
function val(o){ var v=pick(o,VAL_KEYS); if(v!=="") return String(v); return harvest(o,true); }
function sub(o){ var v=pick(o,SUB_KEYS); return v===""?"":String(v); }
function who(o){ var v=pick(o,WHO_KEYS); return v===""?"":String(v); }
function at(o){ var v=pick(o,AT_KEYS); return v===""?"":String(v); }
function stOf(o){ var v=pick(o,ST_KEYS); return v===""?"":String(v); }
function colorOf(o){ var v=pick(o,COLOR_KEYS); return (typeof v==="string"&&/^#[0-9a-f]{3,8}$/i.test(v))?v:""; }
function iconOf(o){ var v=pick(o,ICON_KEYS); return (typeof v==="string"&&v.length<=4)?v:""; }
function num(v, d){ var n=parseFloat(v); return isFinite(n)?n:(d||0); }

/* 键名汉化：杜绝把英文内部字段名当界面内容原样摆出来
   （用户截图里的 init / dead / dept / doctor / date / data / bench 等泄漏来源）。
   约定：HUMANIZE 命中的键 → 显示中文标签；未映射的英文键一律不显示（只显值）。 */
var HUMANIZE = {
  bench:"基准", method:"方法", data:"数据", stale:"过期天数", sig:"显著性",
  reserve:"底价", prior:"先验概率", offer:"报价", mymax:"我方上限", max:"上限",
  level:"级别", items:"序列", init:"初始", hismin:"对方下限", doctor:"医生",
  detail:"说明", dept:"科室", date:"日期", cur:"当前", budget:"预算",
  n:"名称", v:"值", k:"键", t:"标题", d:"说明", s:"起始", e:"结束",
  st:"状态", at:"时间", who:"负责人", op:"操作", eq:"等权",
  lp:"似然+", ln:"似然-", np:"先验+", nn:"先验-",
  name:"名称", title:"标题", desc:"描述", value:"值", count:"数量", total:"合计",
  status:"状态", time:"时间", owner:"负责人", amount:"金额", price:"价格",
  result:"结果", score:"得分", reason:"原因", operator:"操作人",
  supply:"供给", demand:"需求", prices:"价格", lhs:"左式", expr:"表达式",
  ctrl:"对照组", subs:"替换", treat:"处理组", shares:"份额", points:"点数",
  hist:"历史", cand:"候选", text:"正文", reposts:"转发", quote:"引用",
  likes:"赞", bands:"频段", key:"键", period:"周期", periods:"周期",
  unit:"单位", tol:"容差", src:"来源", servers:"服务节点", ratio:"比率",
  prevWho:"前序负责人", nextWho:"后序负责人", p1x:"点1X", p1y:"点1Y", p2x:"点2X", p2y:"点2Y",
  maxq:"最大量", maxLen:"最大长度", lambda:"参数", hiscost:"历史成本",
  fixed:"固定值", err:"错误", cost:"成本", claim:"申领", benefit:"收益",
  from:"起始", hint:"提示"
};
function humanKey(k){ if(!k) return ""; if(HUMANIZE[k]!==undefined) return HUMANIZE[k]; return String(k); }
/* 仅返回已汉化的标签；未映射的英文键一律不显示（只显值），杜绝键名泄漏 */
function renderKvLabel(k){
  if(HUMANIZE[k]!==undefined) return HUMANIZE[k];
  return null;
}

/* 主数组：取契约里声明的第一个数组字段，回退到常见名 */
function mainArr(dsl, comp){
  var ct = ZX_CONTRACTS[comp] || {};
  var arrs = ct.arrays || [];
  for(var i=0;i<arrs.length;i++){ if(isArr(dsl[arrs[i]])&&dsl[arrs[i]].length) return dsl[arrs[i]]; }
  for(var i=0;i<arrs.length;i++){ if(isArr(dsl[arrs[i]])) return dsl[arrs[i]]; }
  var cand=["items","rows","nodes","pts","steps","v","edges","cols","options","days","cells",
            "slots","groups","blocks","marks","stages","fields","links","opts","cards","levels",
            "series","people","columns","labels","tags","tasks","children","kids"];
  for(var j=0;j<cand.length;j++){ if(isArr(dsl[cand[j]])&&dsl[cand[j]].length) return dsl[cand[j]]; }
  return [];
}
/* 子数组：容器类组件递归用 */
function childArr(dsl){
  var cand=["children","kids","items","blocks","panels","sections","groups"];
  for(var i=0;i<cand.length;i++){ if(isArr(dsl[cand[i]])) return dsl[cand[i]]; }
  return [];
}

/* ═══════════════════════════════════════════════════════════════
   3. DOM 助手
   ═══════════════════════════════════════════════════════════════ */
function el(tag, cls, html){
  var n=document.createElement(tag);
  if(cls) n.className=cls;
  if(html!==undefined&&html!==null) n.innerHTML=html;
  return n;
}
function svg(tag, attrs){
  var n=document.createElementNS("http://www.w3.org/2000/svg", tag);
  for(var k in attrs) n.setAttribute(k, attrs[k]);
  return n;
}

/* ═══════════════════════════════════════════════════════════════
   4. 布局原语（按契约编译，不是猜）
   ═══════════════════════════════════════════════════════════════ */
var LAYOUTS = {};

/* ---- 4.1 表格：columns + rows ---- */
LAYOUTS.table = function(dsl, comp, ctx){
  /* cols 是模型常用的别名，一并认下（契约真名是 columns） */
  var cols = isArr(dsl.columns)?dsl.columns:(isArr(dsl.cols)?dsl.cols:[]);
  var rows = isArr(dsl.rows)?dsl.rows:mainArr(dsl, comp);
  var box = el("div","sec"), wrap=el("div","tw"), tb=el("table","t");
  if(!cols.length && !rows.length){ box.appendChild(el("div","empty","暂无表格数据")); return box; }
  function colKey(c){ return isObj(c)?(pick(c,["key","k","field","id"])||txt(c)||String(c)):String(c); }
  var thead=el("thead"), tr=el("tr");
  cols.forEach(function(c){
    var h = isObj(c)?(txt(c)||colKey(c)):String(c);
    var th=el("th",null,esc(h)); tr.appendChild(th);
  });
  if(!cols.length && rows.length && isObj(rows[0])){
    Object.keys(rows[0]).forEach(function(k){ tr.appendChild(el("th",null,esc(k))); });
  }
  thead.appendChild(tr); tb.appendChild(thead);
  var tbody=el("tbody");
  rows.forEach(function(r, ri){
    var tr2=el("tr");
    /* 🔴 旧码这里是 `if(!isObj(r)) return;` —— 把数组行**整行丢掉**。
       而契约自己的 sample 就是数组行：
         rows:[["服务器","1200","按月"],["域名","80","年付"]]
       于是「照契约生成的表格」只剩表头、正文一格没有，也就是用户看到的空盒子。
       现在：数组行按**位置**与 columns 对齐；标量行也照画，不许静默丢。 */
    var cells;
    if(isArr(r)){
      var n = cols.length || r.length;
      cells = [];
      for(var ci0=0; ci0<n; ci0++) cells.push(r[ci0]);
    } else if(isObj(r)){
      if(cols.length){
        var ks=Object.keys(r);
        cells = cols.map(function(c, ci){
          var key = colKey(c);
          if(r[key]!==undefined) return r[key];
          /* 列名与对象键对不上：先按位置退让，再如实留空 */
          var hit;
          ks.forEach(function(o2){
            if(hit===undefined && String(normKey(o2)).indexOf(normKey(key))>=0 && normKey(key)) hit=r[o2];
          });
          if(hit!==undefined) return hit;
          return ci<ks.length ? r[ks[ci]] : "";
        });
      } else {
        cells = Object.keys(r).map(function(k){ return r[k]; });
      }
    } else {
      cells = [r];
    }
    cells.forEach(function(cv, ci){
      var td=el("td");
      if(typeof cv==="number") td.className="num";
      td.innerHTML = isObj(cv)?esc(txt(cv)||JSON.stringify(cv)):esc(cv===null?"—":(cv===undefined?"":cv));
      td.addEventListener("click", function(e){
        e.stopPropagation();
        ctx.interact("cell", {row:ri, col:ci, key:cols[ci]?colKey(cols[ci]):ci, value:cv});
      });
      tr2.appendChild(td);
    });
    tr2.addEventListener("click", function(){ ctx.interact("row", {row:ri, data:r}); });
    tbody.appendChild(tr2);
  });
  tb.appendChild(tbody); wrap.appendChild(tb); box.appendChild(wrap);
  return box;
};

/* ---- 4.2 指标卡组：items[k+v] ---- */
LAYOUTS.metrics = function(dsl, comp, ctx){
  var items = mainArr(dsl, comp);
  var box=el("div","sec");
  if(!items.length){
    var mph = txt(dsl)||dsl.title||dsl.label||"";
    box.appendChild(el("div","empty", mph?esc(mph):"（暂无指标）"));
    return box;
  }
  var g=el("div","mgrid");
  items.forEach(function(it, i){
    if(!isObj(it)) return;
    var m=el("div","met");
    var k=txt(it)||"", v=val(it);
    if(!k) k = pick(it,["k","label","name"])||"";
    var d=sub(it), w=who(it), a=at(it);
    m.appendChild(el("div","met-k",esc(k)));
    var vv=el("div","met-v",esc(v||"—"));
    m.appendChild(vv);
    if(d) m.appendChild(el("div","met-d",esc(d)));
    var meta=[];
    if(w) meta.push(esc(w));
    if(a) meta.push(esc(a));
    if(meta.length) m.appendChild(el("div","met-d",meta.join(" · ")));
    m.addEventListener("click", function(){ ctx.interact("metric", {index:i, key:k, value:v}); });
    g.appendChild(m);
  });
  box.appendChild(g);
  return box;
};

/* ---- 4.3 列表 ---- */
LAYOUTS.list = function(dsl, comp, ctx){
  var items = mainArr(dsl, comp);
  var box=el("div","sec"), l=el("div","lst");
  if(!items.length) l.appendChild(el("div","empty","暂无条目"));
  items.forEach(function(it, i){
    var b=el("button","li");
    var ic=iconOf(it);
    b.appendChild(el("div","idx", ic?esc(ic):String(i+1)));
    var inner=el("div","li-i");
    var t = isObj(it)?(txt(it)||val(it)):String(it);
    inner.appendChild(el("div","li-t",esc(t)));
    var d = isObj(it)?sub(it):"";
    if(d) inner.appendChild(el("div","li-d",esc(d)));
    var meta=[];
    var w=who(it); if(w) meta.push(esc(w));
    var a=at(it); if(a) meta.push(esc(a));
    var s=stOf(it); if(s) meta.push(esc(s));
    if(meta.length) inner.appendChild(el("div","li-m",meta.join(" · ")));
    b.appendChild(inner);
    var c=colorOf(it);
    if(c){ var dot=el("div","li-r"); var sp=el("span","st"); sp.style.background=c; sp.style.width="10px"; sp.style.height="10px"; sp.style.padding="0"; sp.style.borderRadius="50%"; dot.appendChild(sp); b.appendChild(dot); }
    b.addEventListener("click", function(){ ctx.interact("item", {index:i, data:isObj(it)?it:{value:it}}); });
    l.appendChild(b);
  });
  box.appendChild(l);
  return box;
};

/* ---- 4.4 步骤 / 时间线 ---- */
LAYOUTS.stepper = function(dsl, comp, ctx){
  var steps = mainArr(dsl, comp);
  var cur = dsl.cur!==undefined?num(dsl.cur,-1):-1;
  var box=el("div","sec"), l=el("div","steps");
  if(!steps.length) l.appendChild(el("div","empty","暂无步骤"));
  steps.forEach(function(s, i){
    var row=el("div","stp"+(i===cur?" cur":"")+(i<cur?" done":""));
    row.appendChild(el("div","stp-dot"));
    var b=el("div","stp-b");
    var t=isObj(s)?(txt(s)||("步骤 "+(i+1))):String(s);
    b.appendChild(el("div","stp-t",esc(t)));
    var d=isObj(s)?sub(s):"";
    if(d) b.appendChild(el("div","stp-d",esc(d)));
    var a=isObj(s)?at(s):"";
    if(a) b.appendChild(el("div","stp-m",esc(a)));
    row.appendChild(b);
    row.addEventListener("click", function(){ ctx.interact("step", {index:i, current:cur}); });
    l.appendChild(row);
  });
  box.appendChild(l);
  return box;
};

/* ---- 4.5 表单：fields + submit ---- */
LAYOUTS.form = function(dsl, comp, ctx){
  var fields = isArr(dsl.fields)?dsl.fields:mainArr(dsl, comp);
  var box=el("div","sec"), f=el("form","fm");
  f.addEventListener("submit", function(e){ e.preventDefault(); });
  fields.forEach(function(fd, i){
    if(!isObj(fd)) return;
    var id = pick(fd,["id","key","k","name"])||("f"+i);
    var kind = (pick(fd,["kind","type","t"])||"text").toLowerCase();
    var label = txt(fd)||id;
    var row=el("div","fld");
    row.appendChild(el("div","fld-l",esc(label)));
    var inp;
    if(kind==="select"||kind==="dropdown"||kind==="choice"){
      inp=el("select");
      var opts=isArr(fd.options)?fd.options:[];
      opts.forEach(function(o){
        var ov=isObj(o)?(val(o)||txt(o)):o;
        var ot=isObj(o)?(txt(o)||val(o)):o;
        var op=el("option",null,esc(ot)); op.value=String(ov); inp.appendChild(op);
      });
    } else if(kind==="textarea"||kind==="multiline"||kind==="long"){
      inp=el("textarea");
    } else if(kind==="switch"||kind==="toggle"||kind==="bool"){
      inp=el("input"); inp.type="checkbox"; inp.style.width="22px"; inp.style.height="22px"; inp.style.minHeight="0";
    } else if(kind==="slider"||kind==="range"){
      inp=el("input"); inp.type="range";
      if(fd.min!==undefined) inp.min=fd.min;
      if(fd.max!==undefined) inp.max=fd.max;
    } else if(kind==="number"){
      inp=el("input"); inp.type="number";
      if(fd.min!==undefined) inp.min=fd.min;
      if(fd.max!==undefined) inp.max=fd.max;
    } else if(kind==="date"||kind==="time"){
      inp=el("input"); inp.type=kind;
    } else {
      inp=el("input"); inp.type="text";
    }
    inp.id="fld_"+id; inp.name=id;
    if(fd.placeholder) inp.placeholder=fd.placeholder;
    if(fd.default!==undefined||fd.v!==undefined) inp.value=fd.default!==undefined?fd.default:fd.v;
    row.appendChild(inp);
    f.appendChild(row);
  });
  var submitLabel = dsl.submit||dsl.action||"提交";
  var btn=el("button","btn btn-p btn-w",esc(submitLabel));
  btn.type="submit";
  btn.addEventListener("click", function(){
    var out={};
    fields.forEach(function(fd, i){
      if(!isObj(fd)) return;
      var id = pick(fd,["id","key","k","name"])||("f"+i);
      var node=document.getElementById("fld_"+id);
      if(!node) return;
      out[id] = node.type==="checkbox"?node.checked:node.value;
    });
    ctx.interact("submit", {values:out});
  });
  f.appendChild(btn);
  box.appendChild(f);
  return box;
};

/* ---- 4.6 选项卡 ---- */
LAYOUTS.choice = function(dsl, comp, ctx){
  var opts = isArr(dsl.options)?dsl.options:(isArr(dsl.opts)?dsl.opts:mainArr(dsl, comp));
  var box=el("div","sec"), row=el("div","opt-row");
  row.style.padding="14px";
  if(!opts.length){ box.appendChild(el("div","empty","暂无选项")); return box; }
  opts.forEach(function(o, i){
    var label = isObj(o)?(txt(o)||val(o)):String(o);
    var b=el("button","btn",esc(label));
    b.addEventListener("click", function(){ ctx.interact("choose", {index:i, option:label}); });
    row.appendChild(b);
  });
  box.appendChild(row);
  return box;
};

/* ---- 4.7 标签 ---- */
LAYOUTS.chips = function(dsl, comp, ctx){
  var tags = isArr(dsl.tags)?dsl.tags:(isArr(dsl.labels)?dsl.labels:mainArr(dsl, comp));
  var box=el("div","sec"), row=el("div","chips");
  if(!tags.length){ box.appendChild(el("div","empty","暂无标签")); return box; }
  tags.forEach(function(t, i){
    var label = isObj(t)?(txt(t)||val(t)):String(t);
    var c=el("button","chip",esc(label));
    c.addEventListener("click", function(){ ctx.interact("tag", {index:i, tag:label}); });
    row.appendChild(c);
  });
  box.appendChild(row);
  return box;
};

/* ---- 4.8 图表：pts / series → SVG 折线或柱状 ---- */
LAYOUTS.chart = function(dsl, comp, ctx){
  var box=el("div","sec");
  var pts = isArr(dsl.pts)?dsl.pts:(isArr(dsl.series)?dsl.series:mainArr(dsl, comp));
  /* ── 多系列：每项自带数值数组（{n:"乐观",v:[100,128,…]}）──
     契约里有 22 个组件长这样（zx_radar / zx_stream / zx_scenario /
     zx_ridgeline / zx_cohort / zx_lane …）。没有这条分支时，整组数值数组
     会被喂给 num() → NaN → 坐标轴与折线全是 NaN，画出来是一块空白；
     用户看到的就是「zxwidget 渲染不出图表」。
     检测到「每项都有一个数值数组字段」就按多系列折线画：至少可见、可交互。 */
  var sk=null;
  if(pts.length && isObj(pts[0])){
    Object.keys(pts[0]).forEach(function(k){
      var v=pts[0][k];
      if(!sk && isArr(v) && v.length && typeof v[0]==="number") sk=k;
    });
  }
  if(sk){
    var mW=660,mH=200,mP=30,mAll=[];
    pts.forEach(function(p){ (isArr(p[sk])?p[sk]:[]).forEach(function(x){ mAll.push(num(x)); }); });
    var Mx=Math.max.apply(null,mAll.concat([1])), Mn=Math.min.apply(null,mAll.concat([0]));
    if(Mx===Mn) Mx=Mn+1;
    var ms=svg("svg",{viewBox:"0 0 "+mW+" "+mH,class:"chart",preserveAspectRatio:"xMidYMid meet"});
    for(var mg=0;mg<=4;mg++){
      var mgy=mP+(mH-mP*2)*mg/4;
      ms.appendChild(svg("line",{x1:mP,y1:mgy,x2:mW-mP,y2:mgy,stroke:"var(--line)","stroke-width":"1"}));
      var mtv=el("text",null,esc((Mx-(Mx-Mn)*mg/4).toFixed(Mx>10?0:1)));
      mtv.setAttribute("x",2); mtv.setAttribute("y",mgy+4); mtv.setAttribute("fill","var(--fg-3)"); mtv.setAttribute("font-size","9.5");
      ms.appendChild(mtv);
    }
    var mCols=["var(--accent)","#e8590c","#1c7ed6","#0ca678","#ae3ec9","#f03e9c"];
    var mN=(isArr(pts[0][sk])?pts[0][sk].length:0), mBw=(mW-mP*2)/Math.max(mN,1);
    pts.forEach(function(p,si){
      var col=mCols[si%mCols.length], d="";
      (isArr(p[sk])?p[sk]:[]).forEach(function(x,i){
        var y=mH-mP-(num(x)-Mn)/(Mx-Mn)*(mH-mP*2);
        d+=(i?"L":"M")+(mP+mBw*i+mBw/2).toFixed(1)+" "+y.toFixed(1);
      });
      ms.appendChild(svg("path",{d:d,fill:"none",stroke:col,"stroke-width":"2","stroke-linejoin":"round","stroke-linecap":"round",opacity:.9}));
      (isArr(p[sk])?p[sk]:[]).forEach(function(x,i){
        var cx=mP+mBw*i+mBw/2, cy=mH-mP-(num(x)-Mn)/(Mx-Mn)*(mH-mP*2);
        var c=svg("circle",{cx:cx,cy:cy,r:3.2,fill:"var(--surface)",stroke:col,"stroke-width":"2"});
        c.style.cursor="pointer";
        c.addEventListener("click",function(){ ctx.interact("point",{series:txt(p)||("系列"+(si+1)),index:i,value:num(x)}); });
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
  var W=680,H=220,P=32;
  var s=svg("svg",{viewBox:"0 0 "+W+" "+H,class:"chart",preserveAspectRatio:"xMidYMid meet"});
  var nums = pts.map(function(p){ return isObj(p)?num(pick(p,VAL_KEYS)||p.v||p.y||0):num(p); });
  var labels = pts.map(function(p,i){ return isObj(p)?(txt(p)||pick(p,["x","label","n","t"])||""):String(i+1); });
  var mx=Math.max.apply(null,nums.concat([1])), mn=Math.min.apply(null,nums.concat([0]));
  if(mx===mn) mx=mn+1;
  var bw = (W-P*2)/Math.max(nums.length,1);
  /* 网格线 */
  for(var g=0;g<=4;g++){
    var gy=P+(H-P*2)*g/4;
    s.appendChild(svg("line",{x1:P,y1:gy,x2:W-P,y2:gy,stroke:"var(--line)","stroke-width":"1"}));
    var tv=el("text",null,esc((mx-(mx-mn)*g/4).toFixed(mx>10?0:1)));
    tv.setAttribute("x",4); tv.setAttribute("y",gy+4); tv.setAttribute("fill","var(--fg-3)");
    tv.setAttribute("font-size","10"); tv.setAttribute("font-family","var(--mono)");
    s.appendChild(tv);
  }
  var isBar = comp.indexOf("bar")>=0 || comp.indexOf("column")>=0 || comp.indexOf("hist")>=0;
  nums.forEach(function(v,i){
    var h=(v-mn)/(mx-mn)*(H-P*2);
    var x=P+bw*i+bw*0.18, w=bw*0.64, y=H-P-h;
    if(isBar){
      var r=svg("rect",{x:x,y:y,width:w,height:Math.max(h,1),rx:3,fill:"var(--accent)",opacity:0.85});
      r.style.cursor="pointer";
      r.addEventListener("click",function(){ ctx.interact("bar",{index:i,label:labels[i],value:v}); });
      s.appendChild(r);
    }
    var lb=el("text",null,esc(labels[i]));
    lb.setAttribute("x",P+bw*i+bw/2); lb.setAttribute("y",H-P+14);
    lb.setAttribute("text-anchor","middle"); lb.setAttribute("fill","var(--fg-3)");
    lb.setAttribute("font-size","10"); s.appendChild(lb);
  });
  if(!isBar && nums.length>1){
    var d="";
    nums.forEach(function(v,i){
      var x=P+bw*i+bw/2, y=H-P-(v-mn)/(mx-mn)*(H-P*2);
      d += (i?"L":"M")+x.toFixed(1)+" "+y.toFixed(1);
    });
    s.appendChild(svg("path",{d:d,fill:"none",stroke:"var(--accent)","stroke-width":"2.5","stroke-linejoin":"round","stroke-linecap":"round"}));
    nums.forEach(function(v,i){
      var x=P+bw*i+bw/2, y=H-P-(v-mn)/(mx-mn)*(H-P*2);
      var c=svg("circle",{cx:x,cy:y,r:4,fill:"var(--surface)",stroke:"var(--accent)","stroke-width":"2.5"});
      c.style.cursor="pointer";
      c.addEventListener("click",function(){ ctx.interact("point",{index:i,label:labels[i],value:v}); });
      s.appendChild(c);
    });
  }
  box.appendChild(s);
  var title = txt(dsl)||dsl.title||"";
  if(title) box.appendChild(el("div","sec-t",esc(title)));
  return box;
};

/* ---- 4.9 甘特：tasks[n+s+e] ---- */
LAYOUTS.gantt = function(dsl, comp, ctx){
  var tasks = isArr(dsl.tasks)?dsl.tasks:mainArr(dsl, comp);
  var box=el("div","sec"), wrap=el("div","sec-b");
  var lo=Infinity, hi=-Infinity;
  tasks.forEach(function(t){
    var s=num(isObj(t)?(t.s!==undefined?t.s:t.from):0), e=num(isObj(t)?(t.e!==undefined?t.e:t.to):0);
    if(s<lo) lo=s; if(e>hi) hi=e;
  });
  if(!isFinite(lo)){ lo=0; hi=1; }
  if(hi<=lo) hi=lo+1;
  var W=680,ROW=34;
  var s=svg("svg",{viewBox:"0 0 "+W+" "+(ROW*tasks.length+8),class:"chart",preserveAspectRatio:"xMidYMid meet"});
  tasks.forEach(function(t,i){
    var y=i*ROW+4;
    var sv=num(isObj(t)?(t.s!==undefined?t.s:t.from):0), ev=num(isObj(t)?(t.e!==undefined?t.e:t.to):0);
    var x1=(sv-lo)/(hi-lo)*(W-150)+150, x2=(ev-lo)/(hi-lo)*(W-150)+150;
    var nm=isObj(t)?(txt(t)||("任务"+(i+1))):String(t);
    var lb=el("text",null,esc(nm.length>12?nm.slice(0,12)+"…":nm));
    lb.setAttribute("x",0); lb.setAttribute("y",y+18); lb.setAttribute("fill","var(--fg-2)"); lb.setAttribute("font-size","11.5");
    s.appendChild(lb);
    var col = colorOf(t)||"var(--accent)";
    var r=svg("rect",{x:x1,y:y+7,width:Math.max(x2-x1,3),height:16,rx:5,fill:col,opacity:0.9});
    r.style.cursor="pointer";
    r.addEventListener("click",function(){ ctx.interact("task",{index:i,name:nm,start:sv,end:ev}); });
    s.appendChild(r);
    s.appendChild(svg("line",{x1:150,y1:y+2,x2:150,y2:y+28,stroke:"var(--line)","stroke-width":"1"}));
  });
  wrap.appendChild(s); box.appendChild(wrap);
  return box;
};

/* ---- 4.10 看板：cols[cards] ---- */
LAYOUTS.kanban = function(dsl, comp, ctx){
  var cols = isArr(dsl.cols)?dsl.cols:mainArr(dsl, comp);
  var box=el("div","sec"), kb=el("div","kb");
  if(!cols.length){ box.appendChild(el("div","empty","（暂无看板列）")); return box; }
  cols.forEach(function(c, ci){
    var col=el("div","kb-c");
    var name=isObj(c)?(txt(c)||("列 "+(ci+1))):String(c);
    var cards=isObj(c)?(isArr(c.cards)?c.cards:(isArr(c.items)?c.items:[])):[];
    col.appendChild(el("div","kb-h","<span>"+esc(name)+"</span><span>"+cards.length+"</span>"));
    cards.forEach(function(cd, ki){
      var it=el("div","kb-i",esc(isObj(cd)?(txt(cd)||val(cd)):String(cd)));
      it.addEventListener("click",function(){ ctx.interact("card",{col:ci,card:ki,data:isObj(cd)?cd:{value:cd}}); });
      col.appendChild(it);
    });
    kb.appendChild(col);
  });
  box.appendChild(kb);
  return box;
};

/* ---- 4.11 日历 / 热力 ---- */
LAYOUTS.calendar = function(dsl, comp, ctx){
  var box=el("div","sec"), cal=el("div","cal");
  var days = isArr(dsl.days)?dsl.days:(isArr(dsl.cells)?dsl.cells:mainArr(dsl, comp));
  var hd=el("div","cal-h");
  ["一","二","三","四","五","六","日"].forEach(function(w){ hd.appendChild(el("div",null,esc(w))); });
  cal.appendChild(hd);
  var g=el("div","cal-g");
  days.forEach(function(d, i){
    var cell=el("div","cd");
    var label = isObj(d)?(val(d)||txt(d)):d;
    cell.innerHTML = esc(label);
    if(isObj(d)&&d.on) cell.className="cd on";
    else if(isObj(d)&&(d.hot||d.v!==undefined&&num(d.v)>0)) cell.className="cd hot";
    cell.addEventListener("click",function(){ ctx.interact("day",{index:i,data:isObj(d)?d:{value:d}}); });
    g.appendChild(cell);
  });
  cal.appendChild(g); box.appendChild(cal);
  return box;
};

/* ---- 4.12 组织 / 树：nodes 递归 ---- */
LAYOUTS.tree = function(dsl, comp, ctx){
  var nodes = isArr(dsl.nodes)?dsl.nodes:(isArr(dsl.people)?dsl.people:mainArr(dsl, comp));
  var box=el("div","sec"), wrap=el("div","tree");
  if(!nodes.length){ box.appendChild(el("div","empty","暂无节点")); return box; }
  function draw(list, depth){
    var holder=el("div");
    (list||[]).forEach(function(n, i){
      if(typeof n==="string"){
        var srow=el("div","tn");
        srow.appendChild(el("div","tn-tw",""));
        srow.appendChild(el("div","tn-t",esc(n)));
        srow.addEventListener("click",function(){ ctx.interact("node",{depth:depth,index:i,data:n}); });
        holder.appendChild(srow);
        return;
      }
      if(!isObj(n)) return;
      var row=el("div","tn");
      var tw=el("div","tn-tw", (isArr(n.children)&&n.children.length)?"▸":"");
      tw.addEventListener("click",function(e){
        e.stopPropagation();
        var sub=row.nextSibling;
        if(sub&&sub.className==="tn-k"){ sub.style.display = sub.style.display==="none"?"":"none"; tw.innerHTML = sub.style.display==="none"?"▾":"▸"; }
      });
      row.appendChild(tw);
      row.appendChild(el("div","tn-t",esc(txt(n)||val(n))));
      var s=stOf(n); if(s) row.appendChild(el("span","st st-info",esc(s)));
      row.addEventListener("click",function(){ ctx.interact("node",{depth:depth,index:i,data:n}); });
      holder.appendChild(row);
      var kids = isArr(n.children)?n.children:(isArr(n.kids)?n.kids:(isArr(n.items)?n.items:(isArr(n.rings)?n.rings:null)));
      if(kids&&kids.length){
        var k=el("div","tn-k"); k.style.display="none"; k.appendChild(draw(kids, depth+1));
        holder.appendChild(k);
      }
    });
    return holder;
  }
  wrap.appendChild(draw(nodes,0)); box.appendChild(wrap);
  return box;
};

/* ---- 4.13 提示条 ---- */
LAYOUTS.note = function(dsl, comp, ctx){
  var lvl = (stOf(dsl)||"").toLowerCase();
  var cls = "note"+(lvl==="ok"||lvl==="success"||lvl==="done"?" ok":(lvl==="warn"||lvl==="warning"?" warn":(lvl==="err"||lvl==="error"?" err":"")));
  var box=el("div",cls);
  var ic = lvl==="ok"||lvl==="success"?"✓":(lvl==="warn"?"!":(lvl==="err"||lvl==="error"?"✕":"i"));
  box.appendChild(el("div","note-i",esc(ic)));
  var b=el("div",null);
  var t=txt(dsl)||dsl.title||"";
  if(t) b.appendChild(el("div",null,"<b>"+esc(t)+"</b>"));
  var d=sub(dsl)||dsl.content||dsl.body||"";
  if(d) b.appendChild(el("div",null,esc(d)));
  if(!t && !d) b.appendChild(el("div",null,esc("（暂无内容）")));
  box.appendChild(b);
  box.addEventListener("click",function(){ ctx.interact("note",{}); });
  return box;
};

/* ---- 4.14 终端 ---- */
LAYOUTS.terminal = function(dsl, comp, ctx){
  var lines = isArr(dsl.lines)?dsl.lines:mainArr(dsl, comp);
  var box=el("div","sec"), t=el("div","term");
  lines.forEach(function(l){
    var s = isObj(l)?(txt(l)||val(l)):String(l);
    var d=el("div");
    if(/^\s*[$#>]/.test(s)) d.className="p";
    else if(/error|fail|exception/i.test(s)) d.className="e";
    else if(/warn/i.test(s)) d.className="w";
    d.textContent=s;
    t.appendChild(d);
  });
  box.appendChild(t);
  return box;
};

/* ---- 4.15 差异 ---- */
LAYOUTS.diff = function(dsl, comp, ctx){
  var hunks = isArr(dsl.hunks)?dsl.hunks:mainArr(dsl, comp);
  var box=el("div","sec"), d=el("div","diff");
  hunks.forEach(function(h){
    if(isObj(h)){
      d.appendChild(el("div","dl hunk","<span class='g'>@@</span>"+esc(h.at||h.n||"")));
      var lines = isArr(h.lines)?h.lines:[];
      lines.forEach(function(l){
        var s=isObj(l)?(txt(l)||val(l)):String(l);
        var cls = /^\+/.test(s)?"add":(/^-/.test(s)?"del":"");
        var row=el("div","dl "+cls,"<span class='g'></span>"+esc(s));
        d.appendChild(row);
      });
    } else {
      var s2=String(h);
      var cls2=/^\+/.test(s2)?"add":(/^-/.test(s2)?"del":"");
      d.appendChild(el("div","dl "+cls2,"<span class='g'></span>"+esc(s2)));
    }
  });
  box.appendChild(d);
  return box;
};

/* ---- 4.16 评分 ---- */
LAYOUTS.rating = function(dsl, comp, ctx){
  var box=el("div","sec");
  var dims = isArr(dsl.dims)?dsl.dims:[];
  if(dims.length){
    dims.forEach(function(dm,di){
      if(!isObj(dm)) return;
      var max=num(dm.max,5), v=num(dm.v!==undefined?dm.v:dm.value,0);
      var label=txt(dm)||("维度 "+(di+1));
      box.appendChild(el("div",null,"<div style='font-size:13px;color:var(--fg-2);margin:10px 14px 0'>"+esc(label)+"</div>"));
      var row=el("div","rate");
      for(var i=1;i<=max;i++){
        var s=el("div","rate-s"+(i<=v?" on":""),"★");
        (function(idx){ s.addEventListener("click",function(){ ctx.interact("rate",{dim:txt(dm)||di,value:idx,max:max}); }); })(i);
        row.appendChild(s);
      }
      box.appendChild(row);
    });
  } else {
    var max=num(dsl.max,5), v=num(dsl.v||dsl.value,0);
    var row=el("div","rate");
    for(var i=1;i<=max;i++){
      var s=el("div","rate-s"+(i<=v?" on":""),"★");
      (function(idx){ s.addEventListener("click",function(){ ctx.interact("rate",{value:idx,max:max}); }); })(i);
      row.appendChild(s);
    }
    box.appendChild(row);
  }
  if(sub(dsl)) box.appendChild(el("div","hint",esc(sub(dsl))));
  return box;
};

/* ---- 4.17 进度 / 仪表 ---- */
LAYOUTS.gauge = function(dsl, comp, ctx){
  var items = mainArr(dsl, comp);
  var box=el("div","sec");
  if(!items.length && dsl.v!==undefined){
    var row=el("div","gauge-row");
    var pct=Math.max(0,Math.min(100,num(dsl.v)/num(dsl.max,100)*100));
    row.appendChild(el("div","pb","<div class='pb-f' style='width:"+pct.toFixed(1)+"%'></div>"));
    row.appendChild(el("div",null,esc(dsl.v+""+(dsl.unit||""))));
    box.appendChild(row);
    return box;
  }
  items.forEach(function(it,i){
    var row=el("div","gauge-row");
    var label=isObj(it)?(txt(it)||""):"", v=isObj(it)?val(it):it, mx=isObj(it)?num(it.max,100):100;
    var pct=Math.max(0,Math.min(100,num(v)/mx*100));
    var cls = pct>=80?"ok":(pct>=40?"":"warn");
    row.appendChild(el("div",null,"<div style='font-size:12.5px;color:var(--fg-2);flex:0 0 32%'>"+esc(label)+"</div>"));
    row.appendChild(el("div","pb","<div class='pb-f "+cls+"' style='width:"+pct.toFixed(1)+"%'></div>"));
    row.appendChild(el("div",null,"<span style='font-family:var(--mono);font-size:12px'>"+esc(v)+"</span>"));
    row.addEventListener("click",function(){ ctx.interact("gauge",{index:i,label:label,value:v}); });
    box.appendChild(row);
  });
  return box;
};

/* ---- 4.18 媒体 ---- */
LAYOUTS.media = function(dsl, comp, ctx){
  var box=el("div","sec"), m=el("div","media");
  var src = dsl.src||dsl.url||dsl.v||"";
  var kind=(dsl.kind||dsl.type||"").toLowerCase();
  if(/\.(mp4|webm|mov)/i.test(src)||kind==="video"){
    var v=el("video"); v.src=src; v.controls=true; m.appendChild(v);
  } else if(src){
    var im=el("img"); im.src=src; im.loading="lazy"; m.appendChild(im);
  } else {
    /* 无媒体源：把标题/时长/波形画出来，而不是空白 */
    var tt = txt(dsl)||dsl.title||dsl.n||"";
    if(tt) m.appendChild(el("div",null,"<div style='font-size:14px;font-weight:600;margin-bottom:6px'>"+esc(tt)+"</div>"));
    var dur = dsl.dur!==undefined?num(dsl.dur):NaN;
    if(isFinite(dur)){
      var mm=Math.floor(dur/60), ss=Math.floor(dur%60);
      m.appendChild(el("div",null,"<div style='font-size:12px;color:var(--fg-3)'>"+esc(kind||"媒体")+" · "+mm+":"+(ss<10?"0":"")+ss+"</div>"));
    }
    if(isArr(dsl.peaks) && dsl.peaks.length){
      var wf=el("div",null);
      wf.style.display="flex"; wf.style.alignItems="flex-end"; wf.style.gap="2px"; wf.style.height="40px"; wf.style.marginTop="8px";
      dsl.peaks.forEach(function(p,i){
        var b=el("div",null,""); b.style.flex="1"; b.style.background="var(--accent-soft)"; b.style.borderRadius="2px";
        b.style.height=(Math.max(0.05,Math.min(1,num(p)))*100)+"%";
        b.addEventListener("click",function(){ ctx.interact("peak",{index:i,value:num(p)}); });
        wf.appendChild(b);
      });
      m.appendChild(wf);
    } else {
      m.appendChild(el("div",null,"<div style='font-size:12px;color:var(--fg-3);padding:8px 0'>（无媒体源）</div>"));
    }
  }
  if(sub(dsl)||dsl.caption) m.appendChild(el("div","hint",esc(sub(dsl)||dsl.caption)));
  box.appendChild(m);
  return box;
};

/* ---- 4.19 二维码：绘制确定性伪 QR 矩阵（定位角 + 模块），下方附可读文本 ---- */
LAYOUTS.qr = function(dsl, comp, ctx){
  var box=el("div","sec");
  var wrap=el("div",null); wrap.style.padding="16px"; wrap.style.textAlign="center";
  var data = dsl.text||dsl.v||dsl.code||dsl.value||dsl.url||"";
  var N=21, cell=8, pad=8;
  var W=N*cell+pad*2, H=N*cell+pad*2;
  var g=svg("svg",{viewBox:"0 0 "+W+" "+H, width:W, height:H, style:"color:var(--fg)"});
  function set(x,y,on){ if(on) g.appendChild(svg("rect",{x:pad+x*cell, y:pad+y*cell, width:cell, height:cell, fill:"currentColor"})); }
  var h=0; for(var i=0;i<data.length;i++){ h=(h*31+data.charCodeAt(i))>>>0; }
  for(var y=0;y<N;y++) for(var x=0;x<N;x++){
    if((x<7&&y<7)||(x>=N-7&&y<7)||(x<7&&y>=N-7)) continue;
    var bit=(((h ^ (x*73856093) ^ (y*19349663))>>>0)%100) < 48;
    set(x,y,bit);
  }
  function finder(ox,oy){ for(var a=0;a<7;a++) for(var b=0;b<7;b++){ var edge=(a===0||a===6||b===0||b===6); var core=(a>=2&&a<=4&&b>=2&&b<=4); set(ox+a,oy+b, edge||core); } }
  finder(0,0); finder(N-7,0); finder(0,N-7);
  wrap.appendChild(g);
  var shown=esc(data);
  if(shown) wrap.appendChild(el("div",null,"<div style='font-family:var(--mono);font-size:12px;color:var(--fg-3);word-break:break-all;margin-top:8px'>"+shown+"</div>"));
  box.appendChild(wrap);
  return box;
};

/* ---- 4.19b 条码：从 code 生成确定性竖条 + 可读文本 ---- */
LAYOUTS.barcode = function(dsl, comp, ctx){
  var code = dsl.code || dsl.value || dsl.text || dsl.v || "";
  var box=el("div","sec");
  var wrap=el("div",null); wrap.style.padding="16px"; wrap.style.textAlign="center";
  var s=String(code);
  var W=Math.max(240, s.length*14), H=80;
  var g=svg("svg",{viewBox:"0 0 "+W+" "+H, width:W, height:H, style:"color:var(--fg)"});
  var x=6;
  for(var i=0;i<s.length;i++){
    var c=s.charCodeAt(i);
    var nb=1+(c%3), bw=2+(c%4);
    for(var b=0;b<nb;b++){ if(x>W-6) break; g.appendChild(svg("rect",{x:x, y:6, width:Math.max(1,bw), height:H-22, fill:"currentColor"})); x+=bw+2; }
    x+=3;
  }
  if(!s.length){ for(var p=0;p<W-12;p+=8){ g.appendChild(svg("rect",{x:6+p, y:6, width:4, height:H-22, fill:"currentColor"})); } }
  wrap.appendChild(g);
  wrap.appendChild(el("div","hint", esc(code)));
  box.appendChild(wrap);
  return box;
};

/* ---- 4.20 键值 ---- */
LAYOUTS.keyvalue = function(dsl, comp, ctx){
  var box=el("div","sec"), dl=el("dl","kv");
  function addRow(k,v,mono){
    if(k==="type") return;
    var r=el("div","kv-r");
    r.appendChild(el("div","kv-k",esc(k)));
    var vv=el("div","kv-v",esc(v===null?"—":(v===undefined?"":v)));
    if(mono) vv.style.fontFamily="var(--mono)";
    r.appendChild(vv);
    r.addEventListener("click",function(){ ctx.interact("kv",{key:k,value:v}); });
    dl.appendChild(r);
  }
  var rows = isArr(dsl.rows)?dsl.rows:(isArr(dsl.items)?dsl.items:(isArr(dsl.pairs)?dsl.pairs:[]));
  if(rows.length){
    rows.forEach(function(r){
      if(isArr(r) && r.length>=2){ addRow(r[0], r[1], false); }
      else if(isObj(r)){
        var k = txt(r)||r.k||r.key||"", v = val(r)!==""?val(r):(r.v!==undefined?r.v:(r.value!==undefined?r.value:""));
        addRow(k, v===null?"—":v, !!r.mono);
      }
    });
    box.appendChild(dl); return box;
  }
  /* 兜底：顶层标量（旧契约形态） */
  Object.keys(dsl).forEach(function(k){
    if(k==="type") return;
    var v=dsl[k];
    if(isObj(v)||isArr(v)) return;
    var lab=renderKvLabel(k);
    addRow(lab===null?"":lab, v, false);
  });
  box.appendChild(dl);
  return box;
};

/* ---- 4.21 容器：递归编译子节点 ---- */
LAYOUTS.container = function(dsl, comp, ctx){
  var kids = childArr(dsl);
  var box=el("div");
  box.style.display="flex"; box.style.flexDirection="column"; box.style.gap="12px";
  if(!kids.length){
    box.appendChild(el("div","empty","容器 "+esc(comp)+" 没有子节点"));
    return box;
  }
  kids.forEach(function(k){
    if(!isObj(k)) return;
    var sub = compile(k.type||k.component||"", k, ctx);
    if(sub) box.appendChild(sub);
  });
  return box;
};

/* ---- 4.22 通用对象渲染：按契约逐字段落位（兜底但不猜） ---- */
LAYOUTS.generic = function(dsl, comp, ctx){
  var ct = ZX_CONTRACTS[comp]||{};
  var arrs = ct.arrays||[], flds = ct.fields||[];
  var box=el("div","sec");
  var scalars=[];
  Object.keys(dsl).forEach(function(k){
    if(k==="type") return;
    var v=dsl[k];
    if(isObj(v)||isArr(v)) return;
    scalars.push([k,v]);
  });
  var rendered=false;
  if(scalars.length>=1){
    rendered=true;
    var dl=el("dl","kv");
    scalars.forEach(function(p){
      var lab=renderKvLabel(p[0]);
      var cls=(HUMANIZE[p[0]]!==undefined)?"kv-k":"kv-k mute";
      var kh=lab===null?"":"<div class='"+cls+"'>"+esc(lab)+"</div>";
      dl.appendChild(el("div","kv-r", kh+"<div class='kv-v'>"+esc(p[1])+"</div>"));
    });
    box.appendChild(dl);
  }
  arrs.forEach(function(a){
    if(!isArr(dsl[a])||!dsl[a].length) return;
    rendered=true;
    var items=dsl[a];
    if(isObj(items[0])){
      var keys=Object.keys(items[0]);
      var hasKV = keys.indexOf("k")>=0 && keys.indexOf("v")>=0;
      var g=el("div","mgrid"); g.style.padding="12px 14px";
      items.forEach(function(it,i){
        if(!isObj(it)) return;
        var m=el("div","met");
        m.appendChild(el("div","met-k",esc(hasKV?txt(it):(txt(it)||a+"["+i+"]"))));
        m.appendChild(el("div","met-v",esc(hasKV?val(it):JSON.stringify(it).slice(0,60))));
        m.addEventListener("click",function(){ ctx.interact(a,{index:i,data:it}); });
        g.appendChild(m);
      });
      box.appendChild(el("div","sec-t",esc(humanKey(a))));
      box.appendChild(g);
    } else {
      var row=el("div","chips");
      items.forEach(function(it,i){
        var c=el("button","chip",esc(isObj(it)?JSON.stringify(it):it));
        c.addEventListener("click",function(){ ctx.interact(a,{index:i,value:it}); });
        row.appendChild(c);
      });
      box.appendChild(el("div","sec-t",esc(humanKey(a))));
      box.appendChild(row);
    }
  });
  if(!rendered) box.appendChild(el("div","empty", esc(txt(dsl)||dsl.title||dsl.label||"（暂无内容）")));
  return box;
};

/* ---- 4.23 数值序列：顶层数字数组 → 迷你多系列折线 ---- */
LAYOUTS.spark = function(dsl, comp, ctx){
  var box=el("div","sec"), series=[];
  Object.keys(dsl).forEach(function(k){
    if(k==="type") return;
    var v=dsl[k];
    if(isArr(v) && v.length && typeof v[0]==="number") series.push([k,v]);
  });
  if(!series.length){ box.appendChild(el("div","empty","没有数值序列")); return box; }
  var W=660,H=180,P=28,all=[];
  series.forEach(function(s){ s[1].forEach(function(x){ all.push(num(x)); }); });
  var mx=Math.max.apply(null,all.concat([1])), mn=Math.min.apply(null,all.concat([0]));
  if(mx===mn) mx=mn+1;
  var s=svg("svg",{viewBox:"0 0 "+W+" "+H,class:"chart",preserveAspectRatio:"xMidYMid meet"});
  for(var g=0;g<=3;g++){
    var gy=P+(H-P*2)*g/3;
    s.appendChild(svg("line",{x1:P,y1:gy,x2:W-P,y2:gy,stroke:"var(--line)","stroke-width":"1"}));
    var tv=el("text",null,esc((mx-(mx-mn)*g/3).toFixed(mx>10?0:1)));
    tv.setAttribute("x",2); tv.setAttribute("y",gy+4); tv.setAttribute("fill","var(--fg-3)"); tv.setAttribute("font-size","9.5");
    s.appendChild(tv);
  }
  var cols=["var(--accent)","#e8590c","#1c7ed6","#0ca678","#ae3ec9"];
  var n=series[0][1].length, bw=(W-P*2)/Math.max(n,1);
  series.forEach(function(sr,si){
    var col=cols[si%cols.length], d="", ly=0;
    sr[1].forEach(function(x,i){
      var y=H-P-(num(x)-mn)/(mx-mn)*(H-P*2);
      d+=(i?"L":"M")+(P+bw*i+bw/2).toFixed(1)+" "+y.toFixed(1); ly=y;
    });
    s.appendChild(svg("path",{d:d,fill:"none",stroke:col,"stroke-width":"2.2","stroke-linejoin":"round","stroke-linecap":"round"}));
    s.appendChild(svg("circle",{cx:P+bw*(n-1)+bw/2,cy:ly,r:3.5,fill:col}));
  });
  box.appendChild(s);
  var lg=el("div","legend");
  series.forEach(function(sr,si){
    var sname = HUMANIZE[sr[0]]!==undefined ? HUMANIZE[sr[0]] : ("序列"+(si+1));
    lg.appendChild(el("span","lg-i","<i style='display:inline-block;width:9px;height:9px;border-radius:50%;background:"+cols[si%cols.length]+"'></i>"+esc(sname)+"（"+esc(sr[1].length)+" 点）"));
  });
  box.appendChild(lg);
  return box;
};

/* ---- 4.24 嵌套对象：递归成树 ---- */
LAYOUTS.objtree = function(dsl, comp, ctx){
  var box=el("div","sec"), wrap=el("div","tree");
  var topKeys=Object.keys(dsl).filter(function(k){ return k!=="type"; });
  if(!topKeys.length){ box.appendChild(el("div","empty","暂无结构化数据")); return box; }
  function draw(o,key){
    var holder=el("div");
    if(isArr(o)){
      o.forEach(function(v,i){ holder.appendChild(draw(v,key+"["+i+"]")); });
      return holder;
    }
    if(isObj(o)){
      var ks=Object.keys(o);
      var row=el("div","tn");
      row.appendChild(el("div","tn-tw",ks.length?"▸":""));
      row.appendChild(el("div","tn-t",esc(humanKey(key))+(ks.length?"（"+ks.length+" 项）":"")));
      row.addEventListener("click",function(){ ctx.interact("node",{key:key,data:o}); });
      holder.appendChild(row);
      if(ks.length){
        var kids=el("div","tn-k"); kids.style.display="none";
        ks.forEach(function(k){ kids.appendChild(draw(o[k],k)); });
        holder.appendChild(kids);
      }
      return holder;
    }
    var leaf=el("div","tn");
    leaf.appendChild(el("div","tn-tw",""));
    leaf.appendChild(el("div","tn-t","<b>"+esc(humanKey(key))+"</b> = "+esc(String(o))));
    leaf.addEventListener("click",function(){ ctx.interact("leaf",{key:key,value:o}); });
    holder.appendChild(leaf);
    return holder;
  }
  /* 顶层只有一个对象键（如 root）时直接展开其内容，不把这个内部结构名当标题摆出来 */
  if(topKeys.length===1 && isObj(dsl[topKeys[0]])){
    var inner=dsl[topKeys[0]];
    Object.keys(inner).forEach(function(k){
      var v=inner[k];
      if(isObj(v)||isArr(v)) wrap.appendChild(draw(v,k));
      else wrap.appendChild(el("div","tn","<div class='tn-tw'></div><div class='tn-t'><b>"+esc(humanKey(k))+"</b> = "+esc(String(v))+"</div>"));
    });
    box.appendChild(wrap);
    return box;
  }
  topKeys.forEach(function(k){
    var v=dsl[k];
    if(isObj(v)||isArr(v)) wrap.appendChild(draw(v,humanKey(k)));
    else wrap.appendChild(el("div","tn","<div class='tn-tw'></div><div class='tn-t'><b>"+esc(humanKey(k))+"</b> = "+esc(String(v))+"</div>"));
  });
  box.appendChild(wrap);
  return box;
};

/* ---- 4.25 矩阵 / 热图：数组的数组 ---- */
LAYOUTS.matrix = function(dsl, comp, ctx){
  var box=el("div","sec");
  var hd = isArr(dsl.cols)?dsl.cols:(isArr(dsl.names)?dsl.names:(isArr(dsl.labels)?dsl.labels:[]));
  var m=null;
  /* 行向量形式：rows=[{k/n, v:[...]}] 与 cols 表头对齐（zx_matrix）。
     旧码只认「数组的数组」，于是每行 v 数组被拼成 "12,31,4" 一格。 */
  if(isArr(dsl.rows) && isObj(dsl.rows[0]) && isArr(dsl.rows[0].v) && hd.length){
    m = dsl.rows.map(function(r){
      var row=[ txt(r)||r.k||r.n||"" ];
      (isArr(r.v)?r.v:[]).forEach(function(x){ row.push(x); });
      return row;
    });
  } else {
    if(isArr(dsl.p) && isObj(dsl.p[0]) && isArr(dsl.p[0].r) && isArr(dsl.states)){
      /* 马尔可夫转移矩阵：states 为行/列头，p[i].r 为第 i 行概率（zx_markov） */
      hd = dsl.states.map(function(s){ return isObj(s)?txt(s):String(s); });
      m = dsl.p.map(function(x){
        var row=[ isObj(x)?(txt(x)||""):"" ];
        (isArr(x.r)?x.r:[]).forEach(function(y){ row.push(y); });
        return row;
      });
    } else {
      Object.keys(dsl).forEach(function(k){
        if(k==="type"||k==="cols"||k==="names"||k==="labels") return;
        var v=dsl[k];
        if(isArr(v) && v.length && isArr(v[0]) && v[0].length && !m) m=v;
      });
    }
  }
  if(!m){ box.appendChild(el("div","empty","没有矩阵数据")); return box; }
  /* 座位图形式：单元是带 st/k 的对象（zx_seat） */
  if(isObj(m[0][0]) && (m[0][0].st!==undefined || m[0][0].k!==undefined)){
    return LAYOUTS._seat(box, m, hd, ctx);
  }
  var flat=[];
  m.forEach(function(r){ r.forEach(function(x){ flat.push(isObj(x)?num(val(x)):num(x)); }); });
  var mx=Math.max.apply(null,flat.concat([1])), mn=Math.min.apply(null,flat.concat([0]));
  if(mx===mn) mx=mn+1;
  var tb=el("table","mtx");
  if(hd.length){
    var hr=el("tr"); hr.appendChild(el("th",null,""));
    hd.forEach(function(c){ hr.appendChild(el("th",null,esc(isObj(c)?txt(c):String(c)))); });
    tb.appendChild(hr);
  }
  m.forEach(function(r,ri){
    var tr=el("tr");
    tr.appendChild(el("th",null,esc(hd[ri]!==undefined?(isObj(hd[ri])?txt(hd[ri]):String(hd[ri])):"")));
    r.forEach(function(c,ci){
      var v=isObj(c)?num(val(c)):num(c), t=(v-mn)/(mx-mn);
      var td=el("td",t<0.34?"lo":(t<0.67?"md":"hi"),esc(isObj(c)?(txt(c)||val(c)||JSON.stringify(c)):String(c)));
      td.addEventListener("click",function(){ ctx.interact("cell",{row:ri,col:ci,data:isObj(c)?c:{value:c}}); });
      tr.appendChild(td);
    });
    tb.appendChild(tr);
  });
  box.appendChild(tb);
  return box;
};

/* 座位图：行×列格子，按 st 着色、可点选（zx_seat） */
LAYOUTS._seat = function(box, m, hd, ctx){
  var wrap=el("div");
  wrap.style.display="grid";
  wrap.style.gridTemplateColumns="repeat("+m[0].length+",minmax(34px,1fr))";
  wrap.style.gap="6px"; wrap.style.padding="12px 14px";
  m.forEach(function(row,ri){
    row.forEach(function(c,ci){
      var st=isObj(c)?(c.st||"free"):"free";
      var label=isObj(c)?(c.k||txt(c)||""):String(c);
      var cell=el("div","kb-i"); cell.style.textAlign="center"; cell.style.padding="10px 4px";
      if(st==="sold"){ cell.style.background="var(--err-soft)"; cell.style.borderColor="var(--err)"; }
      else if(st==="vip"){ cell.style.background="var(--accent)"; cell.style.color="var(--accent-fg)"; }
      else if(st==="block"){ cell.style.background="var(--surface-3)"; }
      cell.innerHTML=esc(label);
      cell.addEventListener("click",function(){ ctx.interact("seat",{row:ri,col:ci,status:st,key:label}); });
      wrap.appendChild(cell);
    });
  });
  box.appendChild(wrap);
  return box;
};

/* ---- 4.26 分隔线 ---- */
LAYOUTS.rule = function(dsl, comp, ctx){
  var box=el("div","sec"), r=el("div","rule");
  var t=txt(dsl)||dsl.label||"";
  if(t) r.appendChild(el("span","rule-t",esc(t)));
  box.appendChild(r);
  return box;
};

/* ---- 4.27 单值大字：表盘 / 里程 / 倒计时 ---- */
LAYOUTS.hero = function(dsl, comp, ctx){
  var box=el("div","sec"), h=el("div","hero");
  var raw = dsl.v!==undefined?dsl.v:(dsl.num!==undefined?dsl.num:(dsl.value!==undefined?dsl.value:dsl.sec));
  var u = dsl.u||dsl.unit||"", shown=raw;
  if(comp==="zx_countdown" && raw!==undefined){
    var s=Math.max(0,Math.round(num(raw)));
    shown=Math.floor(s/60)+":"+("0"+(s%60)).slice(-2);
  }
  var vv=el("div","hero-v");
  vv.innerHTML=esc(shown===undefined||shown===null?"—":shown)+(u?"<span class='hero-u'>"+esc(u)+"</span>":"");
  h.appendChild(vv);
  var lb=txt(dsl)||dsl.label||"";
  if(lb) h.appendChild(el("div","hero-s",esc(lb)));
  var d=sub(dsl)||dsl.desc||"";
  if(d) h.appendChild(el("div","hero-s",esc(d)));
  if(dsl.max!==undefined && raw!==undefined && isFinite(num(raw))){
    var pct=Math.max(0,Math.min(100,num(raw)/num(dsl.max,100)*100));
    var pb=el("div","pb","<div class='pb-f' style='width:"+pct.toFixed(1)+"%'></div>");
    pb.style.margin="13px auto 0"; pb.style.maxWidth="210px";
    h.appendChild(pb);
  }
  h.addEventListener("click",function(){ ctx.interact("value",{value:shown,unit:u,label:lb}); });
  box.appendChild(h);
  return box;
};

/* ── 4.28 图：nodes(+对象或字符串) + edges/flows → SVG 节点连线 ── */
LAYOUTS.graph = function(dsl, comp, ctx){
  var nodes = isArr(dsl.nodes)?dsl.nodes:[];
  var edges = isArr(dsl.edges)?dsl.edges:(isArr(dsl.flows)?dsl.flows:(isArr(dsl.b)?dsl.b:[]));
  var box=el("div","sec");
  if(!nodes.length){ box.appendChild(el("div","empty","没有节点")); return box; }
  var W=680,H=420,P=40;
  var pos=[];
  var allXY = nodes.length && nodes.every(function(n){ return isObj(n) && n.x!==undefined && n.y!==undefined; });
  if(allXY){
    nodes.forEach(function(n){ pos.push([P+num(n.x)*(W-2*P), H-P-num(n.y)*(H-2*P)]); });
  } else {
    var R=Math.min(W,H)/2-P;
    nodes.forEach(function(n,i){ var a=2*Math.PI*i/nodes.length; pos.push([W/2+R*Math.cos(a), H/2+R*Math.sin(a)]); });
  }
  var s=svg("svg",{viewBox:"0 0 "+W+" "+H,class:"chart",preserveAspectRatio:"xMidYMid meet"});
  var defs=svg("defs");
  var mk=svg("marker",{id:"zxarr",viewBox:"0 0 10 10",refX:"9",refY:"5",markerWidth:"7",markerHeight:"7",orient:"auto-start-reverse"});
  mk.appendChild(svg("path",{d:"M0,0 L10,5 L0,10 z",fill:"var(--line-strong)"}));
  defs.appendChild(mk); s.appendChild(defs);
  edges.forEach(function(e,i){
    var f=num(e.f!==undefined?e.f:(e.from!==undefined?e.from:(e.source!==undefined?e.source:(e.parent!==undefined?e.parent:0))));
    var t=num(e.t!==undefined?e.t:(e.to!==undefined?e.to:(e.target!==undefined?e.target:(e.child!==undefined?e.child:0))));
    if(!pos[f]||!pos[t]) return;
    var w=num(e.w||e.v||1);
    var ln=svg("line",{x1:pos[f][0],y1:pos[f][1],x2:pos[t][0],y2:pos[t][1],stroke:"var(--line-strong)","stroke-width":Math.max(1,Math.min(6,w/2)),"marker-end":"url(#zxarr)",opacity:.75});
    ln.style.cursor="pointer";
    ln.addEventListener("click",function(){ ctx.interact("edge",{from:f,to:t,data:e}); });
    s.appendChild(ln);
  });
  nodes.forEach(function(n,i){
    var label = isObj(n)?(txt(n)||("节点"+(i+1))):String(n);
    var col = isObj(n)?(colorOf(n)||"var(--accent)"):"var(--accent)";
    var isRoot = isObj(n)&&(n.root!==undefined||n.isRoot);
    var c=svg("circle",{cx:pos[i][0],cy:pos[i][1],r:isRoot?13:10,fill:col,opacity:.92,stroke:"var(--surface)","stroke-width":"2"});
    c.style.cursor="pointer";
    c.addEventListener("click",function(){ ctx.interact("node",{index:i,label:label,data:isObj(n)?n:{value:n}}); });
    s.appendChild(c);
    var lb=el("text",null,esc(label.length>14?label.slice(0,14)+"…":label));
    lb.setAttribute("x",pos[i][0]); lb.setAttribute("y",pos[i][1]-16); lb.setAttribute("text-anchor","middle");
    lb.setAttribute("fill","var(--fg-2)"); lb.setAttribute("font-size","11"); s.appendChild(lb);
  });
  box.appendChild(s);
  var gt=txt(dsl)||dsl.n||dsl.title;
  if(gt) box.appendChild(el("div","sec-t",esc(gt)));
  return box;
};

/* ── 4.29 网格 / 磁贴 / 泳道 / 散点（cells 家族，原先被误归日历）── */
LAYOUTS.grid = function(dsl, comp, ctx){
  var box=el("div","sec");
  /* 泳道：lanes[].cells */
  if(isArr(dsl.lanes) && dsl.lanes.length){
    dsl.lanes.forEach(function(lane){
      var name=isObj(lane)?(txt(lane)||"泳道"):String(lane);
      box.appendChild(el("div","sec-t",esc(name)));
      var cells=isObj(lane)?(isArr(lane.cells)?lane.cells:[]):[];
      box.appendChild(LAYOUTS._gridTiles(cells, comp, ctx));
    });
    return box;
  }
  var cells = isArr(dsl.cells)?dsl.cells:(isArr(dsl.nodes)?dsl.nodes:mainArr(dsl, comp));
  if(!cells.length){ box.appendChild(el("div","empty","没有网格数据")); return box; }
  /* 散点：x/y 定位（zx_wxradar） */
  if(isObj(cells[0]) && cells[0].x!==undefined && cells[0].y!==undefined){
    return LAYOUTS._gridScatter(box, cells, dsl, comp, ctx);
  }
  /* 坐标格：r/c 或 a/b 定位（zx_branch / zx_payoff） */
  if(isObj(cells[0]) && (cells[0].r!==undefined || cells[0].a!==undefined)){
    return LAYOUTS._gridPlaced(box, cells, dsl, comp, ctx);
  }
  /* 否则磁贴流（zx_bento / zx_choro / zx_hexgrid） */
  box.appendChild(LAYOUTS._gridTiles(cells, comp, ctx));
  return box;
};
LAYOUTS._gridTiles = function(cells, comp, ctx){
  var wrap=el("div");
  wrap.style.display="grid"; wrap.style.gridTemplateColumns="repeat(auto-fill,minmax(108px,1fr))";
  wrap.style.gap="8px"; wrap.style.padding="12px 14px";
  var vals=[];
  cells.forEach(function(c){ if(isObj(c)){ var v=val(c); if(/^-?[0-9]+(\.[0-9]+)?$/.test(String(v))) vals.push(num(v)); } });
  var mx=vals.length?Math.max.apply(null,vals):1, mn=vals.length?Math.min.apply(null,vals):0; if(mx===mn)mx=mn+1;
  cells.forEach(function(c,i){
    if(!isObj(c)) return;
    var label=txt(c)||"", v=val(c);
    var tile=el("div","met");
    if(label) tile.appendChild(el("div","met-k",esc(label)));
    if(v!=="") tile.appendChild(el("div","met-v",esc(v)));
    if(/^-?[0-9]+(\.[0-9]+)?$/.test(String(v))){
      var t=(num(v)-mn)/(mx-mn);
      tile.style.background = t>0.66?"var(--accent-soft)":(t>0.33?"var(--surface-2)":"var(--surface-3)");
    }
    if(c.span){ var sp=num(c.span,1); if(sp>1&&sp<=4) tile.style.gridColumn="span "+sp; }
    tile.addEventListener("click",function(){ ctx.interact("tile",{index:i,data:c}); });
    wrap.appendChild(tile);
  });
  return wrap;
};
LAYOUTS._gridPlaced = function(box, cells, dsl, comp, ctx){
  function rI(c){ return c.r!==undefined?c.r:(c.a!==undefined?c.a:0); }
  function cI(c){ return c.c!==undefined?c.c:(c.b!==undefined?c.b:0); }
  var rowsHdr = isArr(dsl.rows)?dsl.rows:[];
  var colsHdr = isArr(dsl.cols)?dsl.cols:(isArr(dsl.columns)?dsl.columns:[]);
  var maxR=0,maxC=0;
  cells.forEach(function(c){ if(isObj(c)){ maxR=Math.max(maxR,rI(c)); maxC=Math.max(maxC,cI(c)); }});
  if(typeof dsl.cols==="number") maxC=Math.max(maxC,dsl.cols-1);
  if(rowsHdr.length) maxR=Math.max(maxR,rowsHdr.length-1);
  if(colsHdr.length) maxC=Math.max(maxC,colsHdr.length-1);
  var wrap=el("div","tw"), tb=el("table","t");
  var thead=el("thead"),tr=el("tr"); tr.appendChild(el("th",null,""));
  for(var c=0;c<=maxC;c++){ tr.appendChild(el("th",null,esc(colsHdr[c]!==undefined?(isObj(colsHdr[c])?txt(colsHdr[c]):String(colsHdr[c])):String(c+1)))); }
  thead.appendChild(tr); tb.appendChild(thead);
  var tbody=el("tbody");
  for(var r=0;r<=maxR;r++){
    var tr2=el("tr"); tr2.appendChild(el("th",null,esc(rowsHdr[r]!==undefined?(isObj(rowsHdr[r])?txt(rowsHdr[r]):String(rowsHdr[r])):String(r+1))));
    for(var cc=0;cc<=maxC;cc++){ tr2.appendChild(el("td",null,"")); }
    tbody.appendChild(tr2);
  }
  cells.forEach(function(c,i){ if(!isObj(c))return; var ri=rI(c),ci=cI(c);
    var label=txt(c)||val(c)||c.k||"";
    if(!label){
      var extra=[]; for(var kk in c){ if(["r","c","a","b"].indexOf(kk)>=0) continue; if(typeof c[kk]==="object") continue; extra.push(c[kk]); }
      if(extra.length) label=extra.join("/");
      else if(c.a!==undefined||c.b!==undefined) label="("+(c.a!==undefined?c.a:"")+","+(c.b!==undefined?c.b:"")+")";
    }
    var row=tbody.children[ri]; if(!row)return; var td=row.children[ci+1];
    if(td){ td.textContent=String(label); td.style.fontWeight="500"; td.addEventListener("click",function(){ctx.interact("cell",{row:ri,col:ci,data:c});}); } });
  tb.appendChild(tbody); wrap.appendChild(tb); box.appendChild(wrap); return box;
};
LAYOUTS._gridScatter = function(box, cells, dsl, comp, ctx){
  var W=680,H=400,P=24;
  var s=svg("svg",{viewBox:"0 0 "+W+" "+H,class:"chart",preserveAspectRatio:"xMidYMid meet"});
  [0.25,0.5,0.75,1].forEach(function(rr){ s.appendChild(svg("circle",{cx:W/2,cy:H/2,r:rr*Math.min(W,H)/2*0.9,fill:"none",stroke:"var(--line)","stroke-width":"1"})); });
  cells.forEach(function(c,i){ if(!isObj(c))return; var px=P+num(c.x)*(W-2*P), py=H-P-num(c.y)*(H-2*P);
    var col=colorOf(c)||"var(--accent)";
    var dot=svg("circle",{cx:px,cy:py,r:6,fill:col,opacity:.85}); dot.style.cursor="pointer";
    dot.addEventListener("click",function(){ ctx.interact("dot",{index:i,value:num(c.v),data:c}); }); s.appendChild(dot);
    if(c.n||c.t){ var lb=el("text",null,esc(txt(c)||"")); lb.setAttribute("x",px+8); lb.setAttribute("y",py+4); lb.setAttribute("fill","var(--fg-2)"); lb.setAttribute("font-size","10"); s.appendChild(lb); }
  });
  box.appendChild(s);
  return box;
};

/* ── 4.30 决策矩阵：opts × crit + scores ── */
LAYOUTS.decision = function(dsl, comp, ctx){
  var opts = isArr(dsl.opts)?dsl.opts:(isArr(dsl.options)?dsl.options:[]);
  var crit = isArr(dsl.crit)?dsl.crit:[];
  var scores = isArr(dsl.scores)?dsl.scores:[];
  var box=el("div","sec"), wrap=el("div","tw"), tb=el("table","t");
  if(!opts.length || !crit.length){ box.appendChild(el("div","empty","缺少选项或评判标准")); return box; }
  var thead=el("thead"), tr=el("tr");
  tr.appendChild(el("th",null,"标准 / 权重"));
  opts.forEach(function(o,oi){ tr.appendChild(el("th",null,esc(isObj(o)?(txt(o)||val(o)):String(o)))); });
  thead.appendChild(tr); tb.appendChild(thead);
  var tbody=el("tbody");
  crit.forEach(function(c,ci){
    var tr2=el("tr");
    var cn=isObj(c)?(txt(c)||("标准 "+(ci+1))):String(c);
    var cw=isObj(c)?num(c.w,1):1;
    tr2.appendChild(el("td",null,esc(cn)+(cw!==1?(" ("+cw+")"):"")));
    var rowScores = isArr(scores[ci])?scores[ci]:[];
    opts.forEach(function(o,oi){
      var v=rowScores[oi]!==undefined?rowScores[oi]:"";
      var td=el("td","num",esc(v));
      td.addEventListener("click",function(){ ctx.interact("score",{crit:cn,opt:isObj(o)?(txt(o)||val(o)):String(o),value:v}); });
      tr2.appendChild(td);
    });
    tbody.appendChild(tr2);
  });
  tb.appendChild(tbody); wrap.appendChild(tb); box.appendChild(wrap);
  return box;
};

/* ── 4.31 代码块：src + lang + 行号 + 高亮 ── */
LAYOUTS.code = function(dsl, comp, ctx){
  var src = dsl.src||dsl.code||dsl.v||"";
  var lang = dsl.lang||dsl.language||"";
  var hl = isArr(dsl.hl)?dsl.hl:[];
  var box=el("div","sec");
  if(lang) box.appendChild(el("div","sec-t",esc(lang)));
  var lines = String(src).split("\n");
  var pre=el("div",null);
  pre.style.fontFamily="var(--mono)"; pre.style.fontSize="12px"; pre.style.lineHeight="1.6";
  pre.style.padding="12px 14px"; pre.style.whiteSpace="pre"; pre.style.overflowX="auto";
  pre.style.background="var(--surface-2)"; pre.style.borderRadius="var(--radius-s)";
  lines.forEach(function(ln,i){
    var num=i+1;
    var row=el("div"); row.style.display="flex"; row.style.gap="10px";
    if(hl.indexOf(num)>=0) row.style.background="var(--warn-soft)";
    var g=el("span",null,esc(String(num)));
    g.style.color="var(--fg-3)"; g.style.flex="0 0 22px"; g.style.textAlign="right"; g.style.userSelect="none";
    var c=el("span",null,esc(ln)); c.style.whiteSpace="pre"; c.style.flex="1";
    row.appendChild(g); row.appendChild(c);
    row.addEventListener("click",function(){ ctx.interact("line",{line:num,text:ln}); });
    pre.appendChild(row);
  });
  box.appendChild(pre);
  return box;
};

/* ═══════════════════════════════════════════════════════════════
   5. 布局决策： curated 优先，契约结构推断次之，generic 兜底
   ═══════════════════════════════════════════════════════════════ */
var CONTAINERS = ["zx_tabs","zx_accordion","zx_split","zx_carousel","zx_grid","zx_master","zx_stagger","zx_stack"];

/*  curated：字段结构相同但语义不同的组件，显式指定布局 */
var OVERRIDE = {
  zx_compare:"table", zx_table:"table", zx_datatable:"table", zx_matrix:"matrix", zx_pivot:"table",
  zx_metrics:"metrics", zx_stat:"metrics", zx_stats:"metrics", zx_kpi:"metrics", zx_counter:"metrics",
  zx_list:"list", zx_feed:"list", zx_log:"list", zx_search:"list", zx_results:"list",
  /* zx_tasklist 结构上声明了 tasks 数组，但语义是「一份清单」而不是排期，
     没有起止字段可画 —— 显式归到 list，别交给结构推断去猜。 */
  zx_tasklist:"list",
  zx_timeline:"stepper", zx_steps:"stepper", zx_stepper:"stepper", zx_stages:"stepper", zx_process:"stepper",
  zx_form:"form", zx_survey:"form", zx_gridform:"form",
  zx_choice:"choice", zx_options:"choice", zx_pick:"choice", zx_vote:"choice",
  zx_tags:"chips", zx_chips:"chips", zx_labels:"chips", zx_marks:"chips",
  zx_chart:"chart", zx_line:"chart", zx_bar:"chart", zx_spark:"chart", zx_trend:"chart",
  zx_gantt:"gantt", zx_plan:"gantt",
  zx_kanban:"kanban", zx_board:"kanban",
  zx_calendar:"calendar", zx_heatmap:"calendar", zx_calheat:"calendar",
  zx_tree:"tree", zx_org:"tree", zx_orgchart:"tree", zx_mindmap:"tree", zx_depgraph:"tree",
  zx_alert:"note", zx_notice:"note", zx_callout:"note", zx_banner:"note", zx_quote:"note", zx_hint:"note",
  zx_divider:"rule", zx_stoprule:"keyvalue",
  zx_dial:"hero", zx_dial2:"hero", zx_odometer:"hero", zx_countdown:"hero", zx_flipnum:"hero", zx_dialpad:"hero",
  zx_dial3:"gauge", zx_ring2:"tree", zx_radial:"tree", zx_sign:"keyvalue", zx_joystick:"keyvalue",
  zx_corr:"matrix", zx_missing:"matrix", zx_seat:"matrix",
  zx_sparkline:"spark", zx_bland:"spark", zx_lorenz:"spark", zx_optline:"spark", zx_perm:"spark", zx_qq:"spark",
  zx_ast:"objtree", zx_confront:"objtree", zx_formula:"objtree",
  zx_terminal:"terminal", zx_console:"terminal", zx_shell:"terminal",
  zx_diff:"diff", zx_patch:"diff", zx_changes:"diff",
  zx_rating:"rating", zx_scale:"rating", zx_stars:"rating",
  zx_gauge:"gauge", zx_progress:"gauge", zx_meter:"gauge",
  zx_media:"media", zx_image:"media", zx_video:"media", zx_audio:"media",
  zx_qr:"qr", zx_barcode:"barcode",
  zx_keyvalue:"keyvalue", zx_kv:"keyvalue", zx_detail:"keyvalue", zx_summary:"keyvalue",
  /* 图组件：nodes + edges/flows → graph（不是 tree：tree 不画边、且字符串节点被丢弃 → 空白） */
  zx_arc:"graph", zx_blast:"graph", zx_causal:"graph", zx_dag:"graph", zx_flow:"graph",
  zx_flowedit:"graph", zx_geoflow:"graph", zx_lineage:"graph", zx_optpath:"graph",
  zx_topology:"graph", zx_sankey2:"graph", zx_decision:"decision",
  /* 决策矩阵：opts × crit + scores */
  zx_dmatrix:"decision", zx_scoreboard:"decision",
  /* cells 家族：网格/磁贴/泳道/散点，都不是日历（去掉 cells→calendar 误伤） */
  zx_bento:"grid", zx_branch:"grid", zx_choro:"grid", zx_hexgrid:"grid", zx_payoff:"grid",
  zx_schedule:"grid", zx_swimlane:"grid", zx_weekgrid:"grid", zx_wxradar:"grid",
  /* 座位图：matrix 布局内部分流到 seat 渲染 */
  zx_seat:"matrix",
  /* 此前被 people/levels 误归 tree 而空白的组件，按语义重路由 */
  zx_contour:"matrix", zx_meter:"gauge", zx_pyramid:"chart", zx_workload:"metrics",
  zx_escalation:"list", zx_splitbill:"table", zx_mention:"chips", zx_avatargroup:"chips",
  zx_code:"code", zx_markov:"matrix", zx_sunburst:"tree"
};

function resolveLayout(comp, dsl){
  if(OVERRIDE[comp]) return OVERRIDE[comp];
  if(CONTAINERS.indexOf(comp)>=0) return "container";
  var ct = ZX_CONTRACTS[comp]||{};
  /* 显式 fn 映射优先：契约声明了真实存在的布局名时直接采用，
     避免形状推断在退化 DSL 上漂移到错误布局（list/chart/tree 等高频推断面）。
     死 fn（无对应 LAYOUTS 实现）自动跳过，回落到下面的形状推断。 */
  if(ct.fn && LAYOUTS[ct.fn]) return ct.fn;
  var arrs = ct.arrays||[];
  if(arrs.indexOf("columns")>=0 && arrs.indexOf("rows")>=0) return "table";
  if(arrs.indexOf("fields")>=0) return "form";
  if(arrs.indexOf("cols")>=0 && (arrs.indexOf("cards")>=0||isArr(dsl.cols)&&isObj(dsl.cols[0])&&(isArr(dsl.cols[0].cards)||isArr(dsl.cols[0].items)))) return "kanban";
  /* tasks → 甘特图**必须**有起止证据。
     LAYOUTS.gantt 只读 s/e（或 from/to）；没有起止字段时每根条都塌成 0 宽，
     画出来是一条空轴，用户看到的就是「画不出来」。
     zx_gantt 的 fields 有 s/e；zx_tasklist 只有 t/dep/pri（一份清单）。 */
  if(arrs.indexOf("tasks")>=0){
    var tf=ct.fields||[], tl=mainArr(dsl, comp);
    var head=isObj(tl[0])?tl[0]:null;
    var hasSE = (tf.indexOf("s")>=0 && tf.indexOf("e")>=0)
      || (head && (head.s!==undefined||head.from!==undefined)
              && (head.e!==undefined||head.to!==undefined));
    if(hasSE) return "gantt";
  }
  if(arrs.indexOf("options")>=0 || arrs.indexOf("opts")>=0) return "choice";
  /* 图：nodes + edges/flows → graph。必须在 tree 之前，否则被 nodes 规则误归 tree
     （tree 不画边、且字符串节点被 if(!isObj(n)) 直接丢弃 → 渲染空白）。 */
  if(arrs.indexOf("nodes")>=0 && (arrs.indexOf("edges")>=0 || arrs.indexOf("flows")>=0)) return "graph";
  if(arrs.indexOf("nodes")>=0 && arrs.indexOf("edges")>=0) return "tree";
  if(arrs.indexOf("nodes")>=0) return "tree";
  if(arrs.indexOf("pts")>=0 || arrs.indexOf("series")>=0) return "chart";
  if(arrs.indexOf("steps")>=0 || arrs.indexOf("stages")>=0) return "stepper";
  if(arrs.indexOf("days")>=0) return "calendar";
  if(arrs.indexOf("tags")>=0 || arrs.indexOf("labels")>=0 || arrs.indexOf("marks")>=0) return "chips";
  /* 契约驱动：主数组名 + 声明的标量字段组合，先于数据推断 */
  var fds = ct.fields||[];
  if(arrs.length && fds.indexOf("k")>=0 && fds.indexOf("v")>=0 &&
     ["items","rows","entries","records","list","dials","subs","metrics"].indexOf(arrs[0])>=0) return "metrics";
  if(arrs.indexOf("bins")>=0) return "chart";
  /* items 结构判定 */
  var items = mainArr(dsl, comp);
  if(items.length && isObj(items[0])){
    var keys=Object.keys(items[0]);
    if(keys.indexOf("k")>=0 && keys.indexOf("v")>=0) return "metrics";
    if(keys.indexOf("s")>=0 && keys.indexOf("e")>=0) return "gantt";
    if(keys.indexOf("id")>=0 && (keys.indexOf("kind")>=0||keys.indexOf("type")>=0)) return "form";
    if(keys.indexOf("children")>=0) return "tree";
    if(keys.indexOf("ys")>=0 || keys.indexOf("vals")>=0) return "chart";
    /* 每项 = 标签 + 单个数值数组 → 多系列折线；真正的矩阵/热图除外 */
    if(!/matrix|heat/.test(comp)){
      var ak=0, lab=false;
      for(var kj=0; kj<keys.length; kj++){
        var av=items[0][keys[kj]];
        if(isArr(av)&&av.length&&typeof av[0]==="number") ak++;
        else if(keys[kj]==="n"||keys[kj]==="t"||keys[kj]==="k"||keys[kj]==="label") lab=true;
      }
      if(ak===1 && lab) return "chart";
    }
    for(var ki=0; ki<keys.length; ki++){ if(isArr(items[0][keys[ki]]) && items[0][keys[ki]].length) return "tree"; }
    if(keys.indexOf("at")>=0 || keys.indexOf("time")>=0) return "stepper";
    if(keys.indexOf("v")>=0 && keys.indexOf("max")>=0) return "gauge";
    if(keys.indexOf("f")>=0 && keys.indexOf("t")>=0) return "gantt";
    return "list";
  }
  if(items.length && typeof items[0]==="number" &&
     (items.length>=5 || /spark|line|trend|curve|hist|lorenz|bland|qq|perm|spectrum|series/.test(comp))) return "spark";
  if(items.length && typeof items[0]!=="object") return "chips";
  /* 没有可识别的数组数据：按顶层值形态分流 */
  var hasObj=false, hasNum=false, hasMtx=false;
  Object.keys(dsl).forEach(function(k){
    if(k==="type") return;
    var v=dsl[k];
    if(isObj(v) && Object.keys(v).length) hasObj=true;
    if(isArr(v) && v.length && typeof v[0]==="number") hasNum=true;
    if(isArr(v) && v.length && isArr(v[0]) && v[0].length) hasMtx=true;
  });
  if(hasObj) return "objtree";
  if(hasMtx) return "matrix";
  if(hasNum) return "spark";
  if(Object.keys(dsl).length<=1) return "note";
  return "generic";
}

/* ═══════════════════════════════════════════════════════════════
   不规范 DSL 兜底（安全网）
   模型写 zx_web_card 时常见三种不规范：
   ① 数据多包一层 {data:{...}} / {content:{...}}；
   ② 数组写成对象 {"0":{...},"1":{...}}；
   ③ 图表用 lines/points 别名（契约真名 series/data）。
   这些会让 mainArr 取不到数组 → 画了外壳没画数据。
   这里统一规整**一次**，对所有布局生效，且不改变规范 DSL 的结果。 */
function objToArr(v){
  if(Array.isArray(v)) return v;
  if(v!==null && typeof v==="object"){
    var ks=Object.keys(v);
    if(!ks.length) return [];
    var allNum = ks.every(function(k){ return /^[0-9]+$/.test(k); });
    if(allNum){ ks.sort(function(a,b){ return (+a)-(+b); }); return ks.map(function(k){ return v[k]; }); }
    return ks.map(function(k){ return v[k]; });
  }
  return [];
}
function prepDsl(comp, dsl){
  if(dsl===null || typeof dsl!=="object" || Array.isArray(dsl)) return dsl;
  /* ① 解包 data/content 外壳：仅当外层本身没有可用数组时才解包，避免误伤真正用 data 字段的组件 */
  var arrKeys = ["items","rows","nodes","pts","steps","v","edges","cols","columns","options","days","cells",
    "slots","groups","blocks","marks","stages","fields","links","opts","cards","levels","series","people",
    "labels","tags","tasks","children","kids","lines","data","values","points"];
  var ct = (typeof ZX_CONTRACTS!=="undefined" && ZX_CONTRACTS[comp])||{};
  var compArrs = ct.arrays||[];
  var hasOuter = arrKeys.concat(compArrs).some(function(k){ return Array.isArray(dsl[k]); });
  var out = dsl;
  if(!hasOuter){
    if(isObj(dsl.data)) out = dsl.data;
    else if(isObj(dsl.content)) out = dsl.content;
  }
  if(out!==dsl && (typeof out!=="object" || Array.isArray(out))) return dsl;
  /* ② 对象当数组：把常见容器键下的对象转成数组 */
  var objArrKeys = ["items","rows","nodes","pts","steps","edges","cols","options","days","cells","slots",
    "groups","blocks","marks","stages","cards","levels","people","tasks","children","kids","lines","series"];
  for(var i=0;i<objArrKeys.length;i++){
    var kk=objArrKeys[i];
    if(out[kk]!==undefined && !Array.isArray(out[kk]) && isObj(out[kk])) out[kk]=objToArr(out[kk]);
  }
  /* ③ 图表别名：lines → series，每个 line.points → data */
  if(Array.isArray(out.lines) && !Array.isArray(out.series)){
    out.series = out.lines.map(function(ln){
      if(isObj(ln) && Array.isArray(ln.points) && !Array.isArray(ln.data) && !Array.isArray(ln.values)){
        var c={}; for(var p in ln) c[p]=ln[p]; c.data=ln.points; return c;
      }
      return ln;
    });
    delete out.lines;
  }
  return out;
}

function compile(comp, dsl, ctx){
  dsl = prepDsl(comp, dsl);
  /* 安全网：组件只发了一个 type、完全没有可渲染内容时，给一个友好占位，
     而不是渲染出一块肉眼看不见的空白卡。 */
  var _keys = Object.keys(dsl).filter(function(k){ return k!=="type"; });
  if(!_keys.length){
    var _ph = el("div","note");
    _ph.appendChild(el("div","note-i","!"));
    _ph.appendChild(el("div",null,esc("（该组件暂无可显示内容）")));
    return _ph;
  }
  var layout = resolveLayout(comp, dsl);
  var fn = LAYOUTS[layout];
  if(!fn){ layout="generic"; fn=LAYOUTS.generic; }
  var node;
  try{ node = fn(dsl, comp, ctx); }
  catch(e){
    var b=el("div","sec"); b.appendChild(el("div","err-box", esc(comp+" 渲染失败："+(e&&e.message||e))));
    return b;
  }
  /* 编译后兜底：布局返回了一个「肉眼不可见」的空白产物（无文本 / 无 SVG / 无子节点），
     但 DSL 里明明有数据 —— 这种不可见空白卡正是排查里最刺眼的一类。
     统一换成可见的「暂无内容」占位，绝不交付隐形空白卡。 */
  if(!renderHasContent(node)){
    var _ph = el("div","note");
    _ph.appendChild(el("div","note-i","!"));
    _ph.appendChild(el("div",null,esc("（该组件暂无可显示内容）")));
    return _ph;
  }
  return node;
}
/* 判断渲染产物是否真有可见内容（文本 / 几何图形 / 非空子节点） */
function renderHasContent(n){
  if(!n) return false;
  var h = n._html||"", t = n._text||"";
  if((h.replace(/<[^>]+>/g," ")).trim().length || t.trim().length) return true;
  if((h.match(/<(rect|circle|line|path|polygon|polyline|ellipse|svg)\b/gi)||[]).length) return true;
  var kids = n.children||[];
  for(var i=0;i<kids.length;i++){ if(renderHasContent(kids[i])) return true; }
  return false;
}


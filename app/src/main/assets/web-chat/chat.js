/* ═══════════════════════════════════════════════════════════════════════
   Web UI 运行时 —— **ZorvAI 对话框的对等客户端**
   ═══════════════════════════════════════════════════════════════════════

   定位（用户 2026-10-13 拍板）：
   「web ui拥有zorvAI所有功能，界面一样，功能一样，另外一个zorvAI对话框」。

   本文件是 ZorvAI 对话框在浏览器里的**对等客户端**：
     · 消息渲染与原生同源（块由宿主 parseBlocks 产出，这里只画）；
     · 所有开关**可点击写回**（深度思考/记忆/子智能体/朗读/视觉/CMS/特权/工作区）；
     · 模型 / 人格选择弹层，切换写回原生同一仓库；
     · 上传图片/文件/视频（base64 → 应用私有目录 → 随消息发送）；
     · 语音面板、工具面板、设置、全部功能面板入口。

   数据来源：宿主内嵌的服务（同源，fetch + SSE）。
   ═══════════════════════════════════════════════════════════════════════ */

/* ── 1. 基础工具 ─────────────────────────────────────────────────────── */

function esc(s){
  return String(s === undefined || s === null ? "" : s)
    .replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;")
    .replace(/"/g,"&quot;");
}
function el(tag,cls,text){
  var n=document.createElement(tag);
  if(cls)n.className=cls;
  if(text!==undefined&&text!==null)n.textContent=text;
  return n;
}

/**
 * 正文的**行内**格式化 —— 表格单元格 / 标题 / 引用等行内场景用它。
 * 块级渲染（列表 / 代码围栏 / 表格）见 [mdHtml]。
 */
function inline(s){
  var t=esc(s);
  t=t.replace(/`([^`\n]+)`/g,function(_,c){return "<code>"+c+"</code>";});
  t=t.replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g,'<a href="$2" target="_blank" rel="noopener">$1</a>');
  t=t.replace(/\*\*([^*\n]+)\*\*/g,"<b>$1</b>");
  t=t.replace(/(^|[^*])\*([^*\n]+)\*/g,"$1<i>$2</i>");
  t=t.replace(/~~([^~\n]+)~~/g,"<s>$1</s>");
  return t;
}

/**
 * mini-markdown：**文本块与流式缓冲**的富文本渲染。
 *
 * 为什么不用宿主块：流式期间前端手里只有原始增量文本（宿主 parseBlocks
 * 的块要等整条消息完成才在 assistant_done 里给）。这期间必须自己画，
 * 否则用户看到的就是一坨裸 markdown —— 正是「渲染不完整」的差评来源。
 * 支持：围栏代码块（带复制/语言标签）/ 表格 / 标题 / 引用 / 分割线 /
 * 无序列表 / 有序列表 / 任务列表 / 段落 + 行内格式。
 * 半截 markdown（流式中）也必须安全：未闭合围栏按代码块画到文末。
 *
 * 🔴 原生专属围栏（quro-ui / a2ui / card / quro-card / mermaid / aip / miniapp …）
 *    **绝不在这里裸露源码**：它们由宿主 parseBlocks 产出 native 块，
 *    流式期间随 assistant_delta.blocks 近实时画到消息底部全宽区（任务 #255）。
 *    未闭合的只画一个「生成中」占位；闭合的直接跳过（块马上到）。
 */
function codeBoxHtml(lang,code){
  return '<div class="code">'+
    '<button type="button" class="code-c" data-code="'+esc(code)+'">复制</button>'+
    (lang?'<span class="code-l">'+esc(lang)+'</span>':'')+
    '<pre><code>'+esc(code)+'</code></pre></div>';
}

/* ── 原生专属围栏（语言标签 → 占位文案）─────────────────────────────
   判定表逐条对齐宿主 parseBlocks 的路由（ChatScreen.kt:6798-6828）：
   isCardFenceLang / isSelfCardLang / mermaid·mmd / aip·aip+json·canvas /
   a2ui·a2ui+json / miniapp·mini / isDynamicUiLang（含 quro|zorv 即中招）。 */
var NATIVE_FENCE_LABEL={
  "quro-ui":"动态 UI","quro_ui":"动态 UI","zorv/ui":"动态 UI","zorv-ui":"动态 UI","zorv_ui":"动态 UI",
  "a2ui":"动态 UI","a2ui+json":"动态 UI",
  "quro-card":"自研卡片","quro_card":"自研卡片","zorv-card":"自研卡片","zorv_card":"自研卡片",
  "card":"组件卡片","cards":"组件卡片","cardui":"组件卡片","cardjson":"组件卡片",
  "mermaid":"图表","mmd":"图表",
  "aip":"排版画布","aip+json":"排版画布","canvas":"排版画布",
  "miniapp":"网页应用","mini":"网页应用"
};
function isNativeFence(l){
  var s=String(l||"").trim().toLowerCase();
  if(!s)return false;
  if(NATIVE_FENCE_LABEL[s])return true;
  /* 与宿主 isDynamicUiLang 一致：含 quro / zorv 的任意标签都算动态 UI */
  return s.indexOf("quro")>=0||s.indexOf("zorv")>=0;
}
function nativeFenceHtml(f){
  var label=NATIVE_FENCE_LABEL[String(f.lang||"").toLowerCase()]||"界面";
  return '<div class="fence-wait">'+
    '<span class="fw-dot"></span><span class="fw-dot"></span><span class="fw-dot"></span>'+
    '<span class="fw-t">'+esc(label)+'生成中…</span></div>';
}

function tableHtml(rows){
  if(!rows.length)return "";
  var head=rows[0], body=rows.slice(1);
  var h="<thead><tr>"+head.map(function(c){return "<th>"+inline(c)+"</th>";}).join("")+"</tr></thead>";
  var b="<tbody>"+body.map(function(r){
    return "<tr>"+r.map(function(c){return "<td>"+inline(c)+"</td>";}).join("")+"</tr>";
  }).join("")+"</tbody>";
  return '<div class="tw"><table>'+h+b+"</table></div>";
}

function mdHtml(src,streaming){
  var lines=String(src||"").replace(/\r\n?/g,"\n").split("\n");
  var out=[],para=[],list=null,quote=null,fence=null,tbl=null;
  function flushPara(){ if(para.length){ out.push("<p>"+para.map(inline).join("<br>")+"</p>"); para=[]; } }
  function flushList(){
    if(!list)return;
    if(list.type==="task"){
      out.push('<ul class="md-task">'+list.items.map(function(it){
        return '<li class="'+(it.done?"done":"")+'"><span class="qcheck">'+(it.done?"✓":"")+'</span><span>'+inline(it.text)+'</span></li>';
      }).join("")+"</ul>");
    }else{
      out.push("<"+list.type+">"+list.items.map(function(it){return "<li>"+inline(it.text)+"</li>";}).join("")+"</"+list.type+">");
    }
    list=null;
  }
  function flushQuote(){ if(quote){ out.push("<blockquote>"+quote.map(inline).join("<br>")+"</blockquote>"); quote=null; } }
  function flushTbl(){ if(tbl){ out.push(tableHtml(tbl)); tbl=null; } }
  function flushAll(){ flushPara(); flushList(); flushQuote(); flushTbl(); }
  for(var i=0;i<lines.length;i++){
    var ln=lines[i];
    if(fence){
      if(/^\s*```/.test(ln)){
        if(fence.native){
          /* 已闭合的原生专属围栏：正文里**什么都不画** —— 真正的界面由
             assistant_delta.blocks / assistant_done 的 native 块在全宽区渲染。
             只有流式中且还没闭合时才给「生成中」占位（见文末）。 */
          if(!streaming)out.push(codeBoxHtml(fence.lang,fence.code.join("\n")));
          fence=null;
        }else{ flushAll(); out.push(codeBoxHtml(fence.lang,fence.code.join("\n"))); fence=null; }
      }
      else fence.code.push(ln);
      continue;
    }
    var fm=/^\s*```([\w+#./-]*)\s*$/.exec(ln);
    if(fm){
      flushAll();
      var nat=isNativeFence(fm[1]);
      fence={lang:fm[1]||"",code:[],native:nat};
      continue;
    }
    var raw=ln.replace(/^\s+/,"").replace(/\s+$/,"");
    if(raw===""){ flushAll(); continue; }
    var hm=/^(#{1,6})\s+(.*)$/.exec(raw);
    if(hm){ flushAll(); var lv=Math.min(hm[1].length,4); out.push("<h"+lv+">"+inline(hm[2])+"</h"+lv+">"); continue; }
    if(/^(-{3,}|\*{3,}|_{3,})$/.test(raw)){ flushAll(); out.push("<hr>"); continue; }
    if(raw.charAt(0)===">"){ flushPara(); flushList(); flushTbl(); (quote=quote||[]).push(raw.slice(1).replace(/^\s+/,"")); continue; }
    var km=/^[-*+]\s+\[([ xX])\]\s+(.*)$/.exec(raw);
    var tm=/^[-*+]\s+(.*)$/.exec(raw);
    var om=/^\d+[.)]\s+(.*)$/.exec(raw);
    if(km){ flushPara(); flushQuote(); flushTbl(); if(!list||list.type!=="task"){flushList();list={type:"task",items:[]};} list.items.push({done:km[1]!==" ",text:km[2]}); continue; }
    if(tm){ flushPara(); flushQuote(); flushTbl(); if(!list||list.type!=="ul"){flushList();list={type:"ul",items:[]};} list.items.push({text:tm[1]}); continue; }
    if(om){ flushPara(); flushQuote(); flushTbl(); if(!list||list.type!=="ol"){flushList();list={type:"ol",items:[]};} list.items.push({text:om[1]}); continue; }
    if(raw.charAt(0)==="|"&&raw.indexOf("|",1)>0){
      flushPara(); flushList(); flushQuote();
      if(/^[\s|:-]+$/.test(raw))continue; /* 表头分隔行 */
      (tbl=tbl||[]).push(raw.replace(/^\|/,"").replace(/\|$/,"").split("|").map(function(c){return c.trim();}));
      continue;
    }
    flushQuote(); flushList(); flushTbl();
    para.push(raw);
  }
  if(fence){
    if(fence.native){
      /* 流式中未闭合 → 「生成中」占位；终渲（文本块里的残 fence）→ 诚实地
         按代码块显示源码，绝不永久挂一个假占位。 */
      if(streaming)out.push(nativeFenceHtml(fence));
      else out.push(codeBoxHtml(fence.lang,fence.code.join("\n")));
    }
    else out.push(codeBoxHtml(fence.lang,fence.code.join("\n")));
  }
  flushAll();
  return out.join("");
}

/* ── 2. 状态 ─────────────────────────────────────────────────────────── */

var $stream=document.getElementById("stream"),
    $inp=document.getElementById("inp"),
    $send=document.getElementById("send"),
    $dot=document.getElementById("dot"),
    $title=document.getElementById("title"),
    $model=document.getElementById("model"),
    $persona=document.getElementById("persona"),
    $cnt=document.getElementById("cnt"),
    $link=document.getElementById("link"),
    $modes=document.getElementById("modes"),
    $cms=document.getElementById("cms"),
    $priv=document.getElementById("priv"),
    $ws=document.getElementById("ws"),
    $app=document.getElementById("app"),
    $composer=document.getElementById("composer"),
    $diag=document.getElementById("diag"),
    $attbar=document.getElementById("attbar"),
    $fileInput=document.getElementById("fileInput");

var state={
  bar:{title:"ZorvAI",model:"",persona:""},
  composer:{
    busy:false,deepThink:false,autoSaveMemory:false,subAgent:false,autoRead:false,
    vision:false,workspace:"",cms:"ask",priv:"ask"
  },
  dark:false,lan:"",port:0,messages:[]
};

/* 三态策略的显示词 —— 与原生 qk_03235 / qk_03236 / qk_03166 逐字一致。 */
var POLICY_WORD={allow:"允许",deny:"禁止",ask:"询问"};

/** 规范化策略值 → 三态之一的 key；未知值退化为 "ask"。 */
function policyKey(v){
  var k=String(v===undefined||v===null?"":v);
  return POLICY_WORD[k]?k:"ask";
}
var nodes={};          /* id -> 渲染缓存 */
var emptyBox=null;
var pollTimer=null;
var selectedAttachments=[];   /* {id,name,type,size} */

/* ── 发送流状态（Operit 同款：fetch + reader 逐块读 SSE）──────────────
   active   正在流式（此时快照的 messages 被忽略，由流独占）
   ctrl     AbortController（停止按钮用）
   localUserId / localAid   乐观气泡的本地 id，真实消息到达后迁移
   segId    当前流式助手消息 id（跨工具轮次会换段）
   text / reason  累计正文 / 思考增量
   blocks   宿主随 delta 推来的原生专属块（流式期间近实时渲染，任务 #255）
   node     当前流式气泡的渲染缓存
   raf      待执行的重绘帧（rAF 节流，防止每个 delta 都重排）      */
var stream={active:false,ctrl:null,localUserId:null,localAid:null,
            segId:null,text:"",reason:"",blocks:[],node:null,raf:0,gotDone:false,
            lastAt:0,watchdog:0};

/* 流式假死阈值：发送流在飞但 45s 没有任何字节（含服务端 ": ping" 注释帧）→
   判定连接已死，abort 并走 error 路径。没有它就是 issue 里那个死锁：
   SSE 断线后 stream.active / composer.busy 卡住，后续每次点发送都被
   doSend 守卫悄悄拒绝 —— 表现即「消息半天发不到后端」。 */
var STREAM_STALL_MS=45000, STREAM_WATCHDOG_TICK_MS=5000;

/* 原生专属块的显示名 */
var NATIVE_LABEL={
  aip:"AIP 排版（原生渲染）",
  mermaid:"Mermaid 图（原生渲染）",
  dynamic_ui:"动态 UI（原生渲染）",
  self_card:"自研卡片（原生渲染）",
  card:"组件卡片（原生渲染）",
  miniapp:"MiniApp（原生渲染）"
};

/* ── 2b. 视口高度 + 布局自检（真机白屏事故的防线，别删）──────────────── */

function fit(){
  if(document.documentElement.classList.contains("flow"))return;
  var h=document.documentElement.clientHeight;
  if(h>0)$app.style.height=h+"px";
}

function guardCollapse(){
  if(state.messages.length===0)return;
  if($stream.clientHeight>=60)return;
  var sh0=$stream.clientHeight, ah0=$app.clientHeight, vp0=document.documentElement.clientHeight;
  if(!document.documentElement.classList.contains("flow")){
    document.documentElement.classList.add("flow");
    $app.style.height="";
  }
  var msg="消息区高度塌陷：渲染区仅 "+sh0+"px（容器 "+ah0+"px / 视口 "+vp0+
    "px / 消息 "+state.messages.length+" 条）；已降级为文档流显示";
  if($diag.textContent!==msg){
    $diag.textContent=msg;
    $diag.hidden=false;
    console.log("[webui] "+msg);
    reportDiag(msg,"warn");
  }
}

var healthLogged=false;
function logHealth(){
  if(healthLogged)return;
  if(document.documentElement.clientHeight<=0)return;
  healthLogged=true;
  var dvh=(window.CSS&&CSS.supports)?CSS.supports("height","100dvh"):"?";
  var snap="健康快照 vp="+document.documentElement.clientHeight+
    "x"+document.documentElement.clientWidth+" app="+$app.clientHeight+
    " stream="+$stream.clientHeight+" composer="+$composer.offsetHeight+
    " dvh支持="+dvh+" msgs="+state.messages.length;
  console.log("[webui] "+snap);
  reportDiag(snap,"info");
}

/* ── 3. 渲染：块分派 ─────────────────────────────────────────────────── */

function nearBottom(){
  return $stream.scrollHeight-$stream.scrollTop-$stream.clientHeight<140;
}
function scrollBottom(force){
  if(force||nearBottom())$stream.scrollTop=$stream.scrollHeight;
}

function showEmpty(on){
  if(!on){ if(emptyBox){emptyBox.remove();emptyBox=null;} return; }
  if(emptyBox)return;
  emptyBox=el("div",null);
  emptyBox.id="empty";
  emptyBox.textContent="这个会话还没有消息。";
  $stream.appendChild(emptyBox);
}

function renderChrome(){
  $title.textContent=state.bar.title||"ZorvAI";
  $model.textContent=state.bar.model||"选择模型";
  $persona.textContent=state.bar.persona||"选择人格";
  $cnt.textContent=state.messages.length+" 条消息";
  $dot.className="dot"+(state.composer.busy?" on":"");
  var darkNow=!!state.dark;
  if(darkNow!==state.darkApplied){
    state.darkApplied=darkNow;
    document.documentElement.classList.toggle("dark",darkNow);
    pushDark();
  }
  var sendIco=document.getElementById("send-ico");
  var stopIco=document.getElementById("stop-ico");
  if(sendIco)sendIco.hidden=state.composer.busy;
  if(stopIco)stopIco.hidden=!state.composer.busy;
  $send.className=state.composer.busy?"stop":"";
  $send.setAttribute("aria-label",state.composer.busy?"停止":"发送");
  $link.textContent=state.lan||(state.port?("127.0.0.1:"+state.port):"");
  var modes=$modes.querySelectorAll(".mode[data-mode]");
  for(var i=0;i<modes.length;i++){
    var k=modes[i].getAttribute("data-mode");
    if(state.composer[k])modes[i].classList.add("on"); else modes[i].classList.remove("on");
  }
  var ck=policyKey(state.composer.cms), pk=policyKey(state.composer.priv);
  $cms.textContent="CMS:"+POLICY_WORD[ck];
  $cms.className="mode tri "+ck;
  $priv.textContent="特权:"+POLICY_WORD[pk];
  $priv.className="mode tri "+pk;
  var ws=String(state.composer.workspace||"");
  $ws.textContent=ws?("工作区:"+ws.split("/").pop()):"工作区";
  if(ws)$ws.classList.add("on"); else $ws.classList.remove("on");
  showEmpty(state.messages.length===0);
}

/** 单个块 → DOM。块类型由宿主给，这里只画，不猜。 */
function blockNode(b){
  if(!b||!b.t)return null;
  switch(b.t){
    case "text":{
      var d=el("div","md-text"); d.innerHTML=mdHtml(b.text||""); return d;
    }
    case "heading":{
      var lv=Math.min(Math.max(b.level||3,1),4);
      var h=el("h"+lv); h.innerHTML=inline(b.text||""); return h;
    }
    case "quote":{
      var q=el("blockquote"); q.innerHTML=inline(b.text||""); return q;
    }
    case "rule": return el("hr");
    case "code":{
      var box=el("div","code");
      var btn=el("button","code-c","复制");
      btn.type="button";
      btn.setAttribute("data-code",b.code||"");
      if(b.lang){
        var lg=el("span","code-l",b.lang);
        box.appendChild(lg);
      }
      var pre=el("pre"), cd=el("code",null,b.code||"");
      pre.appendChild(cd); box.appendChild(btn); box.appendChild(pre);
      return box;
    }
    case "table":{
      var wrap=el("div","tw");
      var t=el("table"), thead=el("thead"), tr=el("tr");
      (b.header||[]).forEach(function(h){ var th=el("th"); th.innerHTML=inline(h); tr.appendChild(th); });
      thead.appendChild(tr); t.appendChild(thead);
      var tb=el("tbody");
      (b.rows||[]).forEach(function(r){
        var rtr=el("tr");
        (r||[]).forEach(function(c){ var td=el("td"); td.innerHTML=inline(c); rtr.appendChild(td); });
        tb.appendChild(rtr);
      });
      t.appendChild(tb); wrap.appendChild(t); return wrap;
    }
    case "native":{
      var n=el("div","native");
      var R=window.WebRenderers;
      if(R&&b.kind&&b.data){
        var darkNow=!!state.dark;
        if(b.kind==="mermaid"&&R.renderMermaid)R.renderMermaid(n,b.data,darkNow);
        else if(b.kind==="aip"&&R.renderAip)R.renderAip(n,b.data);
        else if(b.kind==="card"&&R.renderCard)R.renderCard(n,b.data);
        else if(b.kind==="dynamic_ui"&&R.renderDynamicUi)R.renderDynamicUi(n,b.data);
        else if(b.kind==="self_card"&&R.renderSelfCard)R.renderSelfCard(n,b.data);
        else if(b.kind==="miniapp"&&R.renderMiniApp)R.renderMiniApp(n,b.data);
        else return n;
        return n;
      }
      /* 渲染器未就绪 / 数据缺失：保留占位说明，不破坏布局 */
      var nt=el("div","nt",NATIVE_LABEL[b.kind]||("原生渲染："+b.kind));
      n.appendChild(nt);
      if(b.source){
        var det=el("pre",null,b.source);
        n.appendChild(det);
      }
      return n;
    }
    default: return null;
  }
}

/* ── 消息结构（对齐原生 MessageRow，ChatScreen.kt:3424-4141）──────────────
   原生真实结构（用户多次拍板的版式）：
     · **无气泡壳**：正文直接 Markdown 渲染，用户消息文字色 #5A3322（暖褐），
       AI 消息 cs.onBackground，两侧统一靠左；
     · **头像悬浮左上角**（Box overlay 不占流式空间），名字行 padding-start
       = avatarSize+10 避让；用户和 AI 头像都在左上角；
     · **名字行**：`作者 · 时间`（11sp muted）+ 思考/工具 TinyChip 胶囊
       （20dp 高、9sp 字、10dp 圆角；思考胶囊 Accent 底，工具胶囊 primary 底，
       执行中蓝色 #3B82F6 高亮）；
     · **思考胶囊**默认折叠，点「思考过程 · N步」展开（编号步骤列表，
       #FBF8F2 底）；流式中显示「思考中」；
     · **工具胶囊**默认折叠，「· N 工具」；展开是工具卡列表；
     · **WaitingDots**：7dp 三点、primary 色、正弦相位跳动（900ms 循环）；
     · **WorkingIndicator**：工具执行中显示「AI 正在执行工具…」；
     · **操作栏**（仅 AI 消息，28dp 高 11sp）：复制/追问/分享/删除/重试；
     · **底部全宽内联**（不被正文列限宽）：zxwidget / AIP / 组件卡片围栏 /
       富组件卡片 / 动态 UI / 自研卡片。                                        */
var AVATAR_INDENT=38;   /* avatarSize(28) + gap(10)，与原生 avatarIndentDp 一致 */
var QK_ASK="针对上面的回答，我想追问：\n\n";   /* 原生 qk_00066 */

function fmtTime(ts){
  var d=new Date(ts||Date.now()),now=new Date();
  function p(n){return (n<10?"0":"")+n;}
  var same=d.getFullYear()===now.getFullYear()&&d.getMonth()===now.getMonth()&&d.getDate()===now.getDate();
  return same?(p(d.getHours())+":"+p(d.getMinutes())):(p(d.getMonth()+1)+"/"+p(d.getDate()));
}
function isLastMsg(m){
  return state.messages.length>0&&state.messages[state.messages.length-1]===m;
}

/** 原生专属块（消息底部全宽内联）：aip / card / dynamic_ui / self_card。
    mermaid / miniapp 原生在正文列里画（QuroChatCardView），留在 md 区。 */
function isFullBlock(b){
  return !!b&&b.t==="native"&&b.kind!=="mermaid"&&b.kind!=="miniapp";
}

/** 消息纯文本（复制/追问/分享用）：原生取 msg.text，这里按块还原。 */
function plainTextOf(m){
  var out=[];
  (m.blocks||[]).forEach(function(b){
    if(!b||!b.t)return;
    if(b.t==="text"||b.t==="heading"||b.t==="quote")out.push(b.text||"");
    else if(b.t==="code")out.push("```"+(b.lang||"")+"\n"+(b.code||"")+"\n```");
    else if(b.t==="table"){
      var rows=[(b.header||[]).join(" | ")];
      (b.rows||[]).forEach(function(r){rows.push(r.join(" | "));});
      out.push(rows.join("\n"));
    }
  });
  return out.join("\n\n").replace(/\n{3,}/g,"\n\n").trim();
}

function copyText(t,btn){
  if(navigator.clipboard&&navigator.clipboard.writeText){
    navigator.clipboard.writeText(t).then(function(){
      if(btn){ btn.textContent="已复制"; setTimeout(function(){btn.textContent="复制";},1200); }
    });
  }
}
function shareText(t){
  if(navigator.share){ navigator.share({text:t}).catch(function(){}); return; }
  copyText(t,null);
}

/** 思考展开内容：编号步骤列表（原生 ThinkInlineContent：去标签、11sp、#FBF8F2 底）。 */
function thinkStepsHtml(r){
  var steps=String(r||"").split("\n").map(function(s){
    return s.replace(/<[^>]*>/g," ").replace(/\s+/g," ").trim();
  }).filter(function(s){return s;});
  var h="";
  steps.forEach(function(s,i){
    h+='<div class="ts"><span class="ts-n">'+(i+1)+'.</span><span class="ts-t">'+esc(s)+'</span></div>';
  });
  return h;
}
function countThinkSteps(r){
  return String(r||"").split("\n").filter(function(s){return s.trim();}).length;
}

/** 工具展开内容：每个工具一张卡（原生 ToolCallGroup 的网页对等实现）。 */
function toolsHtml(calls){
  var h="";
  (calls||[]).forEach(function(tc){
    var done=!(tc.result===null||tc.result===undefined||tc.result==="");
    h+='<div class="tool'+(done?" ok":"")+'">'+
      '<div class="tool-h"><span class="tool-n">'+esc(tc.name||"tool")+'</span>'+
      '<span class="tool-s">'+(done?"完成":"运行中")+'</span>'+
      '<span class="tool-d">'+(tc.durationMs?(tc.durationMs/1000).toFixed(1)+"s":"")+'</span></div>'+
      '<div class="tool-b">'+
      (tc.arguments?'<div class="lb">参数</div><pre>'+esc(String(tc.arguments))+'</pre>':'')+
      ((done&&String(tc.result)!=="")?'<div class="lb">结果</div><pre>'+esc(String(tc.result))+'</pre>':'')+
      '</div></div>';
  });
  return h;
}

/** 操作栏（仅 AI 消息）：复制/追问/分享/删除/重试，原生五按钮一一对应。 */
var ACT_LABEL={copy:"复制",ask:"追问",share:"分享",del:"删除",retry:"重试"};
function actsHtml(){
  var h="";
  ["copy","ask","share","del","retry"].forEach(function(k){
    h+='<button type="button" class="ab" data-act="'+k+'">'+ACT_LABEL[k]+'</button>';
  });
  return h;
}
function onAct(nd,m,act,btn){
  var txt=plainTextOf(m);
  if(act==="copy"){ copyText(txt,btn); }
  else if(act==="ask"){
    /* 原生 onAskFollowup：qk_00066 + txt.take(200).replace("\n","\n> ") */
    $inp.value=QK_ASK+txt.slice(0,200).replace(/\n/g,"\n> ")+"\n\n";
    autoGrow(); $inp.focus();
  }
  else if(act==="share"){ shareText(txt); }
  else if(act==="del"){
    if(!m.id||String(m.id).indexOf("local-")===0)return;
    post("msg_action",{action:"delete",ids:[m.id]}).then(function(r){
      if(r&&r.ok){ var root=nd.root; delete nodes[m.id]; root.remove(); }
    });
  }
  else if(act==="retry"){
    post("msg_action",{action:"regenerate"}).then(function(r){
      if(r&&r.ok){ /* 新回答经 SSE 流自然上屏 */ }
      else if(r&&r.error)$cnt.textContent="重试失败："+r.error;
    });
  }
}

/** 把原生专属块列表画进指定全宽盒子（清空重画，空则隐藏）。 */
function paintNativeBox(box,list){
  box.innerHTML="";
  list.forEach(function(b){ var n=blockNode(b); if(n)box.appendChild(n); });
  box.hidden=list.length===0;
}

/** 按原生消息底部次序（ChatScreen.kt:4009-4140）切分全宽原生块：
    fence = AIP(5) + card 围栏(6)；tail = 动态UI(8) + 自研卡(9)。
    富组件卡片（msg.cards+inlineCards，原生第 7 位）由 renderCards 画进 cardBox，
    正好夹在 fence 与 tail 之间。 */
var FULL_FENCE_ORDER={aip:1,card:2};
var FULL_TAIL_ORDER={dynamic_ui:1,self_card:2};
function splitFullBlocks(full){
  var fence=[],tail=[];
  full.forEach(function(b){
    if(b.kind==="dynamic_ui"||b.kind==="self_card")tail.push(b); else fence.push(b);
  });
  fence.sort(function(a,b){ return (FULL_FENCE_ORDER[a.kind]||9)-(FULL_FENCE_ORDER[b.kind]||9); });
  tail.sort(function(a,b){ return (FULL_TAIL_ORDER[a.kind]||9)-(FULL_TAIL_ORDER[b.kind]||9); });
  return {fence:fence,tail:tail};
}
function fullBoxSig(list){
  return list.length+"|"+list.map(function(b){
    return (b.kind||"")+":"+JSON.stringify(b.data||{}).length;
  }).join(",");
}

function renderBlocks(nd,m){
  var blocks=m.blocks||[];
  var md=[],full=[];
  blocks.forEach(function(b){
    if(!b||!b.t)return;
    if(isFullBlock(b))full.push(b); else md.push(b);
  });
  /* 正文列：文本/标题/引用/分割线/代码/表格 + mermaid/miniapp（原生同在正文列） */
  var sig=md.length+"|"+md.map(function(b){
    return (b.t||"")+":"+String(b.text||b.code||b.source||b.html||"").length;
  }).join(",");
  if(sig!==nd.mdSig){
    nd.mdSig=sig;
    nd.md.innerHTML="";
    var any=false;
    md.forEach(function(b){ var n=blockNode(b); if(n){ nd.md.appendChild(n); any=true; } });
    nd.md.hidden=!any;
  }
  /* 底部全宽内联：zxwidget → AIP → card 围栏 → 富组件卡片 → 动态 UI → 自研卡片。
     按原生消息底部的次序排（原生 ChatScreen.kt:4009-4140 同序）；
     富组件卡片在 renderCards 里画进 cardBox，夹在 fence 与 tail 之间。 */
  var sp=splitFullBlocks(full);
  var fsig=fullBoxSig(sp.fence);
  if(fsig!==nd.fenceSig){ nd.fenceSig=fsig; paintNativeBox(nd.full.fenceBox,sp.fence); }
  var tsig=fullBoxSig(sp.tail);
  if(tsig!==nd.tailSig){ nd.tailSig=tsig; paintNativeBox(nd.full.tailBox,sp.tail); }
  syncFullHidden(nd);
}

/** 富组件卡片（ui_widget/ui_card 内联下发 + run_code 产物）：全宽内联，FlowRow 排布。 */
function renderCards(nd,m){
  var cards=m.cards||[];
  var sig=cards.map(function(c){return (c.id||"")+":"+(c.cardType||"");}).join(",");
  if(sig===nd.cardsSig)return;
  nd.cardsSig=sig;
  var cb=nd.full.cardBox;
  cb.innerHTML="";
  if(cards.length){
    var box=el("div","cardflow");
    var R=window.WebRenderers;
    cards.forEach(function(c){
      if(!(R&&R.renderCardNode))return;
      /* renderCardNode 只画卡内部；外面套 .qcard 壳（renderCard 同款边框/圆角/阴影） */
      var cell=el("div","cf-cell");
      var shell=el("div","qcard");
      shell.appendChild(R.renderCardNode(c));
      cell.appendChild(shell);
      box.appendChild(cell);
    });
    if(box.children.length)cb.appendChild(box);
  }
  syncFullHidden(nd);
}

/** zxwidget 互动UI界面：iframe 引同一份运行时，全宽内联在消息底部（原生同序：最前）。 */
function renderUi(nd,m){
  var list=m.ui||[];
  var sig=list.map(function(u){
    return (u.component||"")+":"+String(u.dsl||"").length;
  }).join("~");
  if(sig===nd.uiSig)return;
  nd.uiSig=sig;
  var ub=nd.full.uiBox;
  ub.innerHTML="";
  if(list.length){
    var box=el("div","ui-list");
    list.forEach(function(u){ box.appendChild(zxFrame(u.component,u.dsl)); });
    ub.appendChild(box);
  }
  syncFullHidden(nd);
}

/** 全宽区四个子容器都空 → 整块隐藏（不留空边距）。 */
function syncFullHidden(nd){
  var f=nd.full;
  f.hidden=!(f.uiBox.children.length||f.fenceBox.children.length||
             f.cardBox.children.length||f.tailBox.children.length);
}

/** 思考胶囊（原生 TinyChip：默认折叠，点「思考过程 · N步」展开）。
    [forceStreaming] 供流式重绘用：那边手里只有增量文本，没有完整消息对象。 */
function renderThink(nd,m,forceStreaming){
  var r=m.reasoning||"";
  if(!r){
    if(nd.thinkChip){nd.thinkChip.remove();nd.thinkChip=null;}
    nd.thinkbox.hidden=true; nd.thinkbox.innerHTML=""; nd.thinkSrc="";
    return;
  }
  if(!nd.thinkChip){
    var chip=el("button","nchip think");
    chip.type="button";
    chip.appendChild(el("span","nc-ico","✦"));
    chip.appendChild(el("span","nc-t"));
    chip.addEventListener("click",function(){
      nd.showThink=!nd.showThink;
      nd.thinkbox.hidden=!nd.showThink;
      chip.classList.toggle("on",nd.showThink);
      if(nd.showThink&&nd.thinkSrc!==(nd.cur&&nd.cur.reasoning||"")){
        nd.thinkbox.innerHTML=thinkStepsHtml(nd.cur&&nd.cur.reasoning); nd.thinkSrc=nd.cur&&nd.cur.reasoning;
      }
    });
    nd.chips.appendChild(chip);
    nd.thinkChip=chip;
  }
  /* 生成中显示「思考中」，完成后显示「思考过程 · N步」（原生 qk_00093/94 同规则） */
  var streaming=forceStreaming||!!(state.composer.busy&&isLastMsg(m));
  nd.thinkChip.querySelector(".nc-t").textContent=
    streaming?"思考中":("思考过程 · "+countThinkSteps(r)+"步");
  nd.thinkChip.classList.toggle("run",streaming);
  if(nd.showThink&&nd.thinkSrc!==r){
    nd.thinkbox.innerHTML=thinkStepsHtml(r); nd.thinkSrc=r;
  }
}

/** 工具胶囊（原生 TinyChip：默认折叠，「· N 工具」；执行中蓝色高亮）。 */
function renderTools(nd,m){
  var calls=m.tools||[];
  if(!calls.length){
    if(nd.toolsChip){nd.toolsChip.remove();nd.toolsChip=null;}
    nd.toolsbox.hidden=true; nd.toolsbox.innerHTML=""; nd.toolSig="";
    return;
  }
  var sig=calls.map(function(t){
    return (t.name||"")+"|"+(t.result===null||t.result===undefined?"r":"d")+
           "|"+String(t.result===null||t.result===undefined?"":t.result).length+
           "|"+(t.durationMs||0);
  }).join("~");
  if(!nd.toolsChip){
    var chip=el("button","nchip tools");
    chip.type="button";
    chip.appendChild(el("span","nc-ico","⚙"));
    chip.appendChild(el("span","nc-t"));
    chip.addEventListener("click",function(){
      nd.showTools=!nd.showTools;
      nd.toolsbox.hidden=!nd.showTools;
      chip.classList.toggle("on",nd.showTools);
      if(nd.showTools)nd.toolsbox.innerHTML=toolsHtml((nd.cur&&nd.cur.tools)||[]);
    });
    nd.chips.appendChild(chip);
    nd.toolsChip=chip;
  }
  /* 执行中（结果尚未回填）→ 蓝色高亮，让用户知道 AI 还在动手（原生 #3B82F6） */
  var running=calls.some(function(t){
    return t.result===null||t.result===undefined||t.result==="";
  });
  nd.toolsChip.querySelector(".nc-t").textContent="· "+calls.length+" 工具";
  nd.toolsChip.classList.toggle("run",running);
  if(nd.showTools&&sig!==nd.toolSig){
    nd.toolSig=sig;
    nd.toolsbox.innerHTML=toolsHtml(calls);
  }
}

function renderAttachments(nd,m){
  var atts=m.attachments||[];
  if(nd.attsDone)return;
  if(!atts.length){nd.attsDone=true;return;}
  var box=el("div","atts");
  atts.forEach(function(a){
    var url=a.uri||a.url||"";
    if(!url)return;
    var name=a.name||url.split("/").pop()||"附件";
    if(/^https?:/i.test(url)&&/\.(png|jpe?g|gif|webp|bmp)$/i.test(url.split("?")[0])){
      var im=el("img","att-img");
      im.src=url; im.loading="lazy";
      im.onerror=function(){ var chip=el("div","att"); chip.appendChild(el("span",null,name)); im.replaceWith(chip); };
      box.appendChild(im);
    }else{
      var chip=el("div","att");
      chip.appendChild(el("span",null,name));
      box.appendChild(chip);
    }
  });
  if(box.children.length){ nd.atts.innerHTML=""; nd.atts.appendChild(box); }
  nd.attsDone=true;
}

function b64(s){
  try{ return btoa(unescape(encodeURIComponent(s))); }catch(e){ return ""; }
}

function zxFrame(comp,dsl){
  var f=document.createElement("iframe");
  f.className="zxf";
  f.setAttribute("data-zx",comp||"");
  f.setAttribute("scrolling","no");
  f.src="zx/shell#"+b64(JSON.stringify({component:comp||"",dsl:dsl||{},dark:!!state.dark}));
  return f;
}

function frameOf(src){
  var fs=document.querySelectorAll("iframe.zxf");
  for(var i=0;i<fs.length;i++){ if(fs[i].contentWindow===src)return fs[i]; }
  return null;
}

window.addEventListener("message",function(e){
  var d=e.data||{};
  if(!d||typeof d!=="object")return;
  var f=frameOf(e.source);
  if(!f)return;
  if(d.__zx==="h"){
    var h=parseInt(d.px,10);
    if(!isFinite(h)||h<=0)return;
    var cur=parseInt(f.getAttribute("data-h")||"0",10);
    if(h===cur)return;
    f.setAttribute("data-h",String(h));
    f.style.height=Math.max(120,Math.min(h+2,680))+"px";
  }else if(d.__zx==="i"){
    var comp=f.getAttribute("data-zx")||"";
    var payload=d.payload;
    if(!comp||!payload)return;
    var obj;
    try{ obj=JSON.parse(payload); }catch(err){ obj={action:"raw",summary:String(payload)}; }
    post("interact",{component:comp,payload:obj});
  }
});

function pushDark(){
  var fs=document.querySelectorAll("iframe.zxf");
  for(var i=0;i<fs.length;i++){
    try{ fs[i].contentWindow.postMessage({__zxc:"dark",dark:!!state.dark},"*"); }catch(e){}
  }
}

function renderActs(nd,m){
  if(m.mine){ nd.acts.hidden=true; nd.acts.innerHTML=""; return; }
  if(nd.actsDone)return;
  nd.actsDone=true;
  nd.acts.innerHTML=actsHtml();
  nd.acts.hidden=false;
}

function renderMessage(m){
  var nd=nodes[m.id];
  if(!nd)return;
  nd.cur=m;   /* 胶囊点击回调读最新消息，不闭包过期对象 */
  /* 头像（图片型优先，否则首字母圆标——与原生 AvatarContent 一致） */
  var avTxt=(m.avatar||(m.mine?"我":"Z"));
  if(nd.avTxt!==avTxt||nd.avUrl!==(m.avatarUrl||"")){
    nd.avTxt=avTxt; nd.avUrl=m.avatarUrl||"";
    nd.av.innerHTML="";
    if(nd.avUrl){
      var im=el("img"); im.src=nd.avUrl; im.alt=""; im.loading="lazy";
      im.onerror=function(){ nd.av.innerHTML=""; nd.av.appendChild(el("span",null,avTxt)); };
      nd.av.appendChild(im);
    }else{
      nd.av.appendChild(el("span",null,avTxt));
    }
  }
  /* 名字行：作者 · 时间 */
  var author=m.author||m.senderName||(m.mine?"我":(state.bar.title||"ZorvAI"));
  nd.nname.textContent=author+" · "+(m.timeText||fmtTime(m.time));
  renderThink(nd,m);
  renderBlocks(nd,m);
  renderCards(nd,m);
  renderTools(nd,m);
  renderUi(nd,m);
  renderAttachments(nd,m);
  renderActs(nd,m);
  /* 等待态 / 执行态指示（原生 WaitingDots / WorkingIndicator） */
  var busyLast=!!(state.composer.busy&&isLastMsg(m));
  var hasText=(m.blocks||[]).some(function(b){return b&&b.t==="text"&&(b.text||"").trim();});
  nd.waiting.hidden=!(busyLast&&!m.mine&&!hasText&&!(m.reasoning||""));
  var calls=m.tools||[];
  var working=calls.some(function(t){return t.result===null||t.result===undefined||t.result==="";});
  nd.working.hidden=!(busyLast&&working);
  if(nd.md){
    if(busyLast)nd.md.classList.add("streaming"); else nd.md.classList.remove("streaming");
  }
}

function ensureNode(m){
  var nd=nodes[m.id];
  if(nd)return nd;
  var root=el("div","msg "+(m.mine?"mine":"ai"));
  root.setAttribute("data-mid",m.id);
  var av=el("div","av");
  var mcol=el("div","mcol");
  var nrow=el("div","nrow");
  var nname=el("span","nname");
  var chips=el("span","nchips");
  nrow.appendChild(nname); nrow.appendChild(chips);
  var waiting=el("div","waiting");
  waiting.appendChild(el("span"));waiting.appendChild(el("span"));waiting.appendChild(el("span"));
  var working=el("div","working");
  var wd=el("span","wdots");
  wd.appendChild(el("span"));wd.appendChild(el("span"));wd.appendChild(el("span"));
  working.appendChild(wd);
  working.appendChild(el("span","wtxt","AI 正在执行工具…"));
  var thinkbox=el("div","thinkbox"); thinkbox.hidden=true;
  var toolsbox=el("div","toolsbox"); toolsbox.hidden=true;
  var md=el("div","md");
  var atts=el("div","atts");
  var acts=el("div","acts"); acts.hidden=true;
  var full=el("div","fullbox"); full.hidden=true;
  /* 四个固定次序子容器，与原生 ChatScreen.kt:3968-4140 消息底部逐段对齐：
     zxwidget → AIP/card围栏 → 富组件卡片(msg.cards+inlineCards) → 动态UI/自研卡。
     流式与终渲各写各的盒子，不再共写 innerHTML（曾导致 done 后排序抖动）。 */
  var uiBox=el("div","full-ui");
  var fenceBox=el("div","full-fence");
  var cardBox=el("div","full-card");
  var tailBox=el("div","full-tail");
  full.uiBox=uiBox; full.fenceBox=fenceBox; full.cardBox=cardBox; full.tailBox=tailBox;
  full.appendChild(uiBox); full.appendChild(fenceBox); full.appendChild(cardBox); full.appendChild(tailBox);
  mcol.appendChild(nrow);
  mcol.appendChild(waiting);
  mcol.appendChild(working);
  mcol.appendChild(thinkbox);
  mcol.appendChild(toolsbox);
  mcol.appendChild(md);
  mcol.appendChild(atts);
  mcol.appendChild(acts);
  mcol.appendChild(full);
  root.appendChild(av);
  root.appendChild(mcol);
  nd={root:root,av:av,avTxt:null,avUrl:null,mcol:mcol,nrow:nrow,nname:nname,chips:chips,
      waiting:waiting,working:working,
      thinkbox:thinkbox,toolsbox:toolsbox,thinkChip:null,toolsChip:null,thinkSrc:"",
      showThink:false,showTools:false,toolSig:"",
      md:md,mdSig:null,atts:atts,attsDone:false,acts:acts,actsDone:false,
      full:full,fenceSig:null,tailSig:null,cardsSig:null,uiSig:null};
  nodes[m.id]=nd;
  $stream.appendChild(root);
  return nd;
}

function syncOrder(ids){
  var want=[];
  ids.forEach(function(id){ if(nodes[id])want.push(nodes[id].root); });
  var cur=[];
  for(var i=0;i<$stream.children.length;i++){
    var c=$stream.children[i];
    if(c!==emptyBox)cur.push(c);
  }
  /* 孤儿清扫：不在 want 白名单里的 .msg 根一律移除（ migrateNode 历史覆盖键
     曾留下这种节点，prune 只遍历 nodes 管不到）。 */
  cur.forEach(function(c){ if(want.indexOf(c)<0)c.remove(); });
  var same=cur.length===want.length;
  if(same){ for(var j=0;j<want.length;j++){ if(cur[j]!==want[j]){same=false;break;} } }
  if(same)return;
  want.forEach(function(n){$stream.appendChild(n);});
}

function prune(ids){
  Object.keys(nodes).forEach(function(id){
    if(ids.indexOf(id)<0){ nodes[id].root.remove(); delete nodes[id]; }
  });
}

/** 清掉 state.messages 里的本地占位对象（local-* 换键后已成孤儿 id）。
    [keep] 例外保留（即将被改名的乐观气泡）。返回是否已存在 realId 的真实消息。 */
function dropLocalPlaceholders(realId,keep){
  var have=false;
  for(var i=state.messages.length-1;i>=0;i--){
    var mm=state.messages[i],id=mm.id||"";
    if(id===realId){ have=true; continue; }
    if(id.indexOf("local-")===0&&mm!==keep)state.messages.splice(i,1);
  }
  return have;
}

/* ── 4. 与宿主同步：SSE 长连接（流式），轮询只作兜底 ───────────────────── */

function apply(snap){
  if(!snap||!snap.ok)return;
  var stick=nearBottom();
  var wasBusy=state.composer.busy;

  state.bar=snap.bar||state.bar;
  state.composer=snap.composer||state.composer;
  state.dark=!!snap.dark;
  state.lan=snap.lan||"";
  state.port=snap.port||0;
  if(stream.active){
    /* 流式期间消息由 /api/send 的 SSE 独占：快照只更新非消息状态。
       若这里照旧全量重绘，会把增量上屏的气泡整体冲掉重来 —— 那正是
       「AI 写到一半卡了 / 消息延迟」在前端侧的成因。 */
    renderChrome();
    guardCollapse();
    if(!wasBusy&&state.composer.busy)scrollBottom(true);
    return;
  }
  /* chrome-only 快照（服务端流式期间只推非消息字段）：没有 messages 键时
     保持现有消息列表原样不动，只更新 chrome。绝不能当成空列表替换——
     那会把流式期间刚上屏的气泡/别处tab正在看的会话整体清掉。 */
  if(Array.isArray(snap.messages)){
    state.messages=(snap.messages||[]).filter(function(m){
      return m&&m.id&&(m.role==="user"||m.role==="assistant");
    });
  }

  var ids=state.messages.map(function(m){return m.id;});
  prune(ids);
  state.messages.forEach(function(m){ ensureNode(m); renderMessage(m); });
  syncOrder(ids);
  renderChrome();
  guardCollapse();
  logHealth();
  if(stick||(!wasBusy&&state.composer.busy))scrollBottom(true);
}

function fetchSnapshot(){
  fetch("api/snapshot",{cache:"no-store"})
    .then(function(r){return r.json();})
    .then(function(j){ apply(j); })
    .catch(function(){});
}

var es=null, esFails=0, streamDead=false;

function startStream(){
  if(streamDead||!window.EventSource){ schedule(1200); return; }
  try{ es=new EventSource("api/events"); }catch(e){ schedule(1200); return; }
  es.onopen=function(){ esFails=0; };
  es.onmessage=function(ev){
    try{ apply(JSON.parse(ev.data)); }catch(e){}
  };
  es.onerror=function(){
    esFails++;
    if(esFails>=3){
      streamDead=true;
      try{ es.close(); }catch(e){}
      es=null;
      schedule(2000);
    }
  };
}

function poll(){
  if(document.hidden){ schedule(2000); return; }
  fetch("api/snapshot",{cache:"no-store"})
    .then(function(r){return r.json();})
    .then(function(j){
      apply(j);
      schedule(2000);
    })
    .catch(function(){
      $cnt.textContent="连接不上服务，重试中…";
      schedule(2000);
    });
}

function schedule(ms){
  if(pollTimer)clearTimeout(pollTimer);
  pollTimer=setTimeout(poll,ms);
}

/* ── 5. 操作（对等客户端：全部写回原生同一真源）────────────────────── */

function post(path,body){
  return fetch("api/"+path,{
    method:"POST",
    headers:{"Content-Type":"application/json"},
    body:JSON.stringify(body||{})
  })
    .then(function(r){return r.json();})
    .then(function(j){ return j; })
    .catch(function(){ return null; });
}

function reportDiag(msg,level){
  try{
    fetch("api/diag",{
      method:"POST",
      headers:{"Content-Type":"application/json"},
      body:JSON.stringify({level:level||"warn",msg:String(msg).slice(0,900)})
    }).catch(function(){});
  }catch(e){}
}

/* ── 5. 发送：SSE 增量流（Operit web-chat 同款协议）─────────────────── */

/** 解析一个 SSE 帧（`event:` + `data:` 双字段）。 */
function parseSseBlock(block){
  var event="message",data="";
  block.split(/\r?\n/).forEach(function(line){
    if(line.indexOf("event:")===0)event=line.slice(6).trim();
    else if(line.indexOf("data:")===0)data+=(data?"\n":"")+line.slice(5).replace(/^ /,"");
  });
  if(!data)return null;
  try{ return {event:event,data:JSON.parse(data)}; }catch(e){ return null; }
}

/**
 * 逐块读 SSE 流（**不用 EventSource**：POST 要带 body，还要能 AbortController 取消）。
 * 每个 TCP 分片可能含多个事件帧，也可能半个 —— 按空行切帧、余量留在 buffer。
 */
function readSse(resp,onEvent){
  if(!resp.body||!resp.body.getReader){
    /* 兜底（老浏览器）：整读后按帧切。失去流式但协议不变。 */
    stream.lastAt=Date.now();
    return resp.text().then(function(all){
      all.split(/\r?\n\r?\n/).forEach(function(b){
        var ev=parseSseBlock(b.trim()); if(ev)onEvent(ev);
      });
    });
  }
  var reader=resp.body.getReader(),decoder=new TextDecoder(),buf="";
  function pump(){
    return reader.read().then(function(r){
      if(r.done)return;
      /* 收到任何字节即算活着 —— 含服务端每 15s 的 ": ping" 注释帧
         （注释帧过不了 parseSseBlock，不刷新就会误杀长工具调用期间的活连接） */
      stream.lastAt=Date.now();
      buf+=decoder.decode(r.value,{stream:true});
      var blocks=buf.split(/\r?\n\r?\n/);
      buf=blocks.pop()||"";
      for(var i=0;i<blocks.length;i++){
        var ev=parseSseBlock(blocks[i].trim());
        if(ev)onEvent(ev);
      }
      return pump();
    });
  }
  return pump();
}

/** 把本地占位节点改键到真实消息 id（DOM 不动，只换 nodes 表里的键）。 */
function migrateNode(oldId,newId){
  var nd=nodes[oldId];
  if(!nd)return null;
  delete nodes[oldId];
  /* 碰撞保护：newId 节点已存在（刷新页面后快照先渲染了同 id 消息、或轮询快照
     与 SSE 事件竞态）。直接覆盖键会让旧 DOM 变孤儿——不在 nodes 里，prune 只遍历
     nodes、syncOrder 只认 want 白名单，都管不到它 → 页面永久留一条重复消息
     （2026-10-14 探针实测 4 条消息 duiRoots=2）。保留已有节点，撤掉本地占位。 */
  if(nodes[newId]&&nodes[newId]!==nd){
    nd.root.remove();
    return nodes[newId];
  }
  nodes[newId]=nd;
  /* 🔴 data-mid 必须跟着改：操作栏点击 delegation 靠它找回消息对象。
     漏了这行 = 迁移后点「追问/删除」静默无效（2026-10-14 e2e 抓到的实 bug）。 */
  nd.root.setAttribute("data-mid",newId);
  return nd;
}

/** rAF 节流的流式重绘：思考胶囊 + mini-markdown 正文 + 光标 + 近实时原生块。 */
function scheduleStreamRender(){
  if(stream.raf)return;
  stream.raf=requestAnimationFrame(function(){
    stream.raf=0;
    var nd=stream.node;
    if(!nd)return;
    /* 流式期间胶囊回调读这份「增量快照」 */
    nd.cur={reasoning:stream.reason,tools:[]};
    /* 思考：流式期间也走名字行胶囊（「思考中」），内容默认折叠 */
    if(stream.reason){
      renderThink(nd,{reasoning:stream.reason},true);
    }
    /* 正文：原生专属围栏不当代码块裸露（mdHtml streaming 模式） */
    if(!nd.md)return;
    nd.md.classList.add("streaming");
    /* 光标只贴在段落末尾：代码块/表格后面挂一个 inline 光标会掉到下一行，难看 */
    var html=mdHtml(stream.text,true);
    if(/<\/p>\s*$/.test(html))html+='<span class="cur"></span>';
    nd.md.innerHTML=html;
    nd.md.hidden=!html;
    /* 等待态：首 token 前的三点跳动由 WaitingDots 担当，有内容即撤 */
    nd.waiting.hidden=true;
    /* 近实时原生块（任务 #255）：宿主随 delta 推来已可解析的
       quro-ui / 卡片 / AIP 块，这里画进消息底部全宽区 */
    if(stream.blocks&&stream.blocks.length){
      renderStreamBlocks(nd,stream.blocks);
    }
    scrollBottom(false);
  });
}

/** 把流式 delta 带来的 native 块画进消息底部全宽区（与终渲同一条 blockNode 通道）。
    mermaid/miniapp 原生在正文列，流式期间先全宽画出，done 终渲时归位。 */
function renderStreamBlocks(nd,blocks){
  var fence=[],tail=[];
  (blocks||[]).forEach(function(b){
    if(!b||b.t!=="native")return;
    if(isFullBlock(b)){
      if(b.kind==="dynamic_ui"||b.kind==="self_card")tail.push(b); else fence.push(b);
    }else{
      fence.push(b);
    }
  });
  fence.sort(function(a,b){ return (FULL_FENCE_ORDER[a.kind]||9)-(FULL_FENCE_ORDER[b.kind]||9); });
  tail.sort(function(a,b){ return (FULL_TAIL_ORDER[a.kind]||9)-(FULL_TAIL_ORDER[b.kind]||9); });
  var fsig=fullBoxSig(fence);
  if(fsig!==nd.fenceSig){ nd.fenceSig=fsig; paintNativeBox(nd.full.fenceBox,fence); }
  var tsig=fullBoxSig(tail);
  if(tsig!==nd.tailSig){ nd.tailSig=tsig; paintNativeBox(nd.full.tailBox,tail); }
  syncFullHidden(nd);
}

function onStreamEvent(ev){
  if(!ev)return;
  var i,idx;
  switch(ev.event){
    case "start":
      stream.active=true; stream.gotDone=false;
      state.composer.busy=true;
      renderChrome();
      /* 立即建助手占位气泡（原生 WaitingDots 三点跳动），首帧 delta 到达即迁移到真实 id */
      var localAid="local-assistant-"+Date.now();
      var am0={id:localAid,role:"assistant",mine:false,author:"",avatar:"",timeText:fmtTime(Date.now()),
               time:Date.now(),blocks:[]};
      state.messages.push(am0);
      ensureNode(am0);
      var nd0=nodes[localAid];
      nd0.nname.textContent=(state.bar.title||"ZorvAI")+" · "+fmtTime(Date.now());
      nd0.waiting.hidden=false;   /* WaitingDots：首 token 前的三点跳动 */
      stream.localAid=localAid;
      scrollBottom(true);
      break;
    case "user_message":{
      var m=ev.data;
      if(!m||!m.id)break;
      if(stream.localUserId&&nodes[stream.localUserId])migrateNode(stream.localUserId,m.id);
      stream.localUserId=null;
      /* state.messages 对齐：乐观气泡要么改名成真实 id，要么（快照已渲染同 id 时）
         直接清掉。不对齐 = 流式期间 delegation 按 data-mid 回查不到消息。 */
      var haveU=dropLocalPlaceholders(m.id,stream.localUserMsg);
      if(stream.localUserMsg&&!haveU)stream.localUserMsg.id=m.id;
      stream.localUserMsg=null;
      if(!haveU&&!nodes[m.id])state.messages.push(m);
      ensureNode(m); renderMessage(m);
      scrollBottom(true);
      break;
    }
    case "assistant_delta":{
      var d=ev.data||{};
      var id=d.id||"";
      if(!id)break;
      if(stream.segId!==id){
        /* 新段：首 token 占位气泡 / 工具轮次后的新回复段。
           把本地等待气泡迁移到真实 id，去掉三点等待态。 */
        if(stream.localAid&&nodes[stream.localAid]){
          migrateNode(stream.localAid,id);
          nodes[id].waiting.hidden=true;
          stream.localAid=null;
        }
        stream.segId=id; stream.text=""; stream.reason=""; stream.blocks=[];
        /* 段对象进 state.messages：清掉 start 阶段的 local-assistant-* 占位，
           快照已渲染同 id 则复用现有对象。不对齐 = 流式期间操作栏 delegation
           按 data-mid 回查不到消息，点击静默失效。 */
        var haveA=dropLocalPlaceholders(id,null);
        if(!haveA){
          state.messages.push({id:id,role:"assistant",mine:false,author:"",avatar:"",
            timeText:fmtTime(Date.now()),time:Date.now(),blocks:[]});
        }
        if(!nodes[id])ensureNode({id:id,role:"assistant",mine:false,author:"",avatar:"",
          timeText:fmtTime(Date.now()),time:Date.now(),blocks:[]});
        stream.node=nodes[id];
        if(!stream.node.nname.textContent){
          stream.node.nname.textContent=(state.bar.title||"ZorvAI")+" · "+fmtTime(Date.now());
        }
      }
      if(d.text)stream.text+=d.text;
      if(d.reasoning)stream.reason+=d.reasoning;
      if(d.blocks&&d.blocks.length)stream.blocks=d.blocks;
      scheduleStreamRender();
      break;
    }
    case "assistant_done":{
      stream.gotDone=true;
      stream.active=false;
      /* 本地立即解除发送锁：不能干等服务端快照来纠正。若 VM 侧 busy 因故
         不收敛（ issue #11 报的「界面持续转圈」），快照会在下一 tick 把它
         重新置 true —— 真忙才锁，假死必须放行。 */
      state.composer.busy=false;
      var fm=ev.data&&ev.data.message;
      if(fm&&fm.id){
        if(stream.segId&&stream.segId!==fm.id&&nodes[stream.segId]){
          nodes[stream.segId].root.remove(); delete nodes[stream.segId];
        }
        idx=-1;
        for(i=0;i<state.messages.length;i++){ if(state.messages[i].id===fm.id){idx=i;break;} }
        if(idx>=0)state.messages[idx]=fm; else state.messages.push(fm);
        ensureNode(fm); renderMessage(fm);
        var fnd=nodes[fm.id];
        if(fnd&&fnd.md)fnd.md.classList.remove("streaming");
        if(fnd){ fnd.waiting.hidden=true; fnd.working.hidden=true; }
      }
      stream.segId=null; stream.text=""; stream.reason=""; stream.node=null; stream.blocks=[];
      if(stream.localAid&&nodes[stream.localAid]){ nodes[stream.localAid].root.remove(); delete nodes[stream.localAid]; stream.localAid=null; }
      renderChrome();
      scrollBottom(true);
      /* 重拉快照：对齐流式期间被忽略的离散变化（标题/开关/集群发言…） */
      fetchSnapshot();
      break;
    }
    case "error":{
      stream.active=false;
      state.composer.busy=false;
      var em={id:"local-error-"+Date.now(),role:"assistant",mine:false,author:"",avatar:"",
              timeText:fmtTime(Date.now()),time:Date.now(),
              blocks:[{t:"text",text:"⚠️ "+((ev.data&&ev.data.error)||"连接中断")}]};
      state.messages.push(em); ensureNode(em); renderMessage(em);
      stream.segId=null; stream.text=""; stream.reason=""; stream.node=null; stream.blocks=[];
      renderChrome();
      fetchSnapshot();
      break;
    }
  }
}

/** 流结束（无论正常与否）：收尾并重拉快照对齐真相。所有终态路径的唯一收口。 */
function onStreamEnd(){
  stopStreamWatchdog();
  stream.active=false;
  stream.ctrl=null;
  /* 解除发送锁：与 assistant_done / error 同旨。VM 真忙时随后的快照会重新置 true，
     假死（busy 卡 true）必须在这里放行，否则后续每次点发送都被 doSend 守卫拒绝。 */
  state.composer.busy=false;
  if(stream.localAid&&nodes[stream.localAid]){
    /* 没等到任何 delta 就断了：保留占位没有意义，交给快照重建 */
    nodes[stream.localAid].root.remove(); delete nodes[stream.localAid]; stream.localAid=null;
  }
  stream.node=null;
  renderChrome();
  if(!stream.gotDone)fetchSnapshot();
}

/** 假死看门狗：发送流在飞但 45s 没有任何字节（含 ": ping" 注释帧）→ 判定连接已死。
    没有它就是 issue 里那个死锁：SSE 断线后 stream.active / composer.busy 卡住，
    后续每次点发送都被 doSend 守卫悄悄拒绝 —— 表现即「消息半天发不到后端」。 */
function startStreamWatchdog(){
  stopStreamWatchdog();
  stream.lastAt=Date.now();
  stream.watchdog=setInterval(function(){
    if(!stream.ctrl)return;                      /* 没有在飞的发送流 */
    if(Date.now()-stream.lastAt<STREAM_STALL_MS)return;
    var c=stream.ctrl; stream.ctrl=null;
    try{ c.abort(); }catch(e){}
    /* abort 的 AbortError 会被 doSend 的 catch 当「用户点停止」直接收尾、不弹错误，
       这里先补一个可见的错误事件（它自带 busy 复位 + 快照对齐），再让 abort 触发收尾。 */
    onStreamEvent({event:"error",data:{error:"连接无响应（"+(STREAM_STALL_MS/1000)+"s 无数据），已自动中断"}});
  },STREAM_WATCHDOG_TICK_MS);
}
function stopStreamWatchdog(){
  if(stream.watchdog){ clearInterval(stream.watchdog); stream.watchdog=0; }
}

function doSend(){
  var t=$inp.value.trim();
  if(((!t||state.composer.busy)&&selectedAttachments.length===0)||stream.active)return;
  var body={text:t,attachments:selectedAttachments.map(function(a){return a.id;})};
  $inp.value="";
  selectedAttachments=[];
  renderAttBar();
  autoGrow();

  /* 乐观用户气泡：立刻上屏（真实消息由 user_message 事件换键） */
  var localId="local-user-"+Date.now();
  var um={id:localId,role:"user",mine:true,author:"我",avatar:"我",
          timeText:fmtTime(Date.now()),time:Date.now(),
          blocks:[{t:"text",text:t}]};
  state.messages.push(um);
  ensureNode(um); renderMessage(um);
  scrollBottom(true);
  stream.localUserId=localId;
  stream.localUserMsg=um;

  var ctrl=new AbortController();
  stream.ctrl=ctrl;
  startStreamWatchdog();   /* 假死保护：45s 无字节自动中断并解锁发送 */
  fetch("api/send",{
    method:"POST",
    headers:{"Content-Type":"application/json"},
    body:JSON.stringify(body),
    signal:ctrl.signal,
    cache:"no-store"
  })
    .then(function(r){
      if(!r.ok)throw new Error("HTTP "+r.status);
      return readSse(r,onStreamEvent);
    })
    .then(function(){ onStreamEnd(); })
    .catch(function(e){
      if(e&&e.name==="AbortError"){ onStreamEnd(); return; }  /* 用户点停止：不算错误 */
      onStreamEvent({event:"error",data:{error:(e&&e.message)||"发送失败"}});
      onStreamEnd();
    });
}

function doStop(){
  if(stream.ctrl){ try{ stream.ctrl.abort(); }catch(e){} }
  stream.ctrl=null;
  stopStreamWatchdog();
  post("stop",{});
  stream.active=false;
  state.composer.busy=false;
  renderChrome();
  setTimeout(fetchSnapshot,300);
}
function doNew(){
  /* 新对话会整体换掉消息列表：先掐断可能还在跑的发送流，否则流的 delta
     会继续往旧列表节点上写字，与新快照互相打架。 */
  if(stream.ctrl){ try{ stream.ctrl.abort(); }catch(e){} stream.ctrl=null; }
  stopStreamWatchdog();
  stream.active=false; stream.segId=null; stream.text=""; stream.reason=""; stream.node=null;
  stream.localAid=null; stream.localUserId=null;
  post("new",{});
  setTimeout(fetchSnapshot,250);
}

function autoGrow(){
  $inp.style.height="auto";
  $inp.style.height=Math.min($inp.scrollHeight,168)+"px";
}

/* ── 5.1 开关写回（对等客户端核心）──────────────────────────────────── */

function toggleMode(key,on){
  return post("toggle",{key:key,on:on});
}

/* ── 5.2 模型 / 人格弹层 ────────────────────────────────────────────── */

function openSheet(id){
  var o=document.getElementById(id);
  if(o)o.hidden=false;
}
function closeSheet(id){
  var o=document.getElementById(id);
  if(o)o.hidden=true;
}
document.querySelectorAll(".overlay").forEach(function(o){
  o.addEventListener("click",function(e){
    if(e.target===o)o.hidden=true;
  });
});
document.querySelectorAll("[data-close]").forEach(function(b){
  b.addEventListener("click",function(){closeSheet(b.getAttribute("data-close"));});
});

function loadModels(){
  fetch("api/models",{cache:"no-store"})
    .then(function(r){return r.json();})
    .then(function(j){
      if(!j||!j.ok)return;
      var box=document.getElementById("modelList");
      box.innerHTML="";
      (j.groups||[]).forEach(function(g){
        var gh=el("div","group-h",g.name);
        box.appendChild(gh);
        (g.models||[]).forEach(function(m){
          var row=el("div","model-row");
          var mark=el("span","mark",m.mark||"M");
          var info=el("div","mi");
          info.appendChild(el("div","mi-n",m.name));
          info.appendChild(el("div","mi-d",m.desc||""));
          row.appendChild(mark); row.appendChild(info);
          if(m.id===j.current)row.classList.add("cur");
          row.addEventListener("click",function(){
            post("models/select",{id:m.id}).then(function(r){
              if(r&&r.ok){ closeSheet("modelSheet"); refreshBar(); }
            });
          });
          box.appendChild(row);
        });
      });
    });
}

function loadPersonas(){
  fetch("api/personas",{cache:"no-store"})
    .then(function(r){return r.json();})
    .then(function(j){
      if(!j||!j.ok)return;
      var box=document.getElementById("personaList");
      box.innerHTML="";
      (j.personas||[]).forEach(function(p){
        var row=el("div","model-row");
        var mark=el("span","mark",p.avatarEmoji||"🤖");
        var info=el("div","mi");
        info.appendChild(el("div","mi-n",p.name));
        if(p.description)info.appendChild(el("div","mi-d",p.description||""));
        row.appendChild(mark); row.appendChild(info);
        if(p.active)row.classList.add("cur");
        row.addEventListener("click",function(){
          post("personas/select",{id:p.id}).then(function(r){
            if(r&&r.ok){ closeSheet("personaSheet"); refreshBar(); }
          });
        });
        box.appendChild(row);
      });
    });
}

function refreshBar(){
  fetch("api/snapshot",{cache:"no-store"})
    .then(function(r){return r.json();})
    .then(function(j){ if(j&&j.ok){ state.bar=j.bar||state.bar; renderChrome(); } })
    .catch(function(){});
}

/* ── 5.3 上传（base64）──────────────────────────────────────────────── */

function renderAttBar(){
  if(selectedAttachments.length===0){
    $attbar.hidden=true;
    $attbar.innerHTML="";
    return;
  }
  $attbar.hidden=false;
  $attbar.innerHTML="";
  selectedAttachments.forEach(function(a){
    var chip=el("span","att-chip",a.name);
    var x=el("button","att-x","✕");
    x.type="button";
    x.addEventListener("click",function(){
      post("attachments/remove",{id:a.id});
      selectedAttachments=selectedAttachments.filter(function(t){return t.id!==a.id;});
      renderAttBar();
    });
    chip.appendChild(x);
    $attbar.appendChild(chip);
  });
}

function handleFiles(files){
  Array.prototype.forEach.call(files,function(file){
    var reader=new FileReader();
    reader.onload=function(){
      var dataUrl=reader.result||"";
      var b64=dataUrl.split(",")[1]||"";
      post("upload",{name:file.name,mime:file.type||"",dataBase64:b64}).then(function(r){
        if(r&&r.ok){
          selectedAttachments.push({id:r.id,name:r.name,type:r.type,size:r.size});
          renderAttBar();
        }else{
          $cnt.textContent="上传失败";
        }
      });
    };
    reader.readAsDataURL(file);
  });
}

/* ── 5.4 工具面板 / 语音 / 设置 / 功能入口 ──────────────────────────── */

var TOOLS=[
  {action:"ui_open_import_tool",label:"导入工具"},
  {action:"ui_open_ai_search",label:"AI 搜索"},
  {action:"ui_open_doc_generate",label:"文档生成"},
  {action:"ui_open_upload",label:"上传文件"},
  {action:"ui_open_sheet_voice",label:"语音面板"}
];
var VOICE_ITEMS=[
  {action:"ui_open_tts",label:"文本转语音 (TTS)"},
  {action:"ui_open_stt",label:"语音转文本 (STT)"},
  {action:"ui_open_voice",label:"语音服务设置"},
  {action:"ui_open_voice_service",label:"语音服务(高级)"}
];
var SETTINGS_ITEMS=[
  {action:"ui_open_model_config",label:"模型配置"},
  {action:"ui_open_appearance",label:"外观"},
  {action:"ui_open_soul",label:"人格/灵魂"},
  {action:"ui_open_memory",label:"记忆管理"},
  {action:"ui_open_permission",label:"权限中心"},
  {action:"ui_open_cms",label:"能力模块(CMS)"},
  {action:"ui_open_about",label:"关于"},
  {action:"__clear_chat__",label:"清空对话"}
];
var FEATURES=[
  {action:"ui_open_onlyoffice",label:"文档查看器"},
  {action:"ui_open_knowledge",label:"知识库"},
  {action:"ui_open_terminal",label:"终端"},
  {action:"ui_open_editor",label:"代码编辑器"},
  {action:"ui_open_toolbox",label:"工具箱"},
  {action:"ui_open_kaleidobox",label:"KaleidoBox"},
  {action:"ui_open_build",label:"构建台"},
  {action:"ui_open_plugins",label:"插件管理"},
  {action:"ui_open_skills",label:"技能管理"},
  {action:"ui_open_aci",label:"ACI 管理中心"},
  {action:"ui_open_system_status",label:"系统状态"},
  {action:"ui_open_component_gallery",label:"组件画廊"},
  {action:"ui_open_cluster",label:"多角色集群"},
  {action:"ui_open_browser",label:"浏览器"},
  {action:"ui_open_file_manager",label:"文件管理器"},
  {action:"ui_open_schedule",label:"调度"},
  {action:"ui_open_workflow",label:"工作流"},
  {action:"ui_open_bots",label:"Bots"},
  {action:"ui_open_mcp",label:"MCP"},
  {action:"ui_open_webui",label:"Web UI"}
];

function fillList(boxId,items){
  var box=document.getElementById(boxId);
  box.innerHTML="";
  items.forEach(function(it){
    var row=el("div","feat-row");
    row.textContent=it.label;
    row.addEventListener("click",function(){
      if(it.action==="__clear_chat__"){
        if(stream.ctrl){ try{ stream.ctrl.abort(); }catch(e){} stream.ctrl=null; }
        stopStreamWatchdog();
        stream.active=false; stream.segId=null; stream.text=""; stream.reason=""; stream.node=null;
        stream.localAid=null; stream.localUserId=null;
        post("clear").then(function(r){
          if(r&&r.ok){ closeSheet("settingsSheet"); setTimeout(fetchSnapshot,250); }
        });
        return;
      }
      post("ui_action",{action:it.action}).then(function(r){
        if(r&&r.ok){ closeSheet("toolSheet"); closeSheet("voiceSheet"); closeSheet("settingsSheet"); closeSheet("featuresSheet"); }
      });
    });
    box.appendChild(row);
  });
}

/* ── 6. 启动 ─────────────────────────────────────────────────────────── */

$send.addEventListener("click",function(){ state.composer.busy?doStop():doSend(); });
document.getElementById("btn-new").addEventListener("click",doNew);

/* 权限模式开关：对等客户端 —— 点击直接写回原生同一真源 */
$modes.addEventListener("click",function(e){
  var b=e.target;
  while(b&&b!==$modes){
    if(b.className&&String(b.className).indexOf("mode")>=0){
      var mode=b.getAttribute("data-mode");
      if(mode){
        var nowOn=!!state.composer[mode];
        toggleMode(mode,!nowOn);
      }
      return;
    }
    b=b.parentNode;
  }
});

/* CMS / 特权策略：点击循环切换 allow -> ask -> deny */
function cyclePolicy(key,cur){
  var order=["allow","ask","deny"];
  var idx=order.indexOf(cur);
  var next=order[(idx+1)%order.length];
  return post("policy",{key:key,value:next});
}
$cms.addEventListener("click",function(){
  var cur=policyKey(state.composer.cms);
  cyclePolicy("cms",cur).then(function(r){ if(r&&r.ok){ state.composer.cms=nextPolicy("cms",cur); renderChrome(); } });
});
$priv.addEventListener("click",function(){
  var cur=policyKey(state.composer.priv);
  cyclePolicy("priv",cur).then(function(r){ if(r&&r.ok){ state.composer.priv=nextPolicy("priv",cur); renderChrome(); } });
});
function nextPolicy(key,cur){
  var order=["allow","ask","deny"];
  var idx=order.indexOf(cur);
  return order[(idx+1)%order.length];
}

/* 工作区：点击弹 prompt 输入路径 */
$ws.addEventListener("click",function(){
  var cur=String(state.composer.workspace||"");
  var path=window.prompt("输入工作区路径（留空恢复默认）",cur);
  if(path===null)return;
  post("workspace",{path:path}).then(function(r){
    if(r&&r.ok){ state.composer.workspace=path; renderChrome(); }
  });
});

/* 模型 / 人格点击 */
$model.addEventListener("click",function(){ loadModels(); openSheet("modelSheet"); });
$persona.addEventListener("click",function(){ loadPersonas(); openSheet("personaSheet"); });

/* 工具面板 / 上传 / 语音 / 设置 */
document.getElementById("btn-tools").addEventListener("click",function(){
  fillList("toolList",TOOLS);
  openSheet("toolSheet");
});
document.getElementById("btn-attach").addEventListener("click",function(){ $fileInput.click(); });
$fileInput.addEventListener("change",function(){ handleFiles($fileInput.files); $fileInput.value=""; });
document.getElementById("btn-add-model").addEventListener("click",function(){
  post("ui_action",{action:"ui_open_model_config"});
  closeSheet("modelSheet");
});
document.getElementById("btn-fetch-models").addEventListener("click",function(){
  // 服务端暂无独立拉取接口，打开原生模型配置屏触发拉取
  post("ui_action",{action:"ui_open_model_config"});
  closeSheet("modelSheet");
});
document.getElementById("btn-add-persona").addEventListener("click",function(){
  post("ui_action",{action:"ui_open_soul"});
  closeSheet("personaSheet");
});

/* 设置入口 */
document.getElementById("btn-settings").addEventListener("click",function(){
  fillList("settingsList",SETTINGS_ITEMS);
  openSheet("settingsSheet");
});

$inp.addEventListener("input",autoGrow);
$inp.addEventListener("keydown",function(e){
  if(e.key==="Enter"&&!e.shiftKey&&!e.isComposing){ e.preventDefault(); doSend(); }
});
$inp.addEventListener("paste",function(){setTimeout(autoGrow,0);});

$stream.addEventListener("click",function(e){
  var b=e.target;
  while(b&&b!==$stream){
    var cls=String(b.className||"");
    /* 代码块复制 */
    if(cls.indexOf("code-c")>=0){
      var code=b.getAttribute("data-code")||"";
      copyText(code,b);
      return;
    }
    /* 操作栏：复制/追问/分享/删除/重试（原生 BubbleActionButton 五按钮） */
    if(cls.indexOf("ab")>=0&&b.getAttribute("data-act")){
      var msgEl=b;
      while(msgEl&&msgEl!==$stream&&String(msgEl.className||"").indexOf("msg")<0)msgEl=msgEl.parentNode;
      var mid=msgEl&&msgEl.getAttribute?msgEl.getAttribute("data-mid"):null;
      var mm=null;
      for(var i=0;i<state.messages.length;i++){ if(state.messages[i].id===mid){mm=state.messages[i];break;} }
      if(mm){
        var nd=nodes[mid];
        if(nd)onAct(nd,mm,b.getAttribute("data-act"),b);
      }
      return;
    }
    /* 工具卡展开/折叠 */
    if(cls.indexOf("tool-h")>=0){
      var t=b.parentNode;
      if(t&&t.className&&String(t.className).indexOf("tool")>=0)t.classList.toggle("open");
      return;
    }
    b=b.parentNode;
  }
});

fit();
window.addEventListener("resize",fit);
window.addEventListener("orientationchange",function(){setTimeout(fit,120);});

autoGrow();
fetchSnapshot();
startStream();
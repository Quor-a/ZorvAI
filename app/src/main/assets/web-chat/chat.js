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
 * 正文的**行内**格式化 —— 本页唯一一处碰文本的地方。
 * 块级解析（标题/列表/表格/引用）由宿主 parseBlocks 负责，这里不做。
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
  $send.textContent=state.composer.busy?"■":"➤";
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
      var d=el("p"); d.innerHTML=inline(b.text||""); return d;
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

function renderBlocks(nd,m){
  var blocks=m.blocks||[];
  var sig=blocks.length+"|"+blocks.map(function(b){
    return (b.t||"")+":"+String(b.text||b.code||b.source||"").length;
  }).join(",");
  if(sig===nd.blockSig)return;
  nd.blockSig=sig;

  if(!nd.mdBox){
    nd.mdBox=el("div","md");
    if(nd.uiBox&&nd.uiBox.parentNode===nd.bub)nd.bub.insertBefore(nd.mdBox,nd.uiBox);
    else nd.bub.appendChild(nd.mdBox);
  }
  nd.mdBox.innerHTML="";
  var any=false;
  blocks.forEach(function(b){
    var n=blockNode(b);
    if(n){ nd.mdBox.appendChild(n); any=true; }
  });
  if(!any && m.reasoning){ /* 只有思考、没有正文 */ }
}

function renderThink(nd,m){
  var r=m.reasoning||"";
  if(!r){ if(nd.thinkRoot){nd.thinkRoot.remove();nd.thinkRoot=null;nd.thinkSrc="";} return; }
  if(!nd.thinkRoot){
    var th=el("div","think open");
    var hh=el("div","think-h","思考过程");
    var tb=el("div","think-b");
    hh.addEventListener("click",function(){th.classList.toggle("open");});
    th.appendChild(hh); th.appendChild(tb);
    nd.bub.insertBefore(th,nd.bub.firstChild);
    nd.thinkRoot=th; nd.thinkBody=tb; nd.thinkSrc="";
  }
  if(nd.thinkSrc!==r){ nd.thinkBody.textContent=r; nd.thinkSrc=r; }
}

function renderTools(nd,m){
  var calls=m.tools||[];
  var sig=calls.map(function(t){
    return (t.name||"")+"|"+(t.result===null||t.result===undefined?"r":"d")+
           "|"+String(t.result===null||t.result===undefined?"":t.result).length+
           "|"+(t.durationMs||0);
  }).join("~");
  if(sig===nd.toolSig)return;
  nd.toolSig=sig;

  if(nd.toolsRoot){ nd.toolsRoot.remove(); nd.toolsRoot=null; }
  if(!calls.length)return;
  nd.toolsRoot=el("div","tools");
  nd.bub.appendChild(nd.toolsRoot);
  calls.forEach(function(tc){
    var done=!(tc.result===null||tc.result===undefined);
    var t=el("div","tool"+(done?" ok":""));
    var h=el("div","tool-h");
    h.appendChild(el("span","tool-n",tc.name||"tool"));
    h.appendChild(el("span","tool-s",done?"完成":"运行中"));
    h.appendChild(el("span","tool-d",tc.durationMs?(tc.durationMs/1000).toFixed(1)+"s":""));
    var b=el("div","tool-b");
    if(tc.arguments){
      b.appendChild(el("div","lb","参数"));
      b.appendChild(el("pre",null,String(tc.arguments)));
    }
    if(done&&String(tc.result)!==""){
      b.appendChild(el("div","lb","结果"));
      b.appendChild(el("pre",null,String(tc.result)));
    }
    h.addEventListener("click",function(){t.classList.toggle("open");});
    t.appendChild(h); t.appendChild(b);
    nd.toolsRoot.appendChild(t);
  });
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
  if(box.children.length)nd.bub.appendChild(box);
  nd.attsDone=true;
}

function uiAnchor(nd){
  if(nd.uiBox)return nd.uiBox;
  var box=el("div","ui-list");
  nd.bub.appendChild(box);
  nd.uiBox=box;
  return box;
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

function renderUi(nd,m){
  var list=m.ui||[];
  var sig=list.map(function(u){
    return (u.component||"")+":"+String(u.dsl||"").length;
  }).join("~");
  if(sig===nd.uiSig)return;
  nd.uiSig=sig;
  if(nd.uiBox){ nd.uiBox.remove(); nd.uiBox=null; }
  if(!list.length)return;
  var box=uiAnchor(nd);
  list.forEach(function(u){ box.appendChild(zxFrame(u.component,u.dsl)); });
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

function renderMessage(m){
  var nd=nodes[m.id];
  if(!nd)return;
  nd.who.textContent=m.senderName||(m.mine?"我":(state.bar.title||"ZorvAI"));
  renderThink(nd,m);
  renderBlocks(nd,m);
  renderTools(nd,m);
  renderUi(nd,m);
  renderAttachments(nd,m);
  var isLast=state.messages[state.messages.length-1]===m;
  if(nd.mdBox){
    if(state.composer.busy&&isLast)nd.mdBox.classList.add("streaming");
    else nd.mdBox.classList.remove("streaming");
  }
}

function ensureNode(m){
  var nd=nodes[m.id];
  if(nd)return nd;
  var root=el("div","msg "+(m.mine?"mine":"ai"));
  var who=el("div","who");
  var bub=el("div","bub");
  root.appendChild(who); root.appendChild(bub);
  nd={root:root,who:who,bub:bub,mdBox:null,blockSig:null,thinkRoot:null,thinkBody:null,
      thinkSrc:null,toolsRoot:null,toolSig:null,uiBox:null,uiSig:null,attsDone:false};
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
  state.messages=(snap.messages||[]).filter(function(m){
    return m&&m.id&&(m.role==="user"||m.role==="assistant");
  });

  var ids=state.messages.map(function(m){return m.id;});
  prune(ids);
  state.messages.forEach(function(m){ ensureNode(m); renderMessage(m); });
  syncOrder(ids);
  renderChrome();
  guardCollapse();
  logHealth();
  if(stick||(!wasBusy&&state.composer.busy))scrollBottom(true);
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

function doSend(){
  var t=$inp.value.trim();
  if((!t||state.composer.busy)&&selectedAttachments.length===0)return;
  var body={text:t,attachments:selectedAttachments.map(function(a){return a.id;})};
  $inp.value="";
  selectedAttachments=[];
  renderAttBar();
  autoGrow();
  post("send",body);
}
function doStop(){ post("stop",{}); }
function doNew(){ post("new",{}); }

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
  {action:"ui_open_mcp",label:"MCP"}
];

function fillList(boxId,items){
  var box=document.getElementById(boxId);
  box.innerHTML="";
  items.forEach(function(it){
    var row=el("div","feat-row");
    row.textContent=it.label;
    row.addEventListener("click",function(){
      if(it.action==="__clear_chat__"){
        post("clear").then(function(r){
          if(r&&r.ok){ closeSheet("settingsSheet"); }
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
    if(b.className&&String(b.className).indexOf("code-c")>=0){
      var code=b.getAttribute("data-code")||"";
      if(navigator.clipboard&&navigator.clipboard.writeText){
        navigator.clipboard.writeText(code).then(function(){
          b.textContent="已复制";
          setTimeout(function(){b.textContent="复制";},1200);
        });
      }
      return;
    }
    b=b.parentNode;
  }
});

fit();
window.addEventListener("resize",fit);
window.addEventListener("orientationchange",function(){setTimeout(fit,120);});

autoGrow();
fetch("api/snapshot",{cache:"no-store"})
  .then(function(r){return r.json();})
  .then(function(j){ apply(j); })
  .catch(function(){})
  .then(function(){ startStream(); });
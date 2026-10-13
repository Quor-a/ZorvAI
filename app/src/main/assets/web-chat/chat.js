/* ═══════════════════════════════════════════════════════════════════════
   Web UI 运行时 —— **ZorvAI 对话框投影的接收端**
   ═══════════════════════════════════════════════════════════════════════

   职责边界（用户 2026-10-13 拍板，别再混）：

     · 本文件 = 「Web UI」= ZorvAI 对话框的投影接收端。
       它**不设计界面**、**不解析对话**、**不拥有任何能力模块**。
       消息怎么切成块、切出哪些块，全部由宿主 `parseBlocks` 决定
       （见 ui/chat/WebUiProjection.kt）；这里只把投过来的块画出来。

     · zxwidget（互动UI界面）= 独立模块（assets/zx/），宿主是原生对话框。
       本文件**不加载它、不编译它、不为它发任何请求**。
       投影里它只表现为 {t:"ui", component} 的「这里有一块互动界面」标记。

   🔴 所以本文件里出现下列任何一样，都说明投影又退化成了「第二个实现」，必须删掉：
        · 围栏嗅探（``` 的正则）
        · 块级 Markdown 解析（标题/列表/表格/引用）
        · 自造的空状态、标语、欢迎语
        · 任何 zx 相关的加载或编译

   数据来源：宿主内嵌的投影服务（同源，fetch 即可）。宿主不往页面里灌 JS，
   HTML 也不内联任何大块数据——少一层转义就少一类「漏一个字符整页白」的故障。
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
 * 正文的**行内**格式化 —— 本页唯一一处碰文本的地方，理由必须讲清楚。
 *
 * 宿主投过来的块模型只到**块级**（text / heading / quote / code / table / rule …），
 * 行内的 **加粗** / `代码` / [链接]() 不在块模型里（原生侧由 Compose RichText 现渲染）。
 * 浏览器这边没有对应物，所以只能补一层最小的行内处理。
 *
 * 🔴 边界：只做行内。这里**不得**出现标题、列表、表格、围栏这类块级解析——
 *    那些是宿主 parseBlocks 的职责，抢过来做就是两套实现。
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
    $ws=document.getElementById("ws");

var state={
  bar:{title:"ZorvAI",model:"",persona:""},
  composer:{
    busy:false,deepThink:false,autoSaveMemory:false,subAgent:false,autoRead:false,
    vision:false,workspace:"",cms:"ask",priv:"ask"
  },
  dark:false,lan:"",port:0,messages:[]
};

/* 三态策略的显示词 —— 与原生 qk_03235 / qk_03236 / qk_03166 逐字一致。
   契约里 cms/priv 投的是 `allow|deny|ask` 这种**规范化名**（投影只投状态、不投文案），
   所以中文文案在这里落地；这里改字只影响本页，不会反向污染原生。
   缺值一律按 ask 处理：宁可显示「询问」（最保守的读法），
   也不要把未知状态画成「允许」——那会让用户以为 AI 可以放开动手。 */
var POLICY_WORD={allow:"允许",deny:"禁止",ask:"询问"};

/** 规范化策略值 → 三态之一的 key；未知值退化为 "ask"。 */
function policyKey(v){
  var k=String(v===undefined||v===null?"":v);
  return POLICY_WORD[k]?k:"ask";
}
var nodes={};          /* id -> 渲染缓存 */
var emptyBox=null;
var pollTimer=null;

/* 原生专属块的显示名：浏览器里没有对应渲染器，如实说明它是什么 */
var NATIVE_LABEL={
  aip:"AIP 排版（原生渲染）",
  mermaid:"Mermaid 图（原生渲染）",
  dynamic_ui:"动态 UI（原生渲染）",
  self_card:"自研卡片（原生渲染）",
  card:"组件卡片（原生渲染）",
  miniapp:"MiniApp（原生渲染）"
};

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
  emptyBox.textContent="这个会话还没有消息。投影与主对话框共用同一个会话。";
  $stream.appendChild(emptyBox);
}

function renderChrome(){
  $title.textContent=state.bar.title||"ZorvAI";
  $model.textContent=state.bar.model||"";
  $persona.textContent=state.bar.persona||"";
  $cnt.textContent=state.messages.length+" 条消息";
  $dot.className="dot"+(state.composer.busy?" on":"");
  document.documentElement.className=state.dark?"dark":"";
  $send.textContent=state.composer.busy?"■":"➤";
  $send.className=state.composer.busy?"stop":"";
  $send.setAttribute("aria-label",state.composer.busy?"停止":"发送");
  // 底栏链接：从**别的设备**打开这个投影的入口（127.0.0.1 在别的设备上是打不开的）
  $link.textContent=state.lan||(state.port?("127.0.0.1:"+state.port):"");
  // 权限模式开关状态（五个布尔 chip）
  var modes=$modes.querySelectorAll(".mode[data-mode]");
  for(var i=0;i<modes.length;i++){
    var k=modes[i].getAttribute("data-mode");
    if(state.composer[k])modes[i].classList.add("on"); else modes[i].classList.remove("on");
  }
  // 三态策略 chip（CMS / 特权）。文案格式照抄原生摘要行：
  // `CMS:允许`（前缀在原生里是硬编码的）与 `特权:允许`（qk_03224 = "特权:%1$s"）。
  var ck=policyKey(state.composer.cms), pk=policyKey(state.composer.priv);
  $cms.textContent="CMS:"+POLICY_WORD[ck];
  $cms.className="mode tri "+ck;
  $priv.textContent="特权:"+POLICY_WORD[pk];
  $priv.className="mode tri "+pk;
  // 工作区（原生只显示 basename，这里保持一致）
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
    /* 互动UI界面：只标明「这里有一块」。本页不编译、不渲染它——
       那是独立模块，宿主是原生对话框。 */
    case "ui":{
      var u=el("div","ui");
      var ut=el("div","ut","互动 UI 界面");
      var un=el("div",null,"组件："+(b.component||"?")+"（在原生对话框里操作）");
      u.appendChild(ut); u.appendChild(un); return u;
    }
    /* 原生专属渲染的块：如实说明 + 折叠展示源，不假装能画 */
    case "native":{
      var n=el("div","native");
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
  if(!any && m.reasoning){ /* 只有思考、没有正文：留空容器避免占位跳变 */ }
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
    /* 只有 http(s) 的才敢当图片直接显示：附件在宿主私有目录里是 file://，
       而本页由 http:// 提供，浏览器一律拦 file:// 子资源。
       硬塞 <img> 只会得到一个碎图标——不如老实显示成附件名。 */
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

/**
 * 互动UI界面标记的插入点。
 * 位置与原生一致：思考 → 正文 → 工具 → 互动界面。
 * 这里只维护一个**锚点容器**，不往里放任何 zx 运行时。
 */
function uiAnchor(nd){
  if(nd.uiBox)return nd.uiBox;
  var box=el("div","ui-list");
  nd.bub.appendChild(box);
  nd.uiBox=box;
  return box;
}

function renderUi(nd,m){
  var list=m.ui||[];
  var sig=list.map(function(u){return u.component||"";}).join("~");
  if(sig===nd.uiSig)return;
  nd.uiSig=sig;
  if(nd.uiBox){ nd.uiBox.remove(); nd.uiBox=null; }
  if(!list.length)return;
  var box=uiAnchor(nd);
  list.forEach(function(u){
    box.appendChild(blockNode({t:"ui",component:u.component}));
  });
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

/* 只在顺序真的不对时才动 DOM —— 每次轮询都重排会让滚动抖、动画重放 */
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

/* ── 4. 与宿主同步（轮询投影快照）────────────────────────────────────── */

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
  if(stick||(!wasBusy&&state.composer.busy))scrollBottom(true);
}

function poll(){
  if(document.hidden){ schedule(1200); return; }
  fetch("api/snapshot",{cache:"no-store"})
    .then(function(r){return r.json();})
    .then(function(j){
      apply(j);
      schedule(state.composer.busy?180:1200);
    })
    .catch(function(){
      $cnt.textContent="连接不上投影服务，重试中…";
      schedule(1500);
    });
}

function schedule(ms){
  if(pollTimer)clearTimeout(pollTimer);
  pollTimer=setTimeout(poll,ms);
}

/* ── 5. 操作（一律转成对同一个会话的动作）────────────────────────────── */

function post(path,body){
  return fetch("api/"+path,{
    method:"POST",
    headers:{"Content-Type":"application/json"},
    body:JSON.stringify(body||{})
  })
    .then(function(r){return r.json();})
    .then(function(j){ poll(); return j; })
    .catch(function(){ return null; });
}

function doSend(){
  var t=$inp.value.trim();
  if(!t||state.composer.busy)return;
  $inp.value="";
  autoGrow();
  post("send",{text:t});
}
function doStop(){ post("stop",{}); }
function doNew(){ post("new",{}); }

function autoGrow(){
  $inp.style.height="auto";
  $inp.style.height=Math.min($inp.scrollHeight,168)+"px";
}

/* ── 6. 启动 ─────────────────────────────────────────────────────────── */

$send.addEventListener("click",function(){ state.composer.busy?doStop():doSend(); });
document.getElementById("btn-new").addEventListener("click",doNew);

/* 权限模式开关：本页只做**只读投影**——点了不改模型行为，避免「网页上一点，
   把主对话框下一条消息的行为改掉」这种跨介质副作用。原生侧才是配置入口。

   注意这排 chip 显示的是**真实状态**（每次快照现读原生 prefs / 策略），
   不是装饰：CMS 与特权直接决定 AI 能不能调用能力、能不能提权。
   所以点不动的时候必须说清楚**去哪儿改**，否则用户会以为状态是坏的。 */
$modes.addEventListener("click",function(e){
  var b=e.target;
  while(b&&b!==$modes){
    if(b.className&&String(b.className).indexOf("mode")>=0){
      $cnt.textContent="这里是投影，开关请回 ZorvAI 原生对话框设置";
      setTimeout(renderChrome,1800);
      return;
    }
    b=b.parentNode;
  }
});

$inp.addEventListener("input",autoGrow);
$inp.addEventListener("keydown",function(e){
  if(e.key==="Enter"&&!e.shiftKey&&!e.isComposing){ e.preventDefault(); doSend(); }
});
$inp.addEventListener("paste",function(){setTimeout(autoGrow,0);});

/* 代码块复制：委托到 stream，流式重渲染也不会丢监听 */
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

autoGrow();
poll();

/* ═══════════════════════════════════════════════════════════════════════
   Web UI 聊天运行时 —— ZorvAI 对话框的 Web 复制品
   ═══════════════════════════════════════════════════════════════════════

   职责边界（用户 2026-10-13 拍板，别再混）：
     · 本文件 = 「Web UI 对话框」。ZorvAI 对话框有的功能这里全有：
       消息流 / Markdown / 流式输出 / 思考过程 / 工具调用 / 附件 /
       输入框 / 停止 / 新对话。
     · zxwidget（动态交互界面）= 另一个模块（zxwidget.js + zxwidget.css），
       本文件只是「支持它」——把 zx_web_card 工具调用编译成活控件。
     · 数据来自宿主的内嵌 HTTP 服务（同源，fetch 即可）。宿主不再往页面
       里灌 JS、也不再往 HTML 里内联 195 KB 契约——少一层转义就少一类
       「漏一个字符整页白」的故障。
   ═══════════════════════════════════════════════════════════════════════ */

/* ── 1. 基础工具 ─────────────────────────────────────────────────────── */

function esc(s){
  return String(s === undefined || s === null ? "" : s)
    .replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;")
    .replace(/"/g,"&quot;");
}
function el(tag,cls,html){
  var n=document.createElement(tag);
  if(cls)n.className=cls;
  if(html!==undefined&&html!==null)n.textContent=html;
  return n;
}

/* 无依赖 Markdown 渲染（移植自旧运行时，逻辑未改） */
function md(src){
  if(!src)return "";
  var lines=String(src).replace(/\r\n/g,"\n").split("\n"),out=[],i=0;
  function inline(s){
    s=esc(s);
    s=s.replace(/`([^`\n]+)`/g,function(m,c){return "<code>"+c+"</code>";});
    s=s.replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g,'<a href="$2" target="_blank" rel="noopener">$1</a>');
    s=s.replace(/\*\*([^*\n]+)\*\*/g,"<b>$1</b>");
    s=s.replace(/(^|[^*])\*([^*\n]+)\*/g,"$1<i>$2</i>");
    s=s.replace(/~~([^~\n]+)~~/g,"<s>$1</s>");
    return s;
  }
  function hl(code){
    /* 一次扫描完成着色。
       旧实现是「先插注释高亮、再插字符串高亮、再插数字、再插关键字」四轮 replace，
       每一轮都会重扫上一轮插入的标签文本；而关键字表里有 class，
       于是它把自己写的 <span class="tok-n"> 里的 class 又包了一层，
       页面里直接漏出 `<class="tok-n">` 这种破损标签。
       String.replace 不会重扫替换出来的文本，所以合并成一条 alternation 就是正解。 */
    var re=new RegExp(
      "(\\/\\/[^\\n]*|#[^\\n]*)" +
      "|(&quot;[^&]*?&quot;|&#39;[^&]*?&#39;)" +
      "|\\b(\\d+\\.?\\d*)\\b" +
      "|\\b(function|return|if|else|for|while|var|let|const|class|def|import|from|fun|val|object|when|is|in|try|catch|throw|new|async|await|public|private|static|void|int|string|bool|true|false|null)\\b",
      "g");
    return esc(code).replace(re,function(m,c,s,n,k){
      if(c)return '<span class="tok-c">'+c+'</span>';
      if(s)return '<span class="tok-s">'+s+'</span>';
      if(n)return '<span class="tok-n">'+n+'</span>';
      if(k)return '<span class="tok-k">'+k+'</span>';
      return m;
    });
  }
  while(i<lines.length){
    var ln=lines[i];
    var cm=/^```(\w*)\s*$/.exec(ln);
    if(cm){
      var buf=[],j=i+1;
      while(j<lines.length&&!/^```\s*$/.test(lines[j])){buf.push(lines[j]);j++;}
      var code=buf.join("\n");
      out.push('<div class="code"><button type="button" class="code-c" data-code="'+esc(code)+'">复制</button>'+
               '<pre><code>'+hl(code)+'</code></pre></div>');
      i=j+1;continue;
    }
    if(/^\s*\|.*\|\s*$/.test(ln)&&i+1<lines.length&&/^\s*\|[\s:|-]+\|\s*$/.test(lines[i+1])){
      var cells=function(r){return r.trim().replace(/^\||\|$/g,"").split("|").map(function(c){return c.trim();});};
      var head=cells(ln),rows=[],k=i+2;
      while(k<lines.length&&/^\s*\|.*\|\s*$/.test(lines[k])){rows.push(cells(lines[k]));k++;}
      var t="<table><thead><tr>"+head.map(function(h){return "<th>"+inline(h)+"</th>";}).join("")+"</tr></thead><tbody>";
      rows.forEach(function(r){t+="<tr>"+r.map(function(c){return "<td>"+inline(c)+"</td>";}).join("")+"</tr>";});
      out.push(t+"</tbody></table>");i=k;continue;
    }
    var hm=/^(#{1,4})\s+(.*)$/.exec(ln);
    if(hm){var lv=hm[1].length;out.push("<h"+lv+">"+inline(hm[2])+"</h"+lv+">");i++;continue;}
    if(/^>\s?/.test(ln)){
      var qb=[];while(i<lines.length&&/^>\s?/.test(lines[i])){qb.push(lines[i].replace(/^>\s?/,""));i++;}
      out.push("<blockquote>"+qb.map(inline).join("<br>")+"</blockquote>");continue;
    }
    if(/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(ln)){out.push("<hr>");i++;continue;}
    if(/^\s*[-*+]\s+/.test(ln)){
      var lb=[];while(i<lines.length&&/^\s*[-*+]\s+/.test(lines[i])){lb.push(lines[i].replace(/^\s*[-*+]\s+/,""));i++;}
      out.push("<ul>"+lb.map(function(x){return "<li>"+inline(x)+"</li>";}).join("")+"</ul>");continue;
    }
    if(/^\s*\d+[.)]\s+/.test(ln)){
      var ob=[];while(i<lines.length&&/^\s*\d+[.)]\s+/.test(lines[i])){ob.push(lines[i].replace(/^\s*\d+[.)]\s+/,""));i++;}
      out.push("<ol>"+ob.map(function(x){return "<li>"+inline(x)+"</li>";}).join("")+"</ol>");continue;
    }
    if(!ln.trim()){i++;continue;}
    var pb=[];while(i<lines.length&&lines[i].trim()&&!/^\s*([-*+]\s|\d+[.)]\s|#{1,4}\s|>|```|\s*\|)/.test(lines[i])){pb.push(lines[i]);i++;}
    out.push("<p>"+pb.map(inline).join("<br>")+"</p>");
  }
  return out.join("");
}

/* ── 2. 状态 ─────────────────────────────────────────────────────────── */

var $stream=document.getElementById("stream"),
    $inp=document.getElementById("inp"),
    $send=document.getElementById("send"),
    $dot=document.getElementById("dot"),
    $title=document.getElementById("title"),
    $subtitle=document.getElementById("subtitle"),
    $cnt=document.getElementById("cnt"),
    $hint=document.getElementById("hint");

var state={title:"ZorvAI",subtitle:"",busy:false,dark:false,messages:[]};
var nodes={};          /* id -> 渲染缓存 */
var emptyBox=null;
var pollTimer=null;

/* ── 3. 渲染 ─────────────────────────────────────────────────────────── */

function nearBottom(){
  return $stream.scrollHeight-$stream.scrollTop-$stream.clientHeight<140;
}
function scrollBottom(force){
  if(force||nearBottom())$stream.scrollTop=$stream.scrollHeight;
}

function showEmpty(on){
  if(!on){ if(emptyBox){emptyBox.remove();emptyBox=null;} return; }
  if(emptyBox)return;
  emptyBox=el("div",null,null);
  emptyBox.id="empty";
  emptyBox.innerHTML=
    '<div class="badge">Z</div>'+
    '<h2>'+esc(state.title||"ZorvAI")+'</h2>'+
    '<p>这是 ZorvAI 对话框的 Web UI 复制品。发条消息试试——'+
    '消息流、思考过程、工具调用都会在这里实时出现。</p>';
  $stream.appendChild(emptyBox);
}

function renderChrome(){
  $title.textContent=state.title||"ZorvAI";
  $subtitle.textContent=state.subtitle||"";
  $cnt.textContent=state.messages.length+" 条消息";
  $dot.className="dot"+(state.busy?" on":"");
  document.documentElement.className=state.dark?"dark":"";
  $send.textContent=state.busy?"■":"➤";
  $send.className=state.busy?"stop":"";
  $send.setAttribute("aria-label",state.busy?"停止":"发送");
  showEmpty(state.messages.length===0);
}

/* 消息内的元素顺序固定为：思考 → 正文 → 工具/卡片。
   正文可能晚于工具到达（流式），所以插入点必须显式指定，
   不能一路 appendChild —— 否则工具会跑到正文前面去。 */
function mdSlot(nd){
  if(nd.mdBox)return nd.mdBox;
  var box=el("div","md");
  if(nd.placeholder){nd.placeholder.remove();nd.placeholder=null;}
  if(nd.toolsRoot)nd.bub.insertBefore(box,nd.toolsRoot);
  else nd.bub.appendChild(box);
  nd.mdBox=box;
  return box;
}

function renderAttachments(nd,m){
  if(nd.attsDone)return;
  if(!m.attachments||!m.attachments.length){nd.attsDone=true;return;}
  var box=el("div","atts");
  m.attachments.forEach(function(a){
    var url=a.url||a.uri||a.path||"";
    if(!url)return;
    var name=a.name||url.split("/").pop()||"附件";
    /* 只有 http(s) 的才敢当图片直接显示：附件在宿主私有目录里是 file://,
       而本页由 http://127.0.0.1 提供，浏览器一律拦 file:// 子资源。
       硬塞 <img> 只会得到一个碎图标——不如老实显示成附件名。 */
    if(/^https?:/i.test(url)&&/\.(png|jpe?g|gif|webp|bmp)$/i.test(url.split("?")[0])){
      var im=el("img","att-img");
      im.src=url;im.loading="lazy";
      // 即便 http 也可能 404：失败就退回收成附件行，不留碎图
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

function renderThink(nd,m){
  var r=m.reasoning||"";
  if(!r)return;
  if(!nd.thinkRoot){
    var th=el("div","think open");
    var hh=el("div","think-h");
    hh.appendChild(el("span","caret","▶"));
    hh.appendChild(el("span",null,"思考过程"));
    var tb=el("div","think-b");
    hh.addEventListener("click",function(){th.classList.toggle("open");});
    th.appendChild(hh);th.appendChild(tb);
    nd.bub.insertBefore(th,nd.bub.firstChild);
    nd.thinkRoot=th;nd.thinkBody=tb;nd.thinkSrc="";
  }
  if(nd.thinkSrc!==r){nd.thinkBody.textContent=r;nd.thinkSrc=r;}
}

function renderTools(nd,m){
  var calls=m.toolCalls||[];
  var sig=calls.map(function(t){
    return (t.name||"")+"|"+(t.result===null||t.result===undefined?"r":"d")+
           "|"+String(t.result===null||t.result===undefined?"":t.result).length+
           "|"+(t.durationMs||0);
  }).join("~");
  if(sig===nd.toolSig)return;
  nd.toolSig=sig;

  if(!nd.toolsRoot){
    nd.toolsRoot=el("div","tools");
    nd.bub.appendChild(nd.toolsRoot);
  }
  nd.toolsRoot.innerHTML="";

  calls.forEach(function(tc){
    /* zxwidget：zx_web_card 编译成活的动态交互界面，而不是一行 JSON */
    if(tc.name==="zx_web_card"){
      var comp="",dsl={};
      try{var a=JSON.parse(tc.arguments||"{}");comp=a.component||"";dsl=a.dsl||{};}catch(e){}
      if(comp&&typeof compile==="function"){
        var wrap=el("div","zx-wrap");
        var ctx={interact:function(action,payload){
          var msg={action:action||"tap",component:comp,source:"webui"};
          if(payload)for(var k in payload)msg[k]=payload[k];
          msg.summary=comp+" · "+msg.action;
          post("interact",msg);
        }};
        wrap.appendChild(compile(comp,dsl,ctx));
        nd.toolsRoot.appendChild(wrap);
        return;
      }
    }
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
    t.appendChild(h);t.appendChild(b);
    nd.toolsRoot.appendChild(t);
  });

  if(!nd.toolsRoot.children.length){
    nd.toolsRoot.remove();
    nd.toolsRoot=null;
  }
}

function renderMessage(m){
  var nd=nodes[m.id];
  var isLast=state.messages[state.messages.length-1]===m;

  if(m.role==="user"){
    var hu=md(m.content);
    if(nd.mdSrc!==hu){ mdSlot(nd).innerHTML=hu; nd.mdSrc=hu; }
    renderAttachments(nd,m);
    return;
  }

  renderThink(nd,m);

  if(m.content){
    var ha=md(m.content);
    if(nd.mdSrc!==ha){ mdSlot(nd).innerHTML=ha; nd.mdSrc=ha; }
  }else if(!nd.mdBox&&!m.reasoning&&!(m.toolCalls&&m.toolCalls.length)&&!nd.placeholder){
    nd.placeholder=el("div","md","");
    nd.bub.appendChild(nd.placeholder);
  }

  renderTools(nd,m);

  if(nd.mdBox){
    if(state.busy&&isLast)nd.mdBox.classList.add("streaming");
    else nd.mdBox.classList.remove("streaming");
  }
}

function ensureNode(m){
  var nd=nodes[m.id];
  if(nd)return nd;
  var root=el("div","msg "+(m.role==="user"?"user":"ai"));
  var bub=el("div","bub");
  root.appendChild(bub);
  nd={root:root,bub:bub,mdBox:null,mdSrc:null,placeholder:null,attsDone:false,
      thinkRoot:null,thinkBody:null,thinkSrc:null,
      toolsRoot:null,toolSig:null};
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
  if(same){
    for(var j=0;j<want.length;j++){ if(cur[j]!==want[j]){same=false;break;} }
  }
  if(same)return;
  want.forEach(function(n){$stream.appendChild(n);});
}

function prune(ids){
  Object.keys(nodes).forEach(function(id){
    if(ids.indexOf(id)<0){nodes[id].root.remove();delete nodes[id];}
  });
}

/* ── 4. 与宿主同步（轮询快照 + 按键谐和） ─────────────────────────────── */

function apply(snap){
  if(!snap||!snap.ok)return;
  var stick=nearBottom();
  var wasBusy=state.busy;

  state.title=snap.title||"ZorvAI";
  state.subtitle=snap.subtitle||"";
  state.dark=!!snap.dark;
  state.busy=!!snap.busy;
  state.messages=(snap.messages||[]).filter(function(m){
    return m&&m.id&&(m.role==="user"||m.role==="assistant");
  });

  var ids=state.messages.map(function(m){return m.id;});
  prune(ids);
  state.messages.forEach(function(m){ ensureNode(m); renderMessage(m); });
  syncOrder(ids);
  renderChrome();
  if(stick||(!wasBusy&&state.busy))scrollBottom(true);
}

function poll(){
  if(document.hidden){ schedule(1200); return; }
  fetch("api/snapshot",{cache:"no-store"})
    .then(function(r){return r.json();})
    .then(function(j){
      apply(j);
      if($hint)$hint.textContent="Web UI · 由本地服务提供";
      schedule(state.busy?180:1200);
    })
    .catch(function(){
      if($hint)$hint.textContent="连接不上本地服务，重试中…";
      schedule(1500);
    });
}

function schedule(ms){
  if(pollTimer)clearTimeout(pollTimer);
  pollTimer=setTimeout(poll,ms);
}

/* ── 5. 操作 ─────────────────────────────────────────────────────────── */

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
  if(!t||state.busy)return;
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

$send.addEventListener("click",function(){ state.busy?doStop():doSend(); });
document.getElementById("btn-new").addEventListener("click",doNew);

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

/* zxwidget 契约：独立模块自己的数据。拿不到也不影响聊天功能 */
(function loadContracts(){
  fetch("zx_contracts.json",{cache:"force-cache"})
    .then(function(r){return r.json();})
    .then(function(j){ if(typeof zxInstallContracts==="function")zxInstallContracts(j); })
    .catch(function(){});
})();

autoGrow();
poll();

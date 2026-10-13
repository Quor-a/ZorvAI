/* Web UI 桩服务器 —— 用 node 复刻宿主的 HTTP 服务，好在没有真机的情况下验收前端。
 *
 * 为什么要这个：宿主那侧是 Kotlin 手写的 ServerSocket 服务，没法在这里直接跑。
 * 但只要**路由与 JSON 形状一致**，前端行为就是可信的——静态资源路径、CSS 布局、
 * Markdown、思考过程、工具卡片、zxwidget 卡片，全都能验。
 *
 * 用法：node scripts/webchat/stub_server.js [port]
 */
const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const ASSETS = path.join(ROOT, 'app/src/main/assets');
const PORT = parseInt(process.argv[2] || '8791', 10);

/* 路由表：与 Kotlin 侧 QuroWebChatServer 必须逐条对应 */
/* 量溢出用的探针：`/?probe=1` 时注入。把每个超出视口的元素报出来，
   附上它为什么缩不下去（min-width / 兄弟项宽度）。 */
const PROBE = `
<script>
window.addEventListener("load", function(){
  setTimeout(function(){
    var vw = document.documentElement.clientWidth;
    var out = ["VIEWPORT clientWidth=" + vw +
               " scrollWidth=" + document.documentElement.scrollWidth +
               " bodyScrollWidth=" + document.body.scrollWidth];
    var all = document.querySelectorAll("*");
    for (var i = 0; i < all.length; i++) {
      var e = all[i], r = e.getBoundingClientRect();
      if (r.right > vw + 0.5) {
        var cn = (e.className && e.className.baseVal !== undefined)
          ? e.className.baseVal : String(e.className || "");
        var cs = getComputedStyle(e);
        out.push("OVF <" + e.tagName.toLowerCase() + "> id=" + (e.id || "-") +
          " class=\\"" + cn + "\\"" +
          " left=" + Math.round(r.left) + " right=" + Math.round(r.right) +
          " w=" + Math.round(r.width) +
          " minW=" + cs.minWidth + " maxW=" + cs.maxWidth +
          " overflowX=" + cs.overflowX + " tableLayout=" + cs.tableLayout);
      }
    }
    document.title = "PROBE";
    var pre = document.createElement("pre");
    pre.id = "__probe";
    pre.textContent = out.join("\\n");
    document.body.appendChild(pre);
  }, 400);
});
</script>
</body>`;

/* 🔴 真机白屏事故的两条防线，各配一个模拟注入（改样式时必须两种模式都过）。
 *
 * 事故事实（2026-10-13 真机）：`#stream` 高度 **0**、5 条消息被 `overflow-y:auto`
 * 全裁掉，界面只剩「顶栏 + 底栏 + 一大片空白」；而桌面 headless Chrome 完全正常
 * —— 典型的「桌面全绿、真机全白」。
 * 成因：`#app{height:100dvh}`。`dvh` 是**动态视口**单位，而页面在 WebView 拿到尺寸
 * **之前**就开始加载（宿主在 `AndroidView.update` 里直接 `loadUrl`），那一刻动态视口
 * 极小，`dvh` 把 `#app` 钉死在那个值上；`#stream` 是 `flex:1`（=`flex-basis:0%`）
 * 又带 `min-height:0`，剩余空间一被吃光它就正好是 0。
 *
 * 现在两条**互相独立**的防线：
 *   ① CSS `#app{height:100%}`（继承 `html,body{height:100%}`，取自布局视口，无单位协商）
 *   ② JS `fit()` 用 `document.documentElement.clientHeight` 写 px + 监听 resize
 * 外加一条「宁可丑不可白」的可见降级（`html.flow`）。
 *
 * 对应两个夹具模式：
 *   `?novh=1`   掐掉 `html,body` 高度 → **防线①死**，断言防线② 单独能把布局救回来；
 *   `?tinyvh=1` 把 `#app` 强钉 200px → **两条都死**，断言**可见地降级**
 *               （`#diag` 摊出实测数字 + 消息改文档流可见），而不是零信息白屏。 */
const NOVH = `<style>html,body{height:auto !important}</style></body>`;
const TINYVH = `<style>#app{height:200px !important}</style></body>`;

/* 手机宽度取景框。
 *
 * 为什么需要它：本机 headless Chrome 无视 --window-size（实测视口恒为 ~504px，
 * 而截图只截 504 里的一段），于是"右边被切掉"这种假象会骗人——我上一轮就据此
 * 误判过一次溢出。用固定 390px 的 iframe 把应用按真手机宽度排版，
 * 同源可直接测量，截图也所见即所得。 */
/* 🔴 用它验布局可以，用它验**主题**会骗人（2026-10-13 实测）：
 *    同一时刻、同一份快照下，`/` 直连截图是深色正确（#16130F 底 + 浅字 + CMS 绿 chip），
 *    而 `/__frame` 里那个 iframe 截出来是**浅色**——DOM dump 却显示 `<html class="dark">` 已生效。
 *    即无头 Chrome 对 iframe 的合成帧落后于其文档状态，截到的是 JS 跑之前的首绘。
 *    所以：布局/溢出用本夹具，主题一律直接截 `/`（真实 WebView 加载的也正是 `/`，不经 iframe）。 */
const FRAME = (w, h) => `<!doctype html><html><head><meta charset="utf-8">
<title>frame</title><style>
html,body{margin:0;background:#5b6472}
#f{width:${w}px;height:${h}px;border:0;display:block;margin:0 auto;background:#fff}
</style></head><body>
<iframe id="f" src="/"></iframe>
<script>
window.addEventListener("load",function(){
  setTimeout(function(){
    var d=document.getElementById("f").contentDocument;
    var vw=d.documentElement.clientWidth;
    var out=["FRAME innerWidth="+vw+" scrollWidth="+d.documentElement.scrollWidth];
    var all=d.querySelectorAll("*");
    for(var i=0;i<all.length;i++){
      var e=all[i],r=e.getBoundingClientRect();
      if(r.right>vw+0.5){
        var cn=(e.className&&e.className.baseVal!==undefined)?e.className.baseVal:String(e.className||"");
        out.push("OVF <"+e.tagName.toLowerCase()+"> class=\\""+cn+"\\" right="+Math.round(r.right)+" w="+Math.round(r.width));
      }
    }
    var pre=document.createElement("pre");
    pre.id="__probe";pre.style.cssText="position:fixed;left:0;top:0;z-index:9;background:#fff;font:11px monospace;margin:0";
    pre.textContent=out.join("\\n");
    document.body.appendChild(pre);
  },700);
});
</script></body></html>`;

/* 端到端自检：投影是否被**如实呈现**。

   🔴 这一版断言的是「投影」而不是「聊天页」：
        · 顶栏的模型/人格 chip 来自快照（证明顶栏是投出来的，不是写死的）；
        · 正文块按宿主给的顺序出现（heading / quote / code / table / native 都渲染）；
        · 原生专属块必须显示成「原生渲染」说明，**不得**被假装画成别的东西；
        · 互动UI界面只能是「标记」，绝不能是编译出来的可点界面
          —— 本页不拥有 zxwidget，出现可点表格就说明耦合又回来了；
        · 发一条消息能进同一个会话。 */
const E2E = `<!doctype html><html><head><meta charset="utf-8"><title>e2e</title>
<style>html,body{margin:0;background:#111;color:#eee;font:12px monospace}
#f{width:390px;height:844px;border:0;display:block;margin:0 auto;background:#fff}
#log{position:fixed;left:0;top:0;right:0;z-index:9;background:#000;padding:6px;white-space:pre-wrap;max-height:46vh;overflow:auto}
</style></head><body>
<pre id="log">booting…</pre>
<iframe id="f" src="/"></iframe>
<script>
var log=document.getElementById("log"),lines=[];
function say(s){lines.push(s);log.textContent=lines.join("\\n");}
function doc(){return document.getElementById("f").contentDocument;}
function ok(c,label){say((c?"PASS":"FAIL")+": "+label);return c;}
window.addEventListener("load",function(){
  setTimeout(function(){
    var d=doc();
    var msgs=d.querySelectorAll("#stream .msg");
    say("初始消息数 = "+msgs.length);

    // 顶栏 = 投影字段
    say("顶栏模型 chip = \\""+d.getElementById("model").textContent+"\\"");
    say("顶栏人格 chip = \\""+d.getElementById("persona").textContent+"\\"");
    ok(d.getElementById("model").textContent.length>0,"顶栏模型由快照投影而来");

    // 底栏链接 = 对外入口（用户抱怨过没有链接）
    var lk=d.getElementById("link").textContent;
    say("底栏链接 = \\""+lk+"\\"");
    ok(/^http:\\/\\/\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+\\/$/.test(lk)&&lk.indexOf("127.0.0.1")<0,
       "底栏给出的是**局域网**直链（不是 127.0.0.1）");

    // 权限模式条 = 原生 PermissionModeBar 摘要行的投影。
    // 🔴 夹具的 composer 全是**非默认值**，所以这里每一条都在证明
    //    「页面确实消费了快照字段」而不是「恰好撞上页面自己的默认值」。
    var cms=d.getElementById("cms").textContent;
    var priv=d.getElementById("priv").textContent;
    var ws=d.getElementById("ws").textContent;
    say("CMS chip = \\""+cms+"\\" / 特权 chip = \\""+priv+"\\" / "+ws);
    ok(cms==="CMS:允许","CMS 三态由快照投影（allow → 允许）");
    ok(priv==="特权:禁止","特权三态由快照投影（deny → 禁止）");
    ok(d.getElementById("priv").className.indexOf("deny")>=0,
       "禁止态带 deny 配色类（红=AI 会被拒，颜色在这里有语义）");
    ok(ws==="工作区:QuroWorkspace","工作区显示 basename 而非全路径");
    var ar=null,mds=d.querySelectorAll(".mode[data-mode]");
    for(var q=0;q<mds.length;q++){ if(mds[q].getAttribute("data-mode")==="autoRead")ar=mds[q]; }
    ok(!!ar&&ar.className.indexOf("on")>=0,"自动朗读 chip 反映快照的 autoRead=true");

    // 正文块序列
    var ai=msgs[msgs.length-1];
    var hasHeading=!!ai.querySelector(".md h3");
    var hasQuote=!!ai.querySelector(".md blockquote");
    var hasCode=!!ai.querySelector(".code pre code");
    var hasTable=!!ai.querySelector(".md table");
    var hasNative=!!ai.querySelector(".native");
    var hasThink=!!ai.querySelector(".think");
    var hasTools=!!ai.querySelector(".tools .tool");
    ok(hasThink,"思考块渲染");
    ok(hasHeading,"heading 块渲染");
    ok(hasQuote,"quote 块渲染");
    ok(hasCode,"code 块渲染");
    ok(hasTable,"table 块渲染");
    ok(hasTools,"工具块渲染");
    say("native 块文本 = \\""+(ai.querySelector(".native .nt")||{textContent:"(无)"}).textContent+"\\"");
    ok(hasNative,"原生专属块如实标注（不假装能画）");
    ok(!!ai.querySelector(".native pre"),"原生专属块给出源");

    // zxwidget：本页**要真画** —— 画不出正是用户投诉的「根本渲染不了」。
    //   画法是 iframe 引 /zx/shell（承载**同一份** zx_card_runtime.html），
    //   而不是在本页再写一套编译器；所以下面同时断言本页自己没有 zx 布局逻辑。
    var ui=ai.querySelector(".ui-list");
    ok(!!ui,"互动UI界面被标为「一块界面」（.ui-list 容器）");
    var zf0=ai.querySelector("iframe.zxf");
    ok(!!zf0,"这块界面用 iframe 承载（运行时不许塞进本页）");
    var zsrc=zf0?(zf0.getAttribute("src")||""):"";
    say("iframe src = "+zsrc.slice(0,54)+"…");
    ok(zsrc.indexOf("zx/shell#")===0,"iframe 指向 /zx/shell 的 hash 载荷");
    ok(zsrc.indexOf("?")<0,"载荷走 hash 而非查询串（dsl 里的 & # 中文不会被截断）");
    // 这条断言本轮的**方向反转**：以前是「页面不许出现任何 zx」，
    //   现在是「页面自己不许引 zx 资源，zx 只许活在 iframe 里」。
    var zxRefs=[];
    var res=d.querySelectorAll("script[src],link[href]");
    for(var i=0;i<res.length;i++){
      var u=res[i].getAttribute("src")||res[i].getAttribute("href")||"";
      if(u.toLowerCase().indexOf("zx")>=0)zxRefs.push(u);
    }
    ok(zxRefs.length===0,"本页自己不引 zx 脚本/样式（zx 只活在 iframe 里）");

    // 正文**没有**裸露的内联组件 JSON / 围栏残留。
    // 🔴 这里不能直接写字面量三反引号：本段整体是一个 JS **模板字符串**，
    //    三个反引号会当场把模板闭合掉，报错却是 "Unexpected string"（指向性极差）。
    //    与 Kotlin 块注释里写「星号加斜杠」会把注释提前闭合是同一类坑。
    var body=ai.textContent;
    var BT=String.fromCharCode(96);
    ok(body.indexOf(BT+BT+BT)<0,"正文无围栏残留");

    // 发一条消息 → 同一会话
    d.getElementById("inp").value="来自 e2e 的消息";
    d.getElementById("send").click();
    setTimeout(function(){
      var n=doc().querySelectorAll("#stream .msg").length;
      say("发送后消息数 = "+n);
      ok(n>msgs.length,"发送进入同一个会话");
      phaseZx();
    },2500);
  },1200);
});

/* 真机白屏事故的两条防线（成因与模式定义见本文件顶部 NOVH / TINYVH 的注释）。
   这两段是**回归锁**：谁把 #app 的高度改回 viewport 单位、或把 guardCollapse 删了，
   这里立刻红。 */
function metrics(){
  var d=doc();
  var m=d.querySelectorAll("#stream .msg");
  var tallest=0;
  for(var i=0;i<m.length;i++){
    var h=m[i].getBoundingClientRect().height;
    if(h>tallest)tallest=h;
  }
  return {
    msgs:m.length,
    streamH:d.getElementById("stream").clientHeight,
    tallest:tallest,
    diag:d.getElementById("diag")
  };
}
function loadMode(q,cb){
  document.getElementById("f").src="/"+q;
  setTimeout(cb,1800);
}
/* —— 本轮新增：zxwidget 真画 / 高度回灌 / 交互回路 / 流式延迟 ——
   ⚠️ 本段整体在 JS **模板字符串**里：正则的反斜杠要写双份，
      且注释里不许出现反引号（会当场闭合模板，报错位置指向性极差）。 */
function phaseZx(){
  say("—— ① zxwidget 真画（空盒子回归锁）——");
  loadMode("",function(){
    var d=doc();
    var ms=d.querySelectorAll("#stream .msg");
    var zf=null;
    for(var i=0;i<ms.length;i++){
      var f=ms[i].querySelector("iframe.zxf");
      if(f)zf=f;
    }
    ok(!!zf,"重新加载后仍找得到 zxwidget 承载框");
    if(!zf){phaseStream();return;}
    // iframe 载入是异步的，轮询到有文本为止（最多 4s）
    var t0=Date.now();
    var iv=setInterval(function(){
      // 🔴 本段位于 JS **模板字符串**里：正则里的反斜杠必须写双份。
      //    写单份的 \s 会被模板串吃成 s —— 正则静默变成 /s+/g，
      //    于是「612ms」被改成「612m 」（我踩过一次，日志看着还挺像样）。
      var real=0,txt="",h=0;
      try{
        var idoc=zf.contentDocument;
        // 🔴 必须只读渲染容器 #app。
        //    我第一版读的是 idoc.body.textContent，实测 214509 字符 ——
        //    因为 textContent 会把 <script> 的**源码**一起算进来，
        //    而页壳里内联着整份 zx 契约 JSON。结果「数据有没有画出来」
        //    这条断言永远为假，且报错完全指不到真因。
        var ap=idoc.getElementById("body");
        real=ap?ap.querySelectorAll("*").length:0;
        txt=(ap?ap.textContent:"")||"";
        h=Math.ceil((idoc.body&&idoc.body.scrollHeight)||0);
      }catch(e){ say("读 iframe 内部失败: "+e.message); }
      if(!txt.replace(/\\s/g,"").length && Date.now()-t0<4000) return;
      clearInterval(iv);
      say("iframe 内部元素数 = "+real+"，可见文本长度 = "+txt.replace(/\\s/g,"").length+"，内容高 = "+h+"px");
      // 把真画出来的字也摊进日志：断言失败时「画了别的什么」比「没画」好查得多。
      say("界面文字 = "+JSON.stringify(txt.replace(/\\s+/g," ").slice(0,120)));
      // 空盒子的判据正是「壳画出来了、却一个字都没有」——用户截图就是这一态，
      //   所以两条必须一起断言，只断言元素数会被空盒子蒙过去。
      ok(real>3,"zxwidget 真的生成了 DOM（不是空 iframe）");
      ok(txt.replace(/\\s/g,"").length>0,"zxwidget 画出了可见文本（空盒子回归锁）");
      ok(txt.indexOf("612ms")>=0,"dsl 里的数据出现在界面上（数据真穿到了渲染层）");
      // ⚠️ 这里断言的是**内容区**的列名，不是 dsl 里的 title。
      //    dsl.title 属于运行时**自带页眉**（.hd），而 web 壳把页眉隐藏了
      //    （.hd{display:none}，页眉由宿主自己画）—— 所以那个 title 本来就不该出现。
      ok(txt.indexOf("接口")>=0 && txt.indexOf("P99")>=0 && txt.indexOf("错误率")>=0,
         "列名与 dsl 的 columns 对得上");
      var sh=parseInt(zf.style.height||"0",10);
      // chat.js 把回灌值夹在 120~680（太小会裁、太大会留白），所以期望值是夹取后的。
      var exp=Math.min(680,Math.max(120,h));
      say("iframe 声明高度 = "+sh+"px，内容 "+h+"px 夹取后应为 "+exp+"px（CSS 初始 140px）");
      ok(Math.abs(sh-exp)<=2,"高度由 postMessage 按内容回灌（不是卡在 140px 默认值）");
      phaseInteract(zf);
    },120);
  });
}

function phaseInteract(zf){
  say("—— ② 界面里的操作回得到宿主 ——");
  try{ zf.contentWindow.ZorvAIBridge.interact("e2e-click"); }
  catch(e){ say("调桥失败: "+e.message); }
  setTimeout(function(){
    fetch("api/__probe").then(function(r){return r.json();}).then(function(p){
      say("服务端收到交互 "+p.interactions.length+" 条 = "+JSON.stringify(p.interactions));
      ok(p.interactions.length>0,"界面里的操作真的回到了宿主（POST /api/interact）");
      ok(String((p.interactions[0]||{}).component||"").length>0,"交互带上了组件名（宿主才知道唤醒哪个界面）");
      ok(String((p.interactions[0]||{}).payload||"").length>0,"交互带上了载荷（不然宿主不知道点了什么）");
      phaseStream();
    });
  },400);
}

function phaseStream(){
  say("—— ③ 流式：推送 → 上屏 的延迟 ——");
  var d=doc();
  var st=d.getElementById("stream");
  var before=st.textContent.length;
  var t0=Date.now();
  fetch("api/__push",{method:"POST",headers:{"Content-Type":"application/json"},
    body:JSON.stringify({text:"·"})});
  var iv=setInterval(function(){
    var grew=st.textContent.length>before;
    var late=Date.now()-t0>6000;
    if(!grew&&!late)return;
    clearInterval(iv);
    var dt=Date.now()-t0;
    say("流式上屏延迟 = "+dt+"ms"+(grew?"":"（超时未上屏）"));
    // 旧实现是 1.2~2s 的轮询，用户投诉「消息延迟严重、没有流式」。
    //   宿主指纹周期 80ms，所以这里给 800ms 的宽限（含渲染）。
    ok(grew&&dt<800,"推送在 800ms 内上屏（不再是秒级轮询）");
    fetch("api/diag",{method:"POST",headers:{"Content-Type":"application/json"},
      body:JSON.stringify({level:"info",msg:"e2e diag"})}).then(function(){
      return fetch("api/__probe").then(function(r){return r.json();});
    }).then(function(p){
      say("服务端 SSE 订阅者 = "+p.sseClients+"，diag 回传 = "+p.diagReports+" 条");
      ok(p.sseClients>0,"页面确实挂在 SSE 长连接上（不是还在轮询）");
      ok(p.diagReports>0,"页面自检能回传宿主（真机白屏时手上才有东西）");
      phasePeer();
    });
  },25);
}

function phasePeer(){
  say("—— ③b 对等客户端：开关可点、模型/人格可切、上传可用 ——");
  var d=doc();
  // 1. 点击深度思考开关：状态应翻转并写回服务端
  var deepChip=d.querySelector('.mode[data-mode="deepThink"]');
  var wasOn=deepChip.classList.contains("on");
  deepChip.click();
  setTimeout(function(){
    fetch("api/snapshot",{cache:"no-store"}).then(function(r){return r.json();}).then(function(s){
      say("deepThink 点击后 = "+s.composer.deepThink+"（原 "+wasOn+"）");
      ok(s.composer.deepThink!==wasOn,"开关点击写回了服务端（deepThink 翻转）");
      // 2. 打开模型弹层并选择
      d.getElementById("model").click();
      setTimeout(function(){
        var rows=d.querySelectorAll("#modelList .model-row");
        say("模型弹层行数 = "+rows.length);
        ok(rows.length>0,"模型弹层渲染出可选项");
        if(rows.length>0) rows[0].click();
        setTimeout(function(){
          fetch("api/snapshot",{cache:"no-store"}).then(function(r){return r.json();}).then(function(s2){
            say("选择后 model = "+s2.bar.model);
            ok(s2.bar.model.length>0,"模型选择写回服务端");
            // 3. 打开人格弹层
            d.getElementById("persona").click();
            setTimeout(function(){
              var prows=d.querySelectorAll("#personaList .model-row");
              say("人格弹层行数 = "+prows.length);
              ok(prows.length>0,"人格弹层渲染出可选项");
              // 4. ui_action 打开功能面板
              d.getElementById("btn-tools").click();
              setTimeout(function(){
                var tools=d.querySelectorAll("#toolList .feat-row");
                say("工具面板行数 = "+tools.length);
                ok(tools.length>0,"工具面板渲染出入口");
                if(tools.length>0) tools[0].click();
                setTimeout(function(){
                  fetch("api/__probe").then(function(r){return r.json();}).then(function(p){
                    say("ui_action 触发 = "+JSON.stringify(p.uiActions));
                    ok(p.uiActions.length>0,"工具面板点击通过 ui_action 打开了功能");
                    phase2();
                  });
                },200);
              },200);
            },200);
          });
        },200);
      },200);
    });
  },300);
}

function phase2(){
  say("—— 防线①：CSS 高度链死掉，靠 JS 实测视口救回来 ——");
  loadMode("?novh=1",function(){
    var r=metrics();
    say("novh: msgs="+r.msgs+" streamH="+r.streamH+" tallestMsg="+Math.round(r.tallest));
    ok(r.msgs>0,"novh：消息仍在");
    ok(r.streamH>=100,"novh：JS 实测视口把渲染区撑起来了（"+r.streamH+"px）");
    ok(r.tallest>10,"novh：消息实际可见（最高一条 "+Math.round(r.tallest)+"px）");
    ok(r.diag.hidden,"novh：布局正常，自检行不显示");
    phase3();
  });
}
function phase3(){
  say("—— 防线②：两条高度线都死掉，必须**可见地**降级（宁可丑不可白）——");
  loadMode("?tinyvh=1",function(){
    var r=metrics();
    say("tinyvh: msgs="+r.msgs+" streamH="+r.streamH+" tallestMsg="+Math.round(r.tallest));
    say("tinyvh: 自检行 = "+(r.diag.hidden?"（隐藏）":r.diag.textContent));
    ok(r.msgs>0,"tinyvh：消息仍在");
    ok(!r.diag.hidden,"tinyvh：自检行摊出了原因（不再是零信息白屏）");
    ok(/塌陷/.test(r.diag.textContent),"tinyvh：自检行说明了高度塌陷");
    // 🔴 自检行必须报**触发时**的塌陷值，不能报降级后的值 —— 否则记出来的是
    //    「渲染区仅 952px」这种把病因写成结果的自相矛盾数字（我自己先踩了一次）。
    // 🔴 这段整体在 JS 模板字符串里：单反斜杠的 \d 会被吃掉、正则静默变成 (d+)，
    //    所以必须写双反斜杠。同理，本文件模板字符串内部的注释里**不许出现反引号**
    //    —— 哪怕只是行注释，也会当场把模板闭合（我刚踩了一次）。
    var m=/渲染区仅 (\\d+)px/.exec(r.diag.textContent);
    say("tinyvh: 自检行报的塌陷值 = "+(m?m[1]+"px":"（没解析到）")+"，降级后 streamH="+r.streamH);
    ok(m&&Number(m[1])<60,"tinyvh：自检行报的是触发时的塌陷值（"+ (m?m[1]+"px":"?") +"），不是降级后的值");
    ok(r.streamH>Number(m?m[1]:0),"tinyvh：降级后渲染区确实被撑开了");
    ok(r.tallest>10,"tinyvh：降级后消息仍可见（没有静默裁掉）");
    say("—— 结束 ——");
  });
}
</script></body></html>`;

/* 路由表：与 Kotlin 侧 QuroWebUiServer 必须逐条对应。

   🔴 这里**只**有投影自己的三个文件。/zx/shell 是**动态**路由（见下方 GET 分支）：
      它把 zx_card_runtime.html 现拼成页壳，所以不该、也不能进这张静态表。
      两件事要分清：「不要第二份 zx 运行时」与「网页要能画出 zxwidget」并不矛盾 ——
      网页用 iframe 引**同一份**运行时，而不是自己再写一套。
   🔴 webui **要能画** zxwidget（用户原话：zxwidget根本渲染不了）。
      做法是 iframe 引 /zx/shell —— 承载的是**同一份** zx_card_runtime.html，
      网页侧一行渲染逻辑都不写（api_contract_check.js §5 会红）。
      这条曾一度被写成「两者不许混在一起」，方向是反的：
      要分开的是【源码归属】（assets/zx/ 独立模块），不是【能力】（网页当然要画得出）。 */
const STATIC = {
  '/': ['web-chat/index.html', 'text/html; charset=utf-8'],
  '/index.html': ['web-chat/index.html', 'text/html; charset=utf-8'],
  '/style.css': ['web-chat/style.css', 'text/css; charset=utf-8'],
  '/chat.js': ['web-chat/chat.js', 'application/javascript; charset=utf-8'],
};

/* 一份有代表性的**投影快照**：形状与 WebUiProjection 的产出逐字段对应。
   blocks 里每一块都由宿主 parseBlocks 产出 —— 桩数据这里手工写等价形状，
   正是为了验证「接收端只画不猜」。 */
const SNAPSHOT = {
  ok: true,
  bar: { title: 'ZorvAI · Web UI', model: 'deepseek-v4-pro', persona: '默认人格' },
  /* 🔴 这里的值**刻意全部取非默认值**。
     页面自己的 state 默认是 {deepThink:false,autoRead:false,cms:"ask",priv:"ask",workspace:""}；
     如果夹具也用这一套默认值，那么「页面读到了快照」与「页面压根没读、用的就是自己的默认值」
     两种情况的输出**完全一样**，E2E 会全绿地放过一个死投影。
     取反之后，任何一个字段没被消费都会当场 FAIL。 */
  composer: {
    busy: false, deepThink: true, autoSaveMemory: false,
    subAgent: false, autoRead: true, vision: false,
    workspace: '/storage/emulated/0/QuroWorkspace',
    cms: 'allow', priv: 'deny',
  },
  dark: true,
  lan: 'http://192.168.1.7:3080/',
  port: 3080,
  messages: [
    {
      id: 'u1', role: 'user', mine: true, senderName: '我', time: 1,
      blocks: [{ t: 'text', text: '帮我把这周的接口压测结果整理一下，重点看 P99 和错误率。' }],
    },
    {
      id: 'a1', role: 'assistant', mine: false, senderName: 'ZorvAI', time: 2,
      reasoning: '先确认口径：P99 与错误率。拉取本周记录，按接口分组聚合，挑出劣化的几个。',
      blocks: [
        { t: 'text', text: '本周共 **14 个接口**参与压测，整体 P99 从 214ms 升到 **281ms**，劣化集中在 3 个接口。' },
        { t: 'heading', level: 3, text: '结论' },
        { t: 'text', text: '1. `POST /api/order` P99 由 244ms 涨到 **612ms**，是唯一超标的写接口' },
        { t: 'text', text: '2. `GET /api/feed` 错误率 0.02% 升到 **1.8%**，与网关 502 同时出现' },
        { t: 'quote', text: '口径与上周一致，未做剔除。' },
        { t: 'code', lang: 'sql', code: 'select api, percentile_disc(0.99) within group (order by ms) as p99\nfrom perf_week group by api having p99 > 300;' },
        {
          t: 'table',
          header: ['接口', 'P99', '错误率', '判定'],
          rows: [
            ['POST /api/order', '612ms', '0.31%', '劣化'],
            ['GET /api/feed', '388ms', '1.80%', '劣化'],
            ['GET /api/user', '96ms', '0.01%', '正常'],
          ],
        },
        { t: 'rule' },
        { t: 'native', kind: 'aip', source: '{"type":"aip","nodes":[{"t":"deck","title":"压测周报"}]}' },
      ],
      tools: [
        { name: 'perf_query', arguments: '{"range":"7d","group_by":"api"}', result: '{"rows":14,"window":"2026-10-06..2026-10-13"}', durationMs: 820 },
      ],
      ui: [{ component: 'zx_table', dsl: '{"title":"本周压测 · 劣化接口","columns":["接口","P99","错误率"],"rows":[["POST /api/order","612ms","0.31%"],["GET /api/feed","388ms","1.80%"]]}' }],
    },
  ],
};

let snap = JSON.parse(JSON.stringify(SNAPSHOT));
 /* SSE 客户端 + 指纹推送 + 页壳拼装：与宿主 QuroWebUiServer / ZxWebCard.buildWebShell 同语义。
   两边是否同步由 api_contract_check.js 把关（它断言宿主走的是 buildWebShell、且只有一份运行时）。 */
const sseClients = [];
const interactions = [];
const diagReports = [];
const uploaded = [];
const uiActions = [];
let lastRev = '';

/* 指纹推送：只在**真的变了**时推（轮询是「不变也传」）。
   80ms 对齐宿主 QuroWebUiServer.REVISION_INTERVAL_MS。 */
setInterval(function () {
  if (!sseClients.length) { lastRev = ''; return; }
  const rev = JSON.stringify(snap);
  if (rev === lastRev) return;
  lastRev = rev;
  const frame = 'data: ' + rev + '\n\n';
  for (const c of sseClients.slice()) {
    try { c.write(frame); } catch (e) {
      const i = sseClients.indexOf(c);
      if (i >= 0) sseClients.splice(i, 1);
    }
  }
}, 80);

/* 页壳拼装：与 Kotlin 的 ZxWebCard.buildWebShell 逐段对应（只拼一次，和宿主一样缓存）。
   它**不是**第二份 zx 运行时：运行时永远是 zx_card_runtime.html 那一份，
   这里只做「注入契约 + 换桥 + 换载荷来源」。 */
function buildWebShell() {
  const htmlPath = path.join(ASSETS, 'zx_card_runtime.html');
  const ctPath = path.join(ASSETS, 'zx_contracts.json');
  if (!fs.existsSync(htmlPath) || !fs.existsSync(ctPath)) return '';
  let html = fs.readFileSync(htmlPath, 'utf8');
  const ct = fs.readFileSync(ctPath, 'utf8');
  html = html.replace('/*__ZX_CONTRACTS__*/', ct.replace(/<\//g, '<\\/').replace(/<!--/g, '<\\!--'));
  const style = [
    '<style>',
    '  .hd,.foot{display:none !important}',
    '  html,body{height:auto !important;min-height:0 !important;background:transparent !important}',
    '  #app{min-height:0 !important}',
    '</style>',
  ].join('\n');
  const bridge = [
    '<script>',
    '(function(){',
    '  function post(m){ try{ if(window.parent&&window.parent!==window) window.parent.postMessage(m,"*"); }catch(e){} }',
    '  window.ZorvAIBridge={',
    '    height:function(px){ post({__zx:"h",px:px}); },',
    '    interact:function(p){ post({__zx:"i",payload:String(p==null?"":p)}); }',
    '  };',
    '  window.addEventListener("message",function(e){',
    '    var d=e.data||{};',
    '    if(d&&d.__zxc==="dark"&&window.ZorvAI&&window.ZorvAI.setDark) window.ZorvAI.setDark(!!d.dark);',
    '  });',
    '})();',
    '<\/script>',
  ].join('\n');
  const boot = [
    '<script>',
    '(function(){',
    '  function fail(m){ try{ if(window.ZorvAI&&window.ZorvAI.render)',
    '    window.ZorvAI.render({component:"__error__",title:"界面数据无法解析",dsl:{message:String(m)}}); }catch(e){} }',
    '  try{',
    '    var h=String(location.hash||"").replace(/^#/,"");',
    '    if(!h) return;',
    '    var p=JSON.parse(decodeURIComponent(escape(atob(h))));',
    '    p.dark=!!p.dark;',
    // dsl 允许「对象」或「JSON 字符串」两种形状（网页侧投影给的是字符串），
    //   归一语义与 Kotlin 的 ZxWidgetRuntime.WEB_HASH_BOOTSTRAP / buildHtml 严格对齐。
    '    if(typeof p.dsl==="string"){ try{ p.dsl=JSON.parse(p.dsl||"{}"); }catch(e2){ p.dsl={_raw:String(p.dsl)}; } }',
    '    if(!p.dsl||typeof p.dsl!=="object") p.dsl={};',
    '    if(window.ZorvAI&&window.ZorvAI.render) window.ZorvAI.render(p);',
    '    if(window.__zxReportHeight) setTimeout(window.__zxReportHeight,60);',
    '  }catch(e){ fail(e&&e.message?e.message:e); }',
    '})();',
    '<\/script>',
  ].join('\n');
  const heightRep = [
    '<script>',
    '(function(){',
    '  var last=0;',
    '  function rep2(){',
    '    try{',
    '      var h=Math.ceil((document.body&&document.body.scrollHeight)||0);',
    '      if(h>0&&h!==last&&window.ZorvAIBridge&&window.ZorvAIBridge.height){ last=h; window.ZorvAIBridge.height(h); }',
    '    }catch(e){}',
    '  }',
    '  window.__zxReportHeight=rep2;',
    '  setTimeout(rep2,60); setTimeout(rep2,320); setTimeout(rep2,900);',
    '  window.addEventListener("load",rep2);',
    '  document.addEventListener("click",function(){ setTimeout(rep2,90); },true);',
    '  try{ if(window.ResizeObserver&&document.body){ new ResizeObserver(rep2).observe(document.body); } }catch(e){}',
    '})();',
    '<\/script>',
  ].join('\n');
  const extra = [style, bridge, boot, heightRep].join('\n');
  const at = html.lastIndexOf('</body>');
  return at >= 0 ? html.substring(0, at) + extra + '\n' + html.substring(at) : html + extra;
}


function json(res, code, obj) {
  const body = Buffer.from(JSON.stringify(obj), 'utf8');
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function file(res, rel, type) {
  const p = path.join(ASSETS, rel);
  fs.readFile(p, (err, buf) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('not found: ' + rel);
      return;
    }
    res.writeHead(200, { 'Content-Type': type, 'Content-Length': buf.length });
    res.end(buf);
  });
}

http.createServer((req, res) => {
  const [url, query] = req.url.split('?');
  const probe = (query || '').indexOf('probe=1') >= 0;
  const novh = (query || '').indexOf('novh=1') >= 0;
  const tinyvh = (query || '').indexOf('tinyvh=1') >= 0;

  if (req.method === 'GET' && url === '/__e2e') {
    const buf = Buffer.from(E2E, 'utf8');
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': buf.length });
    return res.end(buf);
  }

  if (req.method === 'GET' && url === '/__frame') {
    const m = /w=(\d+)/.exec(query || '');
    const h = /h=(\d+)/.exec(query || '');
    const buf = Buffer.from(FRAME(m ? m[1] : 390, h ? h[1] : 844), 'utf8');
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': buf.length });
    return res.end(buf);
  }

  if (req.method === 'GET' && STATIC[url]) {
    const [rel, type] = STATIC[url];
    const inject = type.startsWith('text/html')
      ? (probe ? PROBE : (novh ? NOVH : (tinyvh ? TINYVH : null)))
      : null;
    if (inject) {
      const html = fs.readFileSync(path.join(ASSETS, rel), 'utf8').replace('</body>', inject);
      const buf = Buffer.from(html, 'utf8');
      res.writeHead(200, { 'Content-Type': type, 'Content-Length': buf.length });
      return res.end(buf);
    }
    return file(res, rel, type);
  }
  if (req.method === 'GET' && url === '/api/snapshot') {
    return json(res, 200, snap);
  }

  /* 对等客户端：模型分组列表 */
  if (req.method === 'GET' && url === '/api/models') {
    return json(res, 200, {
      ok: true,
      current: snap.bar.model || '',
      groups: [
        { name: '当前配置', models: [{ id: snap.bar.model || 'gpt-4o-mini', name: snap.bar.model || 'gpt-4o-mini', desc: '提供商: OPENAI', provider: 'OPENAI', mark: 'Q', group: 'current' }] },
        { name: '从 API 拉取', models: [{ id: 'deepseek-v4-pro', name: 'deepseek-v4-pro', desc: '从 API 拉取', provider: 'OPENAI', mark: 'D', group: 'fetched' }] },
      ],
    });
  }

  /* 对等客户端：人格列表 */
  if (req.method === 'GET' && url === '/api/personas') {
    return json(res, 200, {
      ok: true,
      activeId: 'p1',
      personas: [
        { id: 'p1', name: '默认人格', avatarEmoji: '🤖', avatarType: 'emoji', avatarUri: '', description: '默认人格', active: true },
        { id: 'p2', name: 'Zorv AI', avatarEmoji: '✦', avatarType: 'image', avatarUri: '', description: '全能助手', active: false },
      ],
    });
  }

  /* 对等客户端：已上传附件 */
  if (req.method === 'GET' && url === '/api/attachments') {
    return json(res, 200, { ok: true, attachments: uploaded });
  }

  /* 仅测试用：把服务端侧的状态摊给 E2E 断言（交互有没有到、SSE 有没有挂上）。
     双下划线开头就是提醒：产品里没有这条路由。 */
  if (req.method === 'GET' && url === '/api/__probe') {
    return json(res, 200, {
      interactions: interactions,
      diagReports: diagReports.length,
      sseClients: sseClients.length,
      uiActions: uiActions,
      uploaded: uploaded,
    });
  }

  /* 流式通道：与宿主 QuroWebUiServer 的 /api/events 同语义。
     桩这里用 80ms 的指纹比对驱动推送（宿主也是 80ms），
     所以 E2E 量到的延迟就是前端真实能拿到的延迟。 */
  if (req.method === 'GET' && url === '/api/events') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-store',
      'Connection': 'keep-alive',
      'Access-Control-Allow-Origin': '*',
    });
    res.write('data: ' + JSON.stringify(snap) + '\n\n');
    sseClients.push(res);
    req.on('close', function () {
      var i = sseClients.indexOf(res);
      if (i >= 0) sseClients.splice(i, 1);
    });
    return;
  }

  /* zxwidget 页壳：与宿主 /zx/shell 同语义 ——
     同一份 zx_card_runtime.html，只把原生桥换成 postMessage、载荷换成 hash。 */
  if (req.method === 'GET' && url === '/zx/shell') {
    var shell = buildWebShell();
    if (!shell) {
      res.writeHead(500, { 'Content-Type': 'text/plain' });
      return res.end('zx runtime missing');
    }
    var sbuf = Buffer.from(shell, 'utf8');
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': sbuf.length });
    return res.end(sbuf);
  }

  if (req.method === 'POST') {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
      let body = {};
      try { body = JSON.parse(raw || '{}'); } catch (e) { /* 空体 */ }
      if (url === '/api/send') {
        snap.messages.push({
          id: 'u' + Date.now(), role: 'user', mine: true, senderName: '我',
          time: Date.now(), blocks: [{ t: 'text', text: body.text || '(空)' }],
        });
        snap.composer.busy = true;
        return json(res, 200, { ok: true });
      }
      if (url === '/api/stop') { snap.composer.busy = false; return json(res, 200, { ok: true }); }
      if (url === '/api/new') {
        snap = {
          ok: true,
          bar: SNAPSHOT.bar,
          composer: Object.assign({}, SNAPSHOT.composer, { busy: false }),
          dark: true, lan: SNAPSHOT.lan, port: SNAPSHOT.port, messages: [],
        };
        return json(res, 200, { ok: true });
      }
      /* zxwidget 里的一次操作 → 与原生 onInteract 同回路。
         桩这里只记录，够 E2E 断言「点得动、且回传带上了组件名与动作」即可。 */
      if (url === '/api/interact') {
        interactions.push({ component: body.component, payload: body.payload });
        return json(res, 200, { ok: true, delivered: 'tool' });
      }
      if (url === '/api/diag') { diagReports.push(body); return json(res, 200, { ok: true }); }
      /* 对等客户端：开关写回 */
      if (url === '/api/toggle') {
        if (body.key && Object.prototype.hasOwnProperty.call(snap.composer, body.key)) {
          snap.composer[body.key] = !!body.on;
        }
        return json(res, 200, { ok: true });
      }
      /* 对等客户端：策略写回 */
      if (url === '/api/policy') {
        if (body.key === 'cms' || body.key === 'priv') {
          snap.composer[body.key] = body.value || 'ask';
        }
        return json(res, 200, { ok: true });
      }
      /* 对等客户端：工作区写回 */
      if (url === '/api/workspace') {
        snap.composer.workspace = body.path || '';
        return json(res, 200, { ok: true });
      }
      /* 对等客户端：模型选择 */
      if (url === '/api/models/select') {
        snap.bar.model = body.id || snap.bar.model;
        return json(res, 200, { ok: true });
      }
      /* 对等客户端：人格选择 */
      if (url === '/api/personas/select') {
        snap.bar.persona = body.id || snap.bar.persona;
        return json(res, 200, { ok: true });
      }
      /* 对等客户端：上传 */
      if (url === '/api/upload') {
        var attId = 'att-' + Date.now();
        uploaded.push({ id: attId, name: body.name || 'upload.bin', type: 'file', size: 123 });
        return json(res, 200, { ok: true, id: attId, name: body.name || 'upload.bin', type: 'file', size: 123 });
      }
      /* 对等客户端：打开功能面板 */
      if (url === '/api/ui_action') {
        uiActions.push(body.action || '');
        return json(res, 200, { ok: true });
      }
      /* 对等客户端：清空 */
      if (url === '/api/clear') {
        snap.messages = [];
        return json(res, 200, { ok: true });
      }
      /* 仅测试用：模拟宿主那边「正文在长」的一次推送，用来量流式延迟。
         产品里没有这条路由，名字前面双下划线就是提醒。 */
      if (url === '/api/__push') {
        var last = snap.messages[snap.messages.length - 1];
        if (last && last.blocks && last.blocks.length) {
          var blk = last.blocks[last.blocks.length - 1];
          blk.text = String(blk.text || '') + String(body.text || '·');
        }
        snap.composer.busy = true;
        return json(res, 200, { ok: true });
      }
      return json(res, 404, { ok: false, error: 'unknown api: ' + url });
    });
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('not found: ' + url);
}).listen(PORT, '127.0.0.1', () => {
  console.log('stub server: http://127.0.0.1:' + PORT + '/');
});

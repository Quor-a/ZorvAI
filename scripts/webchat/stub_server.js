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

/* 手机宽度取景框。
 *
 * 为什么需要它：本机 headless Chrome 无视 --window-size（实测视口恒为 ~504px，
 * 而截图只截 504 里的一段），于是"右边被切掉"这种假象会骗人——我上一轮就据此
 * 误判过一次溢出。用固定 390px 的 iframe 把应用按真手机宽度排版，
 * 同源可直接测量，截图也所见即所得。 */
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

    // 互动UI界面：必须是标记，不能是编译出来的界面
    var ui=ai.querySelector(".ui");
    ok(!!ui,"互动UI界面被标为「一块界面」");
    ok(!ai.querySelector(".ui table")&&!ai.querySelector(".ui tbody tr"),
       "互动UI界面**没有**被本页编译渲染（zxwidget 与 webui 未耦合）");

    // 页面不得加载任何 zx 资源
    var zxLoaded=false;
    var res=d.querySelectorAll("script[src],link[href]");
    for(var i=0;i<res.length;i++){
      var u=res[i].getAttribute("src")||res[i].getAttribute("href")||"";
      if(u.toLowerCase().indexOf("zx")>=0)zxLoaded=true;
    }
    ok(!zxLoaded,"页面未加载任何 zx 资源");

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
      say("—— 结束 ——");
    },2500);
  },1200);
});
</script></body></html>`;

/* 路由表：与 Kotlin 侧 QuroWebUiServer 必须逐条对应。

   🔴 这里**只**有投影自己的三个文件。任何 zx 开头的路径都不该出现在本表里
   —— 互动UI界面是独立模块（assets/zx/），宿主是原生对话框，
   Web UI 既不提供它的运行时，也不发它的资源（用户明令：不许把两者混在一起）。 */
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
  composer: {
    busy: false, deepThink: true, autoSaveMemory: false,
    subAgent: false, autoRead: false, vision: false, workspace: '',
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
      ui: [{ component: 'zx_table', dsl: '{"title":"本周压测 · 劣化接口"}' }],
    },
  ],
};

let snap = JSON.parse(JSON.stringify(SNAPSHOT));

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
    if (probe && type.startsWith('text/html')) {
      const html = fs.readFileSync(path.join(ASSETS, rel), 'utf8').replace('</body>', PROBE);
      const buf = Buffer.from(html, 'utf8');
      res.writeHead(200, { 'Content-Type': type, 'Content-Length': buf.length });
      return res.end(buf);
    }
    return file(res, rel, type);
  }
  if (req.method === 'GET' && url === '/api/snapshot') {
    return json(res, 200, snap);
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
      /* 🔴 没有 /api/interact：那是 zxwidget 的交互回传，属于原生对话框，
         不属于投影服务。这里显式不存在，正是「两个模块不许混」的证据。 */
      return json(res, 404, { ok: false, error: 'unknown api: ' + url });
    });
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('not found: ' + url);
}).listen(PORT, '127.0.0.1', () => {
  console.log('stub server: http://127.0.0.1:' + PORT + '/');
});

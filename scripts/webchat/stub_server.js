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

/* 端到端自检：点 zxwidget 卡片里的东西 → POST /api/interact → 宿主回声
   → 下一轮轮询把回声渲染成一条消息。这条回路是「两个对话框都支持 zxwidget」
   的关键，光看截图看不出来。 */
const E2E = `<!doctype html><html><head><meta charset="utf-8"><title>e2e</title>
<style>html,body{margin:0;background:#111;color:#eee;font:12px monospace}
#f{width:390px;height:844px;border:0;display:block;margin:0 auto;background:#fff}
#log{position:fixed;left:0;top:0;right:0;z-index:9;background:#000;padding:6px;white-space:pre-wrap;max-height:44vh;overflow:auto}
</style></head><body>
<pre id="log">booting…</pre>
<iframe id="f" src="/"></iframe>
<script>
var log=document.getElementById("log"),lines=[];
function say(s){lines.push(s);log.textContent=lines.join("\\n");}
function doc(){return document.getElementById("f").contentDocument;}
function msgs(){var d=doc();return d?d.querySelectorAll("#stream .msg"):[];}
window.addEventListener("load",function(){
  setTimeout(function(){
    say("初始消息数 = "+msgs().length);
    var d=doc();
    var card=d.querySelector(".zx-wrap");
    say("zx 卡片存在 = "+!!card);
    var rows=card?card.querySelectorAll("tbody tr"):[];
    say("卡片内可点行数 = "+rows.length);
    if(!rows.length){say("FAIL: 卡片里没有可交互行");return;}
    rows[0].click();
    say("已点击第 1 行");
    setTimeout(function(){
      var n=msgs().length;
      say("点击后消息数 = "+n);
      var last=msgs()[n-1];
      var txt=last?last.textContent.replace(/\\s+/g," ").slice(0,90):"(无)";
      say("末条消息 = "+txt);
      say(n>1?"PASS: 卡片交互已回传宿主":"FAIL: 交互没回传");
    },3000);
  },900);
});
</script></body></html>`;

const STATIC = {
  '/': ['web-chat/index.html', 'text/html; charset=utf-8'],
  '/index.html': ['web-chat/index.html', 'text/html; charset=utf-8'],
  '/style.css': ['web-chat/style.css', 'text/css; charset=utf-8'],
  '/chat.js': ['web-chat/chat.js', 'application/javascript; charset=utf-8'],
  '/zxwidget.css': ['web-chat/zxwidget.css', 'text/css; charset=utf-8'],
  '/zxwidget.js': ['zx/zxwidget.js', 'application/javascript; charset=utf-8'],
  '/zx_contracts.json': ['zx_contracts.json', 'application/json; charset=utf-8'],
};

/* 一份有代表性的会话：用户消息 + 助手（思考 + 正文 + 工具 + zx 卡片） */
const SNAPSHOT = {
  ok: true,
  title: '接口压测结果整理',
  subtitle: 'deepseek-v4-pro · 12.4k/128k',
  dark: true,
  busy: false,
  messages: [
    {
      id: 'u1', role: 'user',
      content: '帮我把这周的接口压测结果整理一下，重点看 P99 和错误率。',
    },
    {
      id: 'a1', role: 'assistant',
      reasoning: '先确认用户要的口径：P99 与错误率。需要拉取本周的压测记录，\n再按接口分组聚合，最后挑出劣化的几个。',
      content: '本周共 **14 个接口**参与压测，整体 P99 从 214ms 升到 **281ms**，劣化集中在 3 个接口。\n\n' +
        '### 结论\n\n' +
        '1. `POST /api/order` P99 由 244ms 涨到 **612ms**，是唯一超标的写接口\n' +
        '2. `GET /api/feed` 错误率 0.02% → **1.8%**，与网关 502 同时出现\n' +
        '3. 其余接口在噪声范围内\n\n' +
        '```sql\nselect api, percentile_disc(0.99) within group (order by ms) as p99\nfrom perf_week group by api having p99 > 300;\n```\n\n' +
        '| 接口 | P99 | 错误率 | 判定 |\n| --- | --- | --- | --- |\n' +
        '| POST /api/order | 612ms | 0.31% | 劣化 |\n| GET /api/feed | 388ms | 1.80% | 劣化 |\n| GET /api/user | 96ms | 0.01% | 正常 |',
      toolCalls: [
        {
          name: 'perf_query', status: 'ok',
          arguments: '{"range":"7d","group_by":"api"}',
          result: '{"rows":14,"window":"2026-10-06..2026-10-13"}',
          durationMs: 820,
        },
        {
          name: 'zx_web_card', status: 'ok',
          arguments: JSON.stringify({
            component: 'zx_table',
            dsl: {
              title: '本周压测 · 劣化接口',
              columns: ['接口', 'P99', '错误率'],
              rows: [
                { '接口': 'POST /api/order', 'P99': '612ms', '错误率': '0.31%' },
                { '接口': 'GET /api/feed', 'P99': '388ms', '错误率': '1.80%' },
                { '接口': 'GET /api/user', 'P99': '96ms', '错误率': '0.01%' },
              ],
            },
          }),
          result: '卡片已展示',
          durationMs: 12,
        },
      ],
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
        snap.messages.push({ id: 'u' + Date.now(), role: 'user', content: body.text || '(空)' });
        snap.busy = true;
        return json(res, 200, { ok: true });
      }
      if (url === '/api/stop') { snap.busy = false; return json(res, 200, { ok: true }); }
      if (url === '/api/new') {
        snap = { ok: true, title: '新对话', subtitle: 'deepseek-v4-pro', dark: true, busy: false, messages: [] };
        return json(res, 200, { ok: true });
      }
      if (url === '/api/interact') {
        console.log('[interact]', JSON.stringify(body));
        snap.messages.push({
          id: 'u' + Date.now(), role: 'user',
          content: '【zxwidget 交互】' + (body.summary || ''),
        });
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

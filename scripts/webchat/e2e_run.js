/* Web UI 端到端运行器 —— 起桩服务器 + 真 Chrome，把 /__e2e 的 PASS/FAIL 收回来。
 *
 * 为什么要它：E2E 页（stub_server.js 里的 E2E 常量）一直只能靠人肉开浏览器看，
 * 结果是「验收存在、但没人跑」——本轮用户投诉的三条（zxwidget 画不出、消息延迟、
 * 没有流式）本来都该被它拦住。这里把它变成可重复执行、有退出码的一步。
 *
 * 用法：node scripts/webchat/e2e_run.js [--headful]
 *   退出码 0 = 全 PASS；1 = 有 FAIL 或超时。
 */
const { spawn } = require('child_process');
const path = require('path');
const http = require('http');

const ROOT = path.resolve(__dirname, '../..');
const HEADFUL = process.argv.indexOf('--headful') >= 0;
/* 🔴 端口不许写死：上一轮我在 8791~8795 留下一堆没关的桩进程，
   于是「起服务」这一步静默失败、运行器却连到了**旧代码**的进程上，
   报出来的错（找不到 #log）跟真实原因（路由不存在）完全不是一回事。
   现在先占 0 让内核给个空闲端口，再把它放掉交给子进程。 */
let PORT = 0;

const CHROME = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find((p) => require('fs').existsSync(p));

if (!CHROME) {
  console.error('找不到 Chrome/Edge，无法跑 E2E');
  process.exit(1);
}

let puppeteer;
try {
  puppeteer = require('puppeteer-core');
} catch (e) {
  console.error('找不到 puppeteer-core（应装在托管 node workspace 里）：' + e.message);
  process.exit(1);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* 拿一个空闲端口：监听 0 让内核分配，记下来再关掉。 */
function freePort() {
  return new Promise((resolve, reject) => {
    const s = require('net').createServer();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}

/* 等到「我们自己的」服务起来：必须 200 且带 __probe 的字段。
   只看「端口有响应」是不够的 —— 那正是上面那次误判的成因。 */
function waitPort(tries) {
  return new Promise((resolve, reject) => {
    const tick = (n) => {
      const req = http.get(
        { host: '127.0.0.1', port: PORT, path: '/api/__probe', timeout: 800 },
        (res) => {
          let b = '';
          res.on('data', (c) => (b += c));
          res.on('end', () => {
            let ok = false;
            try { ok = res.statusCode === 200 && typeof JSON.parse(b).sseClients === 'number'; } catch (e) { ok = false; }
            if (ok) return resolve();
            n <= 0 ? reject(new Error('端口有响应但不是本次的桩服务器（拿到 ' + res.statusCode + '）'))
                   : setTimeout(() => tick(n - 1), 200);
          });
        }
      );
      req.on('error', () => (n <= 0 ? reject(new Error('桩服务器没起来')) : setTimeout(() => tick(n - 1), 200)));
      req.on('timeout', () => { req.destroy(); n <= 0 ? reject(new Error('桩服务器超时')) : setTimeout(() => tick(n - 1), 200); });
    };
    tick(tries || 40);
  });
}

(async () => {
  PORT = await freePort();
  console.log('使用端口 ' + PORT);

  let srvDead = null;
  const srv = spawn(process.execPath, [path.join(__dirname, 'stub_server.js'), String(PORT)], {
    cwd: ROOT,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  srv.on('exit', (code) => { srvDead = '桩服务器提前退出，code=' + code; });
  srv.stdout.on('data', (d) => process.stdout.write('  [桩] ' + d));
  srv.stderr.on('data', (d) => process.stderr.write('  [桩:err] ' + d));

  let browser = null;
  let failed = 0;
  try {
    await waitPort(50);
    if (srvDead) throw new Error(srvDead);

    browser = await puppeteer.launch({
      executablePath: CHROME,
      headless: !HEADFUL,
      args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=900,1400'],
      defaultViewport: { width: 900, height: 1400 },
    });
    const page = await browser.newPage();
    page.on('console', (m) => {
      const t = m.text();
      if (t.indexOf('error') >= 0 || t.indexOf('Error') >= 0) console.log('  [页面] ' + t);
    });
    page.on('pageerror', (e) => console.log('  [页面异常] ' + e.message));

    await page.goto('http://127.0.0.1:' + PORT + '/__e2e', { waitUntil: 'domcontentloaded' });

    /* 等 E2E 页把「—— 结束 ——」打出来。
       阶段链是串行的（每个阶段都有 setTimeout / 轮询），总时长上限给 60s。 */
    const deadline = Date.now() + 60000;
    let log = '';
    while (Date.now() < deadline) {
      log = await page.$eval('#log', (el) => el.textContent);
      if (log.indexOf('—— 结束 ——') >= 0) break;
      await sleep(400);
    }

    console.log('\n============ E2E 输出 ============');
    console.log(log);
    console.log('==================================');

    const lines = log.split('\n').map((s) => s.trim());
    const pass = lines.filter((l) => l.indexOf('PASS:') === 0);
    const fail = lines.filter((l) => l.indexOf('FAIL:') === 0);
    failed = fail.length;

    if (log.indexOf('—— 结束 ——') < 0) {
      console.log('⚠️  E2E 没有跑到结束（超时），可能是某个阶段卡住了');
      failed = failed || 1;
    }

    console.log('\nPASS = ' + pass.length + '   FAIL = ' + fail.length);
    if (fail.length) {
      console.log('失败项：');
      fail.forEach((l) => console.log('  ' + l));
    }
  } catch (e) {
    console.error('运行器异常：' + (e && e.stack ? e.stack : e));
    failed = failed || 1;
  } finally {
    if (browser) { try { await browser.close(); } catch (e) { /* 忽略 */ } }
    srv.kill();
  }
  process.exit(failed ? 1 : 0);
})();

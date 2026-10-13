/* Web UI 前后端契约同步校验
 *
 * 为什么需要：前端（assets/web-chat/）与后端（QuroWebUiServer.kt）是两份独立文件，
 * 靠字符串路径对接。这种地方一旦对不上，表现是「页面能打开但某个功能静默不动」，
 * 而不是报错——正是这个项目里反复吃过的「线没接上」。
 * 本脚本把两边的路径抽出来逐条核对，对不上就 exitCode=1。
 *
 * 用法：node scripts/webchat/api_contract_check.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const ASSETS = path.join(ROOT, 'app/src/main/assets');
const SERVER = path.join(
  ROOT, 'app/src/main/java/com/ai/assistance/quro/ui/chat/WebUiServer.kt');

let bad = 0;
function ok(cond, msg) {
  console.log((cond ? '  ✓ ' : '  ✗ ') + msg);
  if (!cond) { bad++; process.exitCode = 1; }
}

const serverSrc = fs.readFileSync(SERVER, 'utf8');
const htmlSrc = fs.readFileSync(path.join(ASSETS, 'web-chat/index.html'), 'utf8');
const jsSrc = fs.readFileSync(path.join(ASSETS, 'web-chat/chat.js'), 'utf8');

/* ── 1. 从 Kotlin 侧抽出路由表 ── */
const staticMap = {};
const mapBlock = serverSrc.match(/private val STATIC = mapOf\(([\s\S]*?)\n\s*\)/);
if (!mapBlock) { console.log('!! 抽不到 STATIC 路由表'); process.exitCode = 1; process.exit(1); }
for (const m of mapBlock[1].matchAll(/"([^"]+)"\s+to\s+"([^"]+)"/g)) {
  staticMap[m[1]] = m[2];
}
const apiPaths = new Set();
for (const m of serverSrc.matchAll(/"(\/api\/[a-z]+)"/g)) apiPaths.add(m[1]);

console.log('=== 1. 后端登记的路由 ===');
console.log('  静态:', Object.keys(staticMap).join(' '));
console.log('  接口:', [...apiPaths].join(' '));

/* ── 2. index.html 引用的每个静态资源都能被服务 ── */
console.log('=== 2. index.html 引用 vs 静态路由 ===');
const refs = [];
for (const m of htmlSrc.matchAll(/(?:href|src)="([^"]+)"/g)) {
  const u = m[1];
  if (/^(https?:|data:|#)/.test(u)) continue;
  refs.push(u.startsWith('/') ? u : '/' + u);
}
for (const r of refs) {
  const asset = staticMap[r];
  ok(!!asset, `${r} 有路由 → ${asset || '（缺）'}`);
  if (asset) {
    ok(fs.existsSync(path.join(ASSETS, asset)), `${asset} 在 assets 中存在`);
  }
}

/* ── 3. chat.js 调用的每个接口都能被服务 ── */
console.log('=== 3. chat.js 调用 vs 后端接口 ===');
const calls = new Set();
// 排除字符串拼接（如 fetch("api/"+path)）：那不是完整路径，
// 真正的路径来自 post() 的第一个实参
for (const m of jsSrc.matchAll(/fetch\(\s*"([^"]+)"(?!\s*\+)/g)) calls.add(m[1]);
for (const m of jsSrc.matchAll(/post\(\s*"([^"]+)"/g)) calls.add('api/' + m[1]);
for (const c of calls) {
  const full = c.startsWith('/') ? c : '/' + c;
  ok(staticMap[full] !== undefined || apiPaths.has(full),
    `${c} → ${staticMap[full] ? '静态 ' + staticMap[full] : (apiPaths.has(full) ? '接口' : '（两边都没有）')}`);
}

/* ── 4. 后端提供了、但前端从不调用的接口（死路由） ── */
console.log('=== 4. 后端有但前端不调的接口 ===');
for (const p of apiPaths) {
  const short = p.replace(/^\//, '');
  const used = [...calls].some(c => c === short || c === p);
  if (!used) console.log('  ℹ 未被前端调用（可能留给换端访问/调试）:', p);
}

/* ── 5. zxwidget 在 Web UI 里的落点：**同一份运行时**，不许第二实现 ──
 *
 * 🔴 这一节的方向本轮再反了一次，说明白为什么，免得下一个人又把它翻回去：
 *    · 更早一版：zxwidget 与 Web UI 混在一份文件里（`/zxwidget.js` 路由 + 全局 compile()）。
 *    · 上一版：为了让两个模块解耦，Web UI 侧**一条 zx 痕迹都不留**。
 *      结果是网页里那块「互动UI界面」退化成一个只写组件名的占位条 ——
 *      用户原话：「web ui 没有 zorvAI 的所有功能」「zxwidget 根本渲染不了」。
 *    · 本版（用户 2026-10-13 拍板）：**Web UI 要和 ZorvAI 看到同一个东西**，
 *      所以 zxwidget 必须在网页上真画出来；但**不许另写一套渲染器** ——
 *      两边共用同一份 `zx_card_runtime.html`（服务端 `/zx/shell` 就是它，
 *      只把原生桥换成 postMessage）。既解决「画不出来」，也不重新引入漂移。
 *
 * 所以本节断言的是：**真画 + 同源**，而不是「不许出现 zx」。
 */
console.log('=== 5. zxwidget 在 Web UI 里的落点（真画 + 同源）===');
ok(fs.existsSync(path.join(ASSETS, 'zx_card_runtime.html')),
  'zx_card_runtime.html 仍在（两个对话框共用它）');
ok(/"\/zx\/shell"/.test(serverSrc), '服务端有 /zx/shell 路由（网页用它承载每块界面）');
ok(/buildWebShell/.test(serverSrc),
  '/zx/shell 走 ZxWebCard.buildWebShell（与原生 buildHtml 同一份页壳）');
ok(/ZxWebCard\.assets\(/.test(serverSrc),
  '/zx/shell 取的是 ZxWebCard.assets（同一份运行时与契约，不是另一份拷贝）');
ok(apiPaths.has('/api/interact'),
  '有 /api/interact：界面里的操作能回到宿主（命中在等的工具就放行，否则作为用户消息发出）');
ok(/submitByComponent/.test(serverSrc),
  '/api/interact 复用 ZxWebCardQueue.submitByComponent（与原生 onInteract 同一套回路）');
ok(!/LAYOUTS\.|resolveLayout/.test(jsSrc),
  'chat.js 里没有 zx 布局/编译器逻辑（网页不另写一套渲染器）');
ok(/iframe/.test(jsSrc) && /zx\/shell/.test(jsSrc),
  'chat.js 用 iframe 引 /zx/shell（承载而非重写）');
ok(/document\.querySelectorAll\("iframe\.zxf"\)/.test(jsSrc) || /iframe\.zxf/.test(jsSrc),
  'chat.js 维护 iframe.zxf 集合（高度与交互的派发靠它反查来源）');
ok(/m\.ui/.test(jsSrc) || /var list=m\.ui/.test(jsSrc),
  'chat.js 真的消费投影里的 ui 字段（旧版从不读它 → 网页上永远看不到这块界面）');
ok(!/zx_contracts/.test(jsSrc),
  'chat.js 不直接请求 zx_contracts.json（契约由服务端注入页壳，只有一处拼接）');

/* ── 6. 投影协议：快照字段必须与渲染端用到的一致 ──
 * 投影的形状由 WebUiProjection 决定，前端按字段分派；
 * 字段改名而另一边没跟，表现就是「页面能开、某些块静默不出现」。 */
console.log('=== 6. 投影快照字段 vs 前端消费 ===');
const PROJ = path.join(ROOT, 'app/src/main/java/com/ai/assistance/quro/ui/chat/WebUiProjection.kt');
const projSrc = fs.readFileSync(PROJ, 'utf8');
// `messages` 是在服务层组装的（WebUiProjection 只负责单条消息 → 块），
// 所以「产出方」要在两份文件里找，只查投影层会假阳性。
const producers = projSrc + '\n' + serverSrc;
for (const field of ['bar', 'composer', 'messages', 'blocks', 'tools', 'ui', 'attachments']) {
  ok(new RegExp('"' + field + '"').test(producers) || new RegExp(field).test(producers),
    `投影层产出 ${field}`);
  ok(new RegExp(field).test(jsSrc), `前端消费 ${field}`);
}
/* 权限模式条：把 composer 的**产出字段**与页面的**chip** 做双向核对。
 *
 * 为什么单拎出来：这一排 chip 是「投了没人画」与「画了没人投」两种死代码的高发地。
 *   · 投了没人画 → 用户看不到状态（本轮开盘时的 autoRead / workspace 就是这类，
 *     服务里写死 false/""，页面连 chip 都没有）；
 *   · 画了没人投 → chip 永远停在页面自己的默认值，看着有、其实一动不动。
 * 单向核对抓不到第二种，所以这里两个方向都跑。 */
console.log('=== 6b. 权限模式条双向核对 ===');
const composerBody = projSrc.match(/fun composer\(([\s\S]*?)private fun policyName/);
if (!composerBody) {
  ok(false, '抽不到 WebUiProjection.composer 的函数体');
} else {
  const emitted = new Set();
  for (const m of composerBody[1].matchAll(/\.put\("([a-zA-Z]+)"/g)) emitted.add(m[1]);
  console.log('  composer 投出:', [...emitted].join(' '));
  for (const f of emitted) {
    ok(new RegExp('\\b' + f + '\\b').test(jsSrc), `投出的 ${f} 被前端消费`);
  }
  // 反向：布尔 chip 必须都有字段投它
  const chips = [];
  for (const m of htmlSrc.matchAll(/data-mode="([a-zA-Z]+)"/g)) chips.push(m[1]);
  console.log('  页面布尔 chip:', chips.join(' '));
  for (const c of chips) {
    ok(emitted.has(c), `chip ${c} 有对应投影字段`);
  }
  // 三态策略：两个 chip 必须都在，且投影投的是规范化名（不是中文文案）
  ok(emitted.has('cms') && emitted.has('priv'),
    'CMS / 特权 两个三态策略都被投影（权限状态不许残缺）');
  ok(/QuroPolicy\.(ALLOW|DENY|ASK)\s*->\s*"(allow|deny|ask)"/.test(projSrc),
    '策略投的是 allow/deny/ask 规范化名（文案留给接收端，换语言不会错）');
  // 真源：这四个不是 ViewModel 状态，服务层必须从 prefs / 策略库现读
  for (const [src, why] of [
    ['QuroVoiceFeaturePrefs\\.getAutoRead', 'autoRead'],
    ['WorkspacePreferences\\.getCurrentWorkspace', 'workspace'],
    ['QuroPolicyStore\\.getCms', 'cms'],
    ['QuroPolicyStore\\.getPriv', 'priv'],
  ]) {
    ok(new RegExp(src).test(serverSrc), `${why} 的真源现读（不是写死占位）`);
  }
  ok(!/autoRead\s*=\s*false\s*,/.test(serverSrc) && !/workspace\s*=\s*""\s*,/.test(serverSrc),
    '服务层没有残留的写死占位');
}

// 块类型：投影层投出的每种 t，前端必须都有分支
const blockTypes = new Set();
for (const m of projSrc.matchAll(/put\("t",\s*"([a-z_]+)"\)/g)) blockTypes.add(m[1]);
console.log('  投影块类型:', [...blockTypes].join(' '));
for (const t of blockTypes) {
  ok(new RegExp('case\\s+"' + t + '"').test(jsSrc), `前端有 case "${t}" 分支`);
}

/* ── 7. 视口高度链（2026-10-13 真机白屏事故的回归锁）──
 *
 * 事故：`#app{height:100dvh}` 在真机上被钉死在「页面加载那一刻」的极小动态视口上，
 * `#stream`（`flex:1` + `min-height:0`）跟着塌成 0，5 条消息被 `overflow` 全裁掉
 * → 界面只剩顶栏 + 底栏 + 一片空白；桌面 headless Chrome 全绿，只有真机能发现。
 * 下面这些是**不许回退**的结构约束：谁把它们改红了，就说明又踩回去了。 */
console.log('=== 7. 视口高度链（真机白屏事故回归锁）===');
/* 🔴 先剥注释再断言。CSS 注释里是**故意**提到 `dvh` 的（说明为什么不能用），
   直接扫全文会把那段说明本身判成违规 —— 上一轮 zx 边界断言就是这么假阳性的。 */
const cssSrc = fs.readFileSync(path.join(ASSETS, 'web-chat/style.css'), 'utf8');
const cssCode = cssSrc.replace(/\/\*[\s\S]*?\*\//g, '');
ok(!/\bdvh\b/.test(cssCode), 'CSS 声明里没有 dvh 单位（它就是本次事故的根因）');
ok(/#app\{[^}]*height:100%/.test(cssCode), '#app 高度走 height:100%（布局视口，无单位协商）');
ok(/#stream\{[\s\S]{0,60}?flex:1 1 auto/.test(cssCode),
  '#stream 是 flex:1 1 auto（flex:1 的 0 基准在高度链坏掉时正好塌成 0）');
ok(/html\.flow #app\{height:auto\}/.test(cssCode), '有 html.flow 降级：摘掉 flex 高度链');
ok(/html\.flow #stream\{flex:0 0 auto/.test(cssCode), '有 html.flow 降级：消息改回文档流');
ok(/function fit\(\)/.test(jsSrc) && /clientHeight/.test(jsSrc),
  'chat.js 有 fit()，且用实测 clientHeight 而不是视口单位');
ok(/addEventListener\("resize",fit\)/.test(jsSrc),
  'fit() 挂在 resize 上（WebView 后拿到尺寸/旋屏都能纠正）');
ok(/function guardCollapse\(\)/.test(jsSrc),
  'chat.js 有 guardCollapse（宁可丑不可白：塌陷时可见降级，不许静默白屏）');
ok(/classList\.toggle\("dark"/.test(jsSrc),
  '主题用 classList.toggle 而非 className= 整体赋值（后者会抹掉 flow 降级类）');
ok(/id="diag"/.test(htmlSrc) && /getElementById\("diag"\)/.test(jsSrc),
  '#diag 自检行两端都在（出问题时把实测数字摊到界面上）');
/* 页面自检必须有**回到宿主**的通道。
   🔴 实现换过一次，别按旧写法去查 WebChromeClient：
      改前页面跑在应用内嵌 WebView 里，靠 `onConsoleMessage` 收 console；
      现在页面跑在**别的设备的浏览器**里，宿主看不见它的 console，
      所以自检改为 `POST /api/diag` 由服务端写 QuroDiag（见 WebUiServer 的同名路由）。
      没有这条通道，`guardCollapse` / `logHealth` 在真机上就等于白写。 */
ok(apiPaths.has('/api/diag'),
  '有 /api/diag：页面自检能回传宿主（否则真机白屏时开发者手上什么都没有）');
ok(/QuroDiag\.log\(TAG, "页面自检/.test(serverSrc),
  '/api/diag 落到 QuroDiag 日志（不是收下就丢）');
ok(/function reportDiag/.test(jsSrc) && /"api\/diag"/.test(jsSrc),
  'chat.js 有 reportDiag 并调 api/diag');
ok(/reportDiag\(msg,"warn"\)/.test(jsSrc),
  '高度塌陷时真的回传（自检结果不许只留在页面上）');

console.log('---');
console.log(bad === 0 ? '全部通过' : bad + ' 项不一致');

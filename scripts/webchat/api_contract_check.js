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

/* ── 5. 投影边界：zxwidget（互动UI界面）不得出现在 Web UI 里 ──
 *
 * 🔴 这一节的方向在本轮**反过来了**（用户 2026-10-13 原话：
 *    「你怎么老是把 zxwidget 和 webui 混合在一起」）。
 *    旧断言在检查「zxwidget 是否被正确接进 Web UI」（`/zxwidget.js` 有路由、
 *    chat.js 调用全局 compile()）——那正是**在守护缺陷**。
 *    现在要求的是：Web UI 侧一条 zx 痕迹都不许留。 */
console.log('=== 5. zxwidget 与 Web UI 的边界（必须彻底分离）===');
ok(fs.existsSync(path.join(ASSETS, 'zx/zxwidget.js')),
  'zx/zxwidget.js 仍存在（互动UI界面是独立模块，本轮不该动它）');
ok(!fs.existsSync(path.join(ASSETS, 'web-chat/zxwidget.css')),
  'web-chat/ 下没有 zxwidget.css（它的样式不属于 Web UI）');
for (const p of ['/zxwidget.js', '/zxwidget.css', '/zx_contracts.json']) {
  ok(staticMap[p] === undefined, `静态路由里没有 ${p}`);
}
ok(!apiPaths.has('/api/interact'),
  '接口里没有 /api/interact（交互回传属于原生对话框，不属于投影服务）');
ok(!/LAYOUTS\.|resolveLayout|compile\(/.test(jsSrc),
  'chat.js 里没有 zx 编译器/布局逻辑');
ok(!/zx_contracts/.test(jsSrc),
  'chat.js 不请求 zx_contracts.json');
/* 🔴 只看**资源引用**（src/href），不要扫全文：
   本页的注释里**故意**提到 zxwidget（说明「它不属于本页」），
   扫全文会把那段说明本身判成违规 —— 上一版校验器就是这么假阳性的。 */
const htmlAssetRefs = [];
for (const m of htmlSrc.matchAll(/(?:href|src)="([^"]+)"/g)) htmlAssetRefs.push(m[1]);
ok(!htmlAssetRefs.some(u => /zx/i.test(u)),
  'index.html 不引用任何 zx 资源（注释里的说明不算）');

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
const dialogSrc = fs.readFileSync(path.join(
  ROOT, 'app/src/main/java/com/ai/assistance/quro/ui/chat/WebUiDialog.kt'), 'utf8');
ok(/onConsoleMessage/.test(dialogSrc),
  '宿主收页面 console → QuroDiag（否则页面自检在真机 logcat 里等于白写）');

console.log('---');
console.log(bad === 0 ? '全部通过' : bad + ' 项不一致');

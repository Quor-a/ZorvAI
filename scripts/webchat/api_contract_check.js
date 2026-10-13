/* Web UI 前后端契约同步校验
 *
 * 为什么需要：前端（assets/web-chat/）与后端（QuroWebChatServer.kt）是两份独立文件，
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
  ROOT, 'app/src/main/java/com/ai/assistance/quro/ui/chat/QuroWebChatServer.kt');

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
  // 静态资源也走 fetch（zx_contracts.json），两条路都算数
  ok(staticMap[full] !== undefined || apiPaths.has(full),
    `${c} → ${staticMap[full] ? '静态 ' + staticMap[full] : (apiPaths.has(full) ? '接口' : '（两边都没有）')}`);
}

/* ── 4. 后端提供了、但前端从不调用的接口（死路由） ── */
console.log('=== 4. 后端有但前端不调的接口 ===');
for (const p of apiPaths) {
  const short = p.replace(/^\//, '');
  const used = [...calls].some(c => c === short || c === p);
  if (!used) console.log('  ℹ 未被前端调用（可能留给外部链接/调试）:', p);
}

/* ── 5. zxwidget 必须是独立模块，不能被焊进 Web UI ── */
console.log('=== 5. zxwidget 与 Web UI 的边界 ===');
ok(fs.existsSync(path.join(ASSETS, 'zx/zxwidget.js')), 'zx/zxwidget.js 存在（独立模块）');
ok(staticMap['/zxwidget.js'] === 'zx/zxwidget.js',
  'zxwidget 由 /zxwidget.js 提供，不在 web-chat 目录里');
ok(!/var ZX_CONTRACTS = \(function/.test(htmlSrc) && !/LAYOUTS\./.test(htmlSrc),
  'index.html 里没有内联 zx 编译器');
ok(!/LAYOUTS\.|resolveLayout/.test(jsSrc),
  'chat.js 里没有 zx 布局逻辑（只调用 compile()）');
ok(/typeof\s+compile\s*===?\s*"function"/.test(jsSrc),
  'chat.js 通过全局 compile() 使用 zxwidget，缺了也不影响聊天');

console.log('---');
console.log(bad === 0 ? '全部通过' : bad + ' 项不一致');

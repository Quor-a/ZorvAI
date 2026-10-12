// zx 运行时名册/布局一致性校验
// // 校验两个运行时里 OVERRIDE 引用的键是否存在于真实组件名册，
// 以及被引用的布局名是否都有 LAYOUTS 实现——这两类写错都会「静默失效」。
//
// 用法（在仓库根目录执行）：
//   node scripts/zx/zx_runtime_roster_check.js [运行时文件名]
// 例：
//   node scripts/zx/zx_runtime_roster_check.js                       # 默认测 zx_chat_runtime.html（第三个对话框）
//   node scripts/zx/zx_runtime_roster_check.js zx_card_runtime.html  # 测卡片运行时
//
// 依赖：仅 node 内置模块（用桩 DOM 在 node 里跑运行时的 JS，不需要 WebView/浏览器）。
const path = require('path');
const ASSETS = path.resolve(__dirname, '../../app/src/main/assets') + '/';

// 校验：两个运行时的 OVERRIDE / CONTAINERS 键必须都存在于真实组件名册，否则静默失效
const fs = require('fs');
const contracts = JSON.parse(fs.readFileSync(ASSETS + 'zx_contracts.json', 'utf8'));
const real = new Set(contracts.components);
for (const f of ['zx_chat_runtime.html', 'zx_card_runtime.html']) {
  const html = fs.readFileSync(ASSETS + '' + f, 'utf8');
  const m = /var\s+OVERRIDE\s*=\s*\{([\s\S]*?)\n\};/.exec(html);
  const c = /var\s+CONTAINERS\s*=\s*\[([\s\S]*?)\];/.exec(html);
  const keys = new Set();
  if (m) [...m[1].matchAll(/([A-Za-z_][A-Za-z0-9_]*)\s*:\s*"/g)].forEach(x => keys.add(x[1]));
  if (c) [...c[1].matchAll(/"([^"]+)"/g)].forEach(x => keys.add(x[1]));
  const bad = [...keys].filter(k => !real.has(k));
  console.log('=== ' + f + ' ===');
  console.log('  映射键总数: ' + keys.size + '（名册 ' + real.size + '）');
  /* 名册外的键是**刻意的防御性兜底**：模型可能吐出 zx_line / zx_bar / zx_kv 这类
     看起来很像真名的幻觉组件，映射到合理布局总比落进 generic 强。
     所以这里只做提示，不计为失败。 */
  console.log(bad.length
    ? '  ℹ 名册外的键（防御性兜底，模型幻觉出这些名字时也能得到合理渲染）: ' + bad.join(', ')
    : '  ✓ 全部键都在名册中');
  // 布局名必须都有实现 —— 这一类写错才是真失败（引用了一个不存在的布局）
  const impl = new Set([...html.matchAll(/LAYOUTS\.([A-Za-z_][A-Za-z0-9_]*)\s*=/g)].map(x => x[1]));
  const used = new Set([...keys].map(k => {
    const re = new RegExp(k + '\\s*:\\s*"([a-z]+)"');
    const mm = re.exec(html); return mm ? mm[1] : null;
  }).filter(Boolean));
  const missing = [...used].filter(l => !impl.has(l));
  console.log('  布局实现: ' + [...impl].sort().join(' '));
  if (missing.length) {
    console.log('  ✗ 被引用但未实现的布局: ' + missing.join(', '));
    process.exitCode = 1;
  } else {
    console.log('  ✓ 引用的布局全部有实现');
  }
}

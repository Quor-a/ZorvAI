#!/usr/bin/env node
/* ═══════════════════════════════════════════════════════════════════════
   zxwidget 同源守护 —— 两份编译器不许再继续分叉
   ═══════════════════════════════════════════════════════════════════════

   背景：zxwidget 的编译器目前存在两份拷贝
     A) app/src/main/assets/zx/zxwidget.js        —— Web UI 对话框（外链脚本）
     B) app/src/main/assets/zx_card_runtime.html  —— 单卡片对话框（构建期内联）

   为什么 B 必须内联：它走 `loadDataWithBaseURL`，`file://` 下加载不到外链脚本，
   而放宽 `setAllowFileAccessFromFileURLs` 是安全倒退，不做。

   为什么两份拷贝有害：同一个 zx 组件在两个对话框里会渲染成不同样子，
   而且**不会有任何报错**——这正是本仓反复踩过的那类静默失效。

   现状（2026-10-13 实测，HEAD `73586421` 上就已存在，**不是某一轮引入的**）：
   58 个顶层块里 24 个不一致，连 `compile` / `resolveLayout` 都不同，
   `LAYOUTS.chart` 更是差近一倍（A=4150 / B=2345）。
   也就是说：**这两份不是「同一份的微小漂移」，而是两套独立演进过的实现。**

   所以本脚本**不替你做「该以哪一份为准」的设计决策**（那需要真机逐组件对比）。
   它做的是把现状冻结成基线，让「继续分叉」这件事变得不可能静默发生：

     · 基线里没有的新差异      → FAIL（这是回归）
     · 基线块的任一侧内容变了  → FAIL（改了一边就必须同步另一边，或显式 --bless）
     · 基线块变得两边一致了    → WARN（好事，提示你从基线里删掉）
     · 一切照旧                → PASS

   用法：
     node scripts/zx/zxwidget_parity_check.js           # 校验
     node scripts/zx/zxwidget_parity_check.js --bless    # 把当前差异重新记为基线
   退出码：0 = 无新增/变更；1 = 出现新漂移或基线块内容变了
   ═══════════════════════════════════════════════════════════════════════ */
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '..', '..');
const MODULE = path.join(ROOT, 'app/src/main/assets/zx/zxwidget.js');
const CARD = path.join(ROOT, 'app/src/main/assets/zx_card_runtime.html');
const BASELINE = path.join(__dirname, 'zxwidget_parity_baseline.json');

/* 区段锚点：两份拷贝的编译器都从字段槽位表开始。
   结束锚点取「宿主桥」段标题——单卡片运行时的同一个 <script> 里，
   编译器后面还跟着卡片 UI（state / render / $t），不切掉会被误判成漂移。 */
const START = 'var TXT_KEYS';
const CARD_END = '宿主桥';

const BLESS = process.argv.includes('--bless');

/* ───────────────────────── 抽取与归一化 ───────────────────────── */

function sliceRegion(lines, startAnchor, endAnchor) {
  const s = lines.findIndex((l) => l.includes(startAnchor));
  if (s < 0) return null;
  let e = lines.length;
  if (endAnchor) {
    for (let i = s + 1; i < lines.length; i++) {
      if (lines[i].includes(endAnchor)) { e = i; break; }
    }
  }
  return lines.slice(s, e).join('\n');
}

/**
 * 去块注释 / 行注释 / 全部空白。
 *
 * 最后一步专门清「未闭合的块注释开头」：区段在「宿主桥」标题行被切开，
 * 而那个段标题注释的 `*​/` 正好落在切点之后，于是 `/* ═══` 会残留在末尾，
 * 被当成一个多余的顶层块（实测表现为凭空多出一条 `#58` 漂移）。
 */
function normalize(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1')
    .replace(/\/\*[\s\S]*$/, '')
    .replace(/\s+/g, '');
}

/**
 * 按**顶层**语句切块。
 *
 * 踩过的两个坑（都在这版修掉了）：
 *  1. 按正则切 → 函数体内部几十个 `var x` 也算顶层块，输出混进 `var sk`
 *     / `var hd` 这类噪声，真正的不一致反而被淹掉。
 *  2. 改深度计数但**没识别正则字面量** → `esc()` 里的 `/"/g` 被当成字符串起始
 *     引号，一进一出把该函数的收尾 `}` 吞掉，深度从此回不到 0，
 *     整份文件只切出 13 块（假失败）。
 *
 * 所以必须同时维护三套状态：括号深度、字符串/模板串、正则字面量。
 */
function splitTopLevel(src) {
  const out = [];
  let depth = 0;
  let start = 0;
  let quote = null;
  let pendingCut = -1;
  let prev = '';
  const REGEX_ALLOWED_AFTER = '(,=:[!&|?{};+-*%~^<>';

  for (let i = 0; i < src.length; i++) {
    const ch = src[i];

    if (quote) {
      if (ch === '\\') { i++; continue; }
      if (ch === quote) { quote = null; prev = ch; }
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') { quote = ch; continue; }

    /* 正则字面量只出现在「期望表达式」的位置；字符类 [...] 里的 / 不算结束。 */
    if (ch === '/' && (prev === '' || REGEX_ALLOWED_AFTER.indexOf(prev) >= 0)) {
      let inClass = false;
      for (let j = i + 1; j < src.length; j++) {
        const c = src[j];
        if (c === '\\') { j++; continue; }
        if (c === '[') { inClass = true; continue; }
        if (c === ']') { inClass = false; continue; }
        if (c === '/' && !inClass) { i = j; break; }
      }
      prev = '/';
      continue;
    }

    /* 函数声明末尾没有分号，靠「深度回到 0 的那个 }」收尾；紧跟分号则连分号一起收。 */
    if (pendingCut >= 0 && ch !== ';') {
      out.push(src.slice(start, pendingCut + 1));
      start = pendingCut + 1;
      pendingCut = -1;
    }

    if (ch === '{' || ch === '(' || ch === '[') depth++;
    else if (ch === '}') { depth--; if (depth === 0) pendingCut = i; }
    else if (ch === ')' || ch === ']') depth--;
    else if (ch === ';' && depth === 0) { out.push(src.slice(start, i + 1)); start = i + 1; pendingCut = -1; }

    prev = ch;
  }
  if (start < src.length) out.push(src.slice(start));
  return out.filter((s) => s.trim().length > 0);
}

/** 从一条顶层语句里取出稳定的块名。 */
function keyOf(stmt, idx) {
  let m = stmt.match(/^(?:var|let|const)([A-Za-z_$][\w$]*)=/);
  if (m) return m[1];
  m = stmt.match(/^function([A-Za-z_$][\w$]*)/);
  if (m) return m[1];
  m = stmt.match(/^LAYOUTS\.([A-Za-z_$][\w$]*)=/);
  if (m) return 'LAYOUTS.' + m[1];
  m = stmt.match(/^([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)=function/);
  if (m) return m[1] + '.' + m[2];
  return '#' + idx;
}

function blocksOf(src) {
  const map = new Map();
  splitTopLevel(normalize(src)).forEach((stmt, i) => map.set(keyOf(stmt, i), stmt));
  return map;
}

const sha = (s) => crypto.createHash('sha1').update(s).digest('hex').slice(0, 12);

/* ───────────────────────────── 主流程 ───────────────────────────── */

function main() {
  for (const f of [MODULE, CARD]) {
    if (!fs.existsSync(f)) { console.error('FAIL 找不到 ' + f); return 1; }
  }

  const modSrc = sliceRegion(fs.readFileSync(MODULE, 'utf8').split('\n'), START, null);
  const cardSrc = sliceRegion(fs.readFileSync(CARD, 'utf8').split('\n'), START, CARD_END);
  if (!modSrc) { console.error('FAIL zxwidget.js 里找不到锚点 "' + START + '"'); return 1; }
  if (!cardSrc) { console.error('FAIL zx_card_runtime.html 里找不到锚点 "' + START + '"'); return 1; }

  const a = blocksOf(modSrc);
  const b = blocksOf(cardSrc);

  /* 假绿防线：切不出块说明切块器已失效，此时「零差异」是假象。 */
  if (a.size < 20 || b.size < 20) {
    console.error('FAIL 切块失败（A=' + a.size + ' 段, B=' + b.size + ' 段）——切块器与源码形态不匹配，先修脚本。');
    return 1;
  }

  /* 当前实测的漂移块及其两侧哈希。 */
  const drift = {};
  const keys = new Set([...a.keys(), ...b.keys()]);
  for (const k of keys) {
    const ha = a.has(k) ? sha(a.get(k)) : null;
    const hb = b.has(k) ? sha(b.get(k)) : null;
    if (ha !== hb) drift[k] = { a: ha, b: hb, na: a.has(k) ? a.get(k).length : 0, nb: b.has(k) ? b.get(k).length : 0 };
  }

  if (BLESS) {
    const payload = {
      __note: 'zxwidget 两份编译器的已知漂移基线。由 zxwidget_parity_check.js --bless 生成。' +
        '这不是「允许的差异」，而是「尚未收敛、且已冻结」：两侧任一改动都会让校验 FAIL，' +
        '逼你同步另一边或再次 --bless（并说明为什么）。收敛掉一条就 --bless 一次。',
      module: 'app/src/main/assets/zx/zxwidget.js',
      card: 'app/src/main/assets/zx_card_runtime.html',
      total_blocks: { module: a.size, card: b.size },
      blocks: drift,
    };
    const tmp = BASELINE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(payload, null, 2) + '\n', 'utf8');
    fs.renameSync(tmp, BASELINE);
    console.log('已写入基线：' + path.relative(ROOT, BASELINE).replace(/\\/g, '/'));
    console.log('  顶层块 A=' + a.size + ' B=' + b.size + '，冻结漂移块 ' + Object.keys(drift).length + ' 个');
    const names = Object.keys(drift);
    console.log('  ' + (names.length ? names.join(', ') : '（无漂移，已完全同源）'));
    return 0;
  }

  console.log('zxwidget 同源比对');
  console.log('  A) zx/zxwidget.js        顶层块 ' + a.size + ' 个');
  console.log('  B) zx_card_runtime.html  顶层块 ' + b.size + ' 个');

  if (!fs.existsSync(BASELINE)) {
    console.log('');
    console.log('FAIL 基线文件不存在：' + path.relative(ROOT, BASELINE).replace(/\\/g, '/'));
    console.log('     首次使用请先跑一次 `node scripts/zx/zxwidget_parity_check.js --bless`');
    console.log('     把当前差异冻结成基线（并在提交信息里说明这是「记账」而不是「修好」）。');
    return 1;
  }

  const base = JSON.parse(fs.readFileSync(BASELINE, 'utf8')).blocks || {};
  const baseKeys = new Set(Object.keys(base));
  const driftKeys = new Set(Object.keys(drift));

  const brandNew = [...driftKeys].filter((k) => !baseKeys.has(k));
  const changed = [];
  for (const k of driftKeys) {
    if (!baseKeys.has(k)) continue;
    const o = base[k], n = drift[k];
    if (o.a !== n.a || o.b !== n.b) changed.push({ k, o, n });
  }
  const converged = [...baseKeys].filter((k) => !driftKeys.has(k));

  console.log('  基线冻结 ' + baseKeys.size + ' 条，本次实测漂移 ' + driftKeys.size + ' 条');
  console.log('');

  if (converged.length) {
    console.log('CONVERGED（好事：这些块两边已经一致了，请跑 --bless 把它们从基线里摘掉）');
    console.log('    · ' + converged.join(', '));
    console.log('');
  }

  if (!brandNew.length && !changed.length) {
    console.log('PASS 没有新增漂移，也没有基线块被改动（基线 ' + baseKeys.size + ' 条）');
    return 0;
  }

  console.log('FAIL zxwidget 两份拷贝又分叉了');
  if (brandNew.length) {
    console.log('  新增漂移（基线里没有，属于回归）：');
    for (const k of brandNew) {
      const d = drift[k];
      if (!d.na) console.log('    · ' + k + '  ← 只有 B 有（' + d.nb + ' 字符）');
      else if (!d.nb) console.log('    · ' + k + '  ← 只有 A 有（' + d.na + ' 字符）');
      else console.log('    · ' + k + '（A=' + d.na + ' B=' + d.nb + ' 字符）');
    }
  }
  if (changed.length) {
    console.log('  基线块内容被改动（改了一边就必须同步另一边）：');
    for (const { k, o, n } of changed) {
      const side = [];
      if (o.a !== n.a) side.push('A');
      if (o.b !== n.b) side.push('B');
      console.log('    · ' + k + '  ← ' + side.join(' 与 ') + ' 侧变了');
    }
  }
  console.log('');
  console.log('  处理办法：要么把另一边同步过来，要么跑 --bless 显式接受（并在提交信息里说明原因）。');
  return 1;
}

process.exit(main());

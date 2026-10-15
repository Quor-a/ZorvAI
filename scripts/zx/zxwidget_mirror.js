#!/usr/bin/env node
/* ═══════════════════════════════════════════════════════════════════════
   zxwidget 镜像：把 B（卡片运行时内联编译器）的编译器区段，
   覆盖写回 A（Web UI 外链脚本），保留 A 头部（注释 + zxInstallContracts）。

   为什么需要它：B 是运行时真身（被反复改），A 只是 B 的同源副本。
   每次改完 B 必须跑一次本脚本来同步 A，否则 zxwidget_parity_check 会 FAIL。

   区段：A 从首个 `var TXT_KEYS` 行起替换到 EOF；
         B 取 `var TXT_KEYS` 到首个 `宿主桥` 段标题之前（编译器，不含卡片 UI）。
   用法：node scripts/zx/zxwidget_mirror.js
   ═══════════════════════════════════════════════════════════════════════ */
'use strict';
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..', '..');
const A = path.join(ROOT, 'app/src/main/assets/zx/zxwidget.js');
const B = path.join(ROOT, 'app/src/main/assets/zx_card_runtime.html');

function findLine(lines, anchor, from) {
  for (let i = (from || 0); i < lines.length; i++) if (lines[i].includes(anchor)) return i;
  return -1;
}

const aLines = fs.readFileSync(A, 'utf8').split('\n');
const bLines = fs.readFileSync(B, 'utf8').split('\n');

const aTx = findLine(aLines, 'var TXT_KEYS');
const bTx = findLine(bLines, 'var TXT_KEYS');
let bEnd = findLine(bLines, '宿主桥', bTx); // 段标题行

if (aTx < 0 || bTx < 0 || bEnd < 0) {
  console.error('FAIL 锚点缺失 aTx=' + aTx + ' bTx=' + bTx + ' bEnd=' + bEnd);
  process.exit(1);
}

/* 段标题 `/* ═══ ... 6. 宿主桥 ... */` 的开头 `/*` 落在标题行之前一行，
   必须回退到那个 `/*` 行并把整段注释切掉——否则会把未闭合的 `/*` 带进 A，
   导致 A 语法错误（parity 脚本靠 normalize 兜住，镜像不能靠它）。 */
while (bEnd > bTx && !bLines[bEnd].trim().startsWith('/*')) bEnd--;

const aHead = aLines.slice(0, aTx);          // 头部 + zxInstallContracts
const bCompiler = bLines.slice(bTx, bEnd);   // B 编译器（不含卡片 UI）

const out = aHead.concat(bCompiler);
// 末尾保证单个换行
const tmp = A + '.tmp';
fs.writeFileSync(tmp, out.join('\n') + '\n', 'utf8');
fs.renameSync(tmp, A);

console.log('已镜像 B → A');
console.log('  A 头部保留 ' + aHead.length + ' 行（含 zxInstallContracts）');
console.log('  B 编译器区段 ' + bCompiler.length + ' 行（TXT_KEYS → 宿主桥 之前）');
console.log('  A 现共 ' + out.length + ' 行');

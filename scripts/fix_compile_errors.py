# -*- coding: utf-8 -*-
"""编译错误驱动的 i18n 局部修复。

编译错误精确给出了「哪一行在非 @Composable 上下文里调用了 stringResource」，
对这些行把 stringResource( 换成 qstr( 即可 —— qstr 在任何上下文都合法。
const val 初始化器不接受运行时调用，去掉 const 即可（这几处都是文案常量）。
"""
import io
import os
import re
import sys

SRC_ROOT = 'app/src/main/java/com/ai/assistance/quro'
ERR_FILE = sys.argv[1] if len(sys.argv) > 1 else '/tmp/err5.txt'

line_re = re.compile(r'^e: file:///(.+?):(\d+):(\d+) (.*)$')

targets = {}   # rel_path -> {'composable': set(lines), 'constval': set(lines)}
for raw in io.open(ERR_FILE, encoding='utf-8'):
    m = line_re.match(raw.rstrip('\n'))
    if not m:
        continue
    fpath, ln, col, msg = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)
    fpath = fpath.replace('%20', ' ').replace('%28', '(').replace('%29', ')')
    norm = fpath.replace('\\', '/')
    i = norm.find('/app/src/main/java/')
    if i < 0:
        continue
    rel = norm[i + len('/app/src/main/java/'):]
    if 'Composable' in msg or 'Try catch' in msg or 'runCatching call' in msg:
        targets.setdefault(rel, {}).setdefault('composable', set()).add(ln)
    elif "Const 'val' initializer" in msg:
        targets.setdefault(rel, {}).setdefault('constval', set()).add(ln)

print('待修文件数:', len(targets))
total = 0
for rel, kinds in sorted(targets.items()):
    p = os.path.join('app/src/main/java', rel)
    if not os.path.exists(p):
        print('  MISS', rel)
        continue
    src = io.open(p, encoding='utf-8').read()
    lines = src.split('\n')
    changed = 0

    # ① 非 Composable 上下文：整行 stringResource( -> qstr(
    for ln in sorted(kinds.get('composable', ())):
        if not (1 <= ln <= len(lines)):
            continue
        if 'stringResource(' in lines[ln - 1]:
            n = lines[ln - 1].count('stringResource(')
            lines[ln - 1] = lines[ln - 1].replace('stringResource(', 'qstr(')
            changed += n

    # ② const val：去掉 const（编译期常量不能取运行时串）
    for ln in sorted(kinds.get('constval', ())):
        if not (1 <= ln <= len(lines)):
            continue
        new_l = re.sub(r'\bconst\s+val\b', 'val', lines[ln - 1])
        if new_l != lines[ln - 1]:
            lines[ln - 1] = new_l
            changed += 1

    src = '\n'.join(lines)

    # 补 qstr / R 的 import
    pre = ''
    if 'qstr(' in src and 'import com.ai.assistance.quro.util.qstr' not in src \
            and 'package com.ai.assistance.quro.util' not in src:
        pre += 'import com.ai.assistance.quro.util.qstr\n'
    if 'R.string.' in src and 'import com.ai.assistance.quro.R' not in src:
        pre += 'import com.ai.assistance.quro.R\n'
    if pre:
        ls = src.split('\n')
        pkg_idx = next((i for i, l in enumerate(ls) if l.lstrip('\ufeff').startswith('package ')), -1)
        if pkg_idx >= 0:
            ls[pkg_idx] = ls[pkg_idx].lstrip('\ufeff')
            ls.insert(pkg_idx + 1, pre.strip())
        else:
            ls.insert(0, pre.strip())
        src = '\n'.join(ls)

    io.open(p, 'w', encoding='utf-8', newline='\n').write(src)
    print('  %-52s 修改 %d 处' % (rel, changed))
    total += changed

print('合计修改:', total)

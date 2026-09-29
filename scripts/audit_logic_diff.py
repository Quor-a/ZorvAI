# -*- coding: utf-8 -*-
"""分析 git diff：过滤掉「纯文案替换」，只输出真正改变了逻辑的行。

原理：把每行的字符串字面量内容与 qstr/stringResource 调用统一规范化成占位符，
再比较 `-` 行与 `+` 行。若规范化后一致，说明只是文案被抽成资源（安全）；
若不一致，说明控制流/表达式被改动，需要人工审查。
"""
import io
import re
import subprocess
import sys
from difflib import SequenceMatcher

CALL = re.compile(r'\b(qstr|stringResource)\s*\(\s*R\.string\.[A-Za-z0-9_]+')
STRLIT = re.compile(r'"(?:[^"\\]|\\.)*"')
RAWLIT = re.compile(r'"""(?:.|\n)*?"""')
CHARLIT = re.compile(r"'(?:[^'\\]|\\.)*'")


def norm(line):
    s = line.strip()
    s = CALL.sub('S', s)
    s = RAWLIT.sub('S', s)
    s = STRLIT.sub('S', s)
    s = CHARLIT.sub('C', s)
    s = re.sub(r'\s+', ' ', s)
    return s


def hunks_for(path):
    """返回 [(kind, line)]，kind in {'-', '+'}"""
    out = []
    p = subprocess.run(['git', 'diff', '-U0', '--', path],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    for ln in (p.stdout or '').split('\n'):
        if ln.startswith('---') or ln.startswith('+++') or ln.startswith('@@'):
            continue
        if ln.startswith('-'):
            out.append(('-', ln[1:]))
        elif ln.startswith('+'):
            out.append(('+', ln[1:]))
    return out


files = subprocess.run(['git', 'diff', '--name-only'],
                       capture_output=True, text=True, encoding='utf-8',
                       errors='replace').stdout.split('\n')
files = [f for f in files if f.endswith('.kt')]

total = 0
report = []
for f in files:
    pairs = hunks_for(f)
    D = [norm(l) for k, l in pairs if k == '-']
    A = [norm(l) for k, l in pairs if k == '+']
    if not D and not A:
        continue
    sm = SequenceMatcher(None, D, A, autojunk=False)
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == 'equal':
            continue
        # 规范化后仍不同 → 逻辑改动
        for k, l in pairs:
            pass
        # 需要原始行，重新取（保持与 D/A 同序）
    # 重新按原始顺序输出可疑行
    dlines = [l for k, l in pairs if k == '-']
    alines = [l for k, l in pairs if k == '+']
    sm2 = SequenceMatcher(None, D, A, autojunk=False)
    for tag, i1, i2, j1, j2 in sm2.get_opcodes():
        if tag == 'equal':
            continue
        for l in dlines[i1:i2]:
            if l.strip():
                report.append(('  -', f, l))
        for l in alines[j1:j2]:
            if l.strip():
                report.append(('  +', f, l))
        total += (i2 - i1) + (j2 - j1)

print('可疑（非纯文案）改动行数:', total)
cur = None
for mark, f, l in report:
    if f != cur:
        cur = f
        print('\n### ' + cur.replace('app/src/main/java/com/ai/assistance/quro/', ''))
    print(mark, l[:160])

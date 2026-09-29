# -*- coding: utf-8 -*-
"""列出「字符串字面量内」的 U+FFFD 上下文，供人工/规则修字。"""
import io
import sys
from collections import Counter

P = sys.argv[1] if len(sys.argv) > 1 else \
    'app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt'
src = io.open(P, encoding='utf-8').read()
n = len(src)
instr = [False] * n
BS, Q, NL = chr(92), chr(34), chr(10)
i = 0
while i < n:
    if src[i:i + 3] == Q * 3:
        j = src.find(Q * 3, i + 3)
        i = n if j < 0 else j + 3
        continue
    if src[i] == Q:
        j = i + 1
        while j < n:
            if src[j] == BS:
                j += 2
                continue
            if src[j] == Q:
                j += 1
                break
            if src[j] == NL:
                break
            j += 1
        for k in range(i, min(j, n)):
            instr[k] = True
        i = j
        continue
    if src[i:i + 2] == '//':
        j = src.find(NL, i)
        i = n if j < 0 else j
        continue
    if src[i:i + 2] == '/*':
        j = src.find('*/', i + 2)
        i = n if j < 0 else j + 2
        continue
    i += 1

ctxs = []
for idx, c in enumerate(src):
    if c == '\ufffd' and instr[idx]:
        a = max(0, idx - 10)
        b = min(n, idx + 4)
        ctxs.append(src[a:b].replace('\n', ' '))

print('字符串内 U+FFFD 共 %d 处，去重后 %d 种上下文' % (len(ctxs), len(set(ctxs))))
print('\n=== 按频次（≥2 次）===')
c = Counter(ctxs)
for ctx, k in c.most_common():
    if k >= 2:
        print('%3d  ...%s...' % (k, ctx))
print('\n=== 仅出现 1 次的 ===')
for ctx, k in c.most_common():
    if k == 1:
        print('  1  ...%s...' % ctx)

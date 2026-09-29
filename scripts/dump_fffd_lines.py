# -*- coding: utf-8 -*-
"""导出「字符串字面量内含 U+FFFD」的完整行（去重），供逐行修字。"""
import io
import sys

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

# 行内是否有「字符串内的乱码」
lines = src.split(NL)
pos = 0
hit = []
for idx, l in enumerate(lines):
    seg = slice(pos, pos + len(l))
    if any(instr[k] and src[k] == '\ufffd' for k in range(seg.start, seg.stop)):
        hit.append(idx + 1)
    pos += len(l) + 1
print('含乱码字符串的行数：%d' % len(hit))
print(','.join(str(x) for x in hit))

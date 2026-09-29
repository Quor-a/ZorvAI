# -*- coding: utf-8 -*-
import io
import sys

P = sys.argv[1] if len(sys.argv) > 1 else \
    'app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt'
src = io.open(P, encoding='utf-8').read()
n = len(src)
instr = [False] * n
BS = chr(92)
Q = chr(34)
NL = chr(10)
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

cnt = inlit = 0
for idx, c in enumerate(src):
    if c == '\ufffd':
        cnt += 1
        if instr[idx]:
            inlit += 1
print('总 U+FFFD %d，其中位于字符串字面量内 %d，注释/其它 %d' % (cnt, inlit, cnt - inlit))

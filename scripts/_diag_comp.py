# -*- coding: utf-8 -*-
"""诊断：给定 kt 文件 + 行:列，打印 IS_COMPOSABLE 判定与所属作用域区间。"""
import io
import sys
sys.path.insert(0, 'scripts')
import i18n_ctx as C

path, line, col = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
src = io.open(path, encoding='utf-8').read()

off = 0
for i, l in enumerate(src.split('\n')[:line - 1]):
    off += len(l) + 1
pos = off + col - 1

print('pos =', pos, '| char =', repr(src[pos:pos + 30]))

FUNS = C.fun_ranges(src)
NC = C.noncomposable_zones(src)
checker = C.make_composable_checker(src)
print('IS_COMPOSABLE =', checker(pos))

scopes = [(a, b, 'FUN comp=%s' % c) for (a, b, c) in FUNS] + [(a, b, 'NC') for (a, b) in NC]
ins = [s for s in scopes if s[0] <= pos <= s[1]]
ins.sort(key=lambda s: s[0])
for a, b, tag in ins[-6:]:
    print('  scope', a, b, tag, '| head=', repr(src[a:a + 60]))
print('-- 附近 NC 区间 --')
for a, b in NC:
    if abs(a - pos) < 400 or (a <= pos <= b):
        print('  NC', a, b, repr(src[a - 12:a + 40]))

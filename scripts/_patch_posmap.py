# -*- coding: utf-8 -*-
"""补丁：① 位置映射（new↔SRC）消除位置漂移；② add_imports 兼容 BOM。"""
import io

p = 'scripts/i18n_build.py'
s = io.open(p, encoding='utf-8').read()

# ---------- ① 位置映射 ----------
POSMAP = '''
# ---------- 位置映射：替换进行中的 new 位置 ←→ 原始 SRC 位置 ----------
class _PosMap:
    """维护「替换后的文本位置」到「原始文本位置」的映射。

    为什么必须有它：@Composable 判定依赖原始源码（SRC）上预计算的函数/作用域区间；
    而替换是逐条累积的 —— 每把一处中文换成 stringResource(...) / qstr(...) 都会让后续
    文本整体后移。之前直接把「替换中的位置」丢给基于 SRC 的判定器，偏移累积到数百字符后
    位置完全错位，判定结果随机化，于是出现大面积的
    "Composable invocations can only happen from the context of a @Composable function"。
    """

    def __init__(self):
        self.marks = []     # [(new_pos, cumulative_delta)]，按 new_pos 升序
        self.delta = 0

    def reset(self):
        self.marks = []
        self.delta = 0

    def add(self, new_pos, old_len, new_len):
        d = new_len - old_len
        if d == 0:
            return
        self.delta += d
        self.marks.append((new_pos, self.delta))

    def to_src(self, pos):
        lo, hi = 0, len(self.marks)
        while lo < hi:
            mid = (lo + hi) // 2
            if self.marks[mid][0] <= pos:
                lo = mid + 1
            else:
                hi = mid
        d = self.marks[lo - 1][1] if lo > 0 else 0
        return pos - d


PMAP = _PosMap()


def pick_expr(k, arg_str, pos):'''

s = s.replace('\ndef pick_expr(k, arg_str, pos):', POSMAP, 1)

# pick_expr 内部改用映射回原始位置
old_pick = '    if IS_COMPOSABLE(pos):'
assert old_pick in s, 'pick body anchor'
s = s.replace(old_pick,
              '    if IS_COMPOSABLE(PMAP.to_src(pos)):', 1)

# 三个替换闸门记录映射
N = 0
old_rec = '            c[0] += 1\n            return repl(m, expr)'
new_rec = ('            c[0] += 1\n'
           '            _r = repl(m, expr)\n'
           '            PMAP.add(m.start(), len(m.group(0)), len(_r))\n'
           '            return _r')
N += s.count(old_rec)
s = s.replace(old_rec, new_rec)

old_toast = """        gsr = 'qstr(R.string.%s%s)' % (k, arg_str)
        return m.group(1) + gsr + ','"""
assert old_toast in s, 'toast body anchor'
s = s.replace(old_toast, """        gsr = 'qstr(R.string.%s%s)' % (k, arg_str)
        _r = m.group(1) + gsr + ','
        PMAP.add(m.start(), len(m.group(0)), len(_r))
        return _r""", 1)

# 文件循环里重置
old_loop = '        src = open(p, encoding=\'utf-8\').read()\n        new = src'
assert old_loop in s, 'loop anchor'
s = s.replace(old_loop, '        src = open(p, encoding=\'utf-8\').read()\n'
                        '        PMAP.reset()\n        new = src', 1)

# ---------- ② add_imports 兼容 BOM ----------
old_imp = """        lines = src.splitlines()
        pkg_idx = next((i for i, l in enumerate(lines) if l.startswith('package ')), -1)
        insert_at = pkg_idx + 1 if pkg_idx >= 0 else 0
        lines.insert(insert_at, pre.strip())"""
assert old_imp in s, 'imports anchor'
s = s.replace(old_imp, """        lines = src.splitlines()
        # 注意：部分文件首行带 UTF-8 BOM（\\ufeff），startswith('package ') 会失配，
        # 导致 import 被插到文件最前、package 行被挤到第 3 行 —— 直接语法崩坏
        # （"imports are only allowed in the beginning of file"）。
        pkg_idx = next((i for i, l in enumerate(lines) if l.lstrip('\\ufeff').startswith('package ')), -1)
        if pkg_idx >= 0:
            lines[pkg_idx] = lines[pkg_idx].lstrip('\\ufeff')
            insert_at = pkg_idx + 1
        else:
            insert_at = 0
        lines.insert(insert_at, pre.strip())""", 1)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('posmap+import ok, guard record sites =', N)

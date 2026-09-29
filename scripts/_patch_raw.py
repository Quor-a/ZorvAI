# -*- coding: utf-8 -*-
"""修补 i18n_ctx.py / i18n_build.py：Kotlin 三引号原始字符串保护。"""
import io

Q3 = '"""'

# ---------- 1) i18n_ctx.py ----------
p = 'scripts/i18n_ctx.py'
s = io.open(p, encoding='utf-8').read()
anchor = '    j = i + 1\n    while j < n:\n'
i = s.index(anchor)
inject = (
    "    if src[i:i + 3] == '" + Q3 + "':\n"
    "        # Kotlin 三引号原始字符串：内部不做反斜杠转义，遇下一个三引号才结束。\n"
    "        # 必须整体跳过 —— 否则里面的 '{' / '}'（正则、SQL、多行文本）会让括号深度错位，\n"
    "        # 使函数体区间溢出到相邻的非 @Composable 函数（连锁产生上百条编译错误）。\n"
    "        k = src.find('" + Q3 + "', i + 3)\n"
    "        return n if k < 0 else k + 3\n"
)
s = s[:i] + inject + s[i:]
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ctx ok')

# ---------- 2) i18n_build.py ----------
p = 'scripts/i18n_build.py'
s = io.open(p, encoding='utf-8').read()

old_imp = 'from i18n_ctx import make_composable_checker, ctxvar_at, _is_comment'
assert old_imp in s, 'import anchor'
s = s.replace(old_imp, old_imp + ', _scan_string, _scan_char', 1)

RAW = '''
# ---------- Kotlin 三引号原始字符串保护 ----------
_RAW_CACHE = {}


def _compute_raw_ranges(s):
    """扫描所有 Kotlin 原始字符串（三引号 ... 三引号）的 [start, end) 区间。
    这些区间内的内容（Regex / SQL / 多行提示词）绝不能做字面量替换：一旦把
    ctx.getString(...) / stringResource(...) 插进去，就会生成语法崩坏的代码，
    导致函数体不闭合、后续顶层声明被吞进函数体，连锁产生数百条编译错误。"""
    n = len(s)
    out = []
    i = 0
    while i < n:
        c = s[i]
        if c == '/' and s[i + 1:i + 2] == '/':
            k = s.find('\\n', i)
            i = n if k < 0 else k + 1
            continue
        if c == '/' and s[i + 1:i + 2] == '*':
            k = s.find('*/', i + 2)
            i = n if k < 0 else k + 2
            continue
        if c == '"':
            if s[i:i + 3] == '"' * 3:
                k = s.find('"' * 3, i + 3)
                k = n if k < 0 else k + 3
                out.append((i, k))
                i = k
                continue
            i = _scan_string(s, i, n)
            continue
        if c == "'":
            i = _scan_char(s, i, n)
            continue
        i += 1
    return out


def raw_ranges(s):
    h = hash(s)
    v = _RAW_CACHE.get(h)
    if v is None:
        v = _compute_raw_ranges(s)
        if len(_RAW_CACHE) > 128:
            _RAW_CACHE.clear()
        _RAW_CACHE[h] = v
    return v


def in_raw(s, pos):
    for a, b in raw_ranges(s):
        if a <= pos < b:
            return True
        if a > pos:
            break
    return False


def do_replace(new, t, k):'''
anchor = '\ndef do_replace(new, t, k):'
assert anchor in s, 'do_replace anchor'
s = s.replace(anchor, RAW, 1)

# 快速早退
a2 = '    te = re.escape(t)\n    c = [0]'
assert a2 in s, 'early-exit anchor'
s = s.replace(a2, '    te = re.escape(t)\n    if t not in new:\n'
                  '        return new, 0          # 快速早退：省掉后续十来次全区扫描\n    c = [0]', 1)

# 三个闸门加 in_raw 保护
GUARD = ('    def %s(repl):\n        def f(m):\n'
         '            if _is_comment(new, m.start()):\n                return m.group(0)\n')
NEWG = ('    def %s(repl):\n        def f(m):\n'
        '            if _is_comment(new, m.start()):\n                return m.group(0)\n'
        '            if in_raw(new, m.start()):\n                return m.group(0)\n')
for fn in ('guarded', 'guarded_toast', 'guarded_generic'):
    g = GUARD % fn
    assert g in s, 'guard anchor: ' + fn
    s = s.replace(g, NEWG % fn, 1)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('build ok')

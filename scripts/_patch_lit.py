# -*- coding: utf-8 -*-
import io

p = 'scripts/i18n_build.py'
s = io.open(p, encoding='utf-8').read()

old = '''def raw_ranges(s):
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
    return False'''

assert old in s, 'anchor miss'

new = (
    'def lit_ranges(s):\n'
    '    h = hash(s)\n'
    '    v = _RAW_CACHE.get(h)\n'
    '    if v is None:\n'
    '        v = _compute_lit_ranges(s)\n'
    '        if len(_RAW_CACHE) > 128:\n'
    '            _RAW_CACHE.clear()\n'
    '        _RAW_CACHE[h] = v\n'
    '    return v\n'
    '\n'
    '\n'
    'def in_literal_body(s, pos):\n'
    '    # pos 是否落在某个字符串字面量的【内容】里（严格内部，不含起止引号）。\n'
    '    # 这是防止跨串误伤的关键护栏：源码里 Text("${a.ifBlank { "-" }} · ${b.size} 个共享服务")\n'
    '    # 这种嵌套串，若抽取阶段产出一条畸形条目，兜底模式会在 "-" 的【闭引号】处起匹配，\n'
    '    # 把跨串的一大段代码整段换成 qstr(...)，生成语法崩坏的代码（函数体不闭合、\n'
    '    # 后续顶层声明被吞进函数体），连锁产生数百条编译错误。\n'
    '    # 判定「匹配起点落在别的字面量内部」即可准确拦下这类误伤。\n'
    '    for a, b in lit_ranges(s):\n'
    '        if a < pos:\n'
    '            if pos < b - 1:\n'
    '                return True\n'
    '        else:\n'
    '            break\n'
    '    return False'
)

s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('lit ok')

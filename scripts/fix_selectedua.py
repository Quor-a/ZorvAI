# -*- coding: utf-8 -*-
"""补修：脚本执行顺序导致漏掉的「被比较变量的赋值」。"""
import io
import re

p = 'app/src/main/java/com/ai/assistance/quro/ui/QuroBrowserScreen.kt'
s = io.open(p, encoding='utf-8').read()
n = 0
before = s
s = s.replace('selectedUa = qstr(R.string.qk_00036)', 'selectedUa = "自定义"')
s = s.replace('mutableStateOf(qstr(R.string.qk_00850))', 'mutableStateOf("自动")')
n = before.count('selectedUa = qstr(R.string.qk_00036)') + before.count('mutableStateOf(qstr(R.string.qk_00850))')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('QuroBrowserScreen 补修:', n)

# ---- 修脚本自身顺序：④ 的变量收集必须在 ① 之前 ----
p2 = 'scripts/restore_data_strings.py'
t = io.open(p2, encoding='utf-8').read()
if 'cmp_vars_early' not in t:
    old = "        # ① 比较\n"
    assert old in t, 'order anchor'
    t = t.replace(old,
                  "        # ④ 需要用到「被比较的变量名」，必须在 ① 改写比较式之前收集\n"
                  "        cmp_vars_early = sorted(set(CMPVAR_RE.findall(src)))\n\n"
                  "        # ① 比较\n", 1)
    t = t.replace("        for v in sorted(set(CMPVAR_RE.findall(src))):",
                  "        for v in cmp_vars_early:", 1)
    io.open(p2, 'w', encoding='utf-8', newline='\n').write(t)
    print('restore 脚本顺序已修正')
else:
    print('restore 脚本顺序已是最新')

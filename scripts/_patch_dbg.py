# -*- coding: utf-8 -*-
"""补丁：① $var 不含点（"$stamp.md" 曾被解析成变量 stamp.md）；② const val 行跳过；
③ 单文件过滤 + 调试输出。"""
import io

p = 'scripts/i18n_build.py'
s = io.open(p, encoding='utf-8').read()

# ① $var 分支：Kotlin 里 $ 后只跟标识符，'.' 之后是字面量（"$stamp.md" == "$stamp" + ".md"）
old = "                m = re.match(r'[A-Za-z_][\\w]*(?:\\.[\\w?]+|\\[\\w+\\])*(?:!!|\\?)?', t[i + 1:])"
assert old in s, '$var anchor'
s = s.replace(old,
              "                # Kotlin 的 $x 只吃标识符；'.md' / '.size' 等是紧随其后的字面量。\n"
              "                # 旧正则把 '[\\.\\w]+' 一起吞了，于是 \"$stamp.md\" 变成变量 'stamp.md'\n"
              "                # → 生成 qstr(..., (stamp.md).toString()) → \"Unresolved reference 'md'\"。\n"
              "                m = re.match(r'[A-Za-z_]\\w*', t[i + 1:])", 1)

# ② const val 行跳过（编译期常量不能用运行时取串）
old_c = """def do_replace(new, t, k):"""
assert old_c in s, 'do_replace anchor'
s = s.replace(old_c, '''def _line_head(new, pos):
    ls = new.rfind('\\n', 0, pos) + 1
    return new[ls:pos]


def _on_const_val(new, pos):
    """pos 所在行是否为 `const val X = ...`。编译期常量初始化器不接受
    stringResource/qstr（"Const 'val' initializer must be a constant value"），必须跳过。"""
    return _line_head(new, pos).lstrip().startswith('const val')


def do_replace(new, t, k):''', 1)

# 三个闸门加 const val 判定
s = s.replace('            if in_literal_body(new, m.start()):\n                return m.group(0)',
              '            if in_literal_body(new, m.start()) or _on_const_val(new, m.start()):\n                return m.group(0)')
s = s.replace('        self.marks = []\n        self.delta = 0',
              '        self.marks = []\n        self.delta = 0', 1)

# ③ 单文件过滤 + 调试
old_loop = "for dp, _, fs in os.walk(ROOT):\n    for fn in fs:\n        if not fn.endswith('.kt'):\n            continue"
assert old_loop in s, 'walk anchor'
s = s.replace(old_loop, """_ONLY = os.environ.get('I18N_ONLY', '')
_DBG = os.environ.get('I18N_DEBUG', '')

for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if not fn.endswith('.kt'):
            continue""", 1)

old_read = "        p = os.path.join(dp, fn)\n        src = open(p, encoding='utf-8').read()"
assert old_read in s, 'read anchor'
s = s.replace(old_read, "        p = os.path.join(dp, fn)\n"
                        "        if _ONLY and _ONLY not in p.replace('\\\\', '/'):\n"
                        "            continue\n"
                        "        src = open(p, encoding='utf-8').read()", 1)

# pick_expr 调试
old_pick = '    if IS_COMPOSABLE(PMAP.to_src(pos)):'
assert old_pick in s, 'pick anchor'
s = s.replace(old_pick, """    _sp = PMAP.to_src(pos)
    _c = IS_COMPOSABLE(_sp)
    if _DBG:
        print('  [pick] k=%s new_pos=%d src_pos=%d comp=%s | %r'
              % (k, pos, _sp, _c, SRC[max(0, _sp - 40):_sp + 20]), file=sys.stderr)
    if _c:""", 1)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')

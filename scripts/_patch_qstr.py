# -*- coding: utf-8 -*-
"""补丁：① Application 初始化 i18n 运行时；② 判定器修正；③ build 脚本改用 qstr + 字面量护栏。"""
import io

# ---------- 1) QuroApplication 初始化 ----------
p = 'app/src/main/java/com/ai/assistance/quro/activity/QuroApplication.kt'
s = io.open(p, encoding='utf-8').read()
if 'QuroI18nRt.appContext' not in s:
    a = '        super.onCreate()\n'
    assert a in s, 'onCreate anchor'
    s = s.replace(a, a +
                  '\n        // 非 @Composable 位置的取串运行时（工具类 / 回调 / 协程里的界面文案）依赖它。\n'
                  '        com.ai.assistance.quro.util.QuroI18nRt.appContext = applicationContext\n', 1)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
    print('app init ok')
else:
    print('app init skip')

# ---------- 2) i18n_ctx.py：remember 等 lambda 是 @DisallowComposableCalls，必须算非 Composable ----------
p = 'scripts/i18n_ctx.py'
s = io.open(p, encoding='utf-8').read()
s = s.replace(
    '# 注意：remember / derivedStateOf / produceState 的 lambda 本身是 Composable，绝不能标记为「非 Composable」，\n'
    '# 否则 remember 体内合法的 Text(...) 等会被误判而错误降级。',
    '# 注意：remember / rememberSaveable / derivedStateOf / produceState 的 lambda 是\n'
    '# @DisallowComposableCalls（calculation 参数），内部【不能】调用 stringResource ——\n'
    '# 项目里大量 `remember { XxxModel(name = "...") }` 就栽在这里，必须算作非 Composable 作用域。', 1)

# 把 remember 系列插进 NC_TRIGGER 的触发词表
old_words = 'ifBlank|ifEmpty|getOrElse|getOrNull|onSuccess|onFailure'
assert old_words in s, 'NC words anchor'
s = s.replace(old_words,
              'remember|rememberSaveable|derivedStateOf|produceState|'
              'ifBlank|ifEmpty|getOrElse|getOrNull|onSuccess|onFailure', 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ctx ok')

# ---------- 3) i18n_build.py ----------
p = 'scripts/i18n_build.py'
s = io.open(p, encoding='utf-8').read()

# 3a) pick_expr：非 Composable 一律走全局 qstr()
old = """    cv = ctxvar_at(SRC, pos) if SRC else None
    if cv:
        return '%s.getString(R.string.%s%s)' % (cv, k, arg_str)
    return None"""
assert old, 'pick anchor'
if old in s:
    s = s.replace(old, """    # 非 @Composable：统一走全局 qstr()（QuroI18nRt 持有 ApplicationContext）。
    # 不再推断「此处是否可见 Context 变量」—— 那种推断在真实工程里误判率极高
    # （ctx / getString 未解析、context(...) 被当函数调用等），代价远高于收益。
    return 'qstr(R.string.%s%s)' % (k, arg_str)""", 1)
else:
    # 兼容另一种写法
    import re as _re
    m = _re.search(r"\n    cv = ctxvar_at\(SRC, pos\).*?\n    return None\n", s, _re.S)
    assert m, 'pick anchor(2)'
    s = s[:m.start()] + "\n    return 'qstr(R.string.%s%s)' % (k, arg_str)\n" + s[m.end():]

# 3b) Toast：用 qstr 生成 String 作为 Toast 文本参数
old_t = """        uctx = m.group(2).strip()
        if not uctx:
            return m.group(0)
        gsr = '%s.getString(R.string.%s%s)' % (uctx, k, arg_str)
        return m.group(1) + gsr + ','"""
assert old_t in s, 'toast anchor'
s = s.replace(old_t, """        gsr = 'qstr(R.string.%s%s)' % (k, arg_str)
        return m.group(1) + gsr + ','""", 1)

# 3c) add_imports：补 qstr 的 import
old_i = """    need_r = 'R.string.' in src and 'import com.ai.assistance.quro.R' not in src
    pre = ''"""
assert old_i in s, 'import anchor'
s = s.replace(old_i, """    need_r = 'R.string.' in src and 'import com.ai.assistance.quro.R' not in src
    need_q = 'qstr(' in src and 'import com.ai.assistance.quro.util.qstr' not in src \\
             and 'package com.ai.assistance.quro.util' not in src
    pre = ''""", 1)

old_i2 = """    if need_r:
        pre += 'import com.ai.assistance.quro.R\\n'"""
assert old_i2 in s, 'import anchor2'
s = s.replace(old_i2, """    if need_r:
        pre += 'import com.ai.assistance.quro.R\\n'
    if need_q:
        pre += 'import com.ai.assistance.quro.util.qstr\\n'""", 1)

# 3d) 字面量区间护栏：把 raw_ranges/in_raw 升级为 lit_ranges/in_literal_body
old_fn = '_compute_raw_ranges(s):'
s = s.replace(old_fn, '_compute_lit_ranges(s):', 1)
s = s.replace('''        if c == '"':
            if s[i:i + 3] == '"' * 3:
                k = s.find('"' * 3, i + 3)
                k = n if k < 0 else k + 3
                out.append((i, k))
                i = k
                continue
            i = _scan_string(s, i, n)
            continue''', '''        if c == '"':
            if s[i:i + 3] == '"' * 3:
                k = s.find('"' * 3, i + 3)
                k = n if k < 0 else k + 3
                out.append((i, k))
                i = k
                continue
            j = _scan_string(s, i, n)
            out.append((i, j))
            i = j
            continue''', 1)

s = s.replace('''def raw_ranges(s):
    h = hash(s)
    v = _RAW_CACHE.get(h)
    if v is None:
        v = _compute_lit_ranges(s)
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
    return False''', '''def lit_ranges(s):
    h = hash(s)
    v = _RAW_CACHE.get(h)
    if v is None:
        v = _compute_lit_ranges(s)
        if len(_RAW_CACHE) > 128:
            _RAW_CACHE.clear()
        _RAW_CACHE[h] = v
    return v


def in_literal_body(s, pos):
    """pos 是否落在【某个字符串字面量的内容里】（严格内部，不含起止引号）。
    这是防止「跨串误伤」的关键护栏：例如源码里的
        Text("${a.ifBlank { "-" }} · ${b.size} 个共享服务")
    中存在一条畸形抽取条目 t = ` }} · ${b.size} 个共享服务`，兜底模式
    `(["\\'])te\\1` 会在 `"-"` 的【闭引号】处起匹配，把跨串的一大段代码整段换成
    qstr(...)，从而生成语法崩坏的代码（函数体不闭合 → 后续声明被吞进函数体 →
    连锁数百条编译错误）。判定「匹配起点落在别的字面量内部」即可准确拦下这类误伤。"""
    for a, b in lit_ranges(s):
        if a < pos:
            if pos < b - 1:
                return True
        else:
            break
    return False''', 1)

# 闸门改用 in_literal_body
s = s.replace('            if in_raw(new, m.start()):\n                return m.group(0)',
              '            if in_literal_body(new, m.start()):\n                return m.group(0)')

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('build ok')

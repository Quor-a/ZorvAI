# -*- coding: utf-8 -*-
"""修正 % 转义：正则误判 + zh 提前 return 绕过。

两个 bug：
 ① `_FORMAT_SPEC_RE` 把 flags 类写进了空格，于是 `100% same` 里的 `% s` 被判成
    「空格 flag + 转换符 s」= 语法合法 → 不转义。而这恰恰是 Java 运行期
    failMismatch 的「非法 flag」组合（`%s` 不允许前导空格）。
    修：白名单收紧为「只承认本项目流水线真正会生成的规格」——`%%` 与 `%[N$]s`。
 ② `resolve_value` 的 zh 分支是提前 `return`，导致中文/中文镜像从不经过转义
    （`%1$s% 下载中…` 这个崩溃就是这么漏掉的）。
"""
import io, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, 'i18n_build.py')
AUDIT = os.path.join(HERE, 'audit_format_crash.py')
fail = []


def patch_build():
    s = io.open(BUILD, encoding='utf-8').read()
    old_re = ("# 格式规格：%[argIndex$][flags][width][.precision]conversion\n"
              "_FORMAT_SPEC_RE = re.compile(r'%(?:\\d+\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?[bBhHsScCdoxXeEfgGaAn%]')")
    new_re = ("# 「合法格式规格」白名单：只承认本流水线真正会产出的两种 ——\n"
              "#   %%       字面百分号\n"
              "#   %[N$]s   字符串占位（N 为参数序号）\n"
              "# 其余一律视为「裸 %」并转义。**不能**把 flags/width 写得宽松：那样\n"
              "# `100% same` 里的 `% s` 会被判成「空格 flag + 转换符 s」而漏网，\n"
              "# 运行期 String.format 照样 failMismatch 崩溃。\n"
              "_FORMAT_SPEC_RE = re.compile(r'%(?:\\d+\\$)?s')")
    if old_re in s:
        s = s.replace(old_re, new_re, 1)
    elif "re.compile(r'%(?:\\d+\\$)?s')" in s:
        print('  [skip] 白名单已收紧')
    else:
        fail.append('i18n_build.py: 未找到 _FORMAT_SPEC_RE')

    old_fn = """def resolve_value(t, lang):
    \"\"\"返回某语言下该串的资源文本。zh=中文；en=英文；其他=本语言翻译，缺则英文兜底。\"\"\"
    if lang == 'zh':
        if has_kotlin_tpl(t):
            ct = convert_template(t)
            return ct[0] if ct else t
        return t
    if lang == 'en':
        v = EN.get(t, t)
    else:
        v = LANG_TRANS.get(lang, {}).get(t) or EN.get(t, t)  # 英文兜底（绝不放中文）
    # 显式中文目录(values-zh)走 zh 分支；其余语言一律把残留模板转成定位占位符
    if has_kotlin_tpl(v):
        v = dollar_to_positional(v)
    # 源码一定会带参调用（中文原文含 Kotlin 模板）→ 裸 % 必须转义成 %%，
    # 否则 String.format 会把 `% ` / `%下` 之类当成非法规格直接抛异常崩溃。
    if has_kotlin_tpl(t):
        v = escape_stray_percent(v)
    return v"""
    new_fn = """def resolve_value(t, lang):
    \"\"\"返回某语言下该串的资源文本。zh=中文；en=英文；其他=本语言翻译，缺则英文兜底。\"\"\"
    if lang == 'zh':
        if has_kotlin_tpl(t):
            ct = convert_template(t)
            v = ct[0] if ct else t
        else:
            v = t
    else:
        if lang == 'en':
            v = EN.get(t, t)
        else:
            v = LANG_TRANS.get(lang, {}).get(t) or EN.get(t, t)  # 英文兜底（绝不放中文）
        # 显式中文目录(values-zh)走 zh 分支；其余语言一律把残留模板转成定位占位符
        if has_kotlin_tpl(v):
            v = dollar_to_positional(v)
    # 源码一定会带参调用（中文原文含 Kotlin 模板）→ 裸 % 必须转义成 %%，否则
    # String.format 会把 `% ` / `%下` 当成非法规格直接抛异常崩溃。
    # 注意：这一步必须在所有分支（含 zh）之后统一执行，zh 提前 return 曾漏掉此转义。
    if has_kotlin_tpl(t):
        v = escape_stray_percent(v)
    return v"""
    if old_fn in s:
        s = s.replace(old_fn, new_fn, 1)
    elif '这一步必须在所有分支（含 zh）之后统一执行' in s:
        print('  [skip] resolve_value 已统一收尾')
    else:
        fail.append('i18n_build.py: 未找到 resolve_value 原体')

    io.open(BUILD, 'w', encoding='utf-8', newline='\n').write(s)
    print('  ok i18n_build.py 已修白名单 + zh 分支')


def patch_audit():
    s = io.open(AUDIT, encoding='utf-8').read()
    if 'LEGAL_SPEC' in s:
        print('  [skip] audit 已用同一白名单')
        return
    old = s[s.index('# Java Formatter 规格'):s.index('def scan_used_with_args')]
    new = '''# 「合法格式规格」白名单（与 i18n_build.escape_stray_percent 同一口径）：
#   %%       字面百分号
#   %[N$]s   字符串占位
# 其余任何 %（含 `100% same` 的 `% s`、`%1$s% 下载` 的尾部裸 %）都会让
# Resources.getString(id, *args) 里的 String.format 抛异常 → 真机崩溃。
LEGAL_SPEC = re.compile(r'%%|%(?:\\d+\\$)?s')


'''
    s = s.replace(old, new, 1)

    old_fn = s[s.index('def check_value(v):'):s.index('STRAY_PCT = re.compile')]
    new_fn = '''def check_value(v):
    """返回错误描述列表（空 = 合法）。"""
    errs = []
    i = 0
    n = len(v)
    while i < n:
        if v[i] != '%':
            i += 1
            continue
        m = LEGAL_SPEC.match(v, i)
        if m:
            i = m.end()
            continue
        errs.append('裸 %% / 非法规格（%r）' % v[i:i + 8].replace('\\n', '\\\\n'))
        i += 1
    return errs


'''
    s = s.replace(old_fn, new_fn, 1)
    io.open(AUDIT, 'w', encoding='utf-8', newline='\n').write(s)
    print('  ok audit_format_crash.py 已对齐白名单')


patch_build()
patch_audit()
print('FAILED:' if fail else 'ALL OK')
for f in fail:
    print('  !!', f)
sys.exit(1 if fail else 0)

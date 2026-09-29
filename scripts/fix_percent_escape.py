# -*- coding: utf-8 -*-
"""修「带参格式串里的裸 % 导致真机崩溃」——在 i18n 流水线源头做 % 转义。

真机崩溃（用户在 MCP 设置页踩到）：
  java.util.FormatFlagsConversionMismatchException: Conversion = s, Flags =
  at java.util.Formatter$FormatSpecifier.failMismatch
  at android.content.res.Resources.getString
  at QuroMcpSettingsScreenKt.QuroMcpSettingsScreen(QuroMcpSettingsScreen.kt:117)

根因：`Resources.getString(id, *args)` **无条件执行 String.format**（与 xml 里
formatted="false" 毫无关系）。所以只要「带参调用」的文案里出现裸 %，例如
  zh  : 工具：与 AI 对话内工具 100% 同源（共 %1$s 个）
  en  : Tools: 100% same source as in-chat AI tools (total %1$s)
Java 会把 `% `（百分号+空格）解析成「空格 flag + 转换符」，直接抛异常崩溃。
另有 qk_02223 = `%1$s% 下载中…` 同类。

修法：只要该条目的**中文原文含 Kotlin 模板**（= 源码一定会带参调用），
就对该语言最终值里「不属于格式规格的 %」统一转义成 %%。
"""
import io, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, 'i18n_build.py')

HELPER = '''

# 格式规格：%[argIndex$][flags][width][.precision]conversion
_FORMAT_SPEC_RE = re.compile(r'%(?:\\d+\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?[bBhHsScCdoxXeEfgGaAn%]')


def escape_stray_percent(s):
    """把「不属于格式规格」的裸 % 转义为 %%。

    为什么必须做：`Resources.getString(id, *args)` 无条件执行 String.format，
    与外层 xml 的 formatted="false" 无关。中文原文 `100% 同源（共 %1$s 个）` 里的
    `% ` 会被 Java 当成「空格 flag + 转换符 s」→ FormatFlagsConversionMismatchException
    崩溃（QuroMcpSettingsScreen 的 MCP 页真机崩溃即此）。
    只对「源码会带参调用」的条目调用本函数（否则 %% 会原样显示成两个百分号）。
    """
    out = []
    i = 0
    n = len(s)
    while i < n:
        if s[i] != '%':
            out.append(s[i]); i += 1; continue
        if s[i:i + 2] == '%%':
            out.append('%%'); i += 2; continue
        m = _FORMAT_SPEC_RE.match(s, i)
        if m is None:
            out.append('%%'); i += 1; continue
        out.append(m.group(0)); i = m.end()
    return ''.join(out)

'''


def main():
    s = io.open(BUILD, encoding='utf-8').read()
    if 'escape_stray_percent' in s:
        print('  [skip] 已含 escape_stray_percent')
        return 0

    # ① 插入 helper（放在 resolve_value 定义之前）
    anchor = "def resolve_value(t, lang):"
    if anchor not in s:
        print('  !! 未找到 resolve_value 锚点'); return 1
    s = s.replace(anchor, HELPER.strip('\n') + '\n\n\n' + anchor, 1)

    # ② 改写 resolve_value 的收尾：先转定位占位符，再对「带参调用」的条目转义裸 %
    old_tail = ("    # 显式中文目录(values-zh)走 zh 分支；其余语言一律把残留模板转成定位占位符\n"
                "    return dollar_to_positional(v) if has_kotlin_tpl(v) else v")
    new_tail = ("""    # 显式中文目录(values-zh)走 zh 分支；其余语言一律把残留模板转成定位占位符
    if has_kotlin_tpl(v):
        v = dollar_to_positional(v)
    # 源码一定会带参调用（中文原文含 Kotlin 模板）→ 裸 % 必须转义成 %%，
    # 否则 String.format 会把 `% ` / `%下` 之类当成非法规格直接抛异常崩溃。
    if has_kotlin_tpl(t):
        v = escape_stray_percent(v)
    return v""")
    if old_tail not in s:
        print('  !! 未找到 resolve_value 收尾段'); return 1
    s = s.replace(old_tail, new_tail, 1)

    io.open(BUILD, 'w', encoding='utf-8', newline='\n').write(s)
    print('  ok i18n_build.py 已加入裸 % 转义（escape_stray_percent）')
    return 0


sys.exit(main())

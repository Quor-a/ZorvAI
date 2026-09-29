# -*- coding: utf-8 -*-
"""找出「UI 文件里仍未接入 i18n 的中文字面量」。

判据（三道过滤，宁缺毋滥）：
 1) 只扫已具备 i18n 能力的文件：文件里出现过 qstr( / stringResource( / R.string. ，
    或位于 ui/ 、 genui/aiapp/ui/ 目录下 —— 这些文件的文案本该走资源。
 2) 字符串体跳过三引号原始串、行注释、块注释、字符字面量。
 3) 已在 qstr(...) / stringResource(...) 调用内的串不算（那是资源键，不是泄漏）。
    含 $ 插值的串也算（要转 %N$s），但会把表达式单独列出来提醒。

输出：scripts/ui_left.txt（按 文件:行 明细 + 去重清单）
"""
import os, re, json, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = 'app/src/main/java'
CJK = re.compile(r'[\u4e00-\u9fff]')

# 已有词表：这些文本已在 i18n_strings.json 里，只是源码中还有硬编码残留
KNOWN = set()
_p = os.path.join(HERE, 'i18n_strings.json')
if os.path.exists(_p):
    KNOWN = {e['text'] for e in json.load(open(_p, encoding='utf-8'))}

UI_DIRS = ('/ui/', '/genui/aiapp/ui/', '/build/', '/kaleidobox/')

# 明确不是「界面文案」的目录/文件（AI 提示词、工具规范、数据表、shell 命令）
SKIP_PATH_PARTS = (
    '/core/tools/',            # 工具描述 = 给 AI 的提示词
    '/core/QuroAssistant.kt',  # 系统提示词
    '/i18n/',                  # 语言基础设施自身
)


def scan(path):
    """返回 [(line_no, literal, is_template)]"""
    src = open(path, encoding='utf-8', errors='ignore').read()
    n = len(src)
    i, line = 0, 1
    out = []
    while i < n:
        c = src[i]
        if c == '\n':
            line += 1
            i += 1
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            if j < 0:
                i = n
            else:
                line += src.count('\n', i, j + 2)
                i = j + 2
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            if j < 0:
                i = n
            else:
                line += src.count('\n', i, j + 3)
                i = j + 3
            continue
        if c == "'":
            # 字符字面量 'x' 或 '\n'
            j = src.find("'", i + 1)
            if j > 0 and j - i <= 3:
                i = j + 1
                continue
            i += 1
            continue
        if c == '"':
            start_line = line
            j = i + 1
            buf = []
            while j < n and src[j] != '"':
                if src[j] == '\\':
                    buf.append(src[j + 1] if j + 1 < n else '')
                    j += 2
                    continue
                if src[j] == '\n':
                    line += 1
                buf.append(src[j])
                j += 1
            lit = ''.join(buf)
            if CJK.search(lit):
                # 前 30 字符判定是否已在取串调用里
                pre = src[max(0, i - 30):i]
                if not re.search(r'(qstr|stringResource|R\.string\.)\s*\(\s*$', pre):
                    out.append((start_line, lit, '$' in lit))
            i = j + 1
            continue
        i += 1
    return out


files = []
for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if fn.endswith('.kt'):
            files.append(os.path.join(dp, fn).replace('\\', '/'))

rows = []
uniq = {}
for p in files:
    if any(s in p for s in SKIP_PATH_PARTS):
        continue
    try:
        src = open(p, encoding='utf-8', errors='ignore').read()
    except Exception:
        continue
    has_i18n = ('qstr(' in src or 'stringResource(' in src) or any(d in p for d in UI_DIRS)
    if not has_i18n:
        continue
    for ln, lit, is_tpl in scan(p):
        rel = os.path.relpath(p, ROOT).replace('\\', '/')
        rows.append((rel, ln, lit, is_tpl))
        u = uniq.setdefault(lit, [0, KNOWN and lit in KNOWN])
        u[0] += 1

rows.sort()
with open(os.path.join(HERE, 'ui_left.txt'), 'w', encoding='utf-8') as f:
    f.write('=== 明细（%d 处）===\n' % len(rows))
    for rel, ln, lit, is_tpl in rows:
        f.write('%s:%d\t%s%s\n' % (rel, ln, lit, '\t<TPL>' if is_tpl else ''))

newly = [(t, v[0]) for t, v in uniq.items() if not v[1]]
known = [(t, v[0]) for t, v in uniq.items() if v[1]]
print('待处理文件数        :', len({r[0] for r in rows}))
print('中文字面量总处数    :', len(rows))
print('去重 unique         :', len(uniq))
print('  ├ 已在 i18n 词表(需回接):', len(known), '条 /', sum(v for _, v in known), '处')
print('  └ 词表外(全新待翻译)   :', len(newly), '条 /', sum(v for _, v in newly), '处')
print('明细已写入 scripts/ui_left.txt')

print('\n--- 词表外条目 TOP 40 ---')
for t, c in sorted(newly, key=lambda kv: -kv[1])[:40]:
    print('%4d  %s' % (c, t[:70]))

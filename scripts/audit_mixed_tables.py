# -*- coding: utf-8 -*-
"""找出「关键词表 / 数据表」里被 i18n 化的条目。

判定：一个集合构造（listOf/setOf/arrayOf/mapOf…）内部**同时**存在
  ① 中文字面量（说明它是中文关键词/数据表，不是纯展示）
  ② qstr(R.string.x) / stringResource(R.string.x)（说明有条目被翻译走了）
此时被翻译的那条几乎必然是数据 —— 一旦随语言变化，中文输入就配不上，功能静默失效。
"""
import glob
import io
import re
import sys

sys.path.insert(0, 'scripts')
from bad_keys import scan as _scan  # noqa: E402

OPEN = re.compile(r'\b(listOf|setOf|arrayOf|mutableListOf|mutableSetOf|mapOf)\s*\(')
CJK = re.compile(r'[\u4e00-\u9fff]')
REF = re.compile(r'(?:qstr|stringResource)\(\s*R\.string\.(\w+)')
LIT = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def load_qmap():
    m = {}
    for p in glob.glob('app/src/main/res/values/*.xml'):
        src = io.open(p, encoding='utf-8').read()
        for mm in re.finditer(r'<string name="([\w.]+)"[^>]*>(.*?)</string>', src, re.S):
            m[mm.group(1)] = mm.group(2)
    return m


def skip_literal(s, i):
    """从 s[i]（引号或三引号）跳到字面量结束。"""
    if s[i:i + 3] == '"""':
        j = s.find('"""', i + 3)
        return len(s) if j < 0 else j + 3
    j = i + 1
    while j < len(s):
        if s[j] == '\\':
            j += 2
            continue
        if s[j] == '"':
            return j + 1
        if s[j] == '\n':
            return j
        j += 1
    return j


def match(s, i):
    depth, j = 0, i
    n = len(s)
    while j < n:
        c = s[j]
        if c == '"':
            j = skip_literal(s, j)
            continue
        if c == '/' and s[j:j + 2] in ('//', '/*'):
            if s[j:j + 2] == '//':
                k = s.find('\n', j)
                j = n if k < 0 else k
            else:
                k = s.find('*/', j + 2)
                j = n if k < 0 else k + 2
            continue
        if c == '(' or c == '{' or c == '[':
            depth += 1
        elif c == ')' or c == '}' or c == ']':
            depth -= 1
            if depth == 0:
                return j
        j += 1
    return -1


def main():
    qmap = load_qmap()
    hits = []
    for p in glob.glob('app/src/main/java/**/*.kt', recursive=True):
        src = io.open(p, encoding='utf-8').read()
        for m in OPEN.finditer(src):
            end = match(src, m.end() - 1)
            if end < 0:
                continue
            body = src[m.end():end]
            refs = REF.findall(body)
            if not refs:
                continue
            lits = [x for x in LIT.findall(body)]
            zh_lits = [x for x in lits if CJK.search(x) and len(x) >= 2]
            # 表里全是 qstr 而无中文字面量 → 纯展示型（如 tabs），跳过
            if not zh_lits:
                continue
            line = src[:m.start()].count('\n') + 1
            hits.append((p, line, m.group(1), len(refs), len(zh_lits),
                         [qmap.get(r, '?' + r) for r in refs],
                         zh_lits[:6]))

    print('=== 混排集合（中文硬编码 + 取串）共 %d 处 ===' % len(hits))
    for p, ln, kind, nr, nz, vals, zh in hits:
        print('\n%s:%d  %s(…%d 条取串 / %d 条中文)'
              % (p.replace('app/src/main/java/com/ai/assistance/quro/', ''), ln, kind, nr, nz))
        print('   取串值(应恢复): %s' % ' | '.join(v[:26] for v in vals))
        print('   同表中文  : %s' % ' | '.join(z[:16] for z in zh))


if __name__ == '__main__':
    main()

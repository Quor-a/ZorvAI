# -*- coding: utf-8 -*-
"""精准恢复「数据性字符串」（只处理确定属于数据的用法，避免误伤显示文案）。

判定依据：
  ① 比较表达式：    x == qstr(...) / x != qstr(...)             —— 数据
  ② when 分支匹配值： when (x) { qstr(...) -> ... }              —— 数据
  ③ 数据表：变量名含 MARKERS/WORDS/KEYS/PATTERNS/BAD/TRIGGERS…
     的 listOf/setOf/arrayOf 内部                               —— 内置匹配词表
  ④ 被比较变量的赋值：同文件里若 v 参与了 ①/②，则 v = qstr(...)   —— 状态值本身是数据
     （典型：selectedUa 存的是 UA_PRESETS 的键，翻译后 find{} 永远匹配不到）
不放宽到「所有含中文的 listOf」—— 权限声明/用户协议那类 listOf 是给人看的显示文本，
必须保持 i18n 化。
"""
import io
import os
import re
import sys
import xml.etree.ElementTree as ET

RES = 'app/src/main/res/values/strings_i18n.xml'
ROOT = 'app/src/main/java'

tree = ET.parse(RES)
rev = {}
for el in tree.getroot():
    n = el.get('name')
    if n:
        rev[n] = el.text or ''

CALLRE = re.compile(r'\b(qstr|stringResource)\s*\(\s*R\.string\.(qk_\d+)\s*\)')
CMP_RE = re.compile(r'([=!]=\s*)(\b(qstr|stringResource)\s*\(\s*R\.string\.(qk_\d+)\s*\))')
WHEN_RE = re.compile(r'^([ \t]*)(\b(qstr|stringResource)\s*\(\s*R\.string\.(qk_\d+)\s*\))(\s*->)', re.M)
CMPVAR_RE = re.compile(r'(\w+)\s*[=!]=\s*(?:qstr|stringResource)\s*\(')
# 字符串匹配函数：contains / startsWith / equals …(qstr(...))  —— 也是数据用法
STRFUN_RE = re.compile(
    r'\b(startsWith|endsWith|contains|indexOf|lastIndexOf|equals|matches|compareTo|'
    r'startsWithIgnoreCase|equalsIgnoreCase)\s*\(\s*'
    r'(qstr|stringResource)\s*\(\s*R\.string\.(qk_\d+)\s*\)\s*\)')
DATATBL_HEAD = re.compile(
    r'([A-Z_a-z][\w]*)\s*(?::[^=\n]*)?=\s*(listOf|setOf|arrayOf|mutableListOf|mutableSetOf)\s*\(')
DATATBL_NAME = re.compile(r'(MARKERS?|WORDS?|KEYS?|PATTERNS?|BAD|STOP|TRIGGERS?|ALIASES?|TAGS?|'
                          r'BLACKLIST|WHITELIST|DENY|ALLOW|SUFFIXES?|PREFIXES?|EXTENSIONS?)', re.I)
HAN = re.compile(r'[\u4e00-\u9fff]')
ENUM_RE = re.compile(r'\benum\s+class\s+\w+[^{]*\{')
ENUM_CONST_LINE = re.compile(r'^([ \t]*)([A-Z][A-Z0-9_]*)\s*\(', re.M)


def match_brace(s, i):
    """s[i] == '{'，返回匹配 '}' 的下标。"""
    depth = 0
    n = len(s)
    j = i
    while j < n:
        c = s[j]
        if c == '/' and s[j + 1:j + 2] == '/':
            k = s.find('\n', j)
            j = n if k < 0 else k + 1
            continue
        if c == '/' and s[j + 1:j + 2] == '*':
            k = s.find('*/', j + 2)
            j = n if k < 0 else k + 2
            continue
        if c == '"':
            if s[j:j + 3] == '"""':
                k = s.find('"""', j + 3)
                j = n if k < 0 else k + 3
                continue
            j += 1
            while j < n:
                if s[j] == '\\':
                    j += 2
                    continue
                if s[j] == '"':
                    j += 1
                    break
                j += 1
            continue
        if c == "'":
            j += 1
            while j < n:
                if s[j] == '\\':
                    j += 2
                    continue
                if s[j] == "'":
                    j += 1
                    break
                j += 1
            continue
        if c == '{':
            depth += 1
            j += 1
            continue
        if c == '}':
            depth -= 1
            j += 1
            if depth == 0:
                return j - 1
            continue
        j += 1
    return -1



def lit(orig):
    return '"' + orig.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$') + '"'


def restore(text):
    cnt = [0]

    def f(m):
        k = m.group(2)
        if k not in rev:
            return m.group(0)
        cnt[0] += 1
        return lit(rev[k])

    return CALLRE.sub(f, text), cnt[0]


def match_paren(s, i):
    depth = 0
    n = len(s)
    j = i
    while j < n:
        c = s[j]
        if c == '/' and s[j + 1:j + 2] == '/':
            k = s.find('\n', j)
            j = n if k < 0 else k + 1
            continue
        if c == '/' and s[j + 1:j + 2] == '*':
            k = s.find('*/', j + 2)
            j = n if k < 0 else k + 2
            continue
        if c == '"':
            if s[j:j + 3] == '"""':
                k = s.find('"""', j + 3)
                j = n if k < 0 else k + 3
                continue
            j += 1
            while j < n:
                if s[j] == '\\':
                    j += 2
                    continue
                if s[j] == '"':
                    j += 1
                    break
                j += 1
            continue
        if c == "'":
            j += 1
            while j < n:
                if s[j] == '\\':
                    j += 2
                    continue
                if s[j] == "'":
                    j += 1
                    break
                j += 1
            continue
        if c == '(':
            depth += 1
            j += 1
            continue
        if c == ')':
            depth -= 1
            j += 1
            if depth == 0:
                return j - 1
            continue
        j += 1
    return -1


total = 0
report = []
for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if not fn.endswith('.kt'):
            continue
        p = os.path.join(dp, fn)
        src = io.open(p, encoding='utf-8').read()
        orig = src
        n_all = 0

        # ④ 需要用到「被比较的变量名」，必须在 ① 改写比较式之前收集
        cmp_vars_early = sorted(set(CMPVAR_RE.findall(src)))

        # ① 比较
        def _cmp(m):
            k = m.group(4)
            return m.group(1) + lit(rev[k]) if k in rev else m.group(0)
        src, c1 = CMP_RE.subn(_cmp, src)
        n_all += c1

        # ② when 匹配值
        def _when(m):
            k = m.group(4)
            return m.group(1) + lit(rev[k]) + m.group(5) if k in rev else m.group(0)
        src, c2 = WHEN_RE.subn(_when, src)
        n_all += c2

        # ③ 数据表（变量名暗示内置匹配词表）
        spans = []
        for m in DATATBL_HEAD.finditer(src):
            if not DATATBL_NAME.search(m.group(1)):
                continue
            ob = m.end() - 1
            cb = match_paren(src, ob)
            if cb < 0:
                continue
            inner = src[ob + 1:cb]
            if not CALLRE.search(inner) or not HAN.search(inner):
                continue
            spans.append((ob + 1, cb))
        for a, b in reversed(spans):
            new_inner, c3 = restore(src[a:b])
            if c3:
                src = src[:a] + new_inner + src[b:]
                n_all += c3

        # ④ 被比较变量的赋值（含 by remember { mutableStateOf(...) }）
        for v in cmp_vars_early:
            ev = re.escape(v)
            pat1 = re.compile(r'(\b' + ev + r'\s*=\s*)(' + CALLRE.pattern + r')')
            pat2 = re.compile(r'(\b' + ev + r'\s+by\s+remember\s*\{\s*mutableStateOf\(\s*)(' + CALLRE.pattern + r')')

            def mk(gk):
                def f(m):
                    k = m.group(gk)
                    if k not in rev:
                        return m.group(0)
                    # 前 2 组是固定组，qk 在末尾
                    pre = ''.join(m.group(i) for i in range(1, gk - 1))
                    return pre + lit(rev[k])
                return f
            # 直接手写更清晰
            def f1(m, v=v):
                k = m.group(3)
                return m.group(1) + lit(rev[k]) if k in rev else m.group(0)
            def f2(m, v=v):
                k = m.group(3)
                return m.group(1) + lit(rev[k]) if k in rev else m.group(0)
            src, c4a = pat1.subn(f1, src)
            src, c4b = pat2.subn(f2, src)
            n_all += c4a + c4b

        # ⑤ enum 常量构造参数：label/desc 是类加载期求值的常量，
        #    用 qstr 会（在早期加载场景）拿到空串，且与未翻译的同类常量格式不一致。
        for m in ENUM_RE.finditer(src):
            ob = m.end() - 1
            cb = match_brace(src, ob)
            if cb < 0:
                continue
            body = src[ob + 1:cb]
            spans = []
            for cm in ENUM_CONST_LINE.finditer(body):
                line_end = body.find('\n', cm.start())
                line_end = len(body) if line_end < 0 else line_end
                if CALLRE.search(body[cm.start():line_end]):
                    spans.append((cm.start(), line_end))
            for a, b in reversed(spans):
                new_seg, c5 = restore(body[a:b])
                if c5:
                    body = body[:a] + new_seg + body[b:]
                    n_all += c5
            src = src[:ob + 1] + body + src[cb:]

        # ⑥ 字符串匹配函数：x.contains(qstr(...)) 等
        def _strfun(m):
            k = m.group(3)
            return m.group(1) + '(' + lit(rev[k]) + ')' if k in rev else m.group(0)
        src, c6 = STRFUN_RE.subn(_strfun, src)
        n_all += c6

        if src != orig:
            io.open(p, 'w', encoding='utf-8', newline='\n').write(src)
            report.append((p, n_all))
            total += n_all

print('恢复文件数:', len(report), '恢复处数:', total)
for p, n in sorted(report, key=lambda x: -x[1]):
    print('  %-64s %d' % (p.replace('app/src/main/java/com/ai/assistance/quro/', ''), n))

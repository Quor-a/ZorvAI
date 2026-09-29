# -*- coding: utf-8 -*-
"""审计：取串调用的实参个数 vs 资源里的占位符个数。

不匹配时 Android 的 getString/stringResource 会抛异常（format 失败）——
在 UI 里表现为**文案整段变空白/条目消失**，在用户看来就是「功能被删了」。
"""
import glob
import io
import re
from collections import Counter

RES = 'app/src/main/res/values/strings_i18n.xml'
CALL = re.compile(r'(qstr|stringResource)\(\s*R\.string\.(qk_\w+)')
PH = re.compile(r'%(?:(\d+)\$)?[sdf]')


def load_res():
    src = io.open(RES, encoding='utf-8').read()
    return {m.group(1): m.group(2)
            for m in re.finditer(r'<string name="([\w.]+)"[^>]*>(.*?)</string>', src, re.S)}


def ph_count(v):
    """返回 (最大编号, 出现次数)；无编号占位符按出现顺序参与编号。"""
    v = v.replace('%%', '')
    idxs, seq = [], 0
    for m in PH.finditer(v):
        seq += 1
        idxs.append(int(m.group(1)) if m.group(1) else seq)
    return (max(idxs) if idxs else 0), len(idxs)


def split_args(s):
    """把实参串按顶层逗号切分（跳过括号/尖括号/字符串）。"""
    out, depth, buf, i, n = [], 0, [], 0, len(s)
    while i < n:
        c = s[i]
        if c == '"':
            j = i + 1
            while j < n:
                if s[j] == '\\':
                    j += 2
                    continue
                if s[j] == '"':
                    j += 1
                    break
                j += 1
            buf.append(s[i:j])
            i = j
            continue
        if c in '([{<':
            depth += 1
        elif c in ')]}>':
            depth -= 1
        if c == ',' and depth == 0:
            out.append(''.join(buf).strip())
            buf = []
            i += 1
            continue
        buf.append(c)
        i += 1
    if ''.join(buf).strip():
        out.append(''.join(buf).strip())
    return out


def call_end(s, i):
    """s[i] 是 '('，返回匹配的 ')' 下标。"""
    depth, j, n = 0, i, len(s)
    while j < n:
        c = s[j]
        if c == '"':
            k = j + 1
            while k < n:
                if s[k] == '\\':
                    k += 2
                    continue
                if s[k] == '"':
                    k += 1
                    break
                k += 1
            j = k
            continue
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return j
        j += 1
    return -1


def main():
    res = load_res()
    bad, skipped, ok = [], 0, 0
    for p in glob.glob('app/src/main/java/**/*.kt', recursive=True):
        src = io.open(p, encoding='utf-8').read()
        for m in CALL.finditer(src):
            k = m.group(2)
            if k not in res:
                skipped += 1
                continue
            op = src.find('(', m.end() - len(k) - 1)
            op = src.find('(', m.start())
            end = call_end(src, src.index('(', m.start()))
            if end < 0:
                skipped += 1
                continue
            body = src[src.index('(', m.start()) + 1:end]
            args = split_args(body)
            if args and args[0].startswith('R.string.'):
                args = args[1:]
            need, cnt = ph_count(res[k])
            if need == len(args):
                ok += 1
                continue
            line = src[:m.start()].count('\n') + 1
            bad.append((p, line, k, need, len(args), res[k].strip()[:70],
                        ' | '.join(a[:28] for a in args)))

    print('=== 参数个数与占位符不匹配：%d 处（匹配 %d / 跳过 %d）===' % (len(bad), ok, skipped))
    c = Counter()
    for p, line, k, need, got, val, aa in bad:
        c[p.replace('app/src/main/java/com/ai/assistance/quro/', '')] += 1
    for f, n in c.most_common():
        print('  %-56s %d' % (f, n))
    print()
    for p, line, k, need, got, val, aa in bad[:60]:
        print('%s:%d  %s  需 %d 实 %d' % (p.replace('app/src/main/java/com/ai/assistance/quro/', ''), line, k, need, got))
        print('     资源: %s' % val)
        print('     实参: %s' % aa)


if __name__ == '__main__':
    main()

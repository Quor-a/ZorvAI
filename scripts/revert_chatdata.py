# -*- coding: utf-8 -*-
"""只还原 ChatData.kt 里被注入的 stringResource(R.string.qk_...) -> 原始中文。避免 heredoc 转义问题。"""
import json, re

DATA = json.load(open('scripts/i18n_strings.json', encoding='utf-8'))
K2T = {('qk_%05d' % i): e['text'] for i, e in enumerate(DATA)}


def kt_escape(s):
    s = s.replace('\\', '\\\\').replace('"', '\\"')
    return s


def revert_one(src):
    out = []
    i = 0
    n = len(src)
    tot = 0
    while i < n:
        j = src.find('stringResource(R.string.qk_', i)
        if j < 0:
            out.append(src[i:]); break
        out.append(src[i:j])
        m = re.match(r'stringResource\(\s*R\.string\.(qk_\d+)', src[j:])
        if not m:
            out.append(src[j:]); break
        key = m.group(1)
        sp = src.index('(', j)
        depth = 0; k = sp; end = -1
        while k < n:
            if src[k] == '(':
                depth += 1
            elif src[k] == ')':
                depth -= 1
                if depth == 0:
                    end = k; break
            k += 1
        if end < 0:
            out.append(src[j:]); break
        t = K2T.get(key)
        if t is None:
            out.append(src[j:end + 1])
        else:
            tot += 1
            out.append('"%s"' % kt_escape(t))
        i = end + 1
    return ''.join(out), tot


p = 'app/src/main/java/com/ai/assistance/quro/ui/data/ChatData.kt'
src = open(p, encoding='utf-8').read()
new, tot = revert_one(src)
open(p, 'w', encoding='utf-8').write(new)
print('ChatData.kt reverted', tot, 'injections')

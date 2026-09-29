# -*- coding: utf-8 -*-
"""把本仓 ui 目录下所有被 do_replace 注入的 stringResource(R.string.qk_XXXXX...) 还原为原始中文字面量。
反向映射 qk_xxxxx -> 原始 text（来自 i18n_strings.json），逐文件替换，不动其它内容。"""
import json, os, re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..', 'app', 'src', 'main', 'java', 'com', 'ai', 'assistance', 'quro', 'ui')
DATA = json.load(open(os.path.join(HERE, 'i18n_strings.json'), encoding='utf-8'))
KEY2TEXT = {}
for idx, e in enumerate(DATA):
    KEY2TEXT['qk_%05d' % idx] = e['text']

def kt_escape(s):
    s = s.replace('\\', '\\\\').replace('"', '\\"')
    return s

def revert_one(src):
    out = []
    i = 0
    n = len(src)
    total_local = 0
    while i < n:
        j = src.find('stringResource(R.string.qk_', i)
        if j < 0:
            out.append(src[i:]); break
        out.append(src[i:j])
        # 定位 qk_ 数字
        m = re.match(r'stringResource\(\s*R\.string\.(qk_\d+)', src[j:])
        if not m:
            out.append(src[j:]); break
        key = m.group(1)
        # 从 stringResource( 后的 '(' 开始做括号深度匹配，找到匹配的 ')'
        start_paren = src.index('(', j)  # 第一个 '(' 即 stringResource 的括号
        depth = 0
        k = start_paren
        end = -1
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
        text = KEY2TEXT.get(key)
        if text is None:
            out.append(src[j:end+1])  # 未知 key，不动
        else:
            total_local += 1
            out.append('"%s"' % kt_escape(text))
        i = end + 1
    return ''.join(out), total_local

total = 0
files_touched = 0
for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if not fn.endswith('.kt'):
            continue
        p = os.path.join(dp, fn)
        src = open(p, encoding='utf-8').read()
        if 'stringResource(R.string.qk_' not in src:
            continue
        new, tl = revert_one(src)
        total += tl
        if new != src:
            open(p, 'w', encoding='utf-8').write(new)
            files_touched += 1

print('reverted %d stringResource injections across %d files' % (total, files_touched))

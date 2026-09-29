# -*- coding: utf-8 -*-
import os, re, json, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

ROOT = 'app/src/main/java'

# 匹配含中文的字符串字面量
lit = re.compile(r'"((?:[^"\\]|\\.)*?[\u4e00-\u9fff](?:[^"\\]|\\.)*?)"')

files = 0
occurrences = 0
uniq = {}
per_file = {}
for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if not fn.endswith('.kt'):
            continue
        p = os.path.join(dp, fn)
        s = open(p, encoding='utf-8', errors='ignore').read()
        ms = lit.findall(s)
        if ms:
            files += 1
            occurrences += len(ms)
            per_file[p] = len(ms)
            for m in ms:
                uniq[m] = uniq.get(m, 0) + 1

print('== 全仓 Kotlin 中文字面量 ==')
print('含中文字面量文件数 :', files)
print('出现总次数         :', occurrences)
print('去重后 unique 串数 :', len(uniq))

# 已接入 stringResource 的调用点
wired = 0
for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if fn.endswith('.kt'):
            s = open(os.path.join(dp, fn), encoding='utf-8', errors='ignore').read()
            wired += len(re.findall(r'stringResource\(R\.string\.qk_', s))
print('已接入 stringResource 调用点 :', wired)

# i18n 词表（英文基准）
from i18n_translate import TRANSLATIONS
EN = set(TRANSLATIONS)
covered = sum(1 for k in uniq if k in EN)
print('i18n 英文词表条数             :', len(EN))
print('unique 串中已在词表的          :', covered, '(%.1f%%)' % (100.0 * covered / max(1, len(uniq))))

# 全量 i18n_strings.json 对比
sj = os.path.join(HERE, 'i18n_strings.json')
if os.path.exists(sj):
    data = json.load(open(sj, encoding='utf-8'))
    print('i18n_strings.json 条数         :', len(data))

# 输出词表外、出现频率最高的 UI 串（用于扩充翻译范围）
missing = sorted(((c, t) for t, c in uniq.items() if t not in EN), reverse=True)
with open(os.path.join(HERE, 'missing_top.txt'), 'w', encoding='utf-8') as f:
    for c, t in missing:
        f.write('%d\t%s\n' % (c, t))
print('词表外串数                    :', len(missing))
print('已写出 scripts/missing_top.txt (按出现次数降序)')
print('--- 词表外 Top 40 ---')
for c, t in missing[:40]:
    print('%4d  %s' % (c, t))

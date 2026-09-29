# -*- coding: utf-8 -*-
"""把 ui_strings.txt 里有、但 i18n_strings.json 没有的显示串追加进去（保持原索引稳定）。"""
import json, os
HERE = os.path.dirname(os.path.abspath(__file__))
data = json.load(open(os.path.join(HERE, 'i18n_strings.json'), encoding='utf-8'))
indata = set(e['text'] for e in data)
disp = []
seen = set()
for line in open(os.path.join(HERE, 'ui_strings.txt'), encoding='utf-8').read().splitlines():
    line = line.strip()
    if not line:
        continue
    t = line.split('\t', 1)[1] if '\t' in line else line
    if t in seen:
        continue
    seen.add(t)
    disp.append(t)
added = [t for t in disp if t not in indata]
for t in added:
    data.append({'text': t})
json.dump(data, open(os.path.join(HERE, 'i18n_strings.json'), 'w', encoding='utf-8'),
            ensure_ascii=False, indent=0)
print('原有条目:', len(indata), '显示串:', len(disp), '本次追加:', len(added),
      '新总数:', len(data))

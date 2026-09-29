import json, io

P = 'scripts/i18n_lang_ja_9.json'
cat = {e['text'] for e in json.load(io.open('scripts/i18n_strings.json', encoding='utf-8'))}
d = json.load(io.open(P, encoding='utf-8'))

NL = chr(10)          # 真实换行
ESC = chr(92) + 'n'   # 字面反斜杠 + n（词表里存的就是这个）

out = {}
fixed = []
for k, v in d.items():
    if k in cat:
        out[k] = v
        continue
    k2 = k.replace(NL, ESC)
    if k2 in cat:
        out[k2] = v.replace(NL, ESC)
        fixed.append(k2)
    else:
        out[k] = v

json.dump(out, io.open(P, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('修正 %d 条: %s' % (len(fixed), fixed))

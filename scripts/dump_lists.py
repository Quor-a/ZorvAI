import json, collections, re, os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = 'app/src/main/java/com/ai/assistance/quro'
data = json.load(open(os.path.join(HERE, 'i18n_strings.json'), encoding='utf-8'))
uniq = list(dict.fromkeys(e['text'] for e in data))
with open(os.path.join(HERE, 'uniq_strings.txt'), 'w', encoding='utf-8') as f:
    for i, t in enumerate(uniq):
        f.write('%05d\t%s\n' % (i, t))
print('uniq_strings.txt written:', len(uniq))

pat = re.compile(r'[\u4e00-\u9fff]')
strlit = re.compile(r'"((?:[^"\\]|\\.)*)"')
cnt = collections.Counter()
for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if not fn.endswith('.kt'):
            continue
        p = os.path.join(dp, fn)
        try:
            lines = open(p, encoding='utf-8').read().splitlines()
        except Exception:
            continue
        for ln in lines:
            s = ln.strip()
            if s.startswith('//') or s.startswith('*'):
                continue
            for m in strlit.finditer(ln):
                t = m.group(1)
                if pat.search(t):
                    cnt[t] += 1
top = cnt.most_common(600)
with open(os.path.join(HERE, 'freq_top.txt'), 'w', encoding='utf-8') as f:
    for t, c in top:
        f.write('%d\t%s\n' % (c, t))
print('freq_top.txt written:', len(top))
print('top15:', [t for t, c in top[:15]])

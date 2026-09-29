# 把新文案追加进 i18n_strings.json（索引即资源键，只能追加），并打印 text -> qk_key 映射。
# 同时：把定位到的来源文件/行号写入元数据（便于追溯）。
import io, json, os

HERE = os.path.dirname(os.path.abspath(__file__))
STR = os.path.join(HERE, 'i18n_strings.json')

new_texts = list(json.load(io.open(os.path.join(HERE, 'i18n_en_13.json'), encoding='utf-8')).keys())

data = json.load(io.open(STR, encoding='utf-8'))
have = {e['text']: i for i, e in enumerate(data)}

# 一次性读取源码，定位每个新文案的首个出现位置
ROOTS = ['app/src/main/java/com/ai/assistance/quro']
loc = {}
for root in ROOTS:
    for dp, _, fns in os.walk(root):
        for fn in fns:
            if not fn.endswith('.kt'):
                continue
            p = os.path.join(dp, fn)
            src = io.open(p, encoding='utf-8', errors='replace').read()
            for t in new_texts:
                if t in loc:
                    continue
                if t in src:
                    loc[t] = (p.replace('\\', '/'), src.count('\n', 0, src.index(t)) + 1)

added = []
for t in new_texts:
    if t in have:
        continue
    p = loc.get(t, ('', 0))
    data.append({
        'text': t,
        'fmt': ('$' in t),
        'file': p[0],
        'line': p[1],
    })
    added.append(t)

json.dump(data, io.open(STR, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
print('词表 %d -> %d，新增 %d 条' % (len(data) - len(added), len(data), len(added)))

keymap = {'qk_%05d' % i: e['text'] for i, e in enumerate(data)}
for t in added:
    k = 'qk_%05d' % data.index(next(e for e in data if e['text'] == t))
    print('  %-24s %s  (%s)' % (t[:22], k, loc.get(t, ('?', 0))[0].split('/')[-1]))

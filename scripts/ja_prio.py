# 统计日语缺译清单的长度/文件分布，并输出"高可见度"优先清单
import json, io, os, collections

HERE = os.path.dirname(os.path.abspath(__file__))
todo = json.load(io.open(os.path.join(HERE, '_ja_todo.json'), encoding='utf-8'))
cat = json.load(io.open(os.path.join(HERE, 'i18n_strings.json'), encoding='utf-8'))
meta = {}
for e in cat:
    meta.setdefault(e['text'], e)

b = collections.Counter()
for t in todo:
    L = len(t)
    b['<=10' if L <= 10 else '11-20' if L <= 20 else '21-40' if L <= 40
      else '41-80' if L <= 80 else '>80'] += 1
print('总缺译', len(todo))
for k in ['<=10', '11-20', '21-40', '41-80', '>80']:
    print('  %-6s %d' % (k, b[k]))

f = collections.Counter()
for t in todo:
    p = meta.get(t, {}).get('file', '?')
    f[p.replace('\\', '/').split('/')[-1]] += 1
print('\n文件分布 top 20:')
for k, v in f.most_common(20):
    print('  %-42s %d' % (k, v))

# 优先集：<=40 字符（按钮/标题/标签/短提示），按字符长度升序
prio = sorted([t for t in todo if len(t) <= 40], key=lambda s: (len(s), s))
print('\n高可见度(<=40字符)共 %d 条' % len(prio))
with io.open(os.path.join(HERE, '_ja_prio.json'), 'w', encoding='utf-8') as fp:
    json.dump(prio, fp, ensure_ascii=False, indent=0)
with io.open(os.path.join(HERE, '_ja_prio.txt'), 'w', encoding='utf-8') as fp:
    for i, t in enumerate(prio):
        fp.write('%d\t%s\n' % (i, t.replace('\n', '\\n')))

# 校验新增语言词表的键是否与词表/缺译清单精确匹配，并统计覆盖率
import json, io, os, glob, sys

HERE = os.path.dirname(os.path.abspath(__file__))
cat = json.load(io.open(os.path.join(HERE, 'i18n_strings.json'), encoding='utf-8'))
known = {e['text'] for e in cat}
todo_ja = set(json.load(io.open(os.path.join(HERE, '_ja_todo.json'), encoding='utf-8')))

bad = 0
for f in sorted(glob.glob(os.path.join(HERE, 'i18n_lang_*.json'))):
    d = json.load(io.open(f, encoding='utf-8'))
    miss = [k for k in d if k not in known]
    print('%-28s %4d 条, 未匹配键 %d' % (os.path.basename(f), len(d), len(miss)))
    for k in miss[:8]:
        print('    ! %r' % k)
    bad += len(miss)

print('\n未匹配键合计 %d' % bad)

# ja 覆盖推进
ja = set()
for f in sorted(glob.glob(os.path.join(HERE, 'i18n_lang_ja*.json'))):
    ja |= set(json.load(io.open(f, encoding='utf-8')))
print('ja 真译条数 %d' % len(ja))
print('ja 缺译剩余 %d / %d' % (len(todo_ja - ja), len(todo_ja)))
left = sorted(todo_ja - ja, key=lambda s: (len(s), s))
with io.open(os.path.join(HERE, '_ja_left.json'), 'w', encoding='utf-8') as fp:
    json.dump(left, fp, ensure_ascii=False, indent=0)

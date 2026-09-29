import json, re, os, glob, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = 'app/src/main/java'
sys.path.insert(0, HERE)
from i18n_translate import TRANSLATIONS

# 1) 收集 i18n 范围内(已翻译, 即进入 EN)的中文原文集合 —— 排除 545 内部串/正则串(本就不在 EN)
EN = dict(TRANSLATIONS)
for _f in sorted(glob.glob(os.path.join(HERE, 'i18n_en_*.json'))):
    EN.update(json.load(open(_f, encoding='utf-8')))

data = json.load(open(os.path.join(HERE, 'i18n_strings.json'), encoding='utf-8'))
scope_texts = set()
for e in data:
    t = e['text']
    if t in EN and re.search(r'[\u4e00-\u9fff]', t):
        scope_texts.add(t)

print('i18n 范围内且已翻英文(必接)的中文原文条目: %d' % len(scope_texts))

# 2) 扫描源码中所有含中文的双引号字符串字面量
lit_re = re.compile(r'"([^"\n]*[\u4e00-\u9fff][^"\n]*)"')
miss = {}  # text -> list of (file, line)

files = []
for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if fn.endswith('.kt'):
            files.append(os.path.join(dp, fn))

total_lit = 0
for p in files:
    try:
        lines = open(p, encoding='utf-8').read().splitlines()
    except Exception:
        continue
    for i, line in enumerate(lines, 1):
        # 跳过整行注释
        s = line.lstrip()
        if s.startswith('//'):
            continue
        # 跳过已被接线的行
        if 'stringResource(' in line or 'R.string.' in line or 'getString(' in line:
            continue
        for m in lit_re.finditer(line):
            lit = m.group(1)
            total_lit += 1
            if lit in scope_texts:
                miss.setdefault(lit, []).append((p, i))

print('源码含中文字面量总数(粗): %d' % total_lit)
print('==> 属于 i18n 范围但仍以原始中文硬编码出现的「泄漏」条目: %d 种, 命中 %d 处' % (
    len(miss), sum(len(v) for v in miss.values())))

# 按目录汇总（取 com/ai/assistance/quro 之后的第一级）
from collections import Counter
dir_counter = Counter()
for lit, occ in miss.items():
    for p, i in occ:
        rel = os.path.relpath(p, ROOT)
        parts = rel.split(os.sep)
        top = parts[4] if len(parts) > 4 else rel
        dir_counter[top] += 1
print('--- 各目录泄漏处数 ---')
for d, c in dir_counter.most_common():
    print('  %-18s %d' % (d, c))

# 输出明细到文件
out = os.path.join(HERE, 'leak_miss.txt')
with open(out, 'w', encoding='utf-8') as f:
    for lit, occ in sorted(miss.items(), key=lambda kv: -len(kv[1])):
        f.write('### %s  (%d 处)\n' % (lit, len(occ)))
        for p, i in occ[:30]:
            f.write('  %s:%d\n' % (os.path.relpath(p, ROOT), i))
print('明细已写入', out)

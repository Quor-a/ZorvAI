import json, collections, re, os

pat = re.compile(r'[\u4e00-\u9fff]')
strlit = re.compile(r'"((?:[^"\\]|\\.)*)"')
root = 'app/src/main/java/com/ai/assistance/quro/ui'

cnt = collections.Counter()
files = collections.defaultdict(set)
for dp, _, fs in os.walk(root):
    for f in fs:
        if not f.endswith('.kt'):
            continue
        p = os.path.join(dp, f)
        rel = p.replace(root + '/', '')
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
                    files[t].add(rel)

print('唯一串总数:', len(cnt))
print('=== 出现次数 Top 80（跨文件复用最高，杠杆最大）===')
for t, c in cnt.most_common(80):
    print(c, len(files[t]), repr(t))

# 扫描 UI 源码里剩余的硬编码中文字面量（排除注释行、排除已知数据值）
import io, os, re, collections

ROOTS = [
    'app/src/main/java/com/ai/assistance/quro/ui',
    'app/src/main/java/com/ai/assistance/quro/genui/aiapp/ui',
]
HAN = re.compile(r'[\u4e00-\u9fff]')
STR = re.compile(r'"((?:[^"\\\n]|\\.)*)"')

total = collections.Counter()
samples = collections.defaultdict(list)
for root in ROOTS:
    for dp, _, fns in os.walk(root):
        for fn in fns:
            if not fn.endswith('.kt'):
                continue
            p = os.path.join(dp, fn)
            for ln, line in enumerate(io.open(p, encoding='utf-8', errors='replace'), 1):
                s = line.strip()
                if s.startswith('//') or s.startswith('*') or s.startswith('/*'):
                    continue
                # 去掉行尾注释再找字面量
                code = re.sub(r'//.*$', '', line)
                for m in STR.finditer(code):
                    v = m.group(1)
                    if not HAN.search(v):
                        continue
                    if v.strip() in ('[通道]',):
                        continue
                    total[p] += 1
                    if len(samples[p]) < 6:
                        samples[p].append((ln, v[:40]))
print('剩余含中文的字面量总数: %d，涉及文件 %d' % (sum(total.values()), len(total)))
for p, n in total.most_common(20):
    print('  %-58s %d' % (os.path.basename(p), n))
    for ln, v in samples[p][:3]:
        print('       L%-5d %s' % (ln, v))

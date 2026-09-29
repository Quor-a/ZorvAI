import os, re, json, sys

pat = re.compile(r'[\u4e00-\u9fff]')
strlit = re.compile(r'"((?:[^"\\]|\\.)*)"')
root = 'app/src/main/java/com/ai/assistance/quro/ui'

entries = []
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
        for i, ln in enumerate(lines, 1):
            s = ln.strip()
            if s.startswith('//') or s.startswith('*') or s.startswith('/*'):
                continue
            for m in strlit.finditer(ln):
                t = m.group(1)
                if pat.search(t):
                    has_fmt = bool(re.search(r'%[0-9]*\$?[sd]|\$\{', t))
                    entries.append({'text': t, 'fmt': has_fmt, 'file': rel, 'line': i})

# 去重（保留首次出现位置）
seen = {}
order = []
for e in entries:
    t = e['text']
    if t not in seen:
        seen[t] = e
        order.append(t)

print('中文字面量出现次数:', len(entries))
print('去重后唯一中文串:', len(seen))
fmtcount = sum(1 for t in order if seen[t]['fmt'])
print('其中含 %s / ${} 格式参数的唯一串:', fmtcount)

# 输出 JSON 供下一步翻译/替换使用
out = [{'text': t, 'fmt': seen[t]['fmt'], 'file': seen[t]['file'], 'line': seen[t]['line']} for t in order]
with open('scripts/i18n_strings.json', 'w', encoding='utf-8') as fh:
    json.dump(out, fh, ensure_ascii=False, indent=1)

print('已写出 scripts/i18n_strings.json')
print('--- 含格式参数示例(前25) ---')
c = 0
for e in out:
    if e['fmt']:
        print(repr(e['text']), '|', e['file'], ':', e['line'])
        c += 1
        if c >= 25:
            break

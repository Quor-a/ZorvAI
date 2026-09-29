import json, glob, os

out = {}
for f in sorted(glob.glob('trans_batch_*.tsv')):
    with open(f, encoding='utf-8') as fh:
        for line in fh:
            line = line.rstrip('\n')
            if not line.strip():
                continue
            if '\t' not in line:
                print('SKIP (no tab):', repr(line[:40]))
                continue
            zh, en = line.split('\t', 1)
            if zh in out and out[zh] != en:
                print('DUP CONFLICT:', repr(zh), out[zh], 'vs', en)
            out[zh] = en

print('merged entries:', len(out))
with open('i18n_en_5.json', 'w', encoding='utf-8') as f:
    json.dump(out, f, ensure_ascii=False, indent=0)
print('wrote i18n_en_5.json')

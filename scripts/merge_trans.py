import json, sys

# idx-keyed translation merge:
#   trans TSV (idx <TAB> english)  ->  i18n_en_<N>.json  ({chinese_key: english})
# The exact Chinese key is pulled from remaining_dump.tsv so long legal strings
# are matched byte-for-byte (no manual copy errors).
# English may contain literal backslash-n (\n) which is preserved verbatim and
# round-trips through xml_escape into an aapt newline, matching the source.

idx2text = {}
for line in open('remaining_dump.tsv', encoding='utf-8'):
    parts = line.rstrip('\n').split('\t')
    if len(parts) != 4:
        continue
    idx, file, fmt, text = parts
    idx2text[int(idx)] = text

out = {}
missing = []
for line in open(sys.argv[1], encoding='utf-8-sig'):
    line = line.rstrip('\n')
    if not line.strip() or line.lstrip().startswith('#'):
        continue
    idx_s, en = line.split('\t', 1)
    idx = int(idx_s)
    if idx in idx2text:
        out[idx2text[idx]] = en
    else:
        missing.append(idx)

json.dump(out, open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('wrote %d entries to %s' % (len(out), sys.argv[2]))
if missing:
    print('WARN: idx not found in dump:', missing[:20])

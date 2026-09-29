import json, glob

EN = {}
for f in glob.glob('i18n_en_*.json'):
    EN.update(json.load(open(f, encoding='utf-8')))

data = json.load(open('i18n_strings.json', encoding='utf-8'))
# non-EN entries with index
items = [(i, e) for i, e in enumerate(data) if e['text'] not in EN]
# sort by file then line for context
items.sort(key=lambda x: (x[1]['file'], x[1]['line']))

# Group by file
from collections import defaultdict
byfile = defaultdict(list)
for i, e in items:
    byfile[e['file']].append((i, e['text'], e.get('fmt')))

# Dump a compact tsv: idx \t file \t fmt \t text
with open('remaining_dump.tsv', 'w', encoding='utf-8') as f:
    for i, e in items:
        f.write('%d\t%s\t%s\t%s\n' % (i, e['file'], '1' if e.get('fmt') else '0', e['text']))

print('dumped', len(items), 'entries to remaining_dump.tsv')
print('files:', len(byfile))

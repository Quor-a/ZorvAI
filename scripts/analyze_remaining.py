import json, glob
from collections import Counter

EN = {}
for f in glob.glob('i18n_en_*.json'):
    EN.update(json.load(open(f, encoding='utf-8')))

data = json.load(open('i18n_strings.json', encoding='utf-8'))
non = [e for e in data if e['text'] not in EN]
print('total', len(data), 'EN', len(EN), 'non-EN', len(non))
print('unique non-EN text', len(set(e['text'] for e in non)))

def area(f):
    p = f.replace('\\', '/').split('/')
    if 'ui' in p:
        return 'ui'
    if 'core' in p:
        return 'core'
    if 'viewmodel' in f.lower():
        return 'viewmodel'
    if 'genui' in p:
        return 'genui'
    return p[3] if len(p) > 3 else f

c = Counter(area(e['file']) for e in non)
print('by area:', dict(c))

ui_non = [e for e in non if '/ui/' in e['file'].replace('\\', '/')]
print('non-EN in ui/ dir:', len(ui_non))
print('fmt=True among non-EN:', sum(1 for e in non if e.get('fmt')))

# Which ui files hold the most non-EN
cf = Counter(e['file'] for e in ui_non)
print('--- top ui files with non-EN (Text candidates) ---')
for f, n in cf.most_common(25):
    print(n, f)

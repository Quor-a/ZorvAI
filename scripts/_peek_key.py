import json, io

cat = json.load(io.open('scripts/i18n_strings.json', encoding='utf-8'))
for e in cat:
    if '引用文本' in e['text'] or '待办事项' in e['text'] or '代码' == e['text']:
        print(repr(e['text']), e['file'].split('/')[-1] if e['file'] else '')

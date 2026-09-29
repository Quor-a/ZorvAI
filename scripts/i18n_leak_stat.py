# 精确量化各语言资源的中文泄漏（值==zh）与英文回落（值==en）
import re, io, os, collections

RES = 'app/src/main/res'
KEY = re.compile(r'<string name="(qk_\d+)"[^>]*>(.*?)</string>', re.S)

def load(d):
    p = os.path.join(RES, d, 'strings_i18n.xml')
    if not os.path.exists(p):
        return {}
    s = io.open(p, encoding='utf-8').read()
    return dict(KEY.findall(s))

zh = load('values')
en = load('values-en')
print('总条目 zh=%d en=%d' % (len(zh), len(en)))

# 英文兜底残缺（英语缺失的键）
miss_en = [k for k in zh if k not in en or en.get(k, '').strip() == zh[k].strip()]
print('英文缺失/未译的键: %d' % len(miss_en))

for d in ['values-ja', 'values-ko', 'values-ru', 'values-de', 'values-fr',
          'values-es', 'values-pt', 'values-ar', 'values-hi']:
    m = load(d)
    if not m:
        continue
    cn = [k for k, v in m.items() if v.strip() == zh.get(k, '\x00').strip()]
    ef = [k for k, v in m.items() if k not in cn and v.strip() == en.get(k, '\x00').strip()]
    print('%-11s 条目 %d | 中文泄漏(==zh) %d | 回落英文(==en) %d | 真译 %d'
          % (d, len(m), len(cn), len(ef), len(m) - len(cn) - len(ef)))
    if d == 'values-ja' and cn:
        print('   中文泄漏示例:', [zh[k][:14] for k in cn[:6]])

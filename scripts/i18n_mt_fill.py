# 用 MyMemory 把 3416 条中文源串翻译到各目标语言，落盘到 i18n_mt_cache.json。
# 可断点续传（已缓存的跳过）。受免费额度限制，可能一天只能翻一部分——重跑即续传。
import json, sys, time, urllib.parse, urllib.request

sys.path.insert(0, 'scripts')
from i18n_translate import TRANSLATIONS

# en 优先机翻补齐（免费额度优先给英文主力），其余 9 种随后；手翻的占位串会覆盖机翻
LANG_CODES = {
    'en': 'en', 'ar': 'ar', 'de': 'de', 'es': 'es', 'fr': 'fr',
    'hi': 'hi', 'ja': 'ja', 'ko': 'ko', 'pt': 'pt', 'ru': 'ru',
}
CACHE = 'scripts/i18n_mt_cache.json'
SLEEP = 0.45

def load():
    try:
        return json.load(open(CACHE, encoding='utf-8'))
    except Exception:
        return {}

def save(c):
    json.dump(c, open(CACHE, 'w', encoding='utf-8'), ensure_ascii=False)

def mt(text, tgt):
    q = urllib.parse.quote(text)
    url = f"https://api.mymemory.translated.net/get?q={q}&langpair=zh-CN|{tgt}"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=20) as r:
            data = json.loads(r.read().decode('utf-8'))
        if data.get('responseStatus') != 200:
            return None, data.get('responseDetails', '')
        return data['responseData']['translatedText'], ''
    except Exception as e:
        return None, str(e)

def main():
    d = json.load(open('scripts/i18n_strings.json', encoding='utf-8'))
    texts = list({e['text'] for e in d})
    cache = load()
    for lang, code in LANG_CODES.items():
        cache.setdefault(lang, {})
        todo = [t for t in texts if t not in cache[lang]]
        print(f"[{lang}] total {len(texts)}, cached {len(texts)-len(todo)}, todo {len(todo)}")
        done = 0
        for t in todo:
            # en 已经有手翻的优先用已有 TRANSLATIONS（不覆盖）
            if lang == 'en' and t in TRANSLATIONS:
                cache[lang][t] = TRANSLATIONS[t]
                continue
            trans, err = mt(t, code)
            if trans is None:
                print(f"  [{lang}] quota/err: {err}; stopping this lang (resume later)")
                break
            cache[lang][t] = trans
            done += 1
            if done % 50 == 0:
                save(cache)
                print(f"  [{lang}] progressed {done}")
            time.sleep(SLEEP)
        save(cache)
        print(f"[{lang}] finished pass: +{done}")
    print("ALL DONE (or quota-limited). Re-run to resume.")

if __name__ == '__main__':
    main()

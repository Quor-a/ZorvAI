# 修复两类真 bug：
# A. 逻辑匹配词表被 i18n 化 —— FAIL_MARKERS / BAD / DECK_WORDS / MINDMAP_WORDS 里混进了
#    qstr(...)，日语环境下标记词变成日文，比对工具输出永远不命中（功能静默失效）。
#    逐条还原为中文原值（关键词表是「数据」，不是「展示文案」）。
# B. QuroLocale.LANGUAGE_NAMES / MAJOR_LANGUAGES 是 object 初始化期求值的 val，且
#    "跟随系统" 是硬编码中文（语言选择器里永远显示中文）。改为 get() 惰性求值 + 资源串。
import io, json, os, re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

cat = json.load(io.open(os.path.join(HERE, 'i18n_strings.json'), encoding='utf-8'))
KEY2ZH = {'qk_%05d' % i: e['text'] for i, e in enumerate(cat)}

QSTR = re.compile(r'qstr\(R\.string\.(qk_\d+)\)')

# ---------- A. 还原逻辑词表 ----------
TABLES = [
    ('app/src/main/java/com/ai/assistance/quro/core/agent/loop/Verifier.kt', 'FAIL_MARKERS'),
    ('app/src/main/java/com/ai/assistance/quro/core/agent/orchestration/DeliverabilityJudge.kt', 'BAD'),
    ('app/src/main/java/com/ai/assistance/quro/core/canvas/CanvasRouter.kt', 'DECK_WORDS'),
    ('app/src/main/java/com/ai/assistance/quro/core/canvas/CanvasRouter.kt', 'MINDMAP_WORDS'),
]
for rel, var in TABLES:
    p = os.path.join(ROOT, rel)
    src = io.open(p, encoding='utf-8').read()
    out, n = [], 0
    for line in src.split('\n'):
        if ('val %s = ' % var) in line and 'qstr(' in line:
            def rep(m):
                return '"%s"' % KEY2ZH.get(m.group(1), m.group(1))
            new = QSTR.sub(rep, line)
            n += len(QSTR.findall(line))
            out.append(new)
        else:
            out.append(line)
    if n:
        io.open(p, 'w', encoding='utf-8', newline='').write('\n'.join(out))
    print('A. %-28s %-14s 还原 %d 处' % (var, os.path.basename(rel), n))

# ---------- B. QuroLocale ----------
p = os.path.join(ROOT, 'app/src/main/java/com/ai/assistance/quro/util/QuroLocale.kt')
src = io.open(p, encoding='utf-8').read()
before = src

src = src.replace(
    'val LANGUAGE_NAMES: Map<String, String> = mapOf(',
    'val LANGUAGE_NAMES: Map<String, String>\n        get() = mapOf(')
src = src.replace(
    'val MAJOR_LANGUAGES: List<Pair<String, String>> = listOf(',
    'val MAJOR_LANGUAGES: List<Pair<String, String>>\n        get() = listOf(')
# 硬编码「跟随系统」→ 资源串
src = src.replace('"system" to "跟随系统",', '"system" to qstr(R.string.qk_03872),')
src = src.replace('LANGUAGE_NAMES["system"] ?: "跟随系统"', 'LANGUAGE_NAMES["system"] ?: qstr(R.string.qk_03872)')

assert src != before, 'QuroLocale 未被修改（模式未命中）'
assert '走" = "跟随系统"' not in src
io.open(p, 'w', encoding='utf-8', newline='').write(src)
print('B. QuroLocale：LANGUAGE_NAMES/MAJOR_LANGUAGES 改惰性求值 + 「跟随系统」接资源串')
print('   残留硬编码「跟随系统」: %d' % src.count('"跟随系统"'))

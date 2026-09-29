# 提取 UI 源码剩余的中文硬编码字面量，按「疑似可见/疑似提示词」分类
import io, os, re, json, collections

ROOTS = [
    'app/src/main/java/com/ai/assistance/quro/ui',
    'app/src/main/java/com/ai/assistance/quro/genui/aiapp/ui',
    'app/src/main/java/com/ai/assistance/quro/genui/aiapp',
]
# 这些文件的内容是 AI 提示词 / 日志 / 死代码，不参与 UI i18n
SKIP_FILES = {'ChatData.kt'}
HAN = re.compile(r'[\u4e00-\u9fff]')
STR = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
# 疑似提示词/日志的特征
PROMPT_HINT = re.compile(r'(你是|请以|返回 JSON|系统提示|输出格式|不要输出|严格按|角色扮演|提示词|=====)')
LOG_HINT = re.compile(r'(\[|\]|失败原因|堆栈|exception|Exception|log|Log)')

items = []   # (path, line, text)
for root in ROOTS:
    for dp, _, fns in os.walk(root):
        for fn in fns:
            if not fn.endswith('.kt') or fn in SKIP_FILES:
                continue
            p = os.path.join(dp, fn)
            for ln, line in enumerate(io.open(p, encoding='utf-8', errors='replace'), 1):
                s = line.strip()
                if s.startswith('//') or s.startswith('*') or s.startswith('/*'):
                    continue
                code = re.sub(r'//.*$', '', line)
                for m in STR.finditer(code):
                    v = m.group(1)
                    if not HAN.search(v) or v.strip() == '[通道]':
                        continue
                    if v.strip() in ('[通道] ',):
                        continue
                    items.append((p, ln, v))

print('原始候选 %d' % len(items))

ui, prompt = [], []
for p, ln, v in items:
    if len(v) > 100 or PROMPT_HINT.search(v):
        prompt.append((p, ln, v))
    else:
        ui.append((p, ln, v))
print('疑似界面可见(<=100字符且无提示词特征): %d' % len(ui))
print('疑似提示词/长文本: %d' % len(prompt))

byfile = collections.Counter(p for p, _, _ in ui)
print('\n=== 待接线清单（按文件）===')
for p, n in byfile.most_common():
    print('%-58s %d' % (os.path.basename(p), n))

with io.open('scripts/_ui_left_items.json', 'w', encoding='utf-8') as f:
    json.dump([{'file': p, 'line': l, 'text': t} for p, l, t in ui], f, ensure_ascii=False, indent=0)
# 去重文本清单（供翻译）
uniq = sorted({t for _, _, t in ui}, key=lambda s: (len(s), s))
with io.open('scripts/_ui_left_uniq.json', 'w', encoding='utf-8') as f:
    json.dump(uniq, f, ensure_ascii=False, indent=0)
print('\n去重后待翻译条数: %d' % len(uniq))
with io.open('scripts/_ui_left_uniq.txt', 'w', encoding='utf-8') as f:
    for i, t in enumerate(uniq):
        f.write('%d\t%s\n' % (i, t.replace('\n', '\\n')))

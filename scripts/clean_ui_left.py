# 只保留「在源码中以完整字面量出现」的串：规避正则/模板片段（翻了会破坏逻辑）
import io, json, re

SRC = json.load(io.open('scripts/_ui_left_items.json', encoding='utf-8'))
PROMPT_FILES = {
    'QuroChatViewModel.kt', 'ChatViewModel.kt', 'GenUiRules.kt', 'GenUILlmClient.kt',
    'GenUILlmClientKt.kt', 'GenUITool.kt', 'GenUiDiag.kt', 'RegisterComponentTool.kt',
    'RenderChannel.kt', 'AiActionHost.kt', 'AgentThought.kt', 'ZorvBrain.kt',
    'ChatHistory.kt', 'AgentThinkingUI.kt', 'QuroPersonaViewModel.kt',
}
REGEX_HINT = re.compile(r'(\\s|\\n|\\w|\[.+?\][?+*]?|\(\?|\.\+\?|\.\*\?)')
_FC = {}

def content(p):
    if p not in _FC:
        _FC[p] = io.open(p, encoding='utf-8', errors='replace').read()
    return _FC[p]

kept, dropped = [], []
for e in SRC:
    p, t = e['file'], e['text']
    fn = p.replace('\\', '/').split('/')[-1]
    if fn in PROMPT_FILES:
        continue
    if not t.strip():
        continue
    # 必须在文件中以 "完整字面量" 形式存在
    if ('"' + t + '"') not in content(p):
        dropped.append(('非完整字面量', p, t))
        continue
    if REGEX_HINT.search(t):
        dropped.append(('疑似正则', p, t))
        continue
    kept.append(e)

uniq = sorted({e['text'] for e in kept}, key=lambda s: (len(s), s))
print('保留 %d 处 / 去重 %d' % (len(kept), len(uniq)))
print('剔除 %d 处' % len(dropped))
print('\n剔除示例:')
for r, p, t in dropped[:12]:
    print('  %-12s %-34s %s' % (r, p.replace('\\', '/').split('/')[-1], t[:44].replace('\n', '\\n')))

from collections import Counter
print('\n保留清单按文件:')
for p, n in Counter(e['file'].replace('\\', '/').split('/')[-1] for e in kept).most_common():
    print('  %-40s %d' % (p, n))

with io.open('scripts/_fixA_clean.json', 'w', encoding='utf-8') as f:
    json.dump(uniq, f, ensure_ascii=False, indent=0)
with io.open('scripts/_fixA_clean.txt', 'w', encoding='utf-8') as f:
    for i, t in enumerate(uniq):
        f.write('%d\t%s\n' % (i, t.replace('\n', '\\n')))
print('\n=== 完整清单 ===')
for i, t in enumerate(uniq):
    print('%d\t%s' % (i, t.replace('\n', '\\n')))

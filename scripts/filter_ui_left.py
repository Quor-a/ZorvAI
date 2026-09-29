# 从剩余候选中筛出「真正界面可见」的文案（排除 AI 提示词/工具 schema 描述文件）
import io, json, re, collections

SRC = json.load(io.open('scripts/_ui_left_items.json', encoding='utf-8'))

# 这些文件的内容是 AI 提示词 / 工具描述 / 内部数据，不参与 UI i18n
PROMPT_FILES = {
    'QuroChatViewModel.kt', 'ChatViewModel.kt', 'GenUiRules.kt', 'GenUILlmClient.kt',
    'GenUILlmClientKt.kt', 'GenUITool.kt', 'GenUiDiag.kt', 'RegisterComponentTool.kt',
    'RenderChannel.kt', 'AiActionHost.kt', 'AgentThought.kt', 'ZorvBrain.kt',
    'ChatHistory.kt', 'AgentThinkingUI.kt', 'QuroPersonaViewModel.kt',
}
UI = [e for e in SRC if e['file'].replace('\\', '/').split('/')[-1] not in PROMPT_FILES]
# 提示词文件里只捞「短标签」——可能是真的界面文案（如会话标题「新对话」）
SHORT = [e for e in SRC if e['file'].replace('\\', '/').split('/')[-1] in PROMPT_FILES
         and len(e['text']) <= 18 and '\n' not in e['text']]

print('A. 界面文件待接线: %d 处 / 去重 %d' % (len(UI), len({e['text'] for e in UI})))
print('B. 提示词文件中的短标签: %d 处 / 去重 %d' % (len(SHORT), len({e['text'] for e in SHORT})))
print('   短标签示例:', sorted({e['text'] for e in SHORT})[:25])

uniqA = sorted({e['text'] for e in UI}, key=lambda s: (len(s), s))
with io.open('scripts/_fixA_uniq.json', 'w', encoding='utf-8') as f:
    json.dump(uniqA, f, ensure_ascii=False, indent=0)
with io.open('scripts/_fixA_uniq.txt', 'w', encoding='utf-8') as f:
    for i, t in enumerate(uniqA):
        f.write('%d\t%s\n' % (i, t.replace('\n', '\\n')))
print('\nA 去重条数 %d' % len(uniqA))
for i, t in enumerate(uniqA[:200]):
    print('%d\t%s' % (i, t.replace('\n', '\\n')))

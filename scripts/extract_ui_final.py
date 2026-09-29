# 模板感知的 Kotlin 字面量扫描：正确提取完整字面量（含 ${...} 嵌套字符串的场景），
# 并只保留「流水线能安全转换」的串（排除正则 / 不可解析模板 / 已在词表内的）。
import io, os, re, json

ROOTS = [
    'app/src/main/java/com/ai/assistance/quro/ui',
    'app/src/main/java/com/ai/assistance/quro/genui',
]
PROMPT_FILES = {
    'QuroChatViewModel.kt', 'ChatViewModel.kt', 'GenUiRules.kt', 'GenUILlmClient.kt',
    'GenUILlmClientKt.kt', 'GenUITool.kt', 'GenUiDiag.kt', 'RegisterComponentTool.kt',
    'RenderChannel.kt', 'AiActionHost.kt', 'AgentThought.kt', 'ZorvBrain.kt',
    'ChatHistory.kt', 'AgentThinkingUI.kt', 'QuroPersonaViewModel.kt',
}
HAN = re.compile(r'[\u4e00-\u9fff]')
REGEX_LIKE = re.compile(r'(\\s|\\n|\\w|\\d|\(\?|\.\+\?|\.\*\?|\[[^\]]*\]\s*[?+*])')


def kotlin_literals(src):
    """返回 [(start, end, raw_text)]；raw_text 不含首尾引号。"""
    res = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j + 1
            continue
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        # 字符字面量 '"' / '\'' ：必须先跳过，否则其中的引号会被误当成字符串起始，
        # 后续整段代码会被吞进一个假字符串（曾因此把 HTML/JS 模板都当成文案）。
        if c == "'":
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == "'":
                    break
                if src[j] == '\n':
                    break
                j += 1
            i = j + 1
            continue
        if c != '"':
            i += 1
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            if j < 0:
                break
            res.append((i, j + 3, src[i + 3:j]))
            i = j + 3
            continue
        start = i + 1
        j = start
        depth = 0
        instr = False
        while j < n:
            ch = src[j]
            if instr:
                if ch == '\\':
                    j += 2
                    continue
                if ch == '"':
                    instr = False
                j += 1
                continue
            if ch == '\\':
                j += 2
                continue
            if ch == '$' and j + 1 < n and src[j + 1] == '{':
                depth += 1
                j += 2
                continue
            if depth > 0:
                if ch == '"':
                    instr = True
                elif ch == '{':
                    depth += 1
                elif ch == '}':
                    depth -= 1
                j += 1
                continue
            if ch == '"':
                break
            j += 1
        if j >= n:
            break
        res.append((i, j + 1, src[start:j]))
        i = j + 1
    return res


def tpl_safe(t):
    """每个 ${...} 体里不得含 { } " （嵌套花括号/嵌套字符串 → 流水线无法解析，须排除）。"""
    i, n = 0, len(t)
    while i < n:
        if t[i] == '$' and i + 1 < n and t[i + 1] == '{':
            d = 0
            j = i + 1
            body = []
            while j < n:
                if t[j] == '{':
                    d += 1
                elif t[j] == '}':
                    d -= 1
                    if d == 0:
                        break
                if d >= 1 and j > i + 1:
                    body.append(t[j])
                j += 1
            if j >= n:
                return False
            b = ''.join(body)
            if any(ch in b for ch in '{}"'):
                return False
            i = j + 1
        else:
            i += 1
    return True


cat = json.load(io.open('scripts/i18n_strings.json', encoding='utf-8'))
known = {e['text'] for e in cat}

found = {}
for root in ROOTS:
    for dp, _, fns in os.walk(root):
        for fn in fns:
            if not fn.endswith('.kt') or fn in PROMPT_FILES:
                continue
            p = os.path.join(dp, fn)
            src = io.open(p, encoding='utf-8', errors='replace').read()
            for st, en, t in kotlin_literals(src):
                if not t or not HAN.search(t):
                    continue
                if t in known:
                    continue          # 已在词表，流水线已处理
                if t.strip() == '[通道]':
                    continue          # 数据值（removePrefix 用），翻译会破坏解析
                if '\n' in t or len(t) > 120:
                    continue          # 多行 raw 模板 / 长篇说明：风险高、收益低，暂不接线
                if REGEX_LIKE.search(t):
                    continue          # 正则
                if not tpl_safe(t):
                    continue          # 模板不可解析
                found.setdefault(t, []).append(
                    (p.replace('\\', '/').split('/')[-1], src.count('\n', 0, st) + 1))

print('可安全接线的新文案: 去重 %d 条' % len(found))
uniq = sorted(found.keys(), key=lambda s: (len(s), s))
json.dump(uniq, io.open('scripts/_fixB_clean.json', 'w', encoding='utf-8'),
          ensure_ascii=False, indent=0)
with io.open('scripts/_fixB_clean.txt', 'w', encoding='utf-8') as f:
    for i, t in enumerate(uniq):
        f.write('%d\t%s\n' % (i, t.replace('\n', '\\n')))
print()
for i, t in enumerate(uniq):
    print('%d\t%s' % (i, t.replace('\n', '\\n')))

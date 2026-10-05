import io, os, sys

ROOT = r"D:\Calw OS-project\QuroAI"
target = os.path.join(ROOT, "app/src/main/java/com/ai/assistance/quro/core/cards/QuroChatCard.kt")
snippet_path = os.path.join(ROOT, "app/src/main/java/com/ai/assistance/quro/core/cards/_newcards_snippet.kt.txt")

# 读原文，探测换行风格
raw = io.open(target, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in raw else "\n"
print("原文件换行风格:", repr(nl))

lines = raw.split(nl) if nl == "\n" else raw.split("\r\n")
print("总行数:", len(lines))

# 定位 sealed interface QuroChatCard 的闭合大括号
# 策略：找到 "sealed interface QuroChatCard {" 的行号，然后做花括号配平（跳过注释与字符串）
start = None
for i, l in enumerate(lines):
    if l.strip().startswith("sealed interface QuroChatCard"):
        start = i
        break
if start is None:
    print("ERR: 找不到 sealed interface QuroChatCard")
    sys.exit(1)
print("sealed interface 起始行(1-based):", start + 1)

depth = 0
close_idx = None
in_block_comment = False
in_kdoc = False
for i in range(start, len(lines)):
    l = lines[i]
    j = 0
    while j < len(l):
        two = l[j:j+2]
        if in_block_comment:
            if two == "*/":
                in_block_comment = False
                j += 2
                continue
            j += 1
            continue
        if in_kdoc:
            idx = l.find('"""', j)
            if idx < 0:
                break
            in_kdoc = False
            j = idx + 3
            continue
        two = l[j:j+2]
        if two == "//":
            break
        if two == "/*":
            in_block_comment = True
            j += 2
            continue
        if l[j:j+3] == '"""':
            in_kdoc = not in_kdoc
            j += 3
            continue
        if l[j] == '"':
            j += 1
            while j < len(l):
                if l[j] == "\\":
                    j += 2
                    continue
                if l[j] == '"':
                    j += 1
                    break
                j += 1
            continue
        if l[j] == "'":
            j += 1
            while j < len(l):
                if l[j] == "\\":
                    j += 2
                    continue
                if l[j] == "'":
                    j += 1
                    break
                j += 1
            continue
        if l[j] == "{":
            depth += 1
        elif l[j] == "}":
            depth -= 1
            if depth == 0:
                close_idx = i
                break
        j += 1
    if close_idx is not None:
        break

if close_idx is None:
    print("ERR: 花括号配平失败")
    sys.exit(1)
print("闭合大括号行(1-based):", close_idx + 1, "内容:", repr(lines[close_idx]))

# 幂等：已插入过就跳过
if any("v1400：34 种增强组件" in l for l in lines):
    print("已插入过，跳过")
    sys.exit(0)

snippet_raw = io.open(snippet_path, encoding="utf-8", newline="").read()
snip = snippet_raw.replace("\r\n", "\n").rstrip("\n").split("\n")
# snippet 自带一个 "}"，它应该替换掉原闭合括号，所以先摘掉
if snip and snip[-1].strip() == "}":
    snip = snip[:-1]

new_lines = lines[:close_idx] + snip + [lines[close_idx]]
out = nl.join(new_lines)

tmp = target + ".tmp"
io.open(tmp, "w", encoding="utf-8", newline="").write(out)
os.replace(tmp, target)
print("插入完成，新行数:", len(new_lines))

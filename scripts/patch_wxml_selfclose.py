import io, sys

path = r"D:/Calw OS-project/QuroAI/miniapp-sdk/src/main/java/com/yuanbao/miniapp/view/WxmlParser.kt"

with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

old = """            if (nextOpen != -1 && nextOpen < nextClose) {
                depth++
                i = nextOpen + openTag.length
            } else {"""

new = """            if (nextOpen != -1 && nextOpen < nextClose) {
                val gt = src.indexOf('>', nextOpen)
                if (gt != -1 && gt > nextOpen && src[gt - 1] == '/') {
                    // 自闭合标签（如 <view .../>）不计入深度，也无需配对结束标签，
                    // 否则会让外层容器的 close 匹配失败、整段子内容被丢弃（AI 常写自闭合标签）。
                    i = gt + 1
                } else {
                    depth++
                    i = nextOpen + openTag.length
                }
            } else {"""

if src.count(old) != 1:
    print("UNEXPECTED count:", src.count(old))
    sys.exit(1)

src = src.replace(old, new, 1)
with io.open(path, "w", encoding="utf-8") as f:
    f.write(src)
print("WxmlParser self-close fix applied. len=", len(src), "/ OK")

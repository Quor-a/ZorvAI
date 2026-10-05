# -*- coding: utf-8 -*-
"""修 _patch_codec.py 落错的两处补丁。

错因：find() 里用了 lines.index(l)，而 lambda 收到的 l 是**行内容**，
index() 返回的是该字符串第一次出现的位置 —— 于是 payload 兜底插进了
CounterCard 分支中间。另外两处 else 是插在 `else -> null` **前面**，
Kotlin 要求 else 只能在最后，直接报 'else' entry must be the last one。

用法：python scripts/_fix_codec_patch.py（幂等：已经改对就直接退出）
"""
import io
import os
import sys

ROOT = r"D:\Calw OS-project\QuroAI"
T = os.path.join(
    ROOT, "app/src/main/java/com/ai/assistance/quro/core/cards/QuroChatCard.kt"
)

raw = io.open(T, encoding="utf-8", newline="").read()

BAD_SPEC = (
    '            // \u2500\u2500 v1400 \u589e\u5f31\u7ec4\u4ef6\uff1a\u672a\u77e5 type \u4ea4\u7ed9 '
    "[CardSdk.parseObj]\uff08\u552f\u4e00\u89e3\u6790\u5c42\uff09\uff0c\u672a\u77e5\u843d CustomCard "
    "\u800c\u975e\u6298\u635f \u2500\u2500\n"
    "            else -> CardSdk.parseObj(s)\n"
    "            else -> null\n"
)
GOOD_SPEC = BAD_SPEC.split("            else -> null\n")[0]

BAD_PARSE = (
    '            // \u2500\u2500 v1400 \u589e\u5f31\u7ec4\u4ef6\uff1a\u89e3\u6790\u5c42\u53ea\u6709\u8fd9\u4e00\u4efd'
    "\uff08\u4e0e\u5de5\u5177\u4e0b\u53d1\u5171\u7528 [CardSdk.parseObj]\uff09\u2500\u2500\n"
    "            else -> runCatching {\n"
    '                val s = JSONObject(o.toString()).apply { put("type", t) }\n'
    "                CardSdk.parseObj(s)\n"
    "            }.getOrNull()\n"
    "            else -> null\n"
)
GOOD_PARSE = BAD_PARSE.split("            else -> null\n")[0]

BAD_COUNTER = (
    "        is QuroChatCard.CounterCard -> {\n"
    "        // \u2500\u2500 v1400 \u589e\u5f31\u7ec4\u4ef6\uff1a\u5b57\u6bb5\u5199\u76d8\u7edf\u4e00\u4ea4\u7ed9 "
    "[CardCodec]\uff08\u7f16\u7801\u5355\u4e00\u5b9e\u73b0\uff0c\u89c1\u5176\u6587\u4ef6\u5934\u6ce8\u91ca\uff09\u2500\u2500\n"
    "        else -> CardCodec.encode(card, o)\n"
)
GOOD_COUNTER = "        is QuroChatCard.CounterCard -> {\n"

changed = False

# 1) parseComponentSpec：else 合并成一行
if BAD_SPEC in raw:
    raw = raw.replace(BAD_SPEC, GOOD_SPEC, 1)
    print("1) parseComponentSpec 的 else 已合并为一行")
    changed = True

# 2) parseCard：else 合并成一行
if BAD_PARSE in raw:
    raw = raw.replace(BAD_PARSE, GOOD_PARSE, 1)
    print("2) parseCard 的 else 已合并为一行")
    changed = True

# 3) CounterCard 分支里被插错的 3 行删掉
if BAD_COUNTER in raw:
    raw = raw.replace(BAD_COUNTER, GOOD_COUNTER, 1)
    print("3) CounterCard 分支里误插的 3 行已删除")
    changed = True

# 4) payload when 兜底：插到 serializeCard 的 '    }\n    return o' 之前
if "        else -> CardCodec.encode(card, o)" not in raw:
    anchor = "    }\n    return o\n}"
    idx = raw.rfind(anchor)
    if idx < 0:
        print("ERR: 找不到 serializeCard 的收尾（'    }\\n    return o\\n}'）")
        sys.exit(1)
    ins = (
        '\n        // \u2500\u2500 v1400 \u589e\u5f31\u7ec4\u4ef6\uff1a\u5b57\u6bb5\u5199\u76d8\u7edf\u4e00\u4ea4\u7ed9 '
        "[CardCodec]\uff08\u7f16\u7801\u5355\u4e00\u5b9e\u73b0\uff0c\u89c1\u5176\u6587\u4ef6\u5934\u6ce8\u91ca\uff09\u2500\u2500\n"
        "        else -> CardCodec.encode(card, o)"
    )
    raw = raw[:idx] + ins + raw[idx:]
    print("4) payload when 兜底（CardCodec.encode）已插到 serializeCard 收尾前")
    changed = True
else:
    print("4) payload 兜底已存在，跳过")

if not changed:
    print("\u5df2\u7ecf\u5bf9\u4e86\uff0c\u65e0\u9700\u4fee\u6539\uff1b\u66f4\u65b0\u4e2d\u3002")
    sys.exit(0)

tmp = T + ".tmp"
io.open(tmp, "w", encoding="utf-8", newline="").write(raw)
os.replace(tmp, T)
print("\u539f\u5b50\u5199\u5b8c\u6210\uff0c\u65b0\u4e8b\u4ef6\u957f\u5ea6:", len(raw))

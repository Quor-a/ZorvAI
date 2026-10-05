# -*- coding: utf-8 -*-
"""修 QuroComponentGalleryScreen 首编报错（CardTemplate 字段名 / 分组推断 / 缺 import）。"""
import io
import os
import sys

P = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "java", "com", "ai", "assistance", "quro", "ui",
    "QuroComponentGalleryScreen.kt",
)

with io.open(P, "r", encoding="utf-8", newline="") as f:
    raw = f.read()
crlf = "\r\n" in raw
s = raw.replace("\r\n", "\n")

edits = []


def rep(old, new, tag):
    edits.append((tag, old, new))


# 1. 补 Row / Spacer import
rep(
    "import androidx.compose.foundation.layout.FlowRow\n"
    "import androidx.compose.foundation.layout.fillMaxSize\n"
    "import androidx.compose.foundation.layout.fillMaxWidth\n"
    "import androidx.compose.foundation.layout.height\n",
    "import androidx.compose.foundation.layout.FlowRow\n"
    "import androidx.compose.foundation.layout.Row\n"
    "import androidx.compose.foundation.layout.Spacer\n"
    "import androidx.compose.foundation.layout.fillMaxSize\n"
    "import androidx.compose.foundation.layout.fillMaxWidth\n"
    "import androidx.compose.foundation.layout.height\n",
    "import Row/Spacer",
)

# 2. 分组显式 List<Pair>，不再让 + (nullable) 把类型推断成 List<Pair?>
rep(
    "    val grouped: List<Pair<String, List<CardTemplate>>> = remember(catalog, query) {\n"
    "        val q = query.trim()\n"
    "        val hit = if (q.isEmpty()) catalog else catalog.filter {\n"
    "            it.type.contains(q, ignoreCase = true) ||\n"
    "                it.description.contains(q, ignoreCase = true) ||\n"
    "                it.category.contains(q, ignoreCase = true)\n"
    "        }\n"
    "        CATEGORY_ORDER.mapNotNull { c ->\n"
    "            val list = hit.filter { it.category == c }\n"
    "            if (list.isEmpty()) null else c to list\n"
    "        } + (hit.filter { it.category !in CATEGORY_ORDER }.let { rest ->\n"
    "            if (rest.isEmpty()) null else CATEGORY_FALLBACK to rest\n"
    "        })\n"
    "    }\n",
    "    val grouped = remember(catalog, query) {\n"
    "        val q = query.trim()\n"
    "        val hit = if (q.isEmpty()) catalog else catalog.filter {\n"
    "            it.type.contains(q, ignoreCase = true) ||\n"
    "                it.description.contains(q, ignoreCase = true) ||\n"
    "                it.category.contains(q, ignoreCase = true)\n"
    "        }\n"
    "        val out = ArrayList<Pair<String, List<CardTemplate>>>()\n"
    "        CATEGORY_ORDER.forEach { c ->\n"
    "            val list = hit.filter { it.category == c }\n"
    "            if (list.isNotEmpty()) out.add(c to list)\n"
    "        }\n"
    "        val rest = hit.filter { it.category !in CATEGORY_ORDER }\n"
    "        if (rest.isNotEmpty()) out.add(CATEGORY_FALLBACK to rest)\n"
    "        out\n"
    "    }\n",
    "grouped",
)

# 3. CardTemplate 字段是 sampleJson（不是 sample）
rep("spec.sample, spec.type", "spec.sampleJson, spec.type", "sampleJson")

# 4. 三引号串里直接写花括号引号，不用转义
rep(
    """                        FenceHint("cards", "多组件：JSON 数组，或 {\\"layout\\":..,\\"children\\":[..]} 合成一张组合卡")""",
    """                        FenceHint("cards", "多组件：JSON 数组，或 {\\"layout\\":..,\\"children\\":[..]} 合成一张组合卡")""",
    "fencehint",
)
rep(
    '''                        FenceHint("cards", "多组件：JSON 数组，或 {\\"layout\\":..,\\"children\\":[..]} 合成一张组合卡")''',
    '''                        FenceHint("cards", "多组件：JSON 数组，或 {"layout":..,"children":[..]} 合成一张组合卡")''',
    "fencehint-raw",
)

# 5. SectionTitle 参数是 ColorScheme 不是 Color
rep(
    "private fun SectionTitle(text: String, cs: Color) {",
    "private fun SectionTitle(text: String, cs: androidx.compose.material3.ColorScheme) {",
    "sectiontitle",
)

for tag, old, new in edits:
    n = s.count(old)
    if n == 0:
        print("[SKIP] %s（未命中，可能已被上一处修好）" % tag)
        continue
    if n != 1:
        print("[FAIL] %s: 命中 %d 次" % (tag, n))
        sys.exit(1)
    s = s.replace(old, new, 1)
    print("[OK]   %s" % tag)

if crlf:
    s = s.replace("\n", "\r\n")
tmp = P + ".tmp"
with io.open(tmp, "w", encoding="utf-8", newline="") as f:
    f.write(s)
os.replace(tmp, P)
print("\n写盘完成")

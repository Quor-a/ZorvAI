import io, os

p = 'app/src/main/java/com/ai/assistance/quro/core/rag/PromptRagIndex.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

old = """            triggers = listOf("格式", "排版", "表格", "显示", "展示", "出图", "图片", "挂载", "文件"),
            keywords = listOf("attach_file", "ui_card", "ui_widget", "codecanvas", "输出格式"),"""

new = """            triggers = listOf(
                "格式", "排版", "表格", "显示", "展示", "出图", "图片", "挂载", "文件",
                // 🔴 2026-10-08 补组件选型触发词（用户报「老是使用同一个类型组件」）。
                // 这些词不进 triggers 的话，用户说「排名 / 趋势 / 占比 / 时间线」时
                // 本段根本召不回，AI 就退回到最熟的 table/keyvalue。
                "排名", "排行", "趋势", "占比", "比例", "时间线", "对比", "仪表盘",
                "打分", "评分", "进度", "看板", "热力图", "散点", "甘特", "漏斗",
            ),
            keywords = listOf(
                "attach_file", "ui_card", "ui_widget", "codecanvas", "输出格式",
                "card_catalog", "find", "scoreboard", "gauge", "pie", "timeline",
                "compare", "heatmap", "speedometer", "kanban", "gantt",
            ),"""

assert src.count(old) == 1, 'output_format anchor count=%d' % src.count(old)
src = src.replace(old, new, 1)

# 正文里补一条组件选型纪律（这是基础段，每轮都看得见）
old2 = """                - 需要精确排版（表格 / 图表 / 海报 / 代码卡）时用可视化组件工具或 CodeCanvas 出图，
                  不要用 markdown 表格硬凑。
            \"\"\".trimIndent(),"""

new2 = """                - 需要精确排版（表格 / 图表 / 海报 / 代码卡）时用可视化组件工具或 CodeCanvas 出图，
                  不要用 markdown 表格硬凑。
                - 🔴 **不要反复用同一个组件类型**。名册里有 101 种（`card_catalog`
                  能按效果模糊检索，也有全程常驻的 `ui_widget` / `ui_card`）。
                  同一段对话里若上一张用的是表格，这次数据换了性质就该换组件：
                  排名 → scoreboard、占比 → pie、趋势 → chart/line、完成度 → gauge/speedometer、
                  时间线 → timeline、多维打分 → matrix、分组对比 → compare、进度安排 → kanban/gantt。
                  **拿不准时先 `card_catalog(find="用户想要什么效果")` 查一次**，
                  比凭记忆瞎选一个类型强得多。
            \"\"\".trimIndent(),"""

assert src.count(old2) == 1, 'body anchor count=%d' % src.count(old2)
src = src.replace(old2, new2, 1)

tmp = p + '.tmp'
io.open(tmp, 'w', encoding='utf-8', newline='').write(src)
os.replace(tmp, p)
print('OK  %d -> %d' % (len(orig), len(src)))
# -*- coding: utf-8 -*-
"""把 CardSdk.compactCatalog() 接进 ui_widget / ui_card 的工具描述 + 报错提示。

解决的问题：92 种组件的清单模型完全看不到（工具描述里是手写的旧清单，停在 v1068 的
四十来种），第一批、第二批新增的 45 种模型压根不知道，只能靠围栏里撞样例去猜。
本脚本让描述改为运行时从名册生成，名册一扩就自动同步，不再手抄漂移。
"""
import io, os, sys

ROOT = "app/src/main/java/com/ai/assistance/quro/core/tools/"

# ---------- 1) ui_widget ----------
W = ROOT + "QuroToolsUiWidget.kt"
w = io.open(W, encoding="utf-8").read()

OLD_HEAD = '    override val description = "在对话框内直接渲染一张可交互 UI 组件。'
NEW_HEAD = ('    // 组件清单不手抄：从 CardSdk 名册运行时生成（92 种），名册一扩这里自动同步。\n'
            '    // 完整样例用 card_catalog 按需拉，避免十几 KB 样例常驻进系统提示词。\n'
            '    override val description = "在对话框内直接渲染一张可交互 UI 组件。')

if OLD_HEAD not in w:
    print("ABORT: ui_widget 描述锚点未命中")
    sys.exit(1)
w = w.replace(OLD_HEAD, NEW_HEAD, 1)

# 追加清单 + catalog 指引到描述末尾（拼在字符串尾部）
OLD_TAIL = '"以及 v221 新增 open:<url>（内置浏览器打开）/ copy:<文本>（复制剪贴板）/ ai:<提示词>（直接发给 AI）/ screen:<名称>（界面导航）。"'
NEW_TAIL = ('"以及 v221 新增 open:<url>（内置浏览器打开）/ copy:<文本>（复制剪贴板）/ ai:<提示词>（直接发给 AI）/ screen:<名称>（界面导航）。" +\n'
            '        "可用组件共 ${CardSdk.typeCount} 种，按类目：${CardSdk.compactCatalog()}。" +\n'
            '        "要写某类的完整字段与样例，先调 card_catalog（参数 category 或 types），别凭空猜字段名。"')
if OLD_TAIL not in w:
    print("ABORT: ui_widget 描述尾部锚点未命中")
    sys.exit(1)
w = w.replace(OLD_TAIL, NEW_TAIL, 1)

# import CardSdk
if "import com.ai.assistance.quro.core.cards.CardSdk" not in w:
    w = w.replace("import com.ai.assistance.quro.core.cards.QuroChatCardStore",
                  "import com.ai.assistance.quro.core.cards.CardSdk\nimport com.ai.assistance.quro.core.cards.QuroChatCardStore", 1)

# 报错提示：静态手写清单 -> 指向 card_catalog
OLD_ERR_A = 'return "❌ 未知组件类型或 spec 解析失败（请检查 type 与字段，支持 button/toggle/slider/progress/stat/alert/table/list/segmented/pie/rating/countdown/tabs/expandable/form/chips/steps/gauge/media/info/toolcall/stream/mediaplay/quickreply/quickaction/timeline/heatmap/compare/radar/timer/carousel/kanban 及 v221 新增 color/counter/breadcrumb/tagcloud/badge/avatargroup 与 v300 新增 mermaid（AI 自写 Mermaid 图表）与 v1057 新增 miniapp（AI Web 应用）与 v1068 新增 composite（多语言组合卡，可组合可单渲染）与 yuanbao（链接回答卡）/ htmlpreview（HTML 预览卡）详见 CARD_CATALOG；legacy 仍支持 todo/chart/note/actions；链接 yb.tencent.com 会自动生成预览卡）"'
NEW_ERR_A = 'return "❌ 未知组件类型或 spec 解析失败。可用 type 共 ${CardSdk.typeCount} 种：${CardSdk.compactCatalog()}。调 card_catalog 可取完整样例。"'
if OLD_ERR_A not in w:
    print("ABORT: ui_widget 报错锚点未命中")
    sys.exit(1)
w = w.replace(OLD_ERR_A, NEW_ERR_A, 1)

tmp = W + ".tmp"
io.open(tmp, "w", encoding="utf-8", newline="\n").write(w)
os.replace(tmp, W)
print("OK: ui_widget 已接名册")

# ---------- 2) ui_card ----------
C = ROOT + "QuroToolsUiCards.kt"
c = io.open(C, encoding="utf-8").read()

OLD_C = '"以及 v221 新增的 open:<url>（内置浏览器打开）/ copy:<文本>（复制剪贴板）/ ai:<提示词>（直接发给 AI）/ screen:<名称>（界面导航）。"'
NEW_C = ('"以及 v221 新增的 open:<url>（内置浏览器打开）/ copy:<文本>（复制剪贴板）/ ai:<提示词>（直接发给 AI）/ screen:<名称>（界面导航）。" +\n'
         '        "与 ui_widget 共用同一份名册，共 ${CardSdk.typeCount} 种：${CardSdk.compactCatalog()}。" +\n'
         '        "要完整字段与样例先调 card_catalog。"')
if OLD_C not in c:
    print("ABORT: ui_card 描述锚点未命中")
    sys.exit(1)
c = c.replace(OLD_C, NEW_C, 1)

OLD_ERR_C = ('?: return "❌ 未知卡片类型（kind/type 均不支持）。基础：todo/chart/note/actions；" +\n'
             '                    "全量类型见 ui_widget 工具与 CARD_CATALOG（button/toggle/slider/progress/stat/alert/table/list/segmented/pie/rating/countdown/tabs/expandable/form/chips/steps/gauge/media/info/toolcall/stream/mediaplay/quickreply/quickaction/timeline/heatmap/compare/radar/timer/carousel/kanban/color/counter/breadcrumb/tagcloud/badge/avatargroup/mermaid/miniapp/composite/yuanbao/htmlpreview）"')
NEW_ERR_C = ('?: return "❌ 未知卡片类型（kind/type 均不支持）。基础：todo/chart/note/actions。" +\n'
             '                    "全量共 ${CardSdk.typeCount} 种：${CardSdk.compactCatalog()}。调 card_catalog 取完整样例。"')
if OLD_ERR_C not in c:
    print("ABORT: ui_card 报错锚点未命中")
    sys.exit(1)
c = c.replace(OLD_ERR_C, NEW_ERR_C, 1)

if "import com.ai.assistance.quro.core.cards.CardSdk" not in c:
    c = c.replace("import com.ai.assistance.quro.core.cards.QuroChatCardStore",
                  "import com.ai.assistance.quro.core.cards.CardSdk\nimport com.ai.assistance.quro.core.cards.QuroChatCardStore", 1)

tmp = C + ".tmp"
io.open(tmp, "w", encoding="utf-8", newline="\n").write(c)
os.replace(tmp, C)
print("OK: ui_card 已接名册")
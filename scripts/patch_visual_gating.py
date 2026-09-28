# -*- coding: utf-8 -*-
import io

def patch(path, repls):
    with io.open(path, "r", encoding="utf-8") as f:
        c = f.read()
    for old, new in repls:
        assert old in c, ("NOT FOUND in " + path + ":\n" + old[:80])
        c = c.replace(old, new, 1)
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(c)
    print("patched:", path)

# 1) 工具使用提示
hints = r"app/src/main/java/com/ai/assistance/quro/core/tools/QuroToolUsageHints.kt"
patch(hints, [(
    '"visual_analysis" to "「看看屏幕上是什么」「分析这个页面」「屏幕上有什么按钮/文字/图标」都调用——当read_screen节点树不够用时（游戏/WebView/Flutter/自绘UI），用视觉模型分析截图",',
    '"visual_analysis" to "任何需要「看见屏幕」的场景都调用：看屏幕内容、识别按钮/文字/图标、理解游戏或 App 界面、找某个元素、OCR 提取文字、辅助操作。当 read_screen 节点树不够用时（游戏/WebView/Flutter/自绘 UI），用视觉大模型真实分析截图，mode 可选 general/ui/ocr/game/find。",'
)])

# 2) 能力目录
cap = r"app/src/main/java/com/ai/assistance/quro/core/tools/ToolCapabilityDirectory.kt"
cap_old = '''        "visual_analysis" to ToolInfo(
            name = "visual_analysis",
            category = ToolCategory.ACCESSIBILITY,
            description = "视觉分析屏幕内容",
            useCases = listOf("看看屏幕上是什么", "分析这个页面", "屏幕上有什么按钮/文字/图标"),
            examples = listOf("visual_analysis()"),
            parameters = emptyMap(),
            tips = listOf("用视觉模型分析截图", "适合游戏/WebView/Flutter", "比read_screen更全面"),
            relatedTools = listOf("screenshot", "read_screen"),
            priority = 4'''
cap_new = '''        "visual_analysis" to ToolInfo(
            name = "visual_analysis",
            category = ToolCategory.ACCESSIBILITY,
            description = "视觉分析当前屏幕截图（真实视觉理解）：用视觉大模型识别内容，任何需要看见屏幕的场景都可使用",
            useCases = listOf("看看屏幕上是什么", "分析这个页面/App/游戏界面", "屏幕上有什么按钮/文字/图标", "定位某个元素并操作", "OCR 提取截图文字", "辅助点击/操作"),
            examples = listOf("visual_analysis()", "visual_analysis(question=\\"这个按钮是做什么的\\", mode=\\"ui\\")"),
            parameters = emptyMap(),
            tips = listOf("任何场景都能用：只要看见屏幕有助于回答就用", "比 read_screen 更全面（游戏/WebView/Flutter/自绘UI）", "mode=general/ui/ocr/game/find", "需配置支持图像的模型"),
            relatedTools = listOf("screenshot", "read_screen"),
            priority = 4'''
patch(cap, [(cap_old, cap_new)])

# 3) 系统提示词「智能识别」段
vm = r"app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt"
old = ("**智能识别**\ufffd?\n"
       "            - 「屏幕上有什么按钮」→ visual_analysis（当节点树无法识别时用视觉模型）\n"
       "            - 「这个游戏界面怎么操作」→ visual_analysis + tap_screen\n"
       "            - 「这个网页上有什么」→ visual_analysis")
new = ("**屏幕视觉理解（visual_analysis，任何场景可用）**\n"
       "            - visual_analysis 用视觉大模型真实「看」截图，比 read_screen 节点树更全面，任何需要「看见屏幕」的场景都可使用\n"
       "            - 「看看屏幕上是什么」「分析这个页面/App/游戏」「屏幕上有什么按钮/文字/图标」「定位某个元素」「OCR 提取文字」「辅助点击操作」等都调用\n"
       '            - mode 可选：general(综合描述) / ui(UI元素识别) / ocr(文字提取) / game(游戏/App界面) / find(定位目标元素)\n'
       '            - 例：「这个游戏界面怎么操作」→ visual_analysis(mode="game") + tap_screen；「这个网页上有什么」→ visual_analysis()')
patch(vm, [(old, new)])
print("done")

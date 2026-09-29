# -*- coding: utf-8 -*-
"""
把四条语言指令文案升级为「中英双语」。

为什么：模型（尤其国产/开源系）对**英文指令**的遵循度普遍高于中文指令，
而本项目「界面切英文、AI 仍回中文」的问题正是「指令语言被当回复语言」的典型症状。
双语 = 英文负责强约束（模型跟得紧），中文负责点破陷阱（读者是懂中文的模型时更直观）。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
path = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'ai', 'assistance', 'quro',
                    'core', 'QuroReplyLanguage.kt')

data = open(path, 'rb').read()
nl = '\r\n' if b'\r\n' in data else '\n'
text = data.decode('utf-8')

edits = [
    # directive()
    (r'''        val name = nameOf(tag)
        return buildString {
            append("## 回复语言（最高优先级）\n")
            append("你的**所有**回复必须使用 ").append(name).append(" 书写。\n")
            append("注意：本系统提示词、工具说明、人格设定等其余内容都是用中文写的，")
            append("那只是**指令语言**，并不代表回复语言 —— 绝不要因为看到中文就用中文回答。\n")
            append("仅当用户在对话中明确要求换用其他语言时，才改用用户指定的语言。\n\n")
        }''',
     r'''        val name = nameOf(tag)
        // 中英双语：英文句负责强约束（模型对英文指令的遵循度更高），中文句负责点破陷阱。
        return buildString {
            append("## Response Language / 回复语言（HIGHEST PRIORITY · 最高优先级）\n")
            append("You MUST write **all** of your replies in ").append(name).append(".\n")
            append("That applies to every sentence you output, including lists, summaries and interface copy.\n")
            append("NOTE: the rest of this system prompt, all tool descriptions, the persona settings and the ")
            append("conversation history are written in Chinese — that is only the **instruction language**, ")
            append("NOT the reply language. Never answer in Chinese just because you see Chinese above.\n")
            append("（中文说明：你的每一句回复都必须用 ").append(name)
            append(" 书写；上面那些中文只是指令语言，不代表回复语言，绝不要因此改用中文回答。）\n")
            append("Only switch to another language when the user explicitly asks for it in the conversation.\n\n")
        }'''),

    # shortDirective()
    (r'''        return "## 回复语言\n你必须始终用 " + nameOf(tag) + " 回答，不要用中文。\n\n"''',
     r'''        // 本地小模型对长指令遵循度低，故双语各一句、只保留最硬的约束。
        return "## Response Language / 回复语言\nAlways answer in " + nameOf(tag) +
            ". You must NOT answer in Chinese. " +
            "（显示：必须始终用 " + nameOf(tag) + " 回答，不要用中文。）\n\n"'''),

    # turnNudge()
    (r'''        return "【回复语言】本轮回复必须使用 " + nameOf(tag) + " 书写。" +
            "上面的历史消息只是历史记录，不要因为它们大多是中文就继续用中文回答。"''',
     r'''        return "【Response Language / 回复语言】You must reply in " + nameOf(tag) +
            ", not Chinese. 本轮回复必须用 " + nameOf(tag) + " 书写，不要用中文。" +
            "上面的历史消息只是历史记录，不要因为它们大多是中文就继续用中文回答。"'''),

    # tailReminder()
    (r'''        return "\n\n## ⚠️ 语言约束（最末尾·重申）\n接下来的全部输出必须用 " +
            nameOf(tag) + " 书写，禁止使用中文。\n"''',
     r'''        return "\n\n## ⚠️ Response Language — LAST REMINDER / 语言约束·最末尾·重申\n" +
            "Everything you output from now on MUST be written in " + nameOf(tag) +
            ". Do NOT use Chinese. （接下来所有输出必须用 " + nameOf(tag) + " 书写，禁止中文。）\n"'''),
]

for i, (old, new) in enumerate(edits):
    if nl == '\r\n':
        old, new = old.replace('\n', '\r\n'), new.replace('\n', '\r\n')
    n = text.count(old)
    if n != 1:
        print('FAIL edit#%d count=%d\n  %r' % (i, n, old[:130]))
        sys.exit(1)
    text = text.replace(old, new, 1)

open(path, 'wb').write(text.encode('utf-8'))
print('ok core/QuroReplyLanguage.kt (%d 处文案已双语化)' % len(edits))

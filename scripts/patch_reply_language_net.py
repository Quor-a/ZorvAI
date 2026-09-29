# -*- coding: utf-8 -*-
"""
在 QuroAssistant.ask() 这条「所有调用者必经」的路径上补一道语言兜底网。

理由：
  - ask() 是主对话 / 语音球 / 视频通话 / 粘贴键盘 / IM 机器人 共同的出口，
    在这里把语言约束压到 system 消息**最末尾**（= 最高近因偏好），即使某个调用方
    自己的提示词忘了带语言指令，也不会退回中文。
  - 中文（默认情况）时 tailReminder() 返回空串 → 零 token 成本、零行为变化。
  - TranslateTool / WebSearchTools 走 QuroLlmClient.chat() 直连，不经过 ask()，
    因此「翻译成指定语言」这类必须覆盖 UI 语言的场景不受影响。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'ai', 'assistance', 'quro')
path = os.path.join(J, 'core', 'QuroAssistant.kt')

data = open(path, 'rb').read()
nl = '\r\n' if b'\r\n' in data else '\n'
text = data.decode('utf-8')

old = '''            val system = QuroMessage(
                role = "system",
                content = systemPrompt + buildDeepThinkDirective(deepThink),
            )'''

new = '''            // 「AI 回复语言」兜底网：ask() 是所有调用方（主对话 / 语音球 / 视频通话 /
            // 粘贴键盘 / IM 机器人）共同的出口。把语言约束压到 system 消息最末尾
            // （= 最高近因偏好），即使某个调用方自己的提示词漏了语言指令，也不会退回中文。
            // 中文（默认）时 tailReminder() 返回空串 → 零 token 成本、零行为变化。
            // 注：TranslateTool / WebSearchTools 直连 QuroLlmClient.chat()，不经过本函数，
            // 因此「翻译成指定语言」这类需要覆盖 UI 语言的场景不受影响。
            val langTail = if (systemPrompt.isBlank()) "" else QuroReplyLanguage.tailReminder(context)
            val system = QuroMessage(
                role = "system",
                content = systemPrompt + buildDeepThinkDirective(deepThink) + langTail,
            )'''

if nl == '\r\n':
    old = old.replace('\n', '\r\n')
    new = new.replace('\n', '\r\n')

n = text.count(old)
if n != 1:
    print('FAIL count=%d' % n)
    sys.exit(1)
text = text.replace(old, new, 1)
open(path, 'wb').write(text.encode('utf-8'))
print('ok core/QuroAssistant.kt ask() 兜底网已加')

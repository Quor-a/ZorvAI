# -*- coding: utf-8 -*-
"""
补「最高近因」语言注入。

问题：system 提示词里的语言指令位于消息列表开头，而它后面跟着整段中文历史
（尤其上一轮 AI 自己的中文回复）—— 模型顺着最近的历史继续说中文，
开头那句指令被稀释。这正是「对话框英文，AI 仍回中文」最难缠的一层。

做法：在每轮任务**首次请求**的 payload 末尾追加一条 system 提醒
（turnNudge），使它成为生成位置之前最近的一条指令。
  · 只进 llmMessages（API payload），**不写 store** → 不污染历史、不占后续轮次 token；
  · 中文（默认）时返回空串 → 零 token、零行为变化；
  · 尾随 system 消息在本仓库已有成熟先例（中途注入的收尾提醒 / 失败提示 / 任务方案），
    现行上游均正常接受，故不引入新的兼容性风险。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'ai', 'assistance', 'quro')


def read(path):
    data = open(path, 'rb').read()
    return data.decode('utf-8'), ('\r\n' if b'\r\n' in data else '\n')


def write(path, text):
    open(path, 'wb').write(text.encode('utf-8'))


def patch(rel, edits):
    path = os.path.join(J, rel)
    text, nl = read(path)
    for i, (old, new) in enumerate(edits):
        if nl == '\r\n':
            old, new = old.replace('\n', '\r\n'), new.replace('\n', '\r\n')
        n = text.count(old)
        if n != 1:
            print('FAIL %s edit#%d count=%d\n  %r' % (rel, i, n, old[:120]))
            sys.exit(1)
        text = text.replace(old, new, 1)
    write(path, text)
    print('ok %s (%d 处)' % (rel, len(edits)))


# 1) 注入器新增 turnNudge
patch('core/QuroReplyLanguage.kt', [(
    r'''    /**
     * 尾部复述（近因强化）。''',
    r'''    /**
     * 生成前提醒（最高近因，作为消息列表最后一条 system 注入，只进 API payload、不进历史）。
     *
     * 为什么光有 system 提示词里的指令还不够：那条指令排在消息列表**开头**，它后面跟着整段中文
     * 历史（尤其上一轮 AI 自己写的中文回复）。模型顺着最近的历史继续说中文，开头那句就被稀释了。
     * 这条放在生成位置之前，是模型看到的最后一条指令，实测对「中途切语言」最有效。
     */
    fun turnNudge(ctx: Context): String {
        val tag = try {
            resolveTag(ctx)
        } catch (_: Throwable) {
            return ""
        }
        if (!needsDirective(tag)) return ""
        return "【回复语言】本轮回复必须使用 " + nameOf(tag) + " 书写。" +
            "上面的历史消息只是历史记录，不要因为它们大多是中文就继续用中文回答。"
    }

    /**
     * 尾部复述（近因强化）。''',
)])

# 2) ask() 的 payload 末尾追加 turnNudge
patch('core/QuroAssistant.kt', [(
    r'''                val llmMessages = runCatching { store.toLlmMessages(system, effContextWindow, effHistoryRounds) }.getOrElse { emptyList() }''',
    r'''                val baseMessages = runCatching { store.toLlmMessages(system, effContextWindow, effHistoryRounds) }.getOrElse { emptyList() }
                // 「AI 回复语言」最高近因注入：历史消息（尤其上一轮 AI 自己的回复）多为中文，
                // 模型会顺着最近的历史继续说中文，system 提示词开头的语言指令会被稀释。
                // 这里在本轮首次请求的 payload 末尾追加一条 system 提醒，使其成为生成前最近的一条指令。
                // 只进 payload、不写 store → 不污染历史、不占后续轮次 token；中文时为空串 → 完全无影响。
                val langNudge = if (round == 1) QuroReplyLanguage.turnNudge(context) else ""
                val llmMessages = if (langNudge.isBlank()) {
                    baseMessages
                } else {
                    baseMessages + QuroChatMessage("system", langNudge)
                }''',
)])

print('\n完成')

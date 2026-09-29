# -*- coding: utf-8 -*-
"""
GenUI Agent（genui/aiapp/viewmodel/ChatViewModel）也补语言近因注入。

该页面跑的是**自己独立的一套消息列表**（GenUISessionStore 里存的历史多为中文），
只靠 ZorvBrain.systemPrompt() 开头的语言指令，同样会被中文历史拉回去。
它本就有「每轮在 payload 末尾追加 system 消息」的成熟模式（renderRulesMessage），
照着加一条语言提醒即可 —— 只进本次 payload，不写入会话历史。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'ai', 'assistance', 'quro')
path = os.path.join(J, 'genui', 'aiapp', 'viewmodel', 'ChatViewModel.kt')

data = open(path, 'rb').read()
nl = '\r\n' if b'\r\n' in data else '\n'
text = data.decode('utf-8')

edits = [
    # import
    ('import com.ai.assistance.quro.core.model.QuroModelConfig\n',
     'import com.ai.assistance.quro.core.QuroReplyLanguage\n'
     'import com.ai.assistance.quro.core.model.QuroModelConfig\n'),

    # 请求点追加
    (r'''                    currentState.conversationHistory,
                    maxTurns = maxHistoryTurns
                ) + renderRulesMessage(forcedChannel),''',
     r'''                    currentState.conversationHistory,
                    maxTurns = maxHistoryTurns
                ) + renderRulesMessage(forcedChannel) + listOfNotNull(replyLanguageMessage()),'''),

    # 新增 helper（紧跟 renderRulesMessage 之后）
    (r'''        else "")
    )

    /**
     * 工具调用循环''',
     r'''        else "")
    )

    /**
     * 「AI 回复语言」最高近因注入（GenUI Agent 独立消息列表）。
     *
     * 本页历史存在 GenUISessionStore、绝大多数是中文，模型会顺着历史继续说中文；
     * 只靠 system 开头的语言指令压不住。这里在生成位置前再补一条（与 renderRulesMessage 同一模式）：
     * **只进本次 payload，不写入会话历史**。中文（默认）时返回 null → 零影响。
     */
    private fun replyLanguageMessage(): GenUIChatMessage? {
        val nudge = runCatching {
            QuroReplyLanguage.turnNudge(getApplication<Application>().applicationContext)
        }.getOrDefault("")
        return if (nudge.isBlank()) null else GenUIChatMessage(role = "system", content = nudge)
    }

    /**
     * 工具调用循环'''),
]

for i, (old, new) in enumerate(edits):
    if nl == '\r\n':
        old, new = old.replace('\n', '\r\n'), new.replace('\n', '\r\n')
    n = text.count(old)
    if n != 1:
        print('FAIL edit#%d count=%d\n  %r' % (i, n, old[:120]))
        sys.exit(1)
    text = text.replace(old, new, 1)

open(path, 'wb').write(text.encode('utf-8'))
print('ok genui/aiapp/viewmodel/ChatViewModel.kt (3 处)')

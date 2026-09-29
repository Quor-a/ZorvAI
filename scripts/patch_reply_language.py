# -*- coding: utf-8 -*-
"""
把 QuroReplyLanguage（AI 回复语言统一注入器）接到全部系统提示词构建点。

背景（用户反馈：「对话框英文或者其他模式AI仍然回复中文」）：
  - 只有主对话的**云端分支**拼了很弱的语言提示，`app_language == "system"` 时压根不注入；
  - 本地模型极简分支提前 return，没有任何语言指令；
  - 语音球 / 视频通话 / GenUI Agent(ZorvBrain) / IM 机器人 / 子智能体 五条路径全漏；
  - 且极简分支/机器人还写死「用中文回答」，与界面语言直接冲突。

本脚本做纯文本定点替换，每处都断言只命中一次，避免误改。
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'ai', 'assistance', 'quro')

applied = []


def read(path):
    data = open(path, 'rb').read()
    nl = '\r\n' if b'\r\n' in data else '\n'
    return data.decode('utf-8'), nl


def write(path, text):
    open(path, 'wb').write(text.encode('utf-8'))


def patch(rel, edits):
    path = os.path.join(J, rel)
    text, nl = read(path)
    for i, (old, new) in enumerate(edits):
        if nl == '\r\n':
            old = old.replace('\n', '\r\n')
            new = new.replace('\n', '\r\n')
        n = text.count(old)
        if n != 1:
            print('FAIL %s edit#%d count=%d' % (rel, i, n))
            print('  anchor head: %r' % old[:100])
            sys.exit(1)
        text = text.replace(old, new, 1)
        applied.append('%s#%d' % (rel, i))
    write(path, text)
    print('ok %s (%d 处)' % (rel, len(edits)))


# ────────────────────────────── 1. 注入器补 tailReminder ──────────────────────────────
patch('core/QuroReplyLanguage.kt', [(
    r'''        if (!needsDirective(tag)) return ""
        return "## 回复语言\n你必须始终用 " + nameOf(tag) + " 回答，不要用中文。\n\n"
    }
}''',
    r'''        if (!needsDirective(tag)) return ""
        return "## 回复语言\n你必须始终用 " + nameOf(tag) + " 回答，不要用中文。\n\n"
    }

    /**
     * 尾部复述（近因强化）。
     *
     * 云端完整系统提示词可达 16k+ 字符、全篇中文。语言指令只放在开头时，模型读到末尾的近因
     * 偏好会盖过开头那句 —— 真机表现就是「开头明明写了用英文，回答还是中文」。故在最末尾再复述
     * 一次，与本项目「人格卡可视化开关」同属最高近因偏好区。
     */
    fun tailReminder(ctx: Context): String {
        val tag = try {
            resolveTag(ctx)
        } catch (_: Throwable) {
            return ""
        }
        if (!needsDirective(tag)) return ""
        return "\n\n## ⚠️ 语言约束（最末尾·重申）\n接下来的全部输出必须用 " +
            nameOf(tag) + " 书写，禁止使用中文。\n"
    }
}''',
)])

# ────────────────────────────── 2. 主对话 QuroChatViewModel ──────────────────────────────
patch('ui/QuroChatViewModel.kt', [
    # 2.1 import
    ('import com.ai.assistance.quro.core.QuroPlatformManifest\n',
     'import com.ai.assistance.quro.core.QuroPlatformManifest\n'
     'import com.ai.assistance.quro.core.QuroReplyLanguage\n'),

    # 2.2 本地极简分支：此前完全没有语言指令 → 本地模型在英文界面下仍回中文
    (r'''            QuroDiag.log(
                "SysPrompt",
                "built | local=true | persona-core-only | chars=${out.length} | ~tokens=${out.length / 3 * 2}"
            )
            return out''',
     r'''            // 「AI 回复语言」：本地极简分支此前**完全没有**语言指令（云端那句在下方、本函数早已 return，
            // 对本地永远不可达）→ 界面切英文/其他语言时，本地模型仍用中文作答。
            // 本地模型上下文极紧，只用一句话版（shortDirective），且必须放在最前面。
            val localized = QuroReplyLanguage.shortDirective(appContext) + out
            QuroDiag.log(
                "SysPrompt",
                "built | local=true | persona-core-only | replyLang=${QuroReplyLanguage.resolveTag(appContext)}" +
                    " | chars=${localized.length} | ~tokens=${localized.length / 3 * 2}"
            )
            return localized'''),

    # 2.3 云端分支：语言指令放到最前（紧跟平台基座）
    (r'''        // 应用语言感知：让用户所选语言成为 AI 默认回复语言（用户改用其他语言时自然跟随）
        val appLang = _appLanguage.value
        if (appLang != "system") {
            val langName = com.ai.assistance.quro.util.QuroLocale.LANGUAGE_NAMES[appLang] ?: appLang
            sb.append("## 回复语言\n请用 ").append(langName).append(" 回复用户（除非用户用其他语言提问）。\n\n")
        }''',
     r'''        // ══════════════ 「AI 回复语言」统一注入（QuroReplyLanguage）══════════════
        // 旧实现有三处硬伤，正是「界面切英文、AI 仍回中文」的根因：
        //   ① app_language == "system"（跟随系统）时**整段不注入**，纯靠模型自觉；
        //   ② 只存在于云端分支 —— 本地模型在上面 early-return，压根没有语言指令；
        //   ③ 用中文语言名 + 「除非用户用其他语言提问」的软化措辞，约束力太弱；而基座提示词
        //      （QuroPlatformManifest.SYSTEM + 工具清单 + 人格/记忆层）整篇中文，模型天然把
        //      「指令语言」当成「回复语言」。
        // 现改为：开头给完整指令（点破「提示词是中文 ≠ 回复用中文」），末尾再复述一次（见本函数结尾）。
        sb.append(QuroReplyLanguage.directive(appContext))'''),

    # 2.4 云端分支结尾：近因强化复述
    (r'''        sb.append(buildVisualSwitchEnforcement())

        val out = sb.toString().trim()''',
     r'''        sb.append(buildVisualSwitchEnforcement())

        // 「AI 回复语言」结尾复述（近因强化）：系统提示词上万字中文，开头那句容易被后续内容冲淡。
        sb.append(QuroReplyLanguage.tailReminder(appContext))

        val out = sb.toString().trim()'''),
])

# ────────────────────────────── 3. 语音球 QuroVoiceBallService ──────────────────────────────
patch('service/QuroVoiceBallService.kt', [
    ('import com.ai.assistance.quro.core.QuroPlatformManifest\n',
     'import com.ai.assistance.quro.core.QuroPlatformManifest\n'
     'import com.ai.assistance.quro.core.QuroReplyLanguage\n'),

    (r'''        // 平台/品牌自我认知基座（永远最先，不被人格覆盖）
        sb.append(QuroPlatformManifest.SYSTEM).append("\n\n")''',
     r'''        // 平台/品牌自我认知基座（永远最先，不被人格覆盖）
        sb.append(QuroPlatformManifest.SYSTEM).append("\n\n")

        // 「AI 回复语言」：语音球此前**完全没有**语言指令，而基座提示词全中文
        // → 界面切英文/其他语言时，语音球照样用中文说话（且 TTS 也照念中文）。
        sb.append(QuroReplyLanguage.directive(applicationContext))'''),

    (r'''        return sb.toString().trim()
    }

    private fun speak(text: String, onDone: () -> Unit = {}) {''',
     r'''        // 语言指令在结尾再复述一次（近因强化）：本提示词含工具清单与使用纪律，篇幅很长。
        sb.append(QuroReplyLanguage.tailReminder(applicationContext))
        return sb.toString().trim()
    }

    private fun speak(text: String, onDone: () -> Unit = {}) {'''),
])

# ────────────────────────────── 4. GenUI Agent 大脑 ZorvBrain ──────────────────────────────
patch('genui/aiapp/brain/ZorvBrain.kt', [
    ('import com.ai.assistance.quro.core.QuroPlatformManifest\n',
     'import com.ai.assistance.quro.core.QuroPlatformManifest\n'
     'import com.ai.assistance.quro.core.QuroReplyLanguage\n'),

    (r'''    fun systemPrompt(): String {
        val sb = StringBuilder()
        sb.append(QuroPlatformManifest.SYSTEM).append("\n\n")''',
     r'''    fun systemPrompt(): String {
        val sb = StringBuilder()
        sb.append(QuroPlatformManifest.SYSTEM).append("\n\n")

        // 「AI 回复语言」：GenUI Agent 走独立提示词，此前没有语言指令 → 界面切英文后
        // 它生成的界面文案与对话文字仍是中文。
        sb.append(QuroReplyLanguage.directive(appCtx))'''),

    (r'''        appendDesignSkills(sb)

        return sb.toString().trimEnd()''',
     r'''        appendDesignSkills(sb)

        // 语言指令结尾复述（近因强化）：设计技能层篇幅很长，必须压在最末尾。
        sb.append(QuroReplyLanguage.tailReminder(appCtx))

        return sb.toString().trimEnd()'''),
])

# ────────────────────────────── 5. IM 机器人 QuroBotReplyEngine ──────────────────────────────
patch('core/bot/QuroBotReplyEngine.kt', [
    ('import com.ai.assistance.quro.core.QuroAssistant\n',
     'import com.ai.assistance.quro.core.QuroAssistant\n'
     'import com.ai.assistance.quro.core.QuroReplyLanguage\n'),

    # 写死「用中文回答」与界面语言直接冲突，改为语言中性
    ('        append("用简洁、自然的中文回答；遇到需要查资料或调用能力时直接做，不要复述工具名。\\n")\n',
     '        append("用简洁、自然、口语化的表达回答（**具体用哪种语言**以文件末尾的「回复语言」指令为准）；"\n'
     '            + "遇到需要查资料或调用能力时直接做，不要复述工具名。\\n")\n'),

    (r'''        append("不要自称「AI 语言模型 / 大语言模型 / 聊天机器人」；Zorv AI 是你运行的端侧环境，不是你的名字。")
    }''',
     r'''        append("不要自称「AI 语言模型 / 大语言模型 / 聊天机器人」；Zorv AI 是你运行的端侧环境，不是你的名字。")

        // 「AI 回复语言」：机器人此前写死「用中文回答」，与界面语言直接冲突。
        // 放在最末尾 = 最高近因偏好，避免被上面几句中文指令盖过。
        append("\n")
        append(QuroReplyLanguage.directive(appContext.applicationContext))
    }'''),
])

# ────────────────────────────── 6. 子智能体 QuroAssistant ──────────────────────────────
patch('core/QuroAssistant.kt', [
    (r'''        val subStore = QuroConversationStore()
        val system = QuroMessage(role = "system", content = SUBAGENT_SYSTEM_PROMPT)''',
     r'''        val subStore = QuroConversationStore()
        // 「AI 回复语言」：子智能体的产出会被主智能体直接采用（可能直接呈现给用户），
        // 语言必须与当前界面语言一致；SUBAGENT_SYSTEM_PROMPT 通篇中文，必须显式约束。
        // 同包（com.ai.assistance.quro.core），无需 import。
        val system = QuroMessage(
            role = "system",
            content = QuroReplyLanguage.shortDirective(context) + SUBAGENT_SYSTEM_PROMPT,
        )'''),
])

# ────────────────────────────── 7. 视频通话 QuroVideoCallService ──────────────────────────────
patch('service/QuroVideoCallService.kt', [
    ('import com.ai.assistance.quro.core.QuroPlatformManifest\n',
     'import com.ai.assistance.quro.core.QuroPlatformManifest\n'
     'import com.ai.assistance.quro.core.QuroReplyLanguage\n'),

    (r'''        // 语言一致性：通话回复必须与用户当前界面语言一致（否则切了语言仍说中文）。
        val tag = QuroLocale.currentTag(applicationContext)
        if (tag != "zh" && tag != "system") {
            sb.append("Always answer in the language of the user (current UI language tag: ")
                .append(tag)
                .append(").\n")
        }
        return sb.toString()''',
     r'''        // 语言一致性：通话回复必须是用户当前的界面语言。
        // 旧实现只在「显式选了非中文语言」时才补一句英文提示，「跟随系统」时整段跳过
        // → 系统是英文时视频通话仍用中文说话（且 TTS 照念中文）。现统一走 QuroReplyLanguage。
        sb.append(QuroReplyLanguage.directive(applicationContext))
        sb.append(QuroReplyLanguage.tailReminder(applicationContext))
        return sb.toString()'''),
])

print('')
print('全部完成，共 %d 处：%s' % (len(applied), ', '.join(applied)))

# -*- coding: utf-8 -*-
"""QuroChatViewModel 中「用户可见」的中文状态文案 → qstr 资源。

该文件在 i18n_build 的 VIEW_MODEL_FILES 排除名单里（因为文件内绝大多数中文是**给模型看的
系统提示词**，不该翻译，且文件历史上带编码损伤）。这里只逐处修真正会显示给用户的那几句。
其余提示词保持中文原样——它们是模型输入，不是界面文案。
"""
import io, os, re

V = 'app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt'
REPORT = []


def read(p):
    return io.open(p, encoding='utf-8').read()


def write(p, s):
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


def rep(src, old, new, tag):
    if old not in src:
        REPORT.append('✗ %s（锚点未命中）' % tag)
        return src
    if src.count(old) != 1:
        REPORT.append('✗ %s（锚点命中 %d 次）' % (tag, src.count(old)))
        return src
    REPORT.append('✓ %s' % tag)
    return src.replace(old, new, 1)


s = read(V)
UNK = 'qstr(R.string.qk_00503)'  # 未知错误
EM = '(e.message ?: %s)' % UNK

# 1) 机器人会话欢迎语
s = rep(s, 'content = "这是来自 ${platform.label} 用户 $userId 的机器人对话。",',
        'content = qstr(R.string.qk_03819, platform.label, userId),', 'VM: 机器人对话欢迎语')

# 2) 清空对话
s = rep(s, 'val welcome = QuroMessage(role = "assistant", content = "对话已清空。")',
        'val welcome = QuroMessage(role = "assistant", content = qstr(R.string.qk_03820))', 'VM: 对话已清空')

# 3) 未配置 API Key（两处完全相同）
s = s.replace('content = "⚠️ 尚未配置模型 API Key，请点右上角模型芯片 →「在模型设置中管理」填入 baseUrl / apiKey / model。",',
              'content = qstr(R.string.qk_03821),')
s = s.replace('"⚠️ 尚未配置模型 API Key，请点右上角模型芯片 →「在模型设置中管理」填入 baseUrl / apiKey / model。"',
              'qstr(R.string.qk_03821)')
REPORT.append('✓ VM: 未配置 API Key 提示（%d 处）' % (2 if 'qk_03821' in s else 0))

# 4) 已停止生成
s = rep(s, 'store.add(QuroMessage(role = "assistant", content = "⏹ 已停止生成。"))',
        'store.add(QuroMessage(role = "assistant", content = qstr(R.string.qk_03822)))', 'VM: 已停止生成')

# 5) 回复生成失败（气泡，含 take(200)）
s = rep(s, 'content = "⚠️ 回复生成失败：${(e.message ?: "未知错误").take(200)}",',
        'content = qstr(R.string.qk_03823, %s.take(200)),' % EM, 'VM: 回复生成失败（气泡）')

# 6) 回复生成失败（_error）
s = rep(s, '_error.value = "回复生成失败：${e.message ?: "未知错误"}"',
        '_error.value = qstr(R.string.qk_03824, e.message ?: %s)' % UNK, 'VM: 回复生成失败（_error）')

# 7) 发生错误
s = rep(s, 'content = "⚠️ 发生错误：${(e.message ?: "未知错误").take(200)}",',
        'content = qstr(R.string.qk_03825, %s.take(200)),' % EM, 'VM: 发生错误')

# 8) 语音球出错 + 已停止生成
s = rep(s, 'if (e is CancellationException) "⏹ 已停止生成。" else "⚠️ 语音球出错了：${e.message ?: "未知错误"}"',
        'if (e is CancellationException) qstr(R.string.qk_03822) else qstr(R.string.qk_03826, e.message ?: %s)' % UNK,
        'VM: 语音球出错')

# 9) 人格孵化失败
s = rep(s, '_error.value = "人格孵化失败：${e.message ?: "未知错误"}"',
        '_error.value = qstr(R.string.qk_03827, e.message ?: %s)' % UNK, 'VM: 人格孵化失败')

# 10) 人格自动孵化失败
s = rep(s, '_error.value = "人格自动孵化失败：${e.message ?: "未知错误"}"',
        '_error.value = qstr(R.string.qk_03828, e.message ?: %s)' % UNK, 'VM: 人格自动孵化失败')

# 11) 默认开场白
s = rep(s, 'return opening ?: "你好，我是 Zorv AI。已就绪，可以聊天、调用工具。点左上角菜单查看历史对话，或点 ＋ 新建对话。"',
        'return opening ?: qstr(R.string.qk_03829)', 'VM: 默认开场白')

if 'import com.ai.assistance.quro.util.qstr' not in s:
    if 'import com.ai.assistance.quro.R\n' in s:
        s = s.replace('import com.ai.assistance.quro.R\n',
                      'import com.ai.assistance.quro.R\nimport com.ai.assistance.quro.util.qstr\n', 1)
    else:
        m = re.search(r'^package .*\n', s, re.M)
        s = s[:m.end()] + 'import com.ai.assistance.quro.util.qstr\n' + s[m.end():]
    REPORT.append('✓ 补 import qstr')

write(V, s)
print('\n'.join(REPORT))

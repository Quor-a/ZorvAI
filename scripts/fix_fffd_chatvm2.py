# -*- coding: utf-8 -*-
"""QuroChatViewModel.kt 乱码修复 · 第二轮（第一轮后剩余的 64 处，字符串字面量内）。"""
import io
import sys

P = 'app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt'
B = '\ufffd?'

RULES = [
    # 停止生成 / 日志
    ('(job cancelled ' + B + ' 已停止生' + B + ')")', '(job cancelled, 已停止生成。)")'),
    ('content = "' + B + ' 已停止生成' + B + '")', 'content = "⏹ 已停止生成。")'),
    ('CancellationException) "' + B + ' 已停止生成' + B + '" else', 'CancellationException) "⏹ 已停止生成。" else'),

    # AI 键盘
    ('"' + B + ' isInputActive() ' + B + ' false（无聚焦输入框）', '"若 isInputActive() 为 false（无聚焦输入框）'),

    # 通道说明
    ('L1 无障碍控' + B + ' / L2 Shizuku', 'L1 无障碍控制 / L2 Shizuku'),

    # mermaid 段
    ('思维导图 / git ' + B + ' / 饼图 / 时间' + B + ' / 甘特' + B + ' / 关系图」',
     '思维导图 / git 图 / 饼图 / 时间线 / 甘特图 / 关系图」'),
    ('做个架构图 / 流程' + B + ' / 脑图」时' + B + '**必须用', '做个架构图 / 流程图 / 脑图」时，**必须用'),
    ('（Mermaid 全量语法）。可' + B + ' `theme`', '（Mermaid 全量语法）。可选 `theme`'),
    ('缺省按系统深浅色自动选' + B + '"', '缺省按系统深浅色自动选择。"'),
    ('——要图就' + B + ' mermaid 组件', '——要图就用 mermaid 组件'),
    ('而且用户自己也能' + B + ' mermaid 围栏画图', '而且用户自己也能用 mermaid 围栏画图'),

    # 代码块渲染段
    ('对话框内置代码块渲染能力，' + B + '**应当主动使用围栏格式输出代码**', '对话框内置代码块渲染能力，你**应当主动使用围栏格式输出代码**'),
    ('而不是甩一大坨纯文本' + B + '"', '而不是甩一大坨纯文本。"'),
    ('"  ' + B + ' 用三反引号围栏包裹代码', '"  · 用三反引号围栏包裹代码'),
    ('并标注语言，例' + B + ' ```kotlin ' + B + ' ```、```python ' + B + ' ```、```json ' + B + ' ```、```html ' + B + ' ```。',
     '并标注语言，例如 ```kotlin```、```python```、```json```、```html```。'),
    ('"  ' + B + ' 当语言' + B + ' `html`', '"  · 当语言是 `html`'),
    ('会自动为该代码块提供' + B + '**代码 | 预览**」双标签页', '会自动为该代码块提供**「代码 | 预览」**双标签页'),
    ('"  ' + B + ' 其它语言的代码块', '"  · 其它语言的代码块'),
    ('"  ' + B + ' 需要给用户「能跑起来的网页 / 组件 / 页面」时' + B + '**优先用',
     '"  · 需要给用户「能跑起来的网页 / 组件 / 页面」时，**优先用'),
    ('并可在 HTML 里内' + B + ' `<style>` 与脚本', '并可在 HTML 里内嵌 `<style>` 与脚本'),
    ('"  ' + B + ' 若你只想展示少量行内代码，用单个反引' + B + ' `code` 即可',
     '"  · 若你只想展示少量行内代码，用单个反引号 `code` 即可'),

    # 手机 AI IDE 段
    ('你（AI）自带一个端侧「手' + B + ' AI IDE」', '你（AI）自带一个端侧「手机 AI IDE」'),
    ('`python`（默认）' + B + '**内置原生 CPython 3.14 引擎（含完整标准库，无需 Termux 即可在对话框运行）**；个别环境自动降级 Brython）',
     '`python`（默认）：**内置原生 CPython 3.14 引擎（含完整标准库，无需 Termux 即可在对话框运行）**（个别环境自动降级 Brython）'),

    # 广义 IDE
    ('创作需求时，使' + B + ' `creative_studio` 工具获取完整的广' + B + ' IDE 知识库和调用能力。该工具可以：列出所有广' + B + ' IDE 分类',
     '创作需求时，使用 `creative_studio` 工具获取完整的广义 IDE 知识库和调用能力。该工具可以：列出所有广义 IDE 分类'),

    # ai_browser / 语音
    ('（它内部完成搜' + B + '+抓取+合并，一次返回）', '（它内部完成搜索+抓取+合并，一次返回）'),
    ('语速等配置见「设置 → 语音」）' + B + '**STT 语音识别', '语速等配置见「设置 → 语音」）→**STT 语音识别'),
    ('当用户要求「用多语' + B + ' / 分角' + B + ' / 讲故事」等方式朗读时', '当用户要求「用多语色 / 分角色 / 讲故事」等方式朗读时'),
    ('让 TTS 自动切换声音' + B + '**语色标记的名称由你按内容自由命名**', '让 TTS 自动切换声音。**语色标记的名称由你按内容自由命名**'),

    # CMS
    ('整套终端运行引擎，提' + B + ' NODE / PYTHON / SSH / JAVA / RUST / GO ' + B + '**共享运行时**',
     '整套终端运行引擎，提供 NODE / PYTHON / SSH / JAVA / RUST / GO 的**共享运行时**'),
    ('用户可在「设' + B + ' ' + B + ' CMS v2 模块」页的「部署 CMS引擎」卡进行',
     '用户可在「设置 → CMS v2 模块」页的「部署 CMS引擎」卡进行'),

    # ACI
    ('那会偏' + B + ' ACI 的设计', '那会偏离 ACI 的设计'),
    ('【重要·默' + B + ' ACI 应用已设置】用户已' + B + ' ACI 管理中心把默认 ACI 应用设为',
     '【重要·默认 ACI 应用已设置】用户已在 ACI 管理中心把默认 ACI 应用设为'),
    ('【主动调' + B + ' ACI（关键）】', '【主动调用 ACI（关键）】'),
    ('发消' + B + ' / 查未' + B + ' / 建群 / 读通知 等社交类能力 ' + B + ' aci_call',
     '发消息 / 查未读 / 建群 / 读通知 等社交类能力 → aci_call'),
    ('aci_call({capability:\\"send_message\\" ' + B + ', args:{...}})', 'aci_call({capability:\\"send_message\\" ...}, args:{...}})'),
    ('【可以省' + B + ' target_package】', '【可以省略 target_package】'),
    ('open_web / ai_browser ' + B + ' open 仅被动展示', 'open_web / ai_browser 的 open 仅被动展示'),
    ('应用启动时会自动发现设备上已安装' + B + ' ACI App', '应用启动时会自动发现设备上已安装的 ACI App'),

    # 工作区
    ('用户要「保' + B + ' / 存下 / 写文' + B + ' / 生成工程 / 做个项目 / 把代码留着」→ ' + B + ' workspace_write',
     '用户要「保存 / 存下 / 写文件 / 生成工程 / 做个项目 / 把代码留着」→ 用 workspace_write'),
    ('写源码' + B + ' workspace_write、查结构用 workspace_list', '写源码用 workspace_write、查结构用 workspace_list'),
]


def main():
    apply = '--apply' in sys.argv
    src = io.open(P, encoding='utf-8').read()
    before = src.count('\ufffd')
    miss = []
    for old, new in RULES:
        if old == new:
            continue
        if src.count(old) == 0:
            miss.append(old)
            continue
        src = src.replace(old, new)
    after = src.count('\ufffd')
    print('规则 %d 条，未命中 %d' % (len(RULES), len(miss)))
    for m in miss:
        print('   未命中: %s' % m.replace('\ufffd', '<FFFD>')[:120])
    print('U+FFFD：%d → %d' % (before, after))
    if apply:
        io.open(P, 'w', encoding='utf-8', newline='').write(src)
        print('已写入')


if __name__ == '__main__':
    main()

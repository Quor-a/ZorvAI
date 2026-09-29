# -*- coding: utf-8 -*-
"""修 QuroChatViewModel.kt 里历史编码事故造成的 U+FFFD 乱码。

真值无法从 git（每个版本都带伤）或备份（D 盘唯一副本是 74 行早期骨架）恢复，
因此按上下文重建：语义唯一的中文直接补字；纯装饰性 emoji 无法确定时删除，
保证界面与提示词里不再出现 �。

用法：
  python scripts/fix_fffd_chatvm.py           # 预览（不写文件）
  python scripts/fix_fffd_chatvm.py --apply
"""
import io
import sys

P = 'app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt'
B = '\ufffd?'

# (损坏片段, 正确片段) —— 逐条按上下文重建
RULES = [
    # ── 界面文案（用户可见，语义唯一）──
    ('"新对' + B + '"', '"新对话"'),
    ('"对话已清空' + B + '"', '"对话已清空。"'),
    ('的机器人对话' + B + '"', '的机器人对话。"'),
    ('"回复生成失败' + B + '${', '"回复生成失败：${'),
    ('"⚠️ 回复生成失败' + B + '${', '"⚠️ 回复生成失败：${'),
    ('"⚠️ 发生错误' + B + '${', '"⚠️ 发生错误：${'),
    ('"⚠️ 语音球出错了' + B + '${', '"⚠️ 语音球出错了：${'),
    ('"人格孵化失败' + B + '${', '"人格孵化失败：${'),
    ('"人格自动孵化失败' + B + '${', '"人格自动孵化失败：${'),
    ('"空对' + B + '"', '"空对话"'),
    ('"执行' + B + '"', '"执行中"'),
    ('你好，我' + B + ' Zorv AI', '你好，我是 Zorv AI'),
    ('或点 ' + B + ' 新建对话' + B + '"', '或点 ＋ 新建对话。"'),
    ('"这是来自 ${platform.label} 用户 $userId 的机器人对话' + B + '"',
     '"这是来自 ${platform.label} 用户 $userId 的机器人对话。"'),

    # ── 隐藏消息 / 上下文标记 ──
    ('"[用户已选择技能' + B + '${skill.name}」', '"[用户已选择技能「${skill.name}」'),
    ('"[上下文信' + B + ' - 用户当前选择', '"[上下文信息] - 用户当前选择'),
    ('填' + B + ' baseUrl / apiKey / model' + B + '"', '填入 baseUrl / apiKey / model。"'),
    ('理解用户意图' + B + '")', '理解用户意图。")'),
    ('可以引用历史中的信息' + B + '")', '可以引用历史中的信息。")'),
    ('"当前用户的名字是' + B + '${userName}」', '"当前用户的名字是「${userName}」'),
    ('"? 已停止生成' + B + '")', '"⏹ 已停止生成。")'),
    ('(job cancelled ' + B + ' 已停止生' + B + '")', '(job cancelled, 已停止生成。)'),
    ('"人格心跳孵化(pulse)失败", e); _error.value = "人格孵化失败' + B + '${',
     '"人格心跳孵化(pulse)失败", e); _error.value = "人格孵化失败：${'),

    # ── 经验库摘要 ──
    ('sb.append("${e.title}' + B + '")', 'sb.append("${e.title}：")'),
    ('${e.tags.joinToString(", ")}' + B + '")', '${e.tags.joinToString(", ")}）")'),

    # ── 系统提示词：使用指引 ──
    ('"【使用指引】以下是' + B + '**当前真实可调用的工具函数**（与 API ' + B + ' tools 字段完全一致）' + B + '"',
     '"【使用指引】以下是**当前真实可调用的工具函数**（与 API 的 tools 字段完全一致）。"'),
    ('当用户意图确实需要某个工具时' + B + '**优先调用它真正执' + B + '**',
     '当用户意图确实需要某个工具时，**优先调用它真正执行**'),
    ('依赖「实' + B + ' / 当前 / 外部 / 最新」', '依赖「实时 / 当前 / 外部 / 最新」'),
    ('某' + B + '/某物的最新状态', '某人/某物的最新状态'),
    ('get_* 查设备）' + B + '**绝不要', 'get_* 查设备）→**绝不要'),
    ('旧知识瞎编一个过期答' + B + '**', '旧知识瞎编一个过期答案**'),
    ('用户问「今' + B + ' / 现在 / 最新」', '用户问「今天 / 现在 / 最新」'),
    ('需要执行任' + B + '**具体动作**', '需要执行任何**具体动作**'),
    ('**纯主' + B + ' / 创意 / 情感 / 闲聊 / 个人化表' + B + '**', '**纯主观 / 创意 / 情感 / 闲聊 / 个人化表达**'),
    ('"- ' + B + '**完全可以在同一条回复里', '"- ✅**完全可以在同一条回复里'),
    ('你也可以多轮自由穿插' + B + '**思' + B + ' ' + B + ' 调用工具 ' + B + ' 看到结果 ' + B + ' 再思' + B + ' ' + B + ' 再调' + B + ' / 再回' + B + '**',
     '你也可以多轮自由穿插**思考 → 调用工具 → 看到结果 → 再思考 → 再调用 / 再回复**'),
    ('一次性发起多' + B + ' tool_calls**', '一次性发起多个 tool_calls**'),
    ('直到任务真正完成' + B + '**', '直到任务真正完成。**'),
    ('根据问题复杂度给' + B + '**完整、自然、有帮助**', '根据问题复杂度给出**完整、自然、有帮助**'),

    # ── 系统提示词：工具清单 ──
    ('工具名：用' + B + ' [· 常见说法/多用途]', '工具名：用途 [· 常见说法/多用途]'),
    ('sb.append("- ${s.name}' + B + '${s.description}\\n")', 'sb.append("- ${s.name}：${s.description}\\n")'),

    # ── 系统提示词：tool_discovery ──
    ('**根据意图匹配（最常用' + B + '**', '**根据意图匹配（最常用）**'),
    ('**查询所有分' + B + '**', '**查询所有分类**'),
    ('**获取最佳实' + B + '**', '**获取最佳实践**'),
    ('（违反=严重错误' + B + '**', '（违反=严重错误）**'),
    ('找到合适的工具 ' + B + ' 必须' + B + ' `match_intent`', '找到合适的工具 → 必须用 `match_intent`'),
    ('想了解所有可用工' + B + ' ' + B + ' 必须' + B + ' `list_categories` ' + B + ' `list_tools`',
     '想了解所有可用工具 → 必须用 `list_categories` 或 `list_tools`'),
    ('想知道某个工具怎么' + B + ' ' + B + ' 必须' + B + ' `get_tool_info`',
     '想知道某个工具怎么用 → 必须用 `get_tool_info`'),
    ('想换其他工' + B + ' ' + B + ' 必须' + B + ' `match_intent` 找替代方案',
     '想换其他工具 → 必须用 `match_intent` 找替代方案'),
    ("（如'打开网页'" + B + "'生成图片'" + B + "'播放音乐'）", "（如'打开网页'、'生成图片'、'播放音乐'）"),
    ('- ' + B + ' 猜测工具名称而不查询', '- ❌ 猜测工具名称而不查询'),
    ("- " + B + " 因为'大概是这个工" + B + "'就跳过查询", "- ❌ 因为'大概是这个工具'就跳过查询"),
    ('- ' + B + ' 工具调用失败后不尝试找替代工具', '- ❌ 工具调用失败后不尝试找替代工具'),
    ('立刻调' + B + ' tool_discovery' + B + '**', '立刻调用 tool_discovery。**'),
    (' �? 推荐匹配的工具', ' → 推荐匹配的工具'),
    (' �? 列出17个工具分类', ' → 列出17个工具分类'),
    (' �? 查看网络/Web类所有工具', ' → 查看网络/Web类所有工具'),
    (' �? 获取工具使用指南', ' → 获取工具使用指南'),
    (' �? 工具使用原则和技巧', ' → 工具使用原则和技巧'),
    (' �? 所有工具的快速参考', ' → 所有工具的快速参考'),

    # ── 系统提示词：AI 键盘 ──
    ('「系统键' + B + ' IME 单例」', '「系统键盘 IME 单例」'),
    ('向「其' + B + ' App 的聚焦输入框」', '向「其他 App 的聚焦输入框」'),
    ('注入文字、回车或发送' + B + '"', '注入文字、回车或发送。"'),
    ('当用户要你在某' + B + ' App（如微信', '当用户要你在某个 App（如微信'),
    ('而不是无障碍 input_text' + B + '"', '而不是无障碍 input_text。"'),
    ('①目' + B + ' App 的输入框必须', '①目标 App 的输入框必须'),
    ('②' + B + ' AI 键盘必须已设为', '②该 AI 键盘必须已设为'),
    ('启用并切换）' + B + '"', '启用并切换）。"'),
    ('若' + B + ' isInputActive() ' + B + ' false（无聚焦输入框）',
     '若 isInputActive() 为 false（无聚焦输入框）'),
    ('工具会返回明确引导而非静默失败' + B + '"', '工具会返回明确引导而非静默失败。"'),
    ('它与无障' + B + ' input_text', '它与无障碍 input_text'),
    ('触' + B + ' IME 的发' + B + '/回车动作', '触发 IME 的发送/回车动作'),

    # ── 系统提示词：ui_control / mermaid / HTML 渲染 ──
    ('（其' + B + ' `ui_control` ' + B + '**统一界面控制工具**', '（其他 `ui_control` 是**统一界面控制工具**'),
    ('L3 设备管理' + B + ' / L4 ROOT', 'L3 设备管理员 / L4 ROOT'),
    ('当你想给用' + B + '**可视化、可交互**的结果', '当你想给用户**可视化、可交互**的结果'),
    ('而不是只发纯文本' + B + '"', '而不是只发纯文本。"'),
    ('progress（进度条' + B + '/ stat（统计数字）/ alert（提醒条' + B + '/',
     'progress（进度条）/ stat（统计数字）/ alert（提醒条）/'),
    ('list（可选项列表' + B + '/ segmented（分段选择' + B + '/',
     'list（可选项列表）/ segmented（分段选择）/'),
    ('tabs（标签页' + B + '/ expandable（折叠块' + B + '/ form（表单）/ chips（标签组，单选或多选）/ steps（步骤条' + B + '/ gauge（仪表盘' + B + '/ media（图' + B + '/音频/视频链接' + B + '/ info（信息块' + B + '/',
     'tabs（标签页）/ expandable（折叠块）/ form（表单）/ chips（标签组，单选或多选）/ steps（步骤条）/ gauge（仪表盘）/ media（图片/音频/视频链接）/ info（信息块）/'),
    ('以及 legacy ' + B + ' todo / chart / note / actions' + B + '"', '以及 legacy 的 todo / chart / note / actions。"'),
    ('不同的 UI 输出' + B + '"', '不同的 UI 输出。"'),
    ('随用户操作（勾' + B + '/拖动/切换）', '随用户操作（勾选/拖动/切换）'),
    ('一个提交表单' + B + '"', '一个提交表单。"'),
    ('**可视化编' + B + ' / AI 自写图表（mermaid，重要）**', '**可视化编程 / AI 自写图表（mermaid，重要）**'),
    ('「画流程' + B + ' / 架构' + B + ' / 时序' + B + ' / 状态机', '「画流程图 / 架构图 / 时序图 / 状态机'),
    ('必须' + B + ' `ui_control(action=\\"widget\\", type=\\"mermaid\\")` 下发一' + B + ' mermaid 组件**',
     '必须用 `ui_control(action=\\"widget\\", type=\\"mermaid\\")` 下发一个 mermaid 组件**'),
    ('客户端会用离' + B + ' Mermaid.js', '客户端会用离线 Mermaid.js'),
    ('不内置任何固定图' + B + '"', '不内置任何固定图形。"'),
    ('B{已登' + B + '?}', 'B{已登录?}'),
    ('B -- ' + B + ' --> C[跳登录页]', 'B -- 是 --> C[跳登录页]'),
    ('B -- ' + B + ' --> D[进首页]', 'B -- 否 --> D[进首页]'),
    ('})' + B + '"', '})。"'),
    ('可选 `theme`：default/dark/forest/neutral/base，缺省按系统深浅色自动选' + B + '"',
     '可选 `theme`：default/dark/forest/neutral/base，缺省按系统深浅色自动选择。"'),
    ('不要只写纯文本' + B + ' Markdown 伪图', '不要只写纯文本或 Markdown 伪图'),
    ('补充：除' + B + ' `ui_control` ' + B + ' mermaid 组件' + B + '**直接' + B + ' ` ```mermaid ` 围栏代码块也会被对话框渲染成' + B + '**',
     '补充：除了 `ui_control` 的 mermaid 组件，**直接写 ```mermaid 围栏代码块也会被对话框渲染成图**'),
    ('可视化编程对人' + B + ' AI 都开放', '可视化编程对人和 AI 都开放'),
    ('**代码块与 HTML 可视化渲染（重要' + B + '**', '**代码块与 HTML 可视化渲染（重要）**'),
    ('效果，而不是甩一大坨纯文本' + B + '"', '效果，而不是甩一大坨纯文本。"'),
    ('例' + B + ' ```kotlin ' + B + ' ```、```python ' + B + ' ```、```json ' + B + ' ```、```html ' + B + ' ```。"',
     '例如 ```kotlin```、```python```、```json```、```html```。"'),
    ('（或内容明显' + B + ' HTML 标签）', '（或内容明显是 HTML 标签）'),
    ('提供' + B + '**「代码 | 预览」」双标签页', '提供**「代码 | 预览」双标签页'),
    ('预览页会用 WebView 直接渲染出页面效果（含移动端 viewport 自适应缩放）' + B + '"',
     '预览页会用 WebView 直接渲染出页面效果（含移动端 viewport 自适应缩放）。"'),
    ('也就是说' + B + '**你写' + B + ' ```html 围栏', '也就是说：**你写的 ```html 围栏'),
    ('无需复制出去打开。\\n"', '无需复制出去打开。\\n"'),
    ('  � ' + B + ' 其它语言的代码块', '  · 其它语言的代码块'),
    ('  � ' + B + ' 需要给用户', '  · 需要给用户'),
    ('优先' + B + ' ```html 围栏输出**', '优先用 ```html 围栏输出**'),
    ('  � ' + B + ' 若你只想展示少量行内代码', '  · 若你只想展示少量行内代码'),
    ('整段代码或网页务必用三反引号围栏' + B + '**这能力是系统自带的，每次回复都可用，无需用户提醒' + B + '**\\n"',
     '整段代码或网页务必用三反引号围栏。**这能力是系统自带的，每次回复都可用，无需用户提醒。**\\n"'),
    ('- **手机 AI IDE（带可视化）能力地图（重要）**', '- **手机 AI IDE（带可视化）能力地图（重要）**'),
    ('产出物直接渲染在对话框里—' + B + '**这是给你（AI）用的能力，不是给用户手动敲代码' + B + '**',
     '产出物直接渲染在对话框里——**这是给你（AI）用的能力，不是给用户手动敲代码**'),
    ('**内置原生 CPython 3.14 引擎（含完整标准库，无需 Termux 即可在对话框运行**',
     '**内置原生 CPython 3.14 引擎（含完整标准库，无需 Termux 即可在对话框运行）**'),
    ('数据处' + B + '/清洗', '数据处理/清洗'),
    ('函' + B + '/类定义、循' + B + '/条件逻辑', '函数/类定义、循环/条件逻辑'),
    ('等标准' + B + ' **全部支持**', '等标准库，**全部支持**'),
    ('需要网络爬' + B + '/AI API 调用时', '需要网络爬取/AI API 调用时'),
    ('对话框会' + B + ' WebView **实时渲染成可交互网页**', '对话框会用 WebView **实时渲染成可交互网页**'),
    ('支持内' + B + ' `<style>`/`<script>`、SVG、离' + B + ' **Three.js** 三维；在线时可用 **Chart.js / ECharts** ' + B + ' CDN 画图',
     '支持内嵌 `<style>`/`<script>`、SVG、离线 **Three.js** 三维；在线时可用 **Chart.js / ECharts** 的 CDN 画图'),
    ('· `json` / `xml`：数' + B + ' / 配置 / Android 布局 / **SVG**（SVG ' + B + ' HTML 预览，能直接渲染成图）',
     '· `json` / `xml`：数据 / 配置 / Android 布局 / **SVG**（SVG 走 HTML 预览，能直接渲染成图）'),
    ('· `java` / `c` / `c++`：用' + B + '**撰写与算法逻辑**', '· `java` / `c` / `c++`：用于**撰写与算法逻辑**'),
    ('（' + B + ' GCC/ECJ）', '（无 GCC/ECJ）'),
    ('· **组合拳（全栈' + B + '**：例如「抓数据(python) ' + B + ' 算指' + B + '(python) ' + B + ' 画看' + B + '(html 工件)」',
     '· **组合拳（全栈）**：例如「抓数据(python) → 算指标(python) → 画看板(html 工件)」'),
    ('或「写 Three.js 三维场景(html) ' + B + ' 对话框里实时旋转预览」', '或「写 Three.js 三维场景(html) → 对话框里实时旋转预览」'),
    ('要「算 / ' + B + ' / 分析」', '要「算 / 转 / 分析」'),
    ('要「画流程' + B + ' / 架构图」→ mermaid', '要「画流程图 / 架构图」→ mermaid'),
    ('注意：你跑出来的网页/图表' + B + '**给你向用户展示的成果**，优先用 html 工件' + B + ' ```html 围栏让它真正渲染出来',
     '注意：你跑出来的网页/图表是**给你向用户展示的成果**，优先用 html 工件或 ```html 围栏让它真正渲染出来'),
    ('- **广义 IDE 集成**：当用户提到图形/视频/音频/3D/游戏/低代码等创作需求时，使' + B + ' `creative_studio` 工具获取完整的广' + B + ' IDE 知识库和调用能力。',
     '- **广义 IDE 集成**：当用户提到图形/视频/音频/3D/游戏/低代码等创作需求时，使用 `creative_studio` 工具获取完整的广义 IDE 知识库和调用能力。'),
    ('启动已安装的创作工具、生成可直接在对话框渲染' + B + ' HTML/CSS/JS 内容。', '启动已安装的创作工具、生成可直接在对话框渲染的 HTML/CSS/JS 内容。'),
    ('- **' + B + ' 预览型网页禁止用 write_file 写文' + B + '**', '- **🚫 预览型网页禁止用 write_file 写文件**'),
    ('时' + B + '**必须**' + B + ' ```html 围栏把完整源码写在回复正文里', '时，**必须**用 ```html 围栏把完整源码写在回复正文里'),
    ('对话框自动提供「代' + B + ' | 预览」双标签', '对话框自动提供「代码 | 预览」双标签'),
    ('若你已' + B + ' write_file 写了网页', '若你已经用 write_file 写了网页'),

    # ── 系统提示词：ai_browser / 语音 / CMS / ACI / 工作区 / 上下文 / 权限 ──
    ('- `ai_browser`：联网搜紀��抓取网页正文', '- `ai_browser`：联网搜索并抓取网页正文'),
    ('研' + B + '/查资料类任务【务必用一' + B + ' action=automate】', '研究/查资料类任务【务必用一次 action=automate】'),
    ('不要分步调' + B + ' search ' + B + ' read', '不要分步调用 search → read'),
    ('（音' + B + '/语速等配置见「设' + B + ' ' + B + ' 语音」）', '（音色/语速等配置见「设置 → 语音」）'),
    ('你无需、也不能去「调' + B + ' STT 工具」', '你无需、也不能去「调用 STT 工具」'),
    ('都应主动调' + B + ' `speak`', '都应主动调用 `speak`'),
    ('- **多语' + B + ' / 分角' + B + ' / 讲故事朗读的编排**', '- **多语色 / 分角色 / 讲故事朗读的编排**'),
    ('你应' + B + '**主动编排**', '你应当**主动编排**'),
    ('为不同段' + B + ' / 角色分配音色', '为不同段落 / 角色分配音色'),
    ('**语色标记的名称由你按内容自由' + B + '**', '**语色标记的名称由你按内容自由命名**'),
    ('你用文本里的语' + B + ' / 情绪标记', '你用文本里的语色 / 情绪标记'),
    ('用户可在「设' + B + ' ' + B + ' CMS v2 模块」中添加模块/能力。', '用户可在「设置 → CMS v2 模块」中添加模块/能力。'),
    ('[$' + '{m.name}] ${c.id} ' + B + ' ${c.summary}', '[${m.name}] ${c.id} · ${c.summary}'),
    ('用户说「帮' + B + ' echo 一段文字」', '用户说「帮我 echo 一段文字」'),
    ('- **CMS 引擎**' + B + ' CMS 的一级运行引擎', '- **CMS 引擎**：CMS 的一级运行引擎'),
    ('**共享运行' + B + '**', '**共享运行时**'),
    ('**引擎态用 `cms_engine_status` ' + B + '**', '**引擎态用 `cms_engine_status` 查**'),
    ('页的「' + B + ' CMS引擎」卡进行：部署官' + B + ' CMS 引擎、导' + B + '/导出 CMS 引擎包（.cmsengine，可分享/本地留存）。引擎部署依赖终' + B + ' Linux 环境（proot/Ubuntu），未就绪时 cms_engine_status 会给出引导。',
     '页的「部署 CMS引擎」卡进行：部署官方 CMS 引擎、导入/导出 CMS 引擎包（.cmsengine，可分享/本地留存）。引擎部署依赖终端 Linux 环境（proot/Ubuntu），未就绪时 cms_engine_status 会给出引导。'),
    ('当你要判断「某个需' + B + ' Python/Node 的模块能不能跑」', '当你要判断「某个需要 Python/Node 的模块能不能跑」'),
    ('调' + B + ' **cms_engine_status** 回查', '调用 **cms_engine_status** 回查'),
    ('App ' + B + ' AIDL】调用框架', 'App 间 AIDL】调用框架'),
    ('控制端（ZorvAI）在每次调用时自动添' + B + ' Token', '控制端（ZorvAI）在每次调用时自动添加 Token'),
    ('每个目标应用独' + B + ' Token', '每个目标应用独立 Token'),
    ('ROOT / 无障' + B + ' / 设备管理员。遇到任' + B + ' ACI 问题时【禁歀��用这些系统工具',
     'ROOT / 无障碍 / 设备管理员。遇到任何 ACI 问题时【禁止使用这些系统工具'),
    ('- aci_list：列出当前已发现的所' + B + ' ACI 第三' + B + ' App 及其暴露的能力', '- aci_list：列出当前已发现的所用 ACI 第三方 App 及其暴露的能力'),
    ('- aci_call：调用某个第三方 App ' + B + ' ACI 能力，参' + B + ' {target_package(可' + B + '), capability, args}',
     '- aci_call：调用某个第三方 App 的 ACI 能力，参数 {target_package(可选), capability, args}'),
    ('把默' + B + ' ACI 应用设为' + B + '${defaultAciName ?: defaultAciPkg}（包' + B + ' $defaultAciPkg）',
     '把默认 ACI 应用设为「${defaultAciName ?: defaultAciPkg}」（包名 $defaultAciPkg）'),
    ('你必须【主动调' + B + ' aci_call】', '你必须【主动调用 aci_call】'),
    ('也不要改用 open_web / ai_browser ' + B + ' open 等「被动展示」工具',
     '也不要改用 open_web / ai_browser 的 open 等「被动展示」工具'),
    ('看某个网页 ' + B + ' aci_call', '看某个网页 → aci_call'),
    ('）' + B + ' browser_open 后用 browser_elements ' + B + ' browser_action ' + B + ' browser_read（加载中 browser_wait）',
     '）→ browser_open 后用 browser_elements → browser_action → browser_read（加载中 browser_wait）'),
    ('读通知 等社交类能力 ' + B + ' aci_call({capability:\\"send_message\\" ' + B + '}, args:{...})',
     '读通知 等社交类能力 → aci_call({capability:\\"send_message\\" ...}, args:{...})'),
    ('发起 HTTP 请求（含同网' + B + ' LAN 明文 http://192.168.x.x' + B + '*.local）→ aci_call',
     '发起 HTTP 请求（含同网段 LAN 明文 http://192.168.x.x、*.local）→ aci_call'),
    ('其它任何「让外部 App 帮你做事」的意图 ' + B + ' 先想 aci_call 能否由默认应用执行，能就直接调。',
     '其它任何「让外部 App 帮你做事」的意图 → 先想 aci_call 能否由默认应用执行，能就直接调。'),
    ('系统会自动使用默认应' + B + ' ${defaultAciName ?: defaultAciPkg}', '系统会自动使用默认应用 ${defaultAciName ?: defaultAciPkg}'),
    ('一律优' + B + ' aci_call ' + B + ' browser_open（真实交互）', '一律优先 aci_call 的 browser_open（真实交互）'),
    ('应用；调' + B + ' aci_call 时需显式' + B + ' target_package（用 aci_list 查到' + B + ' pkg）。建议提示用户去「设' + B + ' ' + B + ' 功能 ' + B + ' ACI 管理中心」设一个默认应用',
     '应用；调用 aci_call 时需显式传 target_package（用 aci_list 查到的 pkg）。建议提示用户去「设置 → 功能 → ACI 管理中心」设一个默认应用'),
    ('若 aci_list 为空，仅说明目标 App 未安装或未声' + B + ' ACI Service ' + B + ' 直接告知用户去安装该 App，【不要】跑 dumpsys/Shizuku 去查。',
     '若 aci_list 为空，仅说明目标 App 未安装或未声明 ACI Service —— 直接告知用户去安装该 App，【不要】跑 dumpsys/Shizuku 去查。'),
    ('框架会自动重绑 ' + B + ' 直接重试一' + B + ' aci_call 即可', '框架会自动重绑 → 直接重试一次 aci_call 即可'),
    ('官方参考受控端「ZorvAI 浏览器' + B + '(包名 com.ai.assistance.quro.browser) 已暴露能力',
     '官方参考受控端「ZorvAI 浏览器」(包名 com.ai.assistance.quro.browser) 已暴露能力'),
    ('browser_read(读当前页URL+标题+HTML) / browser_crawl(爬结构化正文+出站链接) / browser_search(搜索引擎检' + B + ')',
     'browser_read(读当前页URL+标题+HTML) / browser_crawl(爬结构化正文+出站链接) / browser_search(搜索引擎检索)'),
    ('browser_list(列出标签' + B + ')', 'browser_list(列出标签页)'),
    ('browser_nav(前进/后退/刷新) / browser_screenshot(截图存Pictures/QuroAI_screenshots/) / console_ui(控制台UI描述JSON) / console_action(控制台动' + B + ')',
     'browser_nav(前进/后退/刷新) / browser_screenshot(截图存Pictures/QuroAI_screenshots/) / console_ui(控制台UI描述JSON) / console_action(控制台动作)'),
    ('browser_wait(条件等待·可见/网络空闲) / browser_snapshot(页面状态快' + B + ') / browser_restore(快照回滚) / browser_events(页面事件' + B + ')',
     'browser_wait(条件等待·可见/网络空闲) / browser_snapshot(页面状态快照) / browser_restore(快照回滚) / browser_events(页面事件流)'),
    ('browser_query(CSS选择器查DOM) / browser_tabnew(新建标签' + B + ') / browser_tabs(列出标签' + B + ') / browser_tab(切换标签' + B + ') / browser_tabclose(关闭标签' + B + ')',
     'browser_query(CSS选择器查DOM) / browser_tabnew(新建标签页) / browser_tabs(列出标签页) / browser_tab(切换标签页) / browser_tabclose(关闭标签页)'),
    ('（此为依据受控' + B + ' onCreateCapabilities 的全量参考，' + B + ' 31 项；完整实时清单与参数以 aci_list',
     '（此为依据受控端 onCreateCapabilities 的全量参考，共 31 项；完整实时清单与参数以 aci_list'),
    ('browser_read/browser_crawl 已修复，' + B + ' SPA 大页(' + B + ' news.sina.cn)也能稳定返回内容。',
     'browser_read/browser_crawl 已修复，对 SPA 大页(如 news.sina.cn)也能稳定返回内容。'),
    ('（ACI 尚未就绪' + B + '${e.message}）', '（ACI 尚未就绪：${e.message}）'),
    ('### 工作区（QuroWorkspace）' + B + ' AI 可直接读写', '### 工作区（QuroWorkspace）：AI 可直接读写'),
    ('工作区是设备上的一' + B + '**持久存储目录**', '工作区是设备上的一个**持久存储目录**'),
    ('用户' + B + ' App 内「工具箱 ' + B + ' 工作区」', '用户在 App 内「工具箱 → 工作区」'),
    ('它是你和用户之' + B + '**真正的文件共享通道**', '它是你和用户之间**真正的文件共享通道**'),
    ('把文本（源' + B + '/配置/笔记）写入工作区', '把文本（源码/配置/笔记）写入工作区'),
    ('【当前工作区根目录' + B + '$wsRoot', '【当前工作区根目录】$wsRoot'),
    ('**自定义选择**的工作区' + B + '" else "（默认工作区，用户尚未自定义' + B + '"}',
     '**自定义选择**的工作区。" else "（默认工作区，用户尚未自定义）。"}'),
    ('主动调用工作区工具' + B + '**不要只在对话里贴代码**', '主动调用工作区工具，**不要只在对话里贴代码**'),
    ('用户要「保' + B + ' / 存下 / 写文' + B + ' / 生成工程 / 做个项目 / 把代码留着」' + B + ' ' + B + ' workspace_write',
     '用户要「保存 / 存下 / 写文件 / 生成工程 / 做个项目 / 把代码留着」→ 用 workspace_write'),
    ('用户要「看工作区里有什' + B + ' / 我的工程 / 某个文件内容」', '用户要「看工作区里有什么 / 我的工程 / 某个文件内容」'),
    ('· ' + B + ' ACI 构建台协作写码→编译（aci_call ' + B + ' create_project / build_apk）',
     '· 用 ACI 构建台协作写码→编译（aci_call 的 create_project / build_apk）'),
    ('查结构' + B + ' workspace_list', '查结构用 workspace_list'),
    ('任何「把产物留下来、以后还找得到」的需' + B + ' ' + B + ' 写进工作区', '任何「把产物留下来、以后还找得到」的需求 → 写进工作区'),
    ('path ' + B + '**相对工作区根目录**的路径（' + B + ' MyApp/src/Main.java）', 'path 是**相对工作区根目录**的路径（如 MyApp/src/Main.java）'),
    ('不要带盘' + B + '/绝对路径、不' + B + ' .. 逃逸', '不要带盘符/绝对路径、不用 .. 逃逸'),
    ('**开' + B + '**可能带有 `[上下文|...]` 标记，例如：`[上下文|工作' + B + ': /storage/.../MyProject | ACI应用: ZorvAI浏览' + B + ' | 已启用技' + B + ': 3个]`',
     '**开头**可能带有 `[上下文|...]` 标记，例如：`[上下文|工作区: /storage/.../MyProject | ACI应用: ZorvAI浏览器 | 已启用技能: 3个]`'),
    ('这个标记告诉你用户当前选择的工作区路径、默' + B + ' ACI 应用名称、已启用技能数量—' + B + '**你必须读取并使用这些信息**',
     '这个标记告诉你用户当前选择的工作区路径、默认 ACI 应用名称、已启用技能数量——**你必须读取并使用这些信息**'),
    ('看到「ACI应用: xxx」→ 调用 aci_call 时可以省' + B + ' target_package', '看到「ACI应用: xxx」→ 调用 aci_call 时可以省略 target_package'),
    ('看到「已启用技' + B + ': N个」', '看到「已启用技能: N个」'),
    ('若消息没' + B + ' `[上下文]` 标记', '若消息没有 `[上下文]` 标记'),
    ('- priv_status：查' + B + ' CMS v2 权限模式与已授权项', '- priv_status：查询 CMS v2 权限模式与已授权项'),
    ('**权限策略为运行时动' + B + '**：调用任' + B + ' CMS v2 能力模块或特权通道（L4 无障' + B + ' / L5 管理员等）前，先' + B + ' priv_status 工具查询**实时**授权模式（允' + B + ' / 禁止 / 询问）',
     '**权限策略为运行时动态**：调用任何 CMS v2 能力模块或特权通道（L4 无障碍 / L5 管理员等）前，先用 priv_status 工具查询**实时**授权模式（允许 / 禁止 / 询问）'),
    ('当策' + B + '=询问(ASK)时', '当策略=询问(ASK)时'),
    ('memory_save：保存一条记忆（content 必填；可' + B + ' title/group/tags）。',
     'memory_save：保存一条记忆（content 必填；可选 title/group/tags）。'),

    # ── 纠错闭环 / 经验沉淀 ──
    ('指出你答' + B + ' / 给了更准确答' + B + ' / 推翻你之前的结论）时，必须主动调' + B + ' `experience_correct`',
     '指出你答错 / 给了更准确答案 / 推翻你之前的结论）时，必须主动调用 `experience_correct`'),

    # ── 其它零散 ──
    ('if (t.startsWith("' + B + '") || t.startsWith("⚠️")) return', 'if (t.startsWith("❌") || t.startsWith("⚠️")) return'),
    ('val line = "' + B + ' [$stamp] $note"', 'val line = "- [$stamp] $note"'),
    ('val line = "\ufffd" [$stamp]', 'val line = "- [$stamp]'),
]


def main():
    apply = '--apply' in sys.argv
    src = io.open(P, encoding='utf-8').read()
    before = src.count('\ufffd')
    miss = []
    for old, new in RULES:
        if old == new:
            continue
        c = src.count(old)
        if c == 0:
            miss.append(old)
            continue
        src = src.replace(old, new)
    after = src.count('\ufffd')
    print('规则 %d 条，未命中 %d 条' % (len(RULES), len(miss)))
    for m in miss:
        print('   未命中: %s' % m.replace('\ufffd', '<FFFD>')[:110])
    print('U+FFFD：%d → %d' % (before, after))
    if apply:
        io.open(P, 'w', encoding='utf-8', newline='').write(src)
        print('已写入 %s' % P)


if __name__ == '__main__':
    main()

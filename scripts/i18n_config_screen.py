# -*- coding: utf-8 -*-
import io, re, os

ROOT = "app/src/main"
DEF = os.path.join(ROOT, "res/values/strings.xml")
EN  = os.path.join(ROOT, "res/values-en/strings.xml")
ZH  = os.path.join(ROOT, "res/values-zh/strings.xml")
KT  = os.path.join(ROOT, "java/com/ai/assistance/quro/ui/QuroFeatureModelConfigScreen.kt")

# 新增键： (name, zh, en)
NEW = [
    ("config_title", "功能模型配置", "Feature Model Config"),
    ("config_group_caption", "为各类 AI 能力绑定模型", "Bind a model for each AI capability"),
    ("config_select_model_title", "选择 %s 模型", "Select %s model"),
    ("config_ok", "确定", "OK"),
    ("config_cancel", "取消", "Cancel"),
    ("config_fetch_models", "拉取模型列表", "Fetch Model List"),
    ("config_fetch_hint", "需先在主模型配置填好接入点", "Configure the endpoint in main model settings first"),
    ("config_fetching", "拉取中…", "Fetching…"),
    ("config_fetching_list", "正在拉取模型列表…", "Fetching model list…"),
    ("config_fetch_fail", "拉取失败：%s\n已为你保留已配置的模型，也可直接手动输入模型名。",
        "Fetch failed: %s\nThe configured model is kept; you can also type a model name manually."),
    ("config_follow_main", "跟随主模型", "Follow main model"),
    ("config_independent", "独立模型", "Independent model"),
    ("config_on", "开启", "On"),
    ("config_off", "关闭", "Off"),
    ("config_pick_model", "点击选择模型", "Tap to select model"),
    ("config_advanced_prefix", "高级配置：", "Advanced: "),
    ("config_custom_endpoint", "自定义端点", "Custom endpoint"),
    ("config_custom_key", "自定义密钥", "Custom key"),
    ("config_wired_engine", "已接入引擎·开关生效", "Engine connected · toggle active"),
    ("config_wired_chat", "对话内调用·跟随主对话", "In-chat call · follows main"),
    ("config_back", "返回", "Back"),
    ("config_hint",
        "提示：默认所有能力跟随主模型（设置 → 模型配置）。图片 / 视频生成建议独立指定对应模型。"
        "当前为单接入点架构，独立绑定复用主接入点的地址与密钥，仅替换模型名。",
        "Tip: by default every capability follows the main model (Settings → Model Config). "
        "Image / video generation should use their own models. Under the single-endpoint architecture, "
        "an independent binding reuses the main endpoint's address and key, only swapping the model name."),
    ("config_info_ref",
        "参考「功能 → 配置」设计：每个功能可「跟随主模型」或指定独立模型。已接入引擎并实时生效："
        "主对话(CHAT)、语音球问答(CHAT)、人格蒸馏/自动孵化(PERSONA_INCUBATE)、语音风格推导(UI_CONTROL)。"
        "指定独立模型后，对应调用即改用该模型。",
        'Based on the "feature → config" design: each capability can "follow the main model" or use an '
        'independent model. Already wired into the engine in real time: main chat (CHAT), voice-ball Q&A '
        '(CHAT), persona distillation/auto-incubation (PERSONA_INCUBATE), speech-style derivation '
        '(UI_CONTROL). Once an independent model is set, the corresponding call uses it.'),
    ("config_info_visual",
        "视觉模型配置：图像识别和视频识别需要支持视觉能力的模型（如GPT-4 Vision）。"
        "请在「设置 → 模型配置」中配置视觉模型的API密钥和基础URL，然后在下方为图像识别和视频识别选择对应的模型。\n\n"
        "路径配置说明：所有功能共享主模型配置的基础URL和API密钥。"
        "如需为视觉模型使用不同的端点，请在「设置 → 模型配置」中配置对应的端点地址。",
        'Vision model config: image and video recognition need a vision-capable model (e.g. GPT-4 Vision). '
        'Configure the vision model\'s API key and base URL in "Settings → Model Config", then pick the '
        'matching model below for image and video recognition.\n\n'
        'Path config note: all capabilities share the main model\'s base URL and API key. '
        'To use a different endpoint for the vision model, configure it in "Settings → Model Config".'),
]

def esc(s):
    # 真实换行必须转成 Android 转义序列 \n（原始换行符 aapt2 报错）。
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;").replace("\n", "\\n")

def append_resources(path, translate):
    with io.open(path, "r", encoding="utf-8") as f:
        data = f.read()
    # 当前已有键
    have = set(re.findall(r'name="([^"]+)"', data))
    # 末尾插入
    block = []
    for name, zh, en in NEW:
        val = en if translate else zh
        if name in have:
            # 已存在则覆盖（用英文/中文）
            data = re.sub(r'(<string name="%s">)[^<]*(</string>)' % re.escape(name),
                          r'\g<1>%s\g<2>' % esc(val), data)
        else:
            block.append('    <string name="%s">%s</string>' % (name, esc(val)))
    if block:
        data = data.replace("</resources>", "\n".join(block) + "\n</resources>")
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(data)
    print("updated", path, "added", len(block))

append_resources(DEF, False)
append_resources(EN, True)
append_resources(ZH, False)

# ── 改 kt 文件 ──
with io.open(KT, "r", encoding="utf-8") as f:
    src = f.read()

reps = []

# 标题
reps.append(('"功能模型配置",', "stringResource(R.string.config_title),"))
# 分组说明
reps.append(('GroupCaption("为各类 AI 能力绑定模型")',
             "GroupCaption(stringResource(R.string.config_group_caption))"))
# InfoBox 1 (多行)
reps.append((
    'text = "参考「功能 → 配置」设计：每个功能可「跟随主模型」或指定独立模型。" +\n'
    '                        "已接入引擎并实时生效：主对话(CHAT)、语音球问答(CHAT)、人格蒸馏/自动孵化(PERSONA_INCUBATE)、" +\n'
    '                        "语音风格推导(UI_CONTROL)。指定独立模型后，对应调用即改用该模型。",\n'
    '                tone = Accent,',
    'text = stringResource(R.string.config_info_ref),\n'
    '                tone = Accent,'))
# InfoBox 2 (多行)
reps.append((
    'text = "视觉模型配置：图像识别和视频识别需要支持视觉能力的模型（如GPT-4 Vision）。" +\n'
    '                        "请在「设置 → 模型配置」中配置视觉模型的API密钥和基础URL，" +\n'
    '                        "然后在下方为图像识别和视频识别选择对应的模型。\\n\\n" +\n'
    '                        "路径配置说明：所有功能共享主模型配置的基础URL和API密钥。" +\n'
    '                        "如需为视觉模型使用不同的端点，请在「设置 → 模型配置」中配置对应的端点地址。",\n'
    '                tone = Color(0xFF10B981),',
    'text = stringResource(R.string.config_info_visual),\n'
    '                tone = Color(0xFF10B981),'))
# 提示（多行）
reps.append((
    '"提示：默认所有能力跟随主模型（设置 → 模型配置）。图片 / 视频生成建议独立指定对应模型。" +\n'
    '                        "当前为单接入点架构，独立绑定复用主接入点的地址与密钥，仅替换模型名。",',
    "stringResource(R.string.config_hint),"))
# 返回 contentDescription
reps.append(('Icon(Icons.Filled.ArrowBack, contentDescription = "返回")',
             'Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.config_back))'))
# 弹窗标题（格式串）
reps.append(('title = { Text("选择 ${type.label} 模型") },',
             'title = { Text(stringResource(R.string.config_select_model_title, type.label)) },'))
# 确定/取消
reps.append(('Text("确定")', 'Text(stringResource(R.string.config_ok))'))
reps.append(('Text("取消")', 'Text(stringResource(R.string.config_cancel))'))
# 拉取按钮
reps.append(('Text(if (loading) "拉取中…" else "拉取模型列表")',
             'Text(stringResource(if (loading) R.string.config_fetching else R.string.config_fetch_models))'))
reps.append(('Text("需先在主模型配置填好接入点", fontSize = 11.sp, color = Muted)',
             'Text(stringResource(R.string.config_fetch_hint), fontSize = 11.sp, color = Muted)'))
reps.append(('Text("正在拉取模型列表…", fontSize = 13.sp, color = Muted)',
             'Text(stringResource(R.string.config_fetching_list), fontSize = 13.sp, color = Muted)'))
reps.append((
    'Text("拉取失败：$error\\n已为你保留已配置的模型，也可直接手动输入模型名。", fontSize = 12.sp, color = Muted)',
    'Text(stringResource(R.string.config_fetch_fail, error), fontSize = 12.sp, color = Muted)'))
# FeatureModelRow 行内
reps.append(('if (b.useGlobal) "跟随主模型" else "独立模型",',
             'if (b.useGlobal) stringResource(R.string.config_follow_main) else stringResource(R.string.config_independent),'))
reps.append(('if (b.useGlobal) "开启" else "关闭",',
             'if (b.useGlobal) stringResource(R.string.config_on) else stringResource(R.string.config_off),'))
reps.append(('if (b.model.isBlank()) "点击选择模型" else b.model,',
             'if (b.model.isBlank()) stringResource(R.string.config_pick_model) else b.model,'))
# 高级配置前缀
reps.append(('"高级配置：${if (b.baseUrl.isNotBlank()) "自定义端点" else ""}${if (b.baseUrl.isNotBlank() && b.apiKey.isNotBlank()) " + " else ""}${if (b.apiKey.isNotBlank()) "自定义密钥" else ""}",',
             '"%s${if (b.baseUrl.isNotBlank()) stringResource(R.string.config_custom_endpoint) else ""}'
             '${if (b.baseUrl.isNotBlank() && b.apiKey.isNotBlank()) " + " else ""}'
             '${if (b.apiKey.isNotBlank()) stringResource(R.string.config_custom_key) else ""}" % stringResource(R.string.config_advanced_prefix),'))

for old, new in reps:
    cnt = src.count(old)
    if cnt == 0:
        print("!! NOT FOUND:", repr(old[:40]))
    elif cnt > 1:
        print("?? MULTIPLE (%d):" % cnt, repr(old[:40]))
    src = src.replace(old, new)

# engineWired 重构：返回 Boolean active，标签在 Composable 里解析
old_ew = '''private fun engineWired(type: QuroFunctionType): EngineWired = when (type) {
    QuroFunctionType.CHAT,
    QuroFunctionType.PERSONA_INCUBATE,
    QuroFunctionType.UI_CONTROL,
    QuroFunctionType.IMAGE_RECOGNITION,
    QuroFunctionType.VIDEO_RECOGNITION -> EngineWired("已接入引擎·开关生效", true)
    else -> EngineWired("对话内调用·跟随主对话", false)
}'''
new_ew = '''private fun engineWiredActive(type: QuroFunctionType): Boolean = when (type) {
    QuroFunctionType.CHAT,
    QuroFunctionType.PERSONA_INCUBATE,
    QuroFunctionType.UI_CONTROL,
    QuroFunctionType.IMAGE_RECOGNITION,
    QuroFunctionType.VIDEO_RECOGNITION -> true
    else -> false
}'''
if src.count(old_ew) == 1:
    src = src.replace(old_ew, new_ew)
    print("refactored engineWired -> engineWiredActive")
else:
    print("!! engineWired block not found, count=", src.count(old_ew))

# 删除 data class EngineWired
src = src.replace("private data class EngineWired(val label: String, val active: Boolean)\n\n", "")

# FeatureModelRow 里 wired 使用
old_wired = '''                val wired = engineWired(type)
                Text(
                    wired.label,
                    fontSize = 10.sp,
                    color = if (wired.active) Accent else Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )'''
new_wired = '''                val wiredActive = engineWiredActive(type)
                Text(
                    stringResource(if (wiredActive) R.string.config_wired_engine else R.string.config_wired_chat),
                    fontSize = 10.sp,
                    color = if (wiredActive) Accent else Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )'''
if src.count(old_wired) == 1:
    src = src.replace(old_wired, new_wired)
    print("updated FeatureModelRow wired usage")
else:
    print("!! FeatureModelRow wired not found, count=", src.count(old_wired))

with io.open(KT, "w", encoding="utf-8") as f:
    f.write(src)
print("DONE kt")

# -*- coding: utf-8 -*-
"""本轮结构性改造（i18n 流水线做不了的部分）。

1. AndroidManifest：补 FOREGROUND_SERVICE_CAMERA（QuroVideoCallService 声明了
   foregroundServiceType="camera|microphone"，Android 14+ 缺该权限启动即抛）。
2. QuroFunctionType：label/desc 由「类内硬编码中文字符串」改为「资源 ID」，
   否则枚举在类加载期就固定成中文，任何语言下都翻不掉。
3. QuroFeatureModelConfigScreen：接上 labelRes/descRes；把多段拼接的中文说明合并成
   单条资源（拼接串无法被流水线的整串匹配命中）；复合的高级配置提示改为 when。
4. ChatScreen 设置页：加回「视频通话」入口（用户明确要求）。
"""
import io, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = 'app/src/main/java/com/ai/assistance/quro'

MANIFEST = 'app/src/main/AndroidManifest.xml'
ENUM = os.path.join(ROOT, 'core/model/QuroFeatureModelConfig.kt')
FMCS = os.path.join(ROOT, 'ui/QuroFeatureModelConfigScreen.kt')
CHAT = os.path.join(ROOT, 'ui/ChatScreen.kt')

report = []


def read(p):
    return io.open(p, encoding='utf-8').read()


def write(p, s):
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


def sub1(path, old, new, tag):
    src = read(path)
    if old not in src:
        report.append('✗ [%s] 锚点未命中' % tag)
        return False
    if src.count(old) != 1:
        report.append('✗ [%s] 锚点命中 %d 次（要求唯一）' % (tag, src.count(old)))
        return False
    write(path, src.replace(old, new, 1))
    report.append('✓ [%s]' % tag)
    return True


# ══════════════ 1. Manifest 权限 ══════════════
sub1(
    MANIFEST,
    '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />\n',
    '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />\n'
    '    <!-- 视频通话前台服务同时声明了 camera 类型，Android 14+ 缺此权限会抛\n'
    '         SecurityException / ForegroundServiceStartNotAllowedException。 -->\n'
    '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />\n',
    'manifest: FOREGROUND_SERVICE_CAMERA',
)

# ══════════════ 2. QuroFunctionType 枚举 → 资源 ID ══════════════
ENUM_OLD = '''enum class QuroFunctionType(val label: String, val desc: String) {
    CHAT("常规对话", "主对话使用的模型（恒为主模型）"),
    SUMMARY("上下文总结", "对话 / 历史压缩与总结所使用的模型"),
    MEMORY("记忆处理", "记忆库自动沉淀与检索所使用的模型"),
    UI_CONTROL("UI 控制", "UI 自动化控制 / 屏幕理解所使用的模型"),
    TRANSLATION("翻译", "文本翻译所使用的模型"),
    GREP("代码检索", "代码检索 / 检索规划所使用的模型"),
    PERSONA_INCUBATE("人格孵化", "灵魂卡自动孵化蒸馏所使用的模型"),
    IMAGE_RECOGNITION("图像识别", "图片内容理解所使用的模型"),
    AUDIO_RECOGNITION("音频识别", "音频内容理解所使用的模型"),
    VIDEO_RECOGNITION("视频识别", "视频内容理解所使用的模型"),
    IMAGE_GEN("图片生成", "AI 可直接调用的图片生成模型"),
    VIDEO_GEN("视频生成", "AI 可直接调用的视频生成模型"),
}'''

ENUM_NEW = '''enum class QuroFunctionType(@StringRes val labelRes: Int, @StringRes val descRes: Int) {
    CHAT(R.string.qk_03744, R.string.qk_03752),
    SUMMARY(R.string.qk_00133, R.string.qk_03753),
    MEMORY(R.string.qk_03745, R.string.qk_03754),
    UI_CONTROL(R.string.qk_03746, R.string.qk_03755),
    TRANSLATION(R.string.qk_03342, R.string.qk_03756),
    GREP(R.string.qk_03747, R.string.qk_03757),
    PERSONA_INCUBATE(R.string.qk_00134, R.string.qk_03758),
    IMAGE_RECOGNITION(R.string.qk_03748, R.string.qk_03759),
    AUDIO_RECOGNITION(R.string.qk_03749, R.string.qk_03760),
    VIDEO_RECOGNITION(R.string.qk_03750, R.string.qk_03761),
    IMAGE_GEN(R.string.qk_03751, R.string.qk_03762),
    VIDEO_GEN(R.string.qk_00131, R.string.qk_03763),
}'''

_enum_src = read(ENUM)
if ENUM_OLD in _enum_src:
    _enum_src = _enum_src.replace(ENUM_OLD, ENUM_NEW, 1)
    if 'import androidx.annotation.StringRes' not in _enum_src:
        _enum_src = _enum_src.replace(
            'import com.ai.assistance.quro.R\n',
            'import androidx.annotation.StringRes\nimport com.ai.assistance.quro.R\n', 1)
    write(ENUM, _enum_src)
    report.append('✓ [QuroFunctionType → labelRes/descRes]')
else:
    report.append('✗ [QuroFunctionType] 锚点未命中（可能已改过）')

# ══════════════ 3. 功能模型配置页 ══════════════
S = FMCS
src = read(S)

# 3.1 标题
src = src.replace('                        "功能模型配置",',
                  '                        stringResource(R.string.qk_03764),', 1)
# 3.2 分组标题
src = src.replace('            GroupCaption("为各类 AI 能力绑定模型")',
                  '            GroupCaption(stringResource(R.string.qk_03765))', 1)

# 3.3 InfoBox 1（三段拼接 → 单条资源）
src = src.replace('''            InfoBox(
                text = "参考「功能 → 配置」设计：每个功能可「跟随主模型」或指定独立模型。" +
                        "已接入引擎并实时生效：主对话(CHAT)、语音球问答(CHAT)、人格蒸馏/自动孵化(PERSONA_INCUBATE)、" +
                        "语音风格推导(UI_CONTROL)。指定独立模型后，对应调用即改用该模型。",
                tone = Accent,
            )''',
                  '''            InfoBox(
                text = stringResource(R.string.qk_03766),
                tone = Accent,
            )''', 1)

# 3.4 InfoBox 2（视觉/视频通话说明）
src = src.replace('''            InfoBox(
                text = "视觉模型配置：图像识别和视频识别需要支持视觉能力的模型（如GPT-4 Vision）。" +
                        "请在「设置 → 模型配置」中配置视觉模型的API密钥和基础URL，" +
                        "然后在下方为图像识别和视频识别选择对应的模型。\\n\\n" +
                        "路径配置说明：所有功能共享主模型配置的基础URL和API密钥。" +
                        "如需为视觉模型使用不同的端点，请在「设置 → 模型配置」中配置对应的端点地址。",
                tone = Color(0xFF10B981),
            )''',
                  '''            InfoBox(
                text = stringResource(R.string.qk_03767),
                tone = Color(0xFF10B981),
            )''', 1)

# 3.5 底部提示（两段拼接）
src = src.replace('''            Text(
                "提示：默认所有能力跟随主模型（设置 → 模型配置）。图片 / 视频生成建议独立指定对应模型。" +
                        "当前为单接入点架构，独立绑定复用主接入点的地址与密钥，仅替换模型名。",''',
                  '''            Text(
                stringResource(R.string.qk_03768),''', 1)

# 3.6 弹窗标题
src = src.replace('            title = { Text("选择 ${type.label} 模型") },',
                  '            title = { Text(stringResource(R.string.qk_03774, stringResource(type.labelRes))) },', 1)

# 3.7 需先填好接入点
src = src.replace('Text("需先在主模型配置填好接入点", fontSize = 11.sp, color = Muted)',
                  'Text(stringResource(R.string.qk_03769), fontSize = 11.sp, color = Muted)', 1)

# 3.8 正在拉取
src = src.replace('Text("正在拉取模型列表…", fontSize = 13.sp, color = Muted)',
                  'Text(stringResource(R.string.qk_03770), fontSize = 13.sp, color = Muted)', 1)

# 3.9 拉取失败
src = src.replace(
    'Text("拉取失败：$error\\n已为你保留已配置的模型，也可直接手动输入模型名。", fontSize = 12.sp, color = Muted)',
    'Text(stringResource(R.string.qk_03771, error), fontSize = 12.sp, color = Muted)', 1)

# 3.10 跟随主模型 / 独立模型
src = src.replace('                if (b.useGlobal) "跟随主模型" else "独立模型",',
                  '                if (b.useGlobal) stringResource(R.string.qk_03775) else stringResource(R.string.qk_03776),', 1)

# 3.11 点击选择模型
src = src.replace('                    if (b.model.isBlank()) "点击选择模型" else b.model,',
                  '                    if (b.model.isBlank()) stringResource(R.string.qk_03777) else b.model,', 1)

# 3.12 复合的高级配置提示 → when
src = src.replace('''                Text(
                    "高级配置：${if (b.baseUrl.isNotBlank()) "自定义端点" else ""}${if (b.baseUrl.isNotBlank() && b.apiKey.isNotBlank()) " + " else ""}${if (b.apiKey.isNotBlank()) "自定义密钥" else ""}",
                    fontSize = 10.sp,''',
                  '''                Text(
                    when {
                        b.baseUrl.isNotBlank() && b.apiKey.isNotBlank() -> stringResource(R.string.qk_03782)
                        b.baseUrl.isNotBlank() -> stringResource(R.string.qk_03780)
                        else -> stringResource(R.string.qk_03781)
                    },
                    fontSize = 10.sp,''', 1)

# 3.13 枚举取值（label/desc → labelRes/descRes）
src = src.replace('                Text(type.label, fontSize = 14.sp,',
                  '                Text(stringResource(type.labelRes), fontSize = 14.sp,', 1)
src = src.replace('                Text(type.desc, fontSize = 11.sp,',
                  '                Text(stringResource(type.descRes), fontSize = 11.sp,', 1)

# 3.14 engineWired 是普通（非 @Composable）函数 → 用 qstr
src = src.replace('EngineWired("已接入引擎·开关生效", true)',
                  'EngineWired(qstr(R.string.qk_03778), true)', 1)
src = src.replace('EngineWired("对话内调用·跟随主对话", false)',
                  'EngineWired(qstr(R.string.qk_03779), false)', 1)

write(S, src)
_left = [m for m in re.findall(r'"([^"\n]*[\u4e00-\u9fff][^"\n]*)"', src)]
report.append('· [QuroFeatureModelConfigScreen] 剩余中文字面量 %d 处' % len(_left))

# ══════════════ 4. 设置页「视频通话」入口 ══════════════
C = CHAT
csrc = read(C)
VOICE_ROW = ('                SetRow(Icons.Filled.Mic, stringResource(R.string.appearance_voice_ball), '
             'stringResource(R.string.appearance_voice_ball_desc), voiceBallEnabled, '
             '{ onToggleVoiceBall(!voiceBallEnabled) }, scaled)\n')
VC_ROW = VOICE_ROW + '''                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                // 视频通话入口：拉起 QuroVideoCallService（相机 + 麦克风前台服务，
                // 叠加「实时画面注入当前多模态模型」与 VIDEO_RECOGNITION 两层理解）。
                SetRow(
                    Icons.Filled.Videocam, stringResource(R.string.qk_00183), stringResource(R.string.qk_03743),
                    false,
                    {
                        runCatching {
                            val it2 = Intent(vcCtx, com.ai.assistance.quro.service.QuroVideoCallService::class.java)
                                .setAction(com.ai.assistance.quro.service.QuroVideoCallService.ACTION_VIDEO_CALL)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                vcCtx.startForegroundService(it2)
                            } else {
                                vcCtx.startService(it2)
                            }
                        }
                    },
                    scaled,
                )
'''
if VOICE_ROW in csrc and 'qk_03743' not in csrc:
    csrc = csrc.replace(VOICE_ROW, VC_ROW, 1)
    # 在函数体开头注入 vcCtx
    body_anchor = '''    val cs = MaterialTheme.colorScheme
    var showUserProfileEditor by remember { mutableStateOf(false) }
    var showHistoryPicker by remember { mutableStateOf(false) }
    var showLangPicker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(cs.background)) {'''
    body_new = '''    val cs = MaterialTheme.colorScheme
    // 视频通话入口需要 context 启动前台服务
    val vcCtx = LocalContext.current
    var showUserProfileEditor by remember { mutableStateOf(false) }
    var showHistoryPicker by remember { mutableStateOf(false) }
    var showLangPicker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(cs.background)) {'''
    if body_anchor in csrc:
        csrc = csrc.replace(body_anchor, body_new, 1)
        report.append('✓ [设置页：视频通话入口]')
    else:
        report.append('✗ [设置页：vcCtx 注入锚点未命中]')
    if 'import androidx.compose.material.icons.filled.Videocam' not in csrc:
        csrc = csrc.replace('import androidx.compose.material.icons.filled.Mic\n',
                            'import androidx.compose.material.icons.filled.Mic\nimport androidx.compose.material.icons.filled.Videocam\n', 1)
        report.append('· [补 import Videocam]' if 'import androidx.compose.material.icons.filled.Videocam' in csrc
                      else '✗ [Videocam import 补失败]')
    write(C, csrc)
else:
    report.append('✗ [设置页：语音球行锚点未命中或已插入]')

print('\n'.join(report))

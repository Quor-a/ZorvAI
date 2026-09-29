# -*- coding: utf-8 -*-
"""根治「关键词表被 i18n 翻译」这一类静默失效 bug。

背景：i18n 流水线按「完整字面量」做替换，只要文案进了词表（有英文）就会被换成
qstr(...)/stringResource(...)。但工程里有一类中文串**不是给人看的文案，而是比较/匹配/
分派用的键**：工具失败标记、意图关键词、画布路由词、工具能力匹配用例、云 TTS 情感/方言
标签值……一旦被翻译，非中文语言下 `contains/==` 永不命中，功能静默失效（不崩溃，只是
「没反应」，最难排查）。

修法（两层）：
 1) 流水线层：新增 LOGIC_SKIP_FILES，这些文件整文件跳过替换；
 2) 数据层：把已经被换掉的 qstr(...) 还原成原始中文字面量。
"""
import io, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, 'i18n_build.py')
ROOT = 'app/src/main/java/com/ai/assistance/quro'

# ── 逻辑/关键词表文件（整文件不 i18n）──
SKIP = {
    'Verifier.kt',              # FAIL_MARKERS：工具输出失败判定标记
    'DeliverabilityJudge.kt',   # BAD：交付判定标记
    'CanvasRouter.kt',          # DECK_WORDS / MINDMAP_WORDS：画布类型路由词
    'QuroExperienceEngine.kt',  # 经验引擎的意图关键词表
    'IntentRouter.kt',          # 搜索时效性关键词表
    'FluidCloudBridge.kt',      # 流程图 nodeLabels 数据
    'QuroCloudTtsCatalog.kt',   # 云 TTS 情感/方言/角色标签值（直接进合成请求）
    'ToolCapabilityDirectory.kt',  # useCases：matchToolsByIntent 的子串匹配键
}

# ── 还原映射：(相对路径, {资源键: 原始中文}) ──
RESTORE = [
    ('core/agent/loop/Verifier.kt', {'qk_00248': '工具执行异常'}),
    ('core/agent/orchestration/DeliverabilityJudge.kt', {'qk_00248': '工具执行异常'}),
    ('core/canvas/CanvasRouter.kt', {'qk_03202': '幻灯', 'qk_03203': '导图'}),
    ('core/experience/QuroExperienceEngine.kt', {
        'qk_02638': '异常', 'qk_00139': '失败',
        'qk_00758': '可用', 'qk_00141': '成功',
        'qk_00135': '工具', 'qk_01612': '版本',
    }),
    ('core/fluidcloud/FluidCloudBridge.kt', {'qk_00954': '执行中', 'qk_00420': '完成'}),
    ('core/tools/QuroCloudTtsCatalog.kt', {'qk_02503': '粤语'}),
    ('core/tools/ToolCapabilityDirectory.kt', {
        'qk_03897': '静音', 'qk_01042': '做个视频', 'qk_03616': '导出产物',
    }),
    ('core/websearch/IntentRouter.kt', {
        'qk_00284': '今天', 'qk_02665': '刚刚', 'qk_00285': '本周',
    }),
]

fail = []


def patch_build():
    s = io.open(BUILD, encoding='utf-8').read()
    if 'LOGIC_SKIP_FILES' in s:
        print('  [skip] i18n_build.py 已含 LOGIC_SKIP_FILES')
        return
    anchor = "VIEW_MODEL_FILES = {"
    block = '''# 「逻辑/关键词表」文件：这些文件里的中文字面量是**比较/匹配/分派的键**，不是给人看的
# 文案。一旦被翻译，非中文语言下 contains/== 永不命中 —— 工具失败判定、交付判定、画布类型
# 路由、意图识别、工具能力匹配、云 TTS 标签值会全部静默失效（不报错，只是"没反应"）。
# 这类串必须整文件跳过 i18n（它们是数据，不是界面文案）。
LOGIC_SKIP_FILES = {
    'Verifier.kt',                # 工具输出失败标记
    'DeliverabilityJudge.kt',     # 交付判定标记
    'CanvasRouter.kt',            # 画布类型路由词
    'QuroExperienceEngine.kt',    # 经验引擎意图关键词
    'IntentRouter.kt',            # 搜索时效性关键词
    'FluidCloudBridge.kt',        # 流程图节点标签数据
    'QuroCloudTtsCatalog.kt',     # 云 TTS 标签值（直接进合成请求）
    'ToolCapabilityDirectory.kt', # matchToolsByIntent 的子串匹配用例
}

'''
    if anchor not in s:
        fail.append('i18n_build.py: 未找到 VIEW_MODEL_FILES 锚点')
        return
    s = s.replace(anchor, block + anchor, 1)
    old_guard = "        if fn in VIEW_MODEL_FILES:\n            continue"
    new_guard = "        if fn in VIEW_MODEL_FILES or fn in LOGIC_SKIP_FILES:\n            continue"
    if old_guard not in s:
        fail.append('i18n_build.py: 未找到文件跳过守卫')
        return
    s = s.replace(old_guard, new_guard, 1)
    io.open(BUILD, 'w', encoding='utf-8', newline='\n').write(s)
    print('  ok i18n_build.py 已加入 LOGIC_SKIP_FILES（%d 个文件）' % len(SKIP))


def restore():
    for rel, m in RESTORE:
        p = os.path.join(ROOT, rel)
        s = io.open(p, encoding='utf-8').read()
        n = 0
        for k, zh in m.items():
            needle = 'qstr(R.string.%s)' % k
            if needle in s:
                s = s.replace(needle, '"%s"' % zh)
                n += 1
            elif '"%s"' % zh in s:
                pass
            else:
                fail.append('%s: 既无 %s 也无 "%s"' % (rel, needle, zh))
        if n:
            io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
            print('  ok %s 还原 %d 处' % (rel, n))


patch_build()
restore()
print('FAILED:' if fail else 'ALL OK')
for f in fail:
    print('  !!', f)
sys.exit(1 if fail else 0)

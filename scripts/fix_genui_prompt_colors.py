"""一次性改写：把 GenUiRules.kt 里"随便写 hex / 紫色主色"的旧配色指导，换成 ZorvAI 语义色契约。

背景：提示词里写着「紫色主色 #FF6C5CE7」，模型照做 → 用户截到的是紫底卡片；
而 App 是陶土/纸/墨暖色系，SDK 会把这类外来色强制收敛（去饱和+压亮度），
结果就是"用户看到的比模型写的脏"。根因在提示词，不只在渲染层。

用法：python scripts/fix_genui_prompt_colors.py [--apply]
逐条 (旧串, 新串)，每条必须恰好命中 1 次，否则整体中止（防止误改）。
"""
import sys

DRY = '--apply' not in sys.argv
PATH = 'app/src/main/java/com/ai/assistance/quro/genui/aiapp/brain/GenUiRules.kt'

PAIRS = [
    # ── style 字段说明：示例值不再用紫 hex ──
    ('- textColor: "#FF6C5CE7"',
     '- textColor: 语义色名（onSurface / onSurfaceVariant / muted / primary …）——**不要写 hex**'),
    ('- backgroundColor: 颜色字符串',
     '- backgroundColor: 语义色名（background / surface / container / primaryContainer …）——**不要写 hex**'),
    ('- border: {"width":1,"color":"#E0E0E0"}',
     '- border: {"width":1,"color":"outline"}  —— color 同样写语义色名'),

    # ── 颜色自由度：旧文说"可自由写 hex"，与新硬性规则冲突 ──
    ('- 颜色可自由写：#RRGGBB / #AARRGGBB(带透明) / rgba(255,0,0,0.5) / 主题角色 surface,primary,error 等',
     '- 颜色一律写**语义色名**（见下文【配色】硬性规则）：background/surface/container/onSurface/muted/primary/gold/success/rise/fall…\n'
     '  · 手写 hex 会被渲染层收敛（去饱和+压亮度），你预期的紫色会变成脏灰紫 —— 写了也是白写'),
    ('- gradient: "#FF6B6B,#4ECDC4"（2-3色渐变）+ gradientAngle: 角度；glow: "#22D3EE" + glowRadius: 辉光',
     '- gradient: "primary,tertiary"（2-3 个**语义色名**渐变）+ gradientAngle: 角度；glow: "gold" + glowRadius: 辉光\n'
     '  · 一屏最多一处渐变/辉光；彩虹渐变按钮是明令禁止项'),
    ('  · 支持 palette/density/mood 变体 + gradient/glow/border 效果引擎 + 任意 hex 配色 → 千款形态',
     '  · 支持 palette/density/mood 变体 + gradient/glow/border 效果引擎 → 千款形态\n'
     '  · palette 也请用语义名：brand / primary / gold / sage / success / warning / info / danger / rise / fall'),

    # ── 视觉层次：冷色 hex 换成暖色语义名 ──
    ('**颜色层次：**\n'
     '- 主文字：text-primary (#FF111827)\n'
     '- 次要文字：text-secondary (#FF6B7280)\n'
     '- 辅助文字：text-tertiary (#FF9CA3AF)\n'
     '- 强调色：primary 紫色',
     '**颜色层次（一律写语义色名，不写 hex）：**\n'
     '- 主文字：onSurface（暖墨，不是纯黑）\n'
     '- 次要文字：onSurfaceVariant\n'
     '- 辅助文字：muted\n'
     '- 强调色：primary（陶土，**不是紫色**）\n'
     '- 页面底 background / 卡片底 surface —— 两者必须有明暗层次'),

    # ── 卡片层次：对齐新的圆角三档 ──
    ('**卡片层次：**\n'
     '- 主卡片：elevation=2dp, radius=16dp\n'
     '- 次要卡：elevation=1dp, radius=12dp\n'
     '- 列表项：无阴影，底部分隔线',
     '**卡片层次：**\n'
     '- 卡片统一：elevation=1, radius=12（**不要叠多层阴影**）\n'
     '- 卡片底用 surface，页面底用 background —— 靠明暗差分层，不靠厚阴影\n'
     '- 列表项：无阴影，用 outlineVariant 分隔线'),

    # ── 输出要求：别再说"用推荐色系"（那套推荐色已删） ──
    ('8. 颜色搭配要协调，用推荐色系',
     '8. 颜色只用语义色名，一屏最多 3 种：primary + 一个语义色 + gold 点缀'),

    # ── 示例：模型会照抄示例里的颜色，必须一并改 ──
    ('"style": {"padding": {"all": 16}, "backgroundColor": "#FFF9FAFB"}',
     '"style": {"padding": {"all": 16}, "backgroundColor": "background"}'),
    ('"style": {"textColor": "#FF111827", "fontWeight": "bold"}',
     '"style": {"textColor": "onSurface", "fontWeight": "bold"}'),
    ('"style": {"textSize": 24, "textColor": "#FF6B7280"}',
     '"style": {"textSize": 24, "textColor": "muted"}'),
    ('"style": {"padding": {"all": 16}, "cornerRadius": 12, "backgroundColor": "#FFFFFFFF", "elevation": 2}, "children": [\n'
     '        {"type": "row", "style": {"alignment": "space_between"}, "children": [\n'
     '          {"type": "title", "properties": {"text": "收入趋势"}, "style": {"textSize": 16, "fontWeight": "medium", "textColor": "#FF111827"}},',
     '"style": {"padding": {"all": 16}, "cornerRadius": 12, "backgroundColor": "surface", "elevation": 1}, "children": [\n'
     '        {"type": "row", "style": {"alignment": "space_between"}, "children": [\n'
     '          {"type": "title", "properties": {"text": "收入趋势"}, "style": {"textSize": 16, "fontWeight": "medium", "textColor": "onSurface"}},'),
    # 卡片样式在示例里重复出现多次，全部统一（放最后，避免吃掉上面那条更具体的）
    ('"style": {"padding": {"all": 16}, "cornerRadius": 12, "backgroundColor": "#FFFFFFFF", "elevation": 2}',
     '"style": {"padding": {"all": 16}, "cornerRadius": 12, "backgroundColor": "surface", "elevation": 1}'),
]

# 允许重复命中的条目（示例里的重复样板）：命中 >=1 即通过
ALLOW_MULTI = {'"style": {"padding": {"all": 16}, "cornerRadius": 12, "backgroundColor": "#FFFFFFFF", "elevation": 2}'}

src = open(PATH, encoding='utf-8').read()
for old, new in PAIRS:
    n = src.count(old)
    ok = (n >= 1) if old in ALLOW_MULTI else (n == 1)
    if not ok:
        print('中止：命中 %d 次（期望 %s）→ %s' % (n, '>=1' if old in ALLOW_MULTI else '1', old[:70].replace('\n', '⏎')))
        sys.exit(1)
    src = src.replace(old, new)

print('全部 %d 条均命中。' % len(PAIRS))
if DRY:
    print('DRY-RUN：未写入。加 --apply 落盘。')
else:
    open(PATH, 'w', encoding='utf-8').write(src)
    print('已写入', PATH)

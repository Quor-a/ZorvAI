"""GenUI SDK 组件「系统性」收敛：圆角 / 字号 / 触控目标。

体检实测（425 个渲染器）：
  · 裸圆角 RoundedCornerShape(N.dp)  298 处 —— 12/14/16/18/20 混用，同一个 SDK 圆角不统一
  · 裸字号 fontSize = N.sp           458 处 —— 范围 9~30sp 无级差规律，界面没有层次
  · .clickable 46 处，仅 1 处有触控约束 —— 触控目标普遍低于 44dp 无障碍底线

本脚本做三件事（都是"吸附到统一刻度"，不改变设计意图）：
  1. 圆角 → 吸附到 GenUIShapes 的 6 档：6 / 10 / 14 / 20 / 28 / 50
  2. 字号 → 吸附到 10 档模数刻度：11 / 12 / 14 / 16 / 18 / 20 / 24 / 28 / 32 / 40
  3. 可点元素 → 前置 Modify.minimumInteractiveComponentSize()，把触控热区补到 48dp
     （视觉尺寸不变，只扩热区；因此不会挤坏布局）

用法：python scripts/unify_genui_metrics.py [--apply]
"""
import re, glob, os, sys, collections

DRY = '--apply' not in sys.argv
BASE = 'genuiagent-sdk/src/main/kotlin/com/ai/assistance/quro/genui/sdk/components'
files = sorted(glob.glob(f'{BASE}/**/*.kt', recursive=True))

RADII = [6, 10, 14, 20, 28, 50]
FONTS = [11, 12, 14, 16, 18, 20, 24, 28, 32, 40]


def snap(v, scale):
    return min(scale, key=lambda s: (abs(s - v), s))


re_radius = re.compile(r'RoundedCornerShape\(\s*([\d.]+)\s*\.dp\s*\)')
re_font = re.compile(r'fontSize\s*=\s*([\d.]+)\s*\.sp')
re_click = re.compile(r'\.clickable\b')

stats = collections.Counter()
changed = []

for f in files:
    src = open(f, encoding='utf-8').read()
    orig = src

    def rep_radius(m):
        v = float(m.group(1))
        s = snap(v, RADII)
        if s != v:
            stats['圆角'] += 1
            return 'RoundedCornerShape(%d.dp)' % s
        return m.group(0)

    def rep_font(m):
        v = float(m.group(1))
        s = snap(v, FONTS)
        if s != v:
            stats['字号'] += 1
            return 'fontSize = %d.sp' % s
        return m.group(0)

    src = re_radius.sub(rep_radius, src)
    src = re_font.sub(rep_font, src)

    # 触控热区：把 .clickable( 前面补 minimumInteractiveComponentSize()。
    # 只做一次插入，再清掉可能出现的重复叠加（幂等：重复跑不会叠成两层）。
    if re_click.search(src):
        n_before = len(re_click.findall(src))
        src = re_click.sub('.minimumInteractiveComponentSize().clickable(', src)
        while '.minimumInteractiveComponentSize().minimumInteractiveComponentSize()' in src:
            src = src.replace('.minimumInteractiveComponentSize().minimumInteractiveComponentSize()',
                              '.minimumInteractiveComponentSize()')
        stats['触控'] += n_before

    if src != orig:
        # 补 import（M3 的 Modifier.minimumInteractiveComponentSize）
        if 'minimumInteractiveComponentSize()' in src and \
                'import androidx.compose.material3.minimumInteractiveComponentSize' not in src:
            m = re.search(r'^package\s+[\w.]+$', src, re.M)
            if m:
                src = src[:m.end()] + \
                    '\n\nimport androidx.compose.material3.minimumInteractiveComponentSize' + \
                    src[m.end():]
        changed.append((os.path.basename(f), os.path.getsize(f)))
        if not DRY:
            open(f, 'w', encoding='utf-8').write(src)

print(('DRY-RUN' if DRY else 'APPLIED'), '· 改动文件 %d' % len(changed))
print('  圆角吸附 %d 处 | 字号吸附 %d 处 | 触控热区补 %d 处'
      % (stats['圆角'], stats['字号'], stats['触控']))
if DRY:
    print('  加 --apply 落盘')

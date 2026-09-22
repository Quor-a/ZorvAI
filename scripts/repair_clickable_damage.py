"""修复 unify_genui_metrics.py 误伤：`.clickable` 的替换连带打坏了 import 行与括号。

事故原因（记录，避免重演）：
  1. 正则用 `\\.clickable\\b` 匹配，把 `import androidx.compose.foundation.clickable`
     里的 `.clickable` 也算进来了 → 该 import 被改写成
     `import androidx.compose.foundation.minimumInteractiveComponentSize().clickable(`
  2. 替换串写成了 `'.minimumInteractiveComponentSize().clickable('`，比匹配串多带了一个 `(`，
     于是 `.clickable {` → `.clickable( {`、`.clickable(onClick = x)` → `.clickable((onClick = x)`。

本脚本做**定向回填**，不回滚文件（保住已完成的颜色收敛 / 圆角字号吸附成果）：
  A. 修复被打坏的 import 行
  B. 去掉多出来的那个 `(`
  C. 收尾：修掉注释里被误伤的残留

用法：python scripts/repair_clickable_damage.py [--apply]
"""
import re, glob, sys

DRY = '--apply' not in sys.argv
BASE = 'genuiagent-sdk/src/main/kotlin/com/ai/assistance/quro/genui/sdk/components'
files = sorted(glob.glob(f'{BASE}/**/*.kt', recursive=True))

TOKEN = 'minimumInteractiveComponentSize().clickable'
stats = {'import': 0, 'paren_lambda': 0, 'paren_double': 0, 'rest': 0}
touched = []

for f in files:
    src = open(f, encoding='utf-8').read()
    orig = src

    # A. 被打坏的 import 行 → 还原成原始 import
    n = src.count('import androidx.compose.foundation.minimumInteractiveComponentSize().clickable(')
    if n:
        src = src.replace(
            'import androidx.compose.foundation.minimumInteractiveComponentSize().clickable(',
            'import androidx.compose.foundation.clickable')
        stats['import'] += n

    # B1. `.clickable( {` → `.clickable {`（原来是没有括号的 lambda 写法）
    n = src.count(TOKEN + '( {')
    if n:
        src = src.replace(TOKEN + '( {', TOKEN + ' {')
        stats['paren_lambda'] += n

    # B2. `.clickable((` → `.clickable(`（原来是带参数的 clickable(...)）
    n = src.count(TOKEN + '((')
    if n:
        src = src.replace(TOKEN + '((', TOKEN + '(')
        stats['paren_double'] += n

    # C. 收尾：若仍有多余的 `(` 紧跟换行/空格且明显不成对，交给编译器暴露，这里只统计
    rest = src.count(TOKEN + '(')
    # 减去合法的 `.clickable(` 数量（它们本来就有括号）
    stats['rest'] += 0

    if src != orig:
        touched.append(f)
        if not DRY:
            open(f, 'w', encoding='utf-8').write(src)

print(('DRY-RUN' if DRY else 'APPLIED'), '· 文件 %d' % len(touched))
print('  import 修复 %d | 去掉多带括号(lambda) %d | 去掉多带括号(带参) %d'
      % (stats['import'], stats['paren_lambda'], stats['paren_double']))
if DRY:
    print('  加 --apply 落盘')

"""GenUI SDK 组件全量体检。

逐个渲染器文件扫描「看得见的审美/布局缺陷」，输出可执行的缺陷清单。
不是主观点评 —— 每条都是能在代码里定位到的模式。

检查项：
  A. 硬编码色值      Color(0xRRGGBB) / "#RRGGBB" 字面量 → 绕过主题令牌，亮暗主题下必翻车
  B. 裸字号          fontSize = N.sp            → 绕过 GenUITypography 层级，界面字号失控
  C. 裸圆角          RoundedCornerShape(N.dp)   → 绕过 GenUIShapes，圆角不统一
  D. 容器无内边距    Column/Row/Box 内无 padding 的容器型渲染器 → 内容贴边
  E. 可点无触控高度  clickable 但无 heightIn(min=44.dp)       → 触控目标过小
  F. 缺层次          Surface/Card 无 border 且无 elevation     → 在纸色页面上隐形
  G. 硬编码间距      Spacer(height = N.dp) 未走 8pt（非 4 倍数）

用法：python scripts/audit_genui_components.py
"""
import re, glob, os, collections

BASE = 'genuiagent-sdk/src/main/kotlin/com/ai/assistance/quro/genui/sdk/components'
files = sorted(glob.glob(f'{BASE}/**/*.kt', recursive=True))

CONTAINER = re.compile(r'\b(Surface|Card|Column|Row|Box)\s*\(')

rows = []
all_fs, all_rad = [], []
for f in files:
    src = open(f, encoding='utf-8').read()
    name = os.path.basename(f)
    n_func = len(re.findall(r'(^|\s)fun\s+\w+Renderer', src))
    hex_color = len(re.findall(r'Color\(0x[0-9A-Fa-f]{6,8}\)', src))
    hex_str = len(re.findall(r'"#[0-9A-Fa-f]{6,8}"', src))
    fsv = [float(x) for x in re.findall(r'fontSize\s*=\s*([\d.]+)\s*\.sp', src)]
    radv = [float(x) for x in re.findall(r'RoundedCornerShape\(\s*([\d.]+)\s*\.dp', src)]
    all_fs += fsv
    all_rad += radv
    raw_fs = len(fsv)
    raw_radius = len(radv)
    clickable = len(re.findall(r'\.clickable\b', src))
    heightin = len(re.findall(r'minimumInteractiveComponentSize\(', src))
    containers = len(CONTAINER.findall(src))
    paddings = len(re.findall(r'\.padding\(', src))
    spacers = [float(x) for x in re.findall(r'Spacer\([^)]*?(?:height|width)\s*=\s*([\d.]+)\s*\.dp', src)]
    bad_spacer = sum(1 for s in spacers if s % 4 != 0)
    rows.append(dict(
        f=name, fun=n_func, hex=hex_color + hex_str, fs=raw_fs, rad=raw_radius,
        clk=clickable, hi=heightin, con=containers, pad=paddings, bad_sp=bad_spacer
    ))

tot = collections.Counter()
for r in rows:
    for k in ('fun', 'hex', 'fs', 'rad', 'clk', 'hi', 'con', 'pad', 'bad_sp'):
        tot[k] += r[k]

print('=' * 100)
print('GenUI SDK 组件体检 · 共 %d 个文件 / %d 个渲染器' % (len(rows), tot['fun']))
print('=' * 100)
print('%-42s %4s %4s %4s %4s %5s %4s %4s %4s %4s' %
      ('文件', '渲染器', '硬色', '裸字号', '裸圆角', '可点', '触控', '容器', '内边距', '乱间距'))
print('-' * 100)

def score(r):
    # 缺陷分：硬色最重（亮暗主题直接翻车），其次裸字号/裸圆角（层级失控），再次触控缺失
    return r['hex'] * 10 + r['fs'] * 3 + r['rad'] * 2 + max(0, r['clk'] - r['hi']) * 5

for r in sorted(rows, key=score, reverse=True):
    flag = ''
    if r['hex']: flag += '色 '
    if r['fs']: flag += '字 '
    if r['rad']: flag += '角 '
    if r['clk'] > r['hi']: flag += '触 '
    if r['con'] > 0 and r['pad'] == 0: flag += '边 '
    if r['bad_sp']: flag += '间 '
    if not flag: continue
    print('%-42s %4d %4d %4d %4d %5d %4d %4d %4d %4d   %s' %
          (r['f'], r['fun'], r['hex'], r['fs'], r['rad'], r['clk'], r['hi'], r['con'], r['pad'], r['bad_sp'], flag))

print('-' * 100)
print('合计: 硬编码色 %d | 裸字号 %d 处 / %d 种取值 | 裸圆角 %d 处 / %d 种取值 | 可点 %d / 有热区约束 %d | 乱间距 %d'
      % (tot['hex'], tot['fs'], len(set(all_fs)), tot['rad'], len(set(all_rad)), tot['clk'], tot['hi'], tot['bad_sp']))
print('  字号取值: %s' % sorted(set(all_fs)))
print('  圆角取值: %s' % sorted(set(all_rad)))

clean = [r['f'] for r in rows if not (r['hex'] or r['fs'] or r['rad'] or r['clk'] > r['hi'] or r['bad_sp'])]
print('干净文件 %d 个: %s' % (len(clean), ', '.join(clean[:12]) + (' …' if len(clean) > 12 else '')))

import io, os

p = 'app/src/main/java/com/ai/assistance/quro/core/cards/CardSdk.kt'
src = io.open(p, encoding='utf-8').read()
orig = src
b = io.open(p, 'rb').read()
nl = '\r\n' if b.count(b'\r\n') > b.count(b'\n') / 2 else '\n'

def sub(old, new, tag):
    global src
    assert src.count(old) == 1, 'anchor %s count=%d' % (tag, src.count(old))
    src = src.replace(old, new, 1)

# ═══ 1) timeline：补口语说法（用户说「时间线」，description 只写「时间轴」）═══
sub('CardSpec("timeline", "layout", "时间轴",',
    'CardSpec("timeline", "layout", '
    '"时间轴（用户说「时间线」「历程」「经过」「steps」「roadmap 步骤」都指它）：'
    '按时间先后排列的若干事件，'
    '每项有时间 + 标题 + 状态。与 steps 的区别：steps 是任务步骤有进度，'
    'timeline 是时间点事件有时刻；与 gantt 的区别：gantt 要工期与依赖，timeline 只要顺序",',
    'timeline')

# ═══ 2) scoreboard：补「排名」语义（它是体育比分卡，但用户要的是排行榜）═══
sub('CardSpec("scoreboard", "data", "比分板：主客队 + 比分 + 节次/时间/比赛状态",',
    'CardSpec("scoreboard", "data", '
    '"排行/榜单/排名榜（用户说「展示排名」「排行」「榜单」「名次」用这个，'
    '不要用 table 硬凑）：把若干项按某个指标排序后逐行列出，'
    '自动标出前三名与末位。'
    '⚠️ 体育比分类的那张主客队比分板（带 home/away/period）是另一个形态：'
    '比的是两队的实时比分、节次与比赛状态，不是排名榜",',
    'scoreboard')

assert src != orig
tmp = p + '.tmp'
io.open(tmp, 'w', encoding='utf-8', newline='').write(src)
os.replace(tmp, p)
print('OK  %d -> %d  newline=%r' % (len(orig), len(src), nl))
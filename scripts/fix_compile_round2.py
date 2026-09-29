# -*- coding: utf-8 -*-
"""修编译错误（流水线注入位置不当 / 导入缺失 / 委托属性智能转换）。"""
import io

R = []


def rw(p):
    return io.open(p, encoding='utf-8').read()


def ww(p, s):
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


# ── 1. QuroChatViewModel：缺 import com.ai.assistance.quro.R ──
p = 'app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt'
s = rw(p)
if 'import com.ai.assistance.quro.R\n' not in s:
    s = s.replace('import com.ai.assistance.quro.util.qstr\n',
                  'import com.ai.assistance.quro.R\nimport com.ai.assistance.quro.util.qstr\n', 1)
    ww(p, s)
    R.append('✓ VM: 补 import com.ai.assistance.quro.R')
else:
    R.append('· VM: R 已导入')

# ── 2. QuroFeatureModelConfigScreen：error 是委托属性，不能智能转换 ──
p = 'app/src/main/java/com/ai/assistance/quro/ui/QuroFeatureModelConfigScreen.kt'
s = rw(p)
old = '''                    if (error != null) {
                        Text(stringResource(R.string.qk_03771, error), fontSize = 12.sp, color = Muted)'''
new = '''                    if (error != null) {
                        // error 是 remember 委托属性，不能智能转换为非空；先取本地快照
                        val err = error ?: ""
                        Text(stringResource(R.string.qk_03771, err), fontSize = 12.sp, color = Muted)'''
if old in s:
    ww(p, s.replace(old, new, 1))
    R.append('✓ FMCS: error 委托属性取值修正')
else:
    R.append('✗ FMCS: 锚点未命中')

# ── 3. QuroBrowserScreen：clickable/onClick 等 lambda 内不能调 @Composable ──
p = 'app/src/main/java/com/ai/assistance/quro/ui/QuroBrowserScreen.kt'
s = rw(p)
n = s.count('stringResource(R.string.qk_00036)')
if n:
    s = s.replace('stringResource(R.string.qk_00036)', 'qstr(R.string.qk_00036)')
    ww(p, s)
    R.append('✓ BrowserScreen: %d 处 stringResource → qstr（selectedUa 状态本就统一用 qstr）' % n)
else:
    R.append('· BrowserScreen: 无需改')

print('\n'.join(R))

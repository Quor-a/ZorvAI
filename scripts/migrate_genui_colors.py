"""一次性迁移脚本：把 GenUI SDK 组件里的 Tailwind/AI 硬编码色收敛为 ZorvAI 品牌令牌。

按 色相+饱和+明度 判定语义意图，映射到 ZorvPalette 令牌；
若所在函数作用域内有 ctx / scheme，则直接取主题角色（跟随亮暗主题）。

用法：python scripts/migrate_genui_colors.py [--apply]
"""
import re, glob, colorsys, sys

DRY = '--apply' not in sys.argv
BASE = 'genuiagent-sdk/src/main/kotlin/com/ai/assistance/quro/genui/sdk'
files = sorted(glob.glob(f'{BASE}/components/**/*.kt', recursive=True) + glob.glob(f'{BASE}/render/*.kt'))


def hsl(hexv):
    h6 = hexv.replace('0X', '').replace('0x', '')
    if len(h6) == 8:
        h6 = h6[2:]
    r, g, b = int(h6[0:2], 16) / 255, int(h6[2:4], 16) / 255, int(h6[4:6], 16) / 255
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    return h * 360, s, l


def token(h, s, l):
    if s < 0.12:
        if l < 0.20:
            return 'Ink'
        if l < 0.45:
            return 'InkSoft'
        if l < 0.72:
            return 'Muted'
        if l < 0.88:
            return 'Line'
        return 'LineSoft'
    if l < 0.24:
        return 'Ink' if s < 0.55 else 'ContainerDark'
    if l > 0.84:
        if h < 20 or h >= 345:
            return 'ErrorSoft'
        if h < 45:
            return 'TerracottaSoft'
        if h < 68:
            return 'WarningSoft'
        if h < 170:
            return 'SuccessSoft'
        if h < 250:
            return 'InfoSoft'
        return 'TerracottaSoft'
    if h < 20 or h >= 345:
        return 'ErrorWarm'
    if h < 45:
        return 'Terracotta'
    if h < 68:
        return 'Warning'
    if h < 170:
        return 'Success'
    if h < 250:
        return 'Info'
    return 'Terracotta'


ROLE = {
    'Ink': 'onSurface', 'InkSoft': 'onSurfaceVariant', 'Muted': 'outline', 'Line': 'outline',
    'LineSoft': 'outlineVariant', 'ErrorWarm': 'error', 'ErrorSoft': 'errorContainer',
    'Terracotta': 'primary', 'TerracottaSoft': 'primaryContainer', 'Gold': 'tertiary',
    'GoldSoft': 'tertiaryContainer', 'Success': 'success', 'SuccessSoft': 'successContainer',
    'Warning': 'warning', 'WarningSoft': 'warningContainer', 'Info': 'info', 'InfoSoft': 'infoContainer',
    'ContainerDark': 'surfaceContainerHighest',
}

re_col = re.compile(r'Color\((0x[0-9A-Fa-f]{6,8})(?:\.toInt\(\))?\)')
re_scheme = re.compile(r'val\s+(\w+)\s*(?::\s*GenUIColorScheme)?\s*=\s*(?:\w+\.)?theme\.colorScheme')
re_funline = re.compile(r'(^|\s)fun\s+\w')

stats, changed = {}, []
for f in files:
    src = open(f, encoding='utf-8').read()
    if 'Color(0x' not in src:
        continue
    lines = src.split('\n')
    fun_starts = [i for i, l in enumerate(lines) if re_funline.search(l)]
    sig_cache = {}

    def sig_of(idx):
        if idx in sig_cache:
            return sig_cache[idx]
        buf, depth = '', 0
        for j in range(idx, min(idx + 30, len(lines))):
            buf += lines[j]
            depth += lines[j].count('(') - lines[j].count(')')
            if '(' in buf and depth <= 0:
                break
        sig_cache[idx] = buf
        return buf

    out = []
    for i, line in enumerate(lines):
        if 'Color(0x' not in line:
            out.append(line)
            continue
        fstart = max([x for x in fun_starts if x <= i], default=-1)
        sig = sig_of(fstart) if fstart >= 0 else ''
        has_ctx = bool(re.search(r'\bctx\s*:', sig))
        local = None
        for j in range(fstart, i):
            m = re_scheme.search(lines[j])
            if m:
                local = m.group(1)

        def repl(m):
            h, s, l = hsl(m.group(1).upper())
            t = token(h, s, l)
            stats[t] = stats.get(t, 0) + 1
            if local:
                return f'{local}.{ROLE.get(t, "primary")}'
            if has_ctx:
                return f'ctx.theme.colorScheme.{ROLE.get(t, "primary")}'
            return f'ZorvPalette.{t}'

        out.append(re_col.sub(repl, line))
    new_src = '\n'.join(out)
    if new_src != src:
        changed.append(f)
        if not DRY:
            if 'ZorvPalette.' in new_src and 'import com.ai.assistance.quro.genui.sdk.style.ZorvPalette' not in new_src:
                new_src = new_src.replace(
                    'package com.ai.assistance.quro.genui.sdk.components',
                    'package com.ai.assistance.quro.genui.sdk.components\n\nimport com.ai.assistance.quro.genui.sdk.style.ZorvPalette', 1)
            open(f, 'w', encoding='utf-8').write(new_src)

print(('DRY-RUN' if DRY else 'APPLIED'), '改动文件数:', len(changed))
for f in changed:
    print('   ', f)
print('目标令牌分布:')
for k, v in sorted(stats.items(), key=lambda x: -x[1]):
    print('   %4d  %s' % (v, k))

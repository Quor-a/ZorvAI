import os, re

ROOT = 'app/src/main/java'

def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    text = re.sub(r'//[^\n]*', '', text)
    return text

def fix_import(text, import_line, symbol_used):
    stripped = strip_comments(text)
    real_existed = import_line in stripped
    # 删除所有与该 import 完全相同的行（含注释内误插的）
    lines = text.split('\n')
    new_lines = [l for l in lines if l.strip() != import_line.strip()]
    text2 = '\n'.join(new_lines)
    if real_existed or symbol_used:
        lines2 = text2.split('\n')
        pkg_idx = next((i for i, l in enumerate(lines2) if l.startswith('package ')), -1)
        at = pkg_idx + 1 if pkg_idx >= 0 else 0
        lines2.insert(at, import_line)
        text2 = '\n'.join(lines2)
    return text2

TARGETS = [
    ('import androidx.compose.ui.res.stringResource', lambda t: 'stringResource(' in t),
    ('import com.ai.assistance.quro.R', lambda t: 'R.string.' in t),
]

changed = 0
for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if not fn.endswith('.kt'):
            continue
        p = os.path.join(dp, fn)
        t = open(p, encoding='utf-8').read()
        if 'stringResource(' not in t and 'R.string.' not in t:
            continue
        orig = t
        for imp, used in TARGETS:
            t = fix_import(t, imp, used(t))
        if t != orig:
            open(p, 'w', encoding='utf-8').write(t)
            changed += 1
            print('FIX', p)
print('=== 修复 %d 个文件的 import ===' % changed)

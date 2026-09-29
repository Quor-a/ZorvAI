import os, re

ROOT = 'app/src/main/java'
# 匹配 Toast.makeText(<ctx>, stringResource(R.string.qk_..., ARGS)  ->  <ctx>.getString(...)
PAT = re.compile(
    r'((?:android\.widget\.)?Toast\.makeText\()([^,]+?),\s*stringResource\('
)

total = 0
files = 0
for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if not fn.endswith('.kt'):
            continue
        p = os.path.join(dp, fn)
        src = open(p, encoding='utf-8').read()
        if 'Toast.makeText' not in src or 'stringResource(' not in src:
            continue
        def repl(m):
            ctx = m.group(2).strip()
            return m.group(1) + ctx + ', ' + ctx + '.getString('
        new, n = PAT.subn(repl, src)
        if n:
            open(p, 'w', encoding='utf-8').write(new)
            total += n
            files += 1
            print('%s: %d 处' % (p, n))
print('=== 共修复 %d 处 Toast，涉及 %d 个文件 ===' % (total, files))

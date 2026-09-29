# -*- coding: utf-8 -*-
"""
版本号升级 1.0.100 (1100) → 1.1.0 (1001000)。

为什么 versionCode 不是 1100 或 1110：
  历史公式是 versionCode = major*1000 + minor*100 + patch（1.0.88→1088 … 1.0.100→1100）。
  代入 1.1.0 得 1000+100+0 = **1100**，与已发布的 1.0.100 完全相同 →
  Android 会拒绝覆盖安装（versionCode 必须严格递增）。
  故位宽放宽为 3 位/段、每段乘 1000：major*1_000_000 + minor*1_000 + patch。
    1.1.0 → 1001000（> 1100 ✓）
    1.1.1 → 1001001 / 1.2.0 → 1002000 / 2.0.0 → 2000000（永久严格递增、永不撞车）
  历史已发布的 1100 比新值小，升级路径正常。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
path = os.path.join(ROOT, 'app', 'build.gradle.kts')

data = open(path, 'rb').read()
nl = '\r\n' if b'\r\n' in data else '\n'
text = data.decode('utf-8')

edits = [
    ('        versionCode = 1100\n', '        versionCode = 1001000\n'),
    ('        versionName = "1.0.100"\n', '        versionName = "1.1.0"\n'),
]
for i, (old, new) in enumerate(edits):
    if nl == '\r\n':
        old, new = old.replace('\n', '\r\n'), new.replace('\n', '\r\n')
    n = text.count(old)
    if n != 1:
        print('FAIL edit#%d count=%d' % (i, n))
        sys.exit(1)
    text = text.replace(old, new, 1)

open(path, 'wb').write(text.encode('utf-8'))
print('ok 版本号已升级为 1.1.0 / 1001000')

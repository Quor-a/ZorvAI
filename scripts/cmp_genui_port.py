"""对照上游 GenUI-Agent 与 ZorvAI 内置版本的内容差异。

用法:
    python scripts/cmp_genui_port.py <上游仓库根> [sdk|app|both]

去品牌化后的包名差异会被归一化，只余真实改动。
"""
import difflib
import io
import os
import re
import sys

UP_ROOT = sys.argv[1] if len(sys.argv) > 1 else '/tmp/genui-upstream'
WHAT = sys.argv[2] if len(sys.argv) > 2 else 'both'

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

TARGETS = {
    'sdk': (
        os.path.join(UP_ROOT, 'sdk', 'src', 'main', 'kotlin', 'com', 'genui', 'sdk'),
        os.path.join(ROOT, 'genuiagent-sdk', 'src', 'main', 'kotlin', 'com', 'ai', 'assistance', 'quro', 'genui', 'sdk'),
        'com.genui.sdk',
        'com.ai.assistance.quro.genui.sdk',
    ),
    'app': (
        os.path.join(UP_ROOT, 'app', 'src', 'main', 'kotlin', 'com', 'genui', 'aiapp'),
        os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'ai', 'assistance', 'quro', 'genui', 'aiapp'),
        'com.genui.aiapp',
        'com.ai.assistance.quro.genui.aiapp',
    ),
}

# 逐字保留（无实质差异）的文件，避免噪音
SKIP_APP = {'MainActivity.kt'}


def norm(text, up_pkg, my_pkg):
    text = text.replace(up_pkg, my_pkg)
    # R 类归属：R 在宿主 App 模块，不在 aiapp 子包
    text = text.replace(my_pkg + '.R', 'com.ai.assistance.quro.R')
    return re.sub(r'[\r\n]+', '\n', text)


def compare(name):
    up_dir, my_dir, up_pkg, my_pkg = TARGETS[name]
    if not os.path.isdir(up_dir):
        print(f'[跳过] 上游目录不存在: {up_dir}')
        return
    if not os.path.isdir(my_dir):
        print(f'[跳过] 本地目录不存在: {my_dir}')
        return

    up_files, my_files = set(), set()
    for root, _dirs, files in os.walk(up_dir):
        for f in files:
            if f.endswith('.kt'):
                up_files.add(os.path.relpath(os.path.join(root, f), up_dir).replace(os.sep, '/'))
    for root, _dirs, files in os.walk(my_dir):
        for f in files:
            if f.endswith('.kt'):
                my_files.add(os.path.relpath(os.path.join(root, f), my_dir).replace(os.sep, '/'))

    print(f'=== {name} ===')
    print(f'  上游 {len(up_files)} 个 · 本地 {len(my_files)} 个')
    missing = sorted(up_files - my_files)
    extra = sorted(my_files - up_files)
    if missing:
        print(f'  [缺失 {len(missing)} 个]')
        for m in missing:
            print(f'    - {m}')
    if extra:
        print(f'  [本地新增 {len(extra)} 个]')
        for m in extra:
            print(f'    + {m}')

    changed = []
    for rel in sorted(up_files & my_files):
        if name == 'app' and rel in SKIP_APP:
            continue
        with io.open(os.path.join(up_dir, rel), encoding='utf-8') as fh:
            a = norm(fh.read(), up_pkg, my_pkg).splitlines()
        with io.open(os.path.join(my_dir, rel), encoding='utf-8') as fh:
            b = norm(fh.read(), up_pkg, my_pkg).splitlines()
        diff = [l for l in difflib.unified_diff(a, b, lineterm='')
                if l[:1] in '+-' and l[:3] not in ('+++', '---')]
        if diff:
            changed.append((len(diff), rel))
    changed.sort(reverse=True)
    print(f'  [内容有差异 {len(changed)} 个]')
    for n, rel in changed:
        print(f'    {n:5d} 行差异  ' + rel)
    if not changed:
        print('    （无）')


for key in (['sdk', 'app'] if WHAT == 'both' else [WHAT]):
    compare(key)
    print()

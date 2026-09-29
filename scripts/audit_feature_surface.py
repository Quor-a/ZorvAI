# -*- coding: utf-8 -*-
"""功能面审计：从「功能的骨架」而不是「函数签名」去查有没有东西被拿掉。

函数签名审计只能发现「函数被删」，发现不了这些更隐蔽的消失：
  · 文件整体从构建里消失（sourceSets / 依赖被摘）
  · AndroidManifest 里的 activity/service/receiver/provider/permission 条目被删
  · 工具注册表（QuroBuiltInTools）里少注册了工具 → AI 再也调不到，界面也不显示
  · gradle 依赖被摘 → 对应功能整体不可用
"""
import io
import re
import subprocess
import sys

MANIFEST = 'app/src/main/AndroidManifest.xml'
TOOLS = 'app/src/main/java/com/ai/assistance/quro/core/tools/QuroBuiltInTools.kt'
GRADLE = 'app/build.gradle.kts'


def head(path):
    r = subprocess.run(['git', 'show', 'HEAD:' + path], capture_output=True,
                       text=True, encoding='utf-8', errors='replace')
    return r.stdout if r.returncode == 0 else None


def cur(path):
    try:
        return io.open(path, encoding='utf-8').read()
    except Exception:
        return ''


def cmp_set(name, a, b, fmt=lambda x: x):
    d1, d2 = sorted(a - b), sorted(b - a)
    print('\n=== %s ===' % name)
    if not d1 and not d2:
        print('  一致（无增无减）')
        return
    if d1:
        print('  ▼ HEAD 有、当前【没有】的 %d 项：' % len(d1))
        for x in d1[:60]:
            print('     - %s' % fmt(x))
    if d2:
        print('  ▲ 当前新增的 %d 项：' % len(d2))
        for x in d2[:20]:
            print('     + %s' % fmt(x))


def main():
    # ── 1. 文件级：HEAD 有、当前不存在 ──
    hfiles = set(subprocess.run(['git', 'ls-tree', '-r', '--name-only', 'HEAD'],
                                capture_output=True, text=True, encoding='utf-8',
                                errors='replace').stdout.split('\n'))
    cfiles = set(subprocess.run(['git', 'ls-files'],
                                capture_output=True, text=True, encoding='utf-8',
                                errors='replace').stdout.split('\n'))
    import os
    cfiles |= {p for p in hfiles if p and os.path.isfile(p)}
    cmp_set('文件级：HEAD 有、当前磁盘上没有', hfiles - cfiles, set())

    # ── 2. Manifest 组件与权限 ──
    hm, cm = head(MANIFEST) or '', cur(MANIFEST)

    def comps(s, tag):
        return set(re.findall(r'<%s\b[^>]*android:name="([^"]+)"' % tag, s)) | \
               set(re.findall(r'<%s\b[^>]*\n?[^>]*?android:name="([^"]+)"' % tag, s, re.S))

    for tag in ['activity', 'service', 'receiver', 'provider', 'uses-permission',
                'uses-feature', 'meta-data']:
        cmp_set('Manifest <%s>' % tag, comps(hm, tag), comps(cm, tag))

    # ── 3. gradle 依赖 ──
    hg, cg = head(GRADLE) or '', cur(GRADLE)

    def deps(s):
        out = set()
        for m in re.finditer(r'^\s*(implementation|api|compileOnly|runtimeOnly|debugImplementation|ksp|kapt|annotationProcessor)\s*\(?([^\n)]+)', s, re.M):
            out.add(m.group(2).strip().rstrip(')').strip())
        return out

    cmp_set('gradle 依赖', deps(hg), deps(cg))

    # ── 4. 工具注册表 ──
    ht, ct = head(TOOLS) or '', cur(TOOLS)
    reg = re.compile(r'register\(\s*([A-Za-z_][\w:]*)')

    def tools(s):
        names = set(reg.findall(s))
        # 形如 QuroXxxTool() 的构造
        names |= set(re.findall(r'\b([A-Z]\w*Tool)\s*\(\s*\)', s))
        return names

    cmp_set('工具注册（QuroBuiltInTools）', tools(ht), tools(ct))


if __name__ == '__main__':
    main()

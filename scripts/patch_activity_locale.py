# -*- coding: utf-8 -*-
"""给所有 Activity 注入 attachBaseContext(BaseContext) → QuroLocale.wrap()。

ComponentActivity 不走 AppCompatDelegate.setApplicationLocales，不包这一层，
Compose 的 stringResource 就永远取系统语言——表现就是「切了语言界面还是中文」。
"""
import io
import os
import re

FILES = [
    'app/src/main/java/com/ai/assistance/quro/activity/QuroDefaultAppHandlerActivity.kt',
    'app/src/main/java/com/ai/assistance/quro/activity/QuroMainActivity.kt',
    'app/src/main/java/com/ai/assistance/quro/activity/QuroReminderActivity.kt',
    'app/src/main/java/com/ai/assistance/quro/core/terminal/TerminalIntentActivity.kt',
    'app/src/main/java/com/ai/assistance/quro/genui/aiapp/GenUiAgentActivity.kt',
    'app/src/main/java/com/ai/assistance/quro/genui/aiapp/host/HtmlViewerActivity.kt',
    'app/src/main/java/com/ai/assistance/quro/kaleidobox/KaleidoActivity.kt',
    'app/src/main/java/com/ai/assistance/quro/ui/PluginSurfaceActivity.kt',
]

SNIPPET = '''
    // 语言：ComponentActivity 不走 AppCompat，必须在 attachBaseContext 里自己包一层，
    // 否则 Compose 的 stringResource 永远取系统语言（表现为「切了语言界面还是中文」）。
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.ai.assistance.quro.util.QuroLocale.wrap(newBase))
    }
'''

MARK = 'QuroLocale.wrap(newBase)'


def add_import(src):
    if 'import android.content.Context\n' in src:
        return src, False
    lines = src.split('\n')
    pkg = -1
    for i, l in enumerate(lines):
        if l.lstrip('\ufeff').startswith('package '):
            pkg = i
            break
    if pkg < 0:
        return src, False
    lines[pkg] = lines[pkg].lstrip('\ufeff')
    lines.insert(pkg + 1, 'import android.content.Context')
    return '\n'.join(lines), True


def inject(path):
    if not os.path.isfile(path):
        return 'missing'
    src = io.open(path, encoding='utf-8').read()
    if MARK in src:
        return 'already'
    m = re.search(r'^\s*(?:abstract\s+|open\s+)?class\s+(\w+)', src, re.M)
    if not m:
        return 'no-class'
    # 找类声明后的第一个 '{'
    brace = src.find('{', m.end())
    if brace < 0:
        return 'no-brace'
    src = src[:brace + 1] + SNIPPET + src[brace + 1:]
    src, added = add_import(src)
    io.open(path, 'w', encoding='utf-8', newline='').write(src)
    return 'ok(import=%s)' % added


if __name__ == '__main__':
    for f in FILES:
        print('%-70s %s' % (f.split('/')[-1], inject(f)))

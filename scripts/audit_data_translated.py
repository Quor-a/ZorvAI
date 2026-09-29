# -*- coding: utf-8 -*-
"""找出「被 i18n 化、但本质是数据而非文案」的字符串。

这类串一旦随语言变化，功能就会真的坏掉（shell 命令、URL、正则、JSON、协议串…）
——用户所说的「部分功能被删除」，真凶多半在这里。
"""
import io
import re
import subprocess
import sys

RES = 'app/src/main/res/values/strings_i18n.xml'

# 数据特征（保守，宁可漏报也不误报）
DATA_PAT = [
    ('URL', re.compile(r'[a-z]+://')),
    ('SHELL', re.compile(r'&&|\|\||\becho\b|\bapt-get\b|\bpip\b|\bcargo\b|\brustc\b|\bnpm\b|\bpnpm\b|\bsshpass\b|\bgradle\b')),
    ('SMARTQ', re.compile(r'[\u2018\u2019\u201c\u201d]')),
    ('REGEX', re.compile(r'\\[dswb]|\(\?[:=!]|\{\d+(?:,\d*)?\}')),
    ('JSON', re.compile(r'^\s*[\{\[]\s*"')),
    ('CODE', re.compile(r'\b(?:fun|val|var|class)\s|\{\s*\w+\s*\(|</\w+>')),
]


def read(p):
    try:
        return io.open(p, encoding='utf-8').read()
    except Exception:
        return ''


def main():
    src = read(RES)
    items = []
    for m in re.finditer(r'<string name="([\w.]+)"([^>]*)>(.*?)</string>', src, re.S):
        items.append((m.group(1), m.group(3)))
    print('default 资源条数：%d' % len(items))

    hits = []
    for k, v in items:
        tags = [t for t, rx in DATA_PAT if rx.search(v)]
        if tags:
            hits.append((k, tags, v))

    # 统计
    from collections import Counter
    c = Counter()
    for k, tags, v in hits:
        for t in tags:
            c[t] += 1
    print('命中条数：%d' % len(hits), dict(c))

    print('\n=== 明细 ===')
    for k, tags, v in hits:
        refs = subprocess.run(['git', 'grep', '-ln', 'R.string.' + k],
                              capture_output=True, text=True, encoding='utf-8',
                              errors='replace').stdout.strip().split('\n')
        refs = [r for r in refs if r.endswith('.kt')]
        print('\n[%s] %s' % (k, ','.join(tags)))
        print('   值: %s' % v.strip()[:200])
        for r in refs[:3]:
            print('   引用: %s' % r.replace('app/src/main/java/com/ai/assistance/quro/', ''))


if __name__ == '__main__':
    sys.exit(main())

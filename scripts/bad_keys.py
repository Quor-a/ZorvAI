# -*- coding: utf-8 -*-
"""扫描出「资源条目已损坏 / 本身是数据」的 qk 键。

四类：
  FFFD  值里含 U+FFFD（写资源时编码损坏，界面会显示 �）
  INTERP 值里残留 Kotlin 插值（${if ( / ${SimpleDateFormat( …），界面会显示源码残片
  DATA  值本身是 shell 命令 / 内联 HTML / URL 命令行（翻译即坏功能）
  SMART 值里的 ASCII 引号被替换成中文智能引号（命令、HTML 属性直接失效）
"""
import io
import glob
import re

RES = 'app/src/main/res/values/strings_i18n.xml'
DATA_RX = re.compile(r'&&|\|\||\becho\b|\bapt-get\b|^\s*<body\b|</\w+>|https?://[^\s]*\?')
SMART_RX = re.compile(r'[\u2018\u2019]')


def scan(path=RES):
    src = io.open(path, encoding='utf-8').read()
    out = {}
    for m in re.finditer(r'<string name="([\w.]+)"[^>]*>(.*?)</string>', src, re.S):
        k, v = m.group(1), m.group(2)
        tags = []
        if '\ufffd' in v:
            tags.append('FFFD')
        if '${' in v:
            tags.append('INTERP')
        if DATA_RX.search(v):
            tags.append('DATA')
        if SMART_RX.search(v) and re.search(r'[=;&|]|echo|<', v):
            tags.append('SMART')
        # 只有真正会坏掉的才需要回退：
        #   FFFD   界面直接显示 �
        #   INTERP 界面直接显示 ${if (… 源码残片
        #   SMART  引号被换成 ’ → shell 命令 / HTML 属性失效
        # 纯 DATA（只是文案里提到 URL / 命令名）翻译无害，不动它。
        if {'FFFD', 'INTERP', 'SMART'} & set(tags):
            out[k] = (tags, v)
    return out


def bad_keys():
    return set(scan().keys())


if __name__ == '__main__':
    d = scan()
    from collections import Counter
    c = Counter()
    for k, (tags, v) in d.items():
        for t in tags:
            c[t] += 1
    print('坏键合计 %d  %s' % (len(d), dict(c)))
    for k, (tags, v) in sorted(d.items()):
        print('%-10s %-22s %s' % (k, ','.join(tags), v.strip()[:100]))

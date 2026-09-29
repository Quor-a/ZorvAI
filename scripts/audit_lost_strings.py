# -*- coding: utf-8 -*-
"""硬证据 + 定位：找出「HEAD 有、当前工作树没有」的用户可见文案。

i18n 化把中文字面量搬进了 res/values/strings_i18n.xml（qk_xxxxx）。
所以判定「文案是否还在」必须做反查：
  当前集合 = 工作树里仍在的中文串  ∪  qstr(R.string.qk_x)/stringResource(R.string.qk_x) 反查出的中文原文
差集 = 真正丢掉的文案 = 可能对应「被删掉的功能项」。
"""
import io
import html
import os
import re
import subprocess
import sys

RES_DIR = 'app/src/main/res/values'
RES = RES_DIR + '/strings_i18n.xml'
CJK = re.compile(r'[\u4e00-\u9fff]')
# 占位符归一化：原文 ${f.name} / $name 与资源里 %1$s / %s 视为同一句文案，
# 否则每条带插值的文案都会被误判成「丢失」。
PH = [
    # 顺序敏感：%1$s 必须整体先吃掉，否则 $s 会被下一条当成 Kotlin 变量名。
    (re.compile(r'%\d+\$[sdf]'), '\x00'),
    (re.compile(r'\$\{[^}]*\}'), '\x00'),
    (re.compile(r'\$[A-Za-z_]\w*'), '\x00'),
    (re.compile(r'%[sdf]'), '\x00'),
]


def norm(s):
    s = unesc(s)
    for rx, rep in PH:
        s = rx.sub(rep, s)
    return re.sub(r'\s+', ' ', s).strip()


def unesc(s):
    """Android 资源里的实体/转义还原成源码字面量形态，否则 &gt; 之类永远配不上。"""
    s = html.unescape(s)
    s = s.replace("\\'", "'").replace('\\"', '"')
    return s
# qstr(R.string.qk_00123) / stringResource(R.string.qk_00123)
REF = re.compile(r'(?:qstr|stringResource)\(\s*R\.string\.(qk_\w+)')
# 纯中文字面量（不含取串调用）
LIT = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def read(path):
    try:
        return io.open(path, encoding='utf-8').read()
    except Exception:
        return ''


def head_text(path):
    r = subprocess.run(['git', 'show', 'HEAD:' + path], capture_output=True,
                       text=True, encoding='utf-8', errors='replace')
    return r.stdout if r.returncode == 0 else None


def load_map():
    """qk_x / 普通 string 名 → 中文原文（含 string-array 项）。"""
    m = {}
    names = []
    if os.path.isdir(RES_DIR):
        names = [os.path.join(RES_DIR, n) for n in os.listdir(RES_DIR) if n.endswith('.xml')]
    for p in names:
        src = read(p)
        for mm in re.finditer(r'<string name="([\w.]+)"[^>]*>(.*?)</string>', src, re.S):
            m[mm.group(1)] = mm.group(2)
        for arr in re.finditer(r'<string-array name="([\w.]+)"[^>]*>(.*?)</string-array>', src, re.S):
            for it in re.finditer(r'<item[^>]*>(.*?)</item>', arr.group(2), re.S):
                m.setdefault(arr.group(1) + '#' + str(len(m)), it.group(1))
    return m


# stringResource(R.string.xxx) / qstr(R.string.xxx)，键名不限 qk_ 前缀
REF = re.compile(r'(?:qstr|stringResource|R\.string\.)(?:R\.string\.)?\.?([A-Za-z_]\w*)')
REF2 = re.compile(r'R\.string\.([A-Za-z_]\w*)')


def zh_set(text, qmap):
    """返回该文件里「用户可见中文」的集合（已做占位符归一化）。"""
    out = set()
    for mm in LIT.finditer(text):
        s = mm.group(1)
        if CJK.search(s) and len(s) >= 2:
            out.add(norm(s))
    for k in REF2.findall(text):
        v = qmap.get(k)
        if v and CJK.search(v):
            out.add(norm(v))
    return out


def main():
    global skipped_broken
    skipped_broken = [0]
    qmap = load_map()
    resset = set()
    for v in qmap.values():
        if CJK.search(v):
            resset.add(norm(v))
    files = subprocess.run(['git', 'ls-tree', '-r', '--name-only', 'HEAD'],
                           capture_output=True, text=True, encoding='utf-8',
                           errors='replace').stdout.split('\n')
    files = [f for f in files if f.endswith('.kt') and f.startswith('app/src/main/java/')]

    lost = {}
    for f in files:
        h = head_text(f)
        if h is None:
            continue
        cur = read(f)
        if not cur:
            lost[f] = ['<文件缺失>']
            continue
        hs = zh_set(h, qmap)
        cs = zh_set(cur, qmap)
        # HEAD 侧含 U+FFFD 的条目是历史编码损坏（QuroChatViewModel 那批），
        # 已被单独重建修复；拿损坏文本去比会一律假阳性，故跳过。
        broken = {s for s in hs if '\ufffd' in s}
        skipped_broken[0] += len(broken)
        hs = hs - broken
        blob = '\n'.join(cs)
        d = []
        for s in sorted(hs - cs):
            # i18n 会把一段多行文案拆成多条 qk，形态配不上不等于丢失：
            # 只要每个有效行仍能在当前文件的文案集合里找到，就算保留。
            parts = [p.strip() for p in re.split(r'\\n|\n', s)]
            parts = [p for p in parts if len(p) >= 4 and CJK.search(p)]
            if parts and not all(p in blob for p in parts):
                d.append(s)
            elif not parts and len(s) < 4:
                d.append(s)
        if d:
            lost[f] = d

    total = 0
    in_res, not_in_res = 0, 0
    detail = {}
    print('=== HEAD 有、当前没有的用户可见中文文案 ===')
    if not lost:
        print('  无（全部文案 1:1 保留）')
    for f, d in sorted(lost.items()):
        total += len(d)
        a = [s for s in d if s in resset]
        b = [s for s in d if s not in resset]
        in_res += len(a)
        not_in_res += len(b)
        detail[f] = (a, b)
        print('\n[%s]  丢失 %d 条（资源里已有 %d / 资源里也没有 %d）'
              % (f.replace('app/src/main/java/com/ai/assistance/quro/', ''), len(d), len(a), len(b)))
        for s in d[:40]:
            tag = 'RES' if s in resset else 'GONE'
            print('   - [%s] %s' % (tag, s))
        if len(d) > 40:
            print('   ... 其余 %d 条' % (len(d) - 40))
    print('\n合计丢失文案：%d 条，涉及 %d 个文件' % (total, len(lost)))
    print('  其中 资源里已有（只是代码没引用）：%d 条' % in_res)
    print('  其中 资源里也没有（真丢失）：%d 条' % not_in_res)
    print('  （已跳过 HEAD 侧带编码损坏 U+FFFD 的 %d 条，它们已单独重建修复）' % skipped_broken[0])


if __name__ == '__main__':
    sys.exit(main())

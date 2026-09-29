# -*- coding: utf-8 -*-
"""把「坏了 / 本质是数据」的 i18n 调用点，按 HEAD 原文整行回退为硬编码字面量。

不能用「行号相似度对齐」——那会把 `"python" ->` 配到 HEAD 的 `"rust" ->` 行。
这里改用三重证据定位 HEAD 真值行：
  ① 当前行去掉调用后的前后缀骨架（prefix / suffix）
  ② 资源值里「确定无疑的片段」（剥掉 U+FFFD、${...}、%N$s 之后的中文/ASCII 片段）必须全部出现
  ③ 要求唯一命中，多命中就报出来人工看

用法：
  python scripts/fix_bad_i18n.py          # 仅预览
  python scripts/fix_bad_i18n.py --apply  # 实际写入
"""
import html
import io
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bad_keys import scan  # noqa: E402

BAD = scan()          # key -> (tags, value)
CALL = re.compile(r'(?:qstr|stringResource)\(\s*R\.string\.(qk_\w+)')


def head_lines(path):
    r = subprocess.run(['git', 'show', 'HEAD:' + path], capture_output=True,
                       text=True, encoding='utf-8', errors='replace')
    return r.stdout.split('\n') if r.returncode == 0 else None


def modified_files():
    out = subprocess.run(['git', 'diff', '--name-only'], capture_output=True,
                         text=True, encoding='utf-8', errors='replace').stdout.split('\n')
    return [f for f in out if f.endswith('.kt') and os.path.isfile(f)]


def frags_of(value):
    """从资源值里抽出「确定无疑的片段」，用于在 HEAD 里锚定唯一行。"""
    s = html.unescape(value)
    s = s.replace('\u2019', "'").replace('\u2018', "'")
    s = s.replace('\u201c', '"').replace('\u201d', '"')
    # 剥掉损坏处、Kotlin 插值与格式化占位符
    s = re.sub(r'\ufffd\??', '\n', s)
    s = re.sub(r'\$\{[^}]*\}?', '\n', s)
    s = re.sub(r'\$[A-Za-z_]\w*', '\n', s)
    s = re.sub(r'%\d+\$[sdf]', '\n', s)
    s = re.sub(r'%[sdf]', '\n', s)
    return [p.strip() for p in s.split('\n') if len(p.strip()) >= 2]


def head_frag(h):
    """HEAD 源码行 → 去掉 Kotlin 转义后的可比文本。"""
    s = h.replace('\\"', '"').replace("\\'", "'")
    return s


def find(head, cur_line, value):
    frags = frags_of(value)
    ci = cur_line.find('qstr(')
    if ci < 0:
        ci = cur_line.find('stringResource(')
    # 调用结束位置：从调用起点做括号匹配
    depth, k, n = 0, ci, len(cur_line)
    while k < n:
        if cur_line[k] == '(':
            depth += 1
        elif cur_line[k] == ')':
            depth -= 1
            if depth == 0:
                break
        k += 1
    pre = cur_line[:ci].strip()
    post = cur_line[k + 1:].strip() if k < n else ''

    def ok(h):
        if not frags:
            return True
        hh = head_frag(h)
        return all(f in hh for f in frags)

    cands = [h for h in head if ok(h)]
    if pre:
        p = [h for h in cands if h.strip().startswith(pre)]
        if len(p) == 1:
            return p[0], 'pre'
        if p:
            cands = p
    if post:
        q = [h for h in cands if h.strip().endswith(post)]
        if len(q) == 1:
            return q[0], 'post'
        if q:
            cands = q
    if len(cands) == 1:
        return cands[0], 'frag'
    uniq = {h.strip() for h in cands}
    if len(uniq) == 1:
        # 多个分支共用同一份文案（如 when 的 else 分支），内容一致，取之即可
        return cands[0], 'multi-same'
    return None, 'ambig:%d' % len(cands)


def main():
    apply = '--apply' in sys.argv
    total, files, skipped = 0, 0, []
    for f in modified_files():
        cur = io.open(f, encoding='utf-8').read()
        lines = cur.split('\n')
        idx = []
        for i, l in enumerate(lines):
            m = CALL.search(l)
            if m and m.group(1) in BAD:
                idx.append((i, m.group(1)))
        if not idx:
            continue
        head = head_lines(f)
        if head is None:
            continue
        changed = 0
        for i, key in idx:
            src, how = find(head, lines[i], BAD[key][1])
            if src is None or 'qstr(' in src or 'stringResource(' in src:
                skipped.append('%s:%d %s (%s)' % (f, i + 1, key, how))
                continue
            if not apply:
                print('  %s:%d [%s]' % (f.replace('app/src/main/java/com/ai/assistance/quro/', ''), i + 1, how))
                print('    -   %s' % lines[i].strip()[:120])
                print('    +   %s' % src.strip()[:120])
            lines[i] = src
            changed += 1
        if apply and changed:
            io.open(f, 'w', encoding='utf-8', newline='').write('\n'.join(lines))
        if changed:
            files += 1
            total += changed
            if apply:
                print('  [write] %s  回退 %d 行' % (f, changed))
    print('\n%s：%d 行 / %d 个文件' % ('已写入' if apply else '待回退', total, files))
    if skipped:
        print('未能定位（需人工处理）%d 处：' % len(skipped))
        for s in skipped:
            print('   ', s)


if __name__ == '__main__':
    main()

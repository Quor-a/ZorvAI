# -*- coding: utf-8 -*-
"""把含乱码字符串的行导出为文本，供逐行修正。"""
import io
import subprocess
import sys

sys.path.insert(0, 'scripts')
from dump_fffd_lines import P, src, instr, NL  # noqa: E402

lines = src.split(NL)
pos = 0
out = []
for idx, l in enumerate(lines):
    seg = slice(pos, pos + len(l))
    if any(instr[k] and src[k] == '\ufffd' for k in range(seg.start, seg.stop)):
        out.append('%d\t%s' % (idx + 1, l))
    pos += len(l) + 1

dst = 'scripts/_fffd_lines.txt'
io.open(dst, 'w', encoding='utf-8').write('\n'.join(out))
print('导出 %d 行 → %s' % (len(out), dst))

# -*- coding: utf-8 -*-
"""粗略括号平衡自检：给拆分后的 native 文件做一次廉价的『结构完整性』体检。

注意：这不是编译器，只是把注释/字符串剔掉后数括号。它能抓住
「函数体被 if 吞掉」「漏写右花括号」这类上一轮踩过的坑，
但抓不住类型错误。编译仍是唯一权威。
"""
import io
import re
import os
import sys

LINE_COMMENT = re.compile(r'//[^\n]*')
BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
STR_LIT = re.compile(r'"(?:\\.|[^"\\])*"', re.S)
CHR_LIT = re.compile(r"'(?:\\.|[^'\\])*'", re.S)


def strip_noise(text):
    s = LINE_COMMENT.sub('', text)
    s = BLOCK_COMMENT.sub('', s)
    s = STR_LIT.sub('""', s)
    s = CHR_LIT.sub("''", s)
    return s


def check(base, files):
    bad = 0
    for f in files:
        path = os.path.join(base, f)
        if not os.path.isfile(path):
            print('%-30s 缺失' % f)
            bad += 1
            continue
        text = io.open(path, encoding='utf-8').read()
        s = strip_noise(text)
        b = s.count('{') - s.count('}')
        p = s.count('(') - s.count(')')
        flag = 'OK' if (b == 0 and p == 0) else '<<< 不平衡'
        if b or p:
            bad += 1
        print('%-30s {} %+d   () %+d   %5d 行  %s' % (f, b, p, text.count('\n') + 1, flag))
    return bad


if __name__ == '__main__':
    base = sys.argv[1] if len(sys.argv) > 1 else '.'
    names = sys.argv[2:] if len(sys.argv) > 2 else sorted(
        n for n in os.listdir(base) if n.endswith(('.cpp', '.h', '.inc')))
    print('=== 括号平衡自检：%s ===' % base)
    n = check(base, names)
    print('不平衡文件数 = %d' % n)

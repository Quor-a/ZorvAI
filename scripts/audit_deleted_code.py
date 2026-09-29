# -*- coding: utf-8 -*-
"""硬证据：对比 HEAD 与当前工作树，找出是否有【函数/类/常量被删】。

-i18n 化的文案替换是 1:1 的（删一行字符串、加一行取串调用），
所以只要函数签名没少、行数没大减，就不存在「功能被删除」。
"""
import io
import re
import subprocess

FUN = re.compile(r'\bfun\s+(?:<[^>]+>\s*)?(?:[\w.<>?]+\.)?(\w+)\s*\(')
CLS = re.compile(r'\b(?:class|object|interface)\s+(\w+)')
PROP = re.compile(r'^\s*(?:private\s+|internal\s+|public\s+)?(?:const\s+)?val\s+([A-Za-z_]\w*)', re.M)


def git_show(path):
    r = subprocess.run(['git', 'show', 'HEAD:' + path], capture_output=True,
                       text=True, encoding='utf-8', errors='replace')
    return r.stdout if r.returncode == 0 else None


files = subprocess.run(['git', 'diff', '--name-only'], capture_output=True,
                       text=True, encoding='utf-8', errors='replace').stdout.split('\n')
files = [f for f in files if f.endswith('.kt')]

miss_fun, miss_cls, shrank = [], [], []
for f in files:
    head = git_show(f)
    if head is None:
        continue
    try:
        cur = io.open(f, encoding='utf-8').read()
    except Exception:
        continue
    hf, cf = set(FUN.findall(head)), set(FUN.findall(cur))
    hc, cc = set(CLS.findall(head)), set(CLS.findall(cur))
    d1 = sorted(hf - cf)
    d2 = sorted(hc - cc)
    if d1:
        miss_fun.append((f, d1))
    if d2:
        miss_cls.append((f, d2))
    hl = len([l for l in head.split('\n') if l.strip()])
    cl = len([l for l in cur.split('\n') if l.strip()])
    if hl - cl > 5:
        shrank.append((f, hl, cl, hl - cl))

print('=== 丢失的函数（HEAD 有、当前无）===')
if not miss_fun:
    print('  无')
for f, d in miss_fun:
    print(' ', f, '->', d)

print('=== 丢失的类/对象 ===')
if not miss_cls:
    print('  无')
for f, d in miss_cls:
    print(' ', f, '->', d)

print('=== 非空行净减少 > 5 的文件 ===')
if not shrank:
    print('  无')
for f, a, b, d in sorted(shrank, key=lambda x: -x[3])[:25]:
    print('  %-70s %d -> %d  (-%d)' % (f.replace('app/src/main/java/com/ai/assistance/quro/', ''), a, b, d))

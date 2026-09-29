# -*- coding: utf-8 -*-
"""多行感知的显示类中文字串抽取器（括号深度栈）。"""
import os, re, json, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
ROOT = 'app/src/main/java'
from i18n_translate import TRANSLATIONS
EN = set(TRANSLATIONS)

DISPLAY_CALL = {'Text', 'BasicText', 'AnnotatedString', 'TextButton', 'OutlinedButton',
                'Snackbar', 'DialogTitle', 'ListItem', 'TopAppBar'}
DISPLAY_ARG = {'text', 'label', 'title', 'placeholder', 'contentDescription',
               'supportingText', 'subtitle', 'description', 'hint', 'message',
               'confirmText', 'dismissText', 'setTitle', 'setMessage', 'setText',
               'headline', 'name', 'labelText', 'summary', 'caption'}
DISPLAY_FUNC = {'Toast.makeText', 'snackbar', 'showSnackbar', 'showToast', 'toast'}

CJK = re.compile(r'[\u4e00-\u9fff]')
EXCLUDE_META = re.compile(r'[\\^$|*+?\[\]\(\)\{\}]')

def scan_file(path):
    """返回 (display_strings:list, ) 该文件里处于显示上下文的字面量。"""
    src = open(path, encoding='utf-8', errors='ignore').read()
    n = len(src)
    i = 0
    stack = []            # [(ident, )...] 括号栈
    out = []
    while i < n:
        c = src[i]
        # 行注释
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j + 1
            continue
        # 块注释
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        # 三引号原始串
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        # 普通串
        if c == '"':
            j = i + 1
            buf = []
            while j < n:
                if src[j] == '\\':
                    buf.append(src[j]); buf.append(src[j + 1] if j + 1 < n else '')
                    j += 2
                    continue
                if src[j] == '"':
                    break
                buf.append(src[j]); j += 1
            lit = ''.join(buf)
            # 判断上下文
            if CJK.search(lit):
                is_disp = False
                # 1) 是否在某个显示调用括号内
                for ident in stack:
                    last = ident.split('.')[-1]
                    if last in DISPLAY_CALL or ident in DISPLAY_FUNC:
                        is_disp = True
                        break
                # 2) 是否紧跟 `name = ` 显示参数
                if not is_disp:
                    pre = src[max(0, i - 40):i]
                    m = re.search(r'([A-Za-z_][A-Za-z0-9_]*)\s*=\s*$', pre)
                    if m and m.group(1) in DISPLAY_ARG:
                        is_disp = True
                if is_disp:
                    out.append(lit)
            i = j + 1
            continue
        if c == '(':
            # 向前取标识符/函数名
            k = i - 1
            while k >= 0 and src[k] in ' \t\n':
                k -= 1
            e = k + 1
            while k >= 0 and (src[k].isalnum() or src[k] in '_.$'):
                k -= 1
            ident = src[k + 1:e]
            stack.append(ident)
            i += 1
            continue
        if c == ')':
            if stack:
                stack.pop()
            i += 1
            continue
        i += 1
    return out

all_hits = {}
files = []
for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if fn.endswith('.kt'):
            files.append(os.path.join(dp, fn))

for p in files:
    for t in scan_file(p):
        if EXCLUDE_META.search(t):
            continue
        all_hits[t] = all_hits.get(t, 0) + 1

uniq = set(all_hits)
in_en = {t for t in uniq if t in EN}
print('== 显示类中文字串（多行感知）==')
print('扫描 .kt 文件数:', len(files))
print('去重 unique    :', len(uniq))
print('出现总次数     :', sum(all_hits.values()))
print('已在英文词表   :', len(in_en), '(%.1f%%)' % (100.0 * len(in_en) / max(1, len(uniq))))
print('待补翻译       :', len(uniq - in_en))

with open(os.path.join(HERE, 'ui_strings.txt'), 'w', encoding='utf-8') as f:
    for t in sorted(uniq, key=lambda x: -all_hits[x]):
        f.write('%d\t%s\n' % (all_hits[t], t))

# 校验截图里的串是否被捕获
check = ['工具中心', '包管理', '隔离沙箱', '检查更新', '宠物管理', '设置中心',
         '节点编辑器', '快捷操作', '可视化编程', '私有数据库', '工具包运行器', '插件']
print('--- 截图串捕获校验 ---')
for s in check:
    print('  %-10s captured=%s' % (s, s in uniq))
print('已写出 scripts/ui_strings.txt')

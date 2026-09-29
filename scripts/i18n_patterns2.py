import json, re, os, glob, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = 'app/src/main/java'
sys.path.insert(0, HERE)
from i18n_translate import TRANSLATIONS

EN = dict(TRANSLATIONS)
for _f in sorted(glob.glob(os.path.join(HERE, 'i18n_en_*.json'))):
    EN.update(json.load(open(_f, encoding='utf-8')))

data = json.load(open(os.path.join(HERE, 'i18n_strings.json'), encoding='utf-8'))
# 只收录「已进入 i18n 范围(英文已翻译)」的串作为 key_of，确保转换出的 stringResource
# 引用的资源一定会被 i18n_build.py 生成；未翻译串保持字面量，绝不产生悬空引用。
key_of = {}
for idx, e in enumerate(data):
    t = e['text']
    if t in EN:
        key_of[t] = 'qk_%05d' % idx
scope = set(key_of)

CJK = r'(?:[^\'"]*[\u4e00-\u9fff][^\'"]*)'

# 上下文分析统一由 i18n_ctx 提供（字符串/注释感知的括号匹配），避免函数体内含括号字符串导致区间溢出。
from i18n_ctx import make_composable_checker, _is_comment

PARAM = r'(?:name|title|label|sub|summary|placeholder|hint|subtitle|headline|contentDescription|caption|supporting|subhead|overline|description|text|value|trailing|headlineContent|secondaryText)'

RE_PT_TERN = re.compile(r'(\b%s\s*=\s*(?:[^()=]*?\b)?if\s*\([^()]*\)\s*)([\'"])(%s)\2(\s*else\s*)([\'"])(%s)\3' % (PARAM, CJK, CJK))
RE_IFBRANCH = re.compile(r'(\bif\s*\([^()]*\)\s*)([\'"])(%s)\2(\s*else)' % CJK)
RE_TRIPLE = re.compile(r'Triple\(\s*[\'"](' + r'[^\'"]*' + r')[\'"]\s*,\s*[\'"]((?:%s))[\'"]\s*,\s*[\'"]((?:%s))[\'"]' % (CJK, CJK))
RE_PT_ELVIS= re.compile(r'(\b%s\s*=\s*[^:()]*?\?\s*\:\s*)([\'"])(%s)\2' % (PARAM, CJK))
RE_PT_IFBLANK = re.compile(r'(\b%s\s*=\s*[^}]*?ifBlank\s*\{\s*)([\'"])(%s)\2(\s*\})' % (PARAM, CJK))
RE_WHEN = re.compile(r'(\-\>\s*)([\'"])(%s)\2' % CJK)
RE_LISTOF = re.compile(r'listOf\(([^()]*)\)')

files_touched = 0
total = 0

for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if not fn.endswith('.kt'):
            continue
        if fn in ('QuroChatViewModel.kt', 'QuroPersonaViewModel.kt', 'QuroModelConfigViewModel.kt', 'ChatData.kt'):
            continue
        p = os.path.join(dp, fn)
        try:
            src = open(p, encoding='utf-8').read()
        except Exception:
            continue
        is_composable = make_composable_checker(src)
        new = src
        did = False

        def safe(pos):
            return (not _is_comment(new, pos)) and is_composable(pos)

        # 2) 参数级三元/elvis/ifBlank —— 需 composable 上下文
        def param_tern(m):
            global total
            if not safe(m.start()):
                return m.group(0)
            a, b = m.group(3), m.group(6)
            ra, rb = key_of.get(a), key_of.get(b)
            if ra and rb:
                total += 2
                return m.group(1) + 'stringResource(R.string.%s)' % ra + m.group(4) + 'stringResource(R.string.%s)' % rb
            return m.group(0)
        def param_elvis(m):
            global total
            if not safe(m.start()):
                return m.group(0)
            a = m.group(3); ra = key_of.get(a)
            if ra:
                total += 1
                return m.group(1) + 'stringResource(R.string.%s)' % ra
            return m.group(0)
        def param_ifblank(m):
            global total
            if not safe(m.start()):
                return m.group(0)
            a = m.group(3); ra = key_of.get(a)
            if ra:
                total += 1
                return m.group(1) + 'stringResource(R.string.%s)' % ra + m.group(4)
            return m.group(0)
        new = RE_PT_TERN.sub(param_tern, new)
        new = RE_PT_ELVIS.sub(param_elvis, new)
        new = RE_PT_IFBLANK.sub(param_ifblank, new)

        # 2b) if 分支字面量（`if (cond) "x" else <expr>`）
        def ifbranch_repl(m):
            global total
            if not safe(m.start()):
                return m.group(0)
            a = m.group(3); ra = key_of.get(a)
            if ra:
                total += 1
                return m.group(1) + 'stringResource(R.string.%s)' % ra + m.group(4)
            return m.group(0)
        new = RE_IFBRANCH.sub(ifbranch_repl, new)

        # 2c) Triple("id", "中文名", "中文描述") —— 工具中心卡片列表
        def triple_repl(m):
            global total
            if not safe(m.start()):
                return m.group(0)
            a, b = m.group(2), m.group(3)
            ra, rb = key_of.get(a), key_of.get(b)
            if ra and rb:
                total += 2
                # 注意：原始 Triple(...) 的结尾 ')' 仍保留在源串剩余部分，这里不要再补 ')'，否则会多一个括号。
                return 'Triple("%s", stringResource(R.string.%s), stringResource(R.string.%s)' % (m.group(1), ra, rb)
            return m.group(0)
        new = RE_TRIPLE.sub(triple_repl, new)

        # 3) when 分支 `-> "中文"` —— 需 composable 上下文
        def when_repl(m):
            global total
            if not safe(m.start()):
                return m.group(0)
            a = m.group(3); ra = key_of.get(a)
            if ra:
                total += 1
                return m.group(1) + 'stringResource(R.string.%s)' % ra
            return m.group(0)
        new = RE_WHEN.sub(when_repl, new)

        # 4) listOf("a","b") 列表字面量（标签/tab 等）—— 需 composable 上下文
        def listo_repl_safe(m):
            global total
            seg = m.group(1)
            seg_start = m.start() + m.group(0).index(seg)
            def inner(imm):
                global total
                pos = seg_start + imm.start()
                if not safe(pos):
                    return imm.group(0)
                a = imm.group(2); ra = key_of.get(a)
                if ra:
                    total += 1
                    return imm.group(1) + 'stringResource(R.string.%s)' % ra + imm.group(3)
                return imm.group(0)
            newseg = re.sub(r'([\'"])(%s)(\1)' % CJK, inner, seg)
            return 'listOf(' + newseg + ')'
        new = RE_LISTOF.sub(listo_repl_safe, new)

        if new != src:
            need_sr = 'stringResource(' in new and 'import androidx.compose.ui.res.stringResource' not in new
            need_r = 'R.string.' in new and 'import com.ai.assistance.quro.R' not in new
            pre = ''
            if need_sr:
                pre += 'import androidx.compose.ui.res.stringResource\n'
            if need_r:
                pre += 'import com.ai.assistance.quro.R\n'
            if pre:
                ls = new.splitlines()
                pkg = next((i for i, l in enumerate(ls) if l.startswith('package ')), -1)
                ls.insert(pkg + 1 if pkg >= 0 else 0, pre.strip())
                new = '\n'.join(ls)
            open(p, 'w', encoding='utf-8').write(new)
            files_touched += 1

print('patterns2(增强) 完成：%d 个文件修改，%d 处字面量改为资源引用' % (files_touched, total))

import json, re, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

ROOT = 'app/src/main/java'
STRINGS_JSON = os.path.join(HERE, 'i18n_strings.json')

import glob
from i18n_translate import TRANSLATIONS
from i18n_ctx import make_composable_checker, ctxvar_at, _is_comment, _scan_string, _scan_char

# 每个文件在转换前由主循环写入「当前文件的 Composable 上下文判定器」，guarded/guarded_param 据此决定是否注入。
IS_COMPOSABLE = None
# 当前文件全文（供 ctxvar_at 做作用域分析）
SRC = ''

# ---------- 语言 -> 资源目录映射（全部 11 种，绝不删除任何目录） ----------
# zh 是默认(中文)资源；其余每种语言都会生成完整的 strings_i18n.xml：
# 有该语言翻译就用该语言，否则回退到英文（EN 是完整兜底，绝不漏中文）。
LANG_DIRS = {
    'zh': 'app/src/main/res/values',        # 默认中文
    'en': 'app/src/main/res/values-en',
    'ar': 'app/src/main/res/values-ar',
    'de': 'app/src/main/res/values-de',
    'es': 'app/src/main/res/values-es',
    'fr': 'app/src/main/res/values-fr',
    'hi': 'app/src/main/res/values-hi',
    'ja': 'app/src/main/res/values-ja',
    'ko': 'app/src/main/res/values-ko',
    'pt': 'app/src/main/res/values-pt',
    'ru': 'app/src/main/res/values-ru',
}
# 显式中文目录也镜像一份，保证选 "zh" 时完全一致
ZH_MIRROR = 'app/src/main/res/values-zh'

# ---------- 英文：精选词典 + 批量补全（合并后即为「完整英文」） ----------
EN = dict(TRANSLATIONS)
for _f in sorted(glob.glob(os.path.join(HERE, 'i18n_en_*.json'))):
    EN.update(json.load(open(_f, encoding='utf-8')))

# ---------- 其余 9 种语言：批量真实翻译（缺的回退英文） ----------
# 注意：语言词表里若混进了**未翻译的中文原文**（此前 values-ja 就有这种条目），该语言
# 资源会直接显示中文——属于「选了外语还是中文」的泄漏。这里只剔除「值 == 中文原文」的
# 条目（回落英文）。**不能**按「含中日韩统一表意文字」粗暴过滤：日文汉字本就在该区间，
# 那样会把真日文译文一起删掉。
LANG_TRANS = {}
for _code in LANG_DIRS:
    if _code in ('zh', 'en'):
        continue
    _d = {}
    _base = os.path.join(HERE, 'i18n_lang_%s.json' % _code)
    if os.path.exists(_base):
        _d.update(json.load(open(_base, encoding='utf-8')))
    for _f in sorted(glob.glob(os.path.join(HERE, 'i18n_lang_%s_*.json' % _code))):
        _d.update(json.load(open(_f, encoding='utf-8')))
    # ja 与中文大量同形（一/二/三/四/日/墨墨…），"值 == 原文" 不能判定为未翻译，
    # 否则这些正确译文会被删掉并回落成英文（日文界面冒出英文数字/人名）。
    # 仅对非 ja 语言启用该剔除。
    _bad = [] if _code == 'ja' else [
        k for k, v in _d.items() if isinstance(v, str) and v.strip() == k.strip()
    ]
    for k in _bad:
        _d.pop(k, None)
    if _bad:
        print('  [%s] 剔除未翻译（值==中文原文）条目: %d 条（回落英文）' % (_code, len(_bad)))
    LANG_TRANS[_code] = _d

data = json.load(open(STRINGS_JSON, encoding='utf-8'))
key_of = {}
for idx, e in enumerate(data):
    key_of[e['text']] = 'qk_%05d' % idx


def convert_template(t):
    """把 Kotlin 模板 $var / ${expr} 转成 %N$s 占位，返回 (资源文本, [参数表达式])。
    遇到嵌套花括号等无法干净解析的情况返回 None（交给人工）。"""
    result = []
    args = []
    i = 0
    n = len(t)
    while i < n:
        c = t[i]
        if c == '$':
            if i + 1 < n and t[i + 1] == '{':
                depth = 0
                j = i + 1
                ok = False
                while j < n:
                    if t[j] == '{':
                        depth += 1
                    elif t[j] == '}':
                        depth -= 1
                        if depth == 0:
                            ok = True
                            break
                    j += 1
                if not ok:
                    return None
                expr = t[i + 2:j]
                if '{' in expr or '}' in expr:
                    return None  # 嵌套花括号，跳过
                args.append(expr)
                result.append('%%%d$s' % len(args))
                i = j + 1
            else:
                # Kotlin 的 $x 只吃标识符；'.md' / '.size' 等是紧随其后的字面量。
                # 旧正则把 '[\.\w]+' 一起吞了，于是 "$stamp.md" 变成变量 'stamp.md'
                # → 生成 qstr(..., (stamp.md).toString()) → "Unresolved reference 'md'"。
                m = re.match(r'[A-Za-z_]\w*', t[i + 1:])
                if m:
                    args.append(m.group(0))
                    result.append('%%%d$s' % len(args))
                    i = i + 1 + len(m.group(0))
                else:
                    result.append(c)
                    i += 1
        else:
            result.append(c)
            i += 1
    return ''.join(result), args


def template_ok(t):
    """可安全进入 i18n 范围：英文已翻 且 模板能解析（或纯字面量）。"""
    if t not in EN:
        return False
    if has_kotlin_tpl(t):
        return convert_template(t) is not None
    return True


_DOLLAR_RE = re.compile(r'\$\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}|\$[A-Za-z_][A-Za-z0-9_]*')
# 已是 Android 定位占位符的片段：绝不能当成 Kotlin 模板变量再转一次，
# 否则 `%1$s` 里的 `$s` 会被识别为变量 → 二次包装成 `%1%1$s`（真机显示乱码）。
_POSITIONAL_RE = re.compile(r'%\d+\$[sdf]')
_SENTINEL = '\u0001'


def has_kotlin_tpl(s):
    """是否含**真正需要转换**的 Kotlin 模板（排除已有的 %N$s 占位符）。"""
    return re.search(r'\$\{|\$[A-Za-z_]', _POSITIONAL_RE.sub(_SENTINEL, s)) is not None


def dollar_to_positional(s):
    """把译文里的 Kotlin 模板 $var / ${expr} 顺序换成 %1$s / %2$s …

    为什么必须做：源码替换（do_replace/pick_expr）是按**中文原文**的模板顺序生成实参的，
    资源串若保留 `$var`，运行时 getString(id, args) 不会替换它，界面上就会**字面显示**
    `${app.capabilities.size}` 这种占位符——这正是「界面乱的」的主因之一。
    中英模板的占位符顺序一一对应，故按出现次序编号即可对齐。
    """
    # 先把已有的 %N$s 藏起来，避免其中的 `$s` 被当成变量
    s = _POSITIONAL_RE.sub(_SENTINEL, s)
    n = [0]

    def rep(_m):
        n[0] += 1
        return '%%%d$s' % n[0]

    return _DOLLAR_RE.sub(rep, s).replace(_SENTINEL, '%s')


# 「合法格式规格」白名单：只承认本流水线真正会产出的两种 ——
#   %%       字面百分号
#   %[N$]s   字符串占位（N 为参数序号）
# 其余一律视为「裸 %」并转义。**不能**把 flags/width 写得宽松：那样
# `100% same` 里的 `% s` 会被判成「空格 flag + 转换符 s」而漏网，
# 运行期 String.format 照样 failMismatch 崩溃。
_FORMAT_SPEC_RE = re.compile(r'%(?:\d+\$)?s')


def escape_stray_percent(s):
    """把「不属于格式规格」的裸 % 转义为 %%。

    为什么必须做：`Resources.getString(id, *args)` 无条件执行 String.format，
    与外层 xml 的 formatted="false" 无关。中文原文 `100% 同源（共 %1$s 个）` 里的
    `% ` 会被 Java 当成「空格 flag + 转换符 s」→ FormatFlagsConversionMismatchException
    崩溃（QuroMcpSettingsScreen 的 MCP 页真机崩溃即此）。
    只对「源码会带参调用」的条目调用本函数（否则 %% 会原样显示成两个百分号）。
    """
    out = []
    i = 0
    n = len(s)
    while i < n:
        if s[i] != '%':
            out.append(s[i]); i += 1; continue
        if s[i:i + 2] == '%%':
            out.append('%%'); i += 2; continue
        m = _FORMAT_SPEC_RE.match(s, i)
        if m is None:
            out.append('%%'); i += 1; continue
        out.append(m.group(0)); i = m.end()
    return ''.join(out)


def resolve_value(t, lang):
    """返回某语言下该串的资源文本。zh=中文；en=英文；其他=本语言翻译，缺则英文兜底。"""
    if lang == 'zh':
        if has_kotlin_tpl(t):
            ct = convert_template(t)
            v = ct[0] if ct else t
        else:
            v = t
    else:
        if lang == 'en':
            v = EN.get(t, t)
        else:
            v = LANG_TRANS.get(lang, {}).get(t) or EN.get(t, t)  # 英文兜底（绝不放中文）
        # 显式中文目录(values-zh)走 zh 分支；其余语言一律把残留模板转成定位占位符
        if has_kotlin_tpl(v):
            v = dollar_to_positional(v)
    # 源码一定会带参调用（中文原文含 Kotlin 模板）→ 裸 % 必须转义成 %%，否则
    # String.format 会把 `% ` / `%下` 当成非法规格直接抛异常崩溃。
    # 注意：这一步必须在所有分支（含 zh）之后统一执行，zh 提前 return 曾漏掉此转义。
    if has_kotlin_tpl(t):
        v = escape_stray_percent(v)
    return v


# ---------- xml 转义 ----------
def xml_escape(s):
    s = s.replace('\\', '\\\\')          # 反斜杠先整体转义，下面再还原合法转义
    # aapt2(8.13) 对 ASCII 撇号 ' 一律报 "unescaped apostrophe"，即使 formatted=false 也不豁免；
    # 但花体撇号 ’(U+2019) 不在其检查范围，且对格式串/非格式串都安全。
    s = s.replace("'", "\u2019")
    s = s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
    # 还原 aapt 合法转义：\n \t \\ \' \" \uXXXX
    s = re.sub(r'\\\\n', '\\n', s)
    s = re.sub(r'\\\\t', '\\t', s)
    s = re.sub(r'\\\\\\', '\\\\', s)     # 已经是 \\
    s = re.sub(r'\\\\\'', '\\\'', s)
    s = re.sub(r'\\\\"', '\\"', s)
    s = re.sub(r'\\\\u([0-9a-fA-F]{4})', lambda m: '\\u' + m.group(1), s)
    return s


# ---------- 1) 生成每个语言的 xml（完整：英文兜底下不会有中文泄漏） ----------
counts = {}
for lang, d in LANG_DIRS.items():
    lines = []
    for idx, e in enumerate(data):
        t = e['text']
        if t not in EN:
            # 不在英文范围内 = 未进入 i18n（代码保留字面量），不生成资源
            continue
        k = 'qk_%05d' % idx
        val = resolve_value(t, lang)
        # 是否为「定位格式串」(%1$s 等)，只有这种才允许 aapt 当 format 解析；其余一律 formatted=false
        is_positional = bool(re.search(r'%\d+\$s', val)) and not re.search(r'%(?!\d+\$s)', val)
        attr = '' if is_positional else ' formatted="false"'
        lines.append('    <string name="%s"%s>%s</string>' % (k, attr, xml_escape(val)))
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, 'strings_i18n.xml'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + '\n'.join(lines) + '\n</resources>\n')
    counts[lang] = len(lines)
    # 镜像到显式中文目录
    if lang == 'zh':
        os.makedirs(ZH_MIRROR, exist_ok=True)
        with open(os.path.join(ZH_MIRROR, 'strings_i18n.xml'), 'w', encoding='utf-8', newline='\n') as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + '\n'.join(lines) + '\n</resources>\n')
        counts['zh(zh-mirror)'] = len(lines)

scope_n = sum(1 for e in data if e['text'] in EN)
print('i18n 范围(英文已覆盖): %d / %d 条' % (scope_n, len(data)))
print('各语言生成条目数:', counts)

# ---------- 2) 安全替换源码字面量（仅 i18n 范围内的串） ----------
# 「逻辑/关键词表」文件：这些文件里的中文字面量是**比较/匹配/分派的键**，不是给人看的
# 文案。一旦被翻译，非中文语言下 contains/== 永不命中 —— 工具失败判定、交付判定、画布类型
# 路由、意图识别、工具能力匹配、云 TTS 标签值会全部静默失效（不报错，只是"没反应"）。
# 这类串必须整文件跳过 i18n（它们是数据，不是界面文案）。
LOGIC_SKIP_FILES = {
    'Verifier.kt',                # 工具输出失败标记
    'DeliverabilityJudge.kt',     # 交付判定标记
    'CanvasRouter.kt',            # 画布类型路由词
    'QuroExperienceEngine.kt',    # 经验引擎意图关键词
    'IntentRouter.kt',            # 搜索时效性关键词
    'FluidCloudBridge.kt',        # 流程图节点标签数据
    'QuroCloudTtsCatalog.kt',     # 云 TTS 标签值（直接进合成请求）
    'ToolCapabilityDirectory.kt', # matchToolsByIntent 的子串匹配用例
}

VIEW_MODEL_FILES = {
    'QuroChatViewModel.kt', 'QuroPersonaViewModel.kt', 'QuroModelConfigViewModel.kt',
    'ChatData.kt',
}

# 非 Composable 作用域内可用的 Context 表达式（用于 ctx.getString 兜底）。逐文件探测。
def detect_ctxvar(src):
    if re.search(r'\bval\s+context\s*=', src):
        return 'context'
    if re.search(r'\bval\s+ctx\s*=', src):
        return 'ctx'
    if re.search(r'\bval\s+context\b', src):
        return 'context'
    if 'requireContext()' in src:
        return 'requireContext()'
    if re.search(r'class\s+\w*[Aa]ctivity', src):
        return 'context'
    return None

CTXVAR = None

REPLACED_TOTAL = 0
FILES_TOUCHED = 0


# 显示用途的参数名白名单（这些参数赋值的字符串字面量几乎都是界面文案，且均为 Composable 参数）。
# 注意：刻意排除 name/message/description/body 等过泛的字段名——它们常被用作普通类属性（非 Composable
# 上下文），注入 stringResource（@Composable）会触发 "Composable invocations can only happen from
# the context of a @Composable function" 编译错误。
DISPLAY_PARAMS = (r'text|label|title|placeholder|summary|caption|headline|supporting|'
                  r'overline|sub|subhead|subtitle|contentDescription|headlineContent|'
                  r'supportingText|overlineText|secondaryText|hint|description')


# 注：上下文分析（@Composable 区间 / 非 Composable lambda 区间 / 注释判定）统一由
# i18n_ctx 提供（字符串/注释感知的括号匹配），避免函数体内含括号的字符串导致区间溢出。
# ---------- 位置映射：替换进行中的 new 位置 ←→ 原始 SRC 位置 ----------
class _PosMap:
    """维护「替换后的文本位置」到「原始文本位置」的映射。

    为什么必须有它：@Composable 判定依赖原始源码（SRC）上预计算的函数/作用域区间；
    而替换是逐条累积的 —— 每把一处中文换成 stringResource(...) / qstr(...) 都会让后续
    文本整体后移。之前直接把「替换中的位置」丢给基于 SRC 的判定器，偏移累积到数百字符后
    位置完全错位，判定结果随机化，于是出现大面积的
    "Composable invocations can only happen from the context of a @Composable function"。
    """

    def __init__(self):
        self.marks = []          # [(pos, delta)]，delta = 该处替换造成的长度变化
        self._sorted = False
        self._keys = []
        self._pref = []

    def reset(self):
        self.marks = []
        self._sorted = False
        self._keys = []
        self._pref = []

    def add(self, new_pos, old_len, new_len):
        d = new_len - old_len
        if d == 0:
            return
        self.marks.append((new_pos, d))
        self._sorted = False

    def _build(self):
        # 关键：不同 re.sub 模式命中的位置并不单调（模式 A 可能命中 2000、模式 B 命中 1000），
        # 所以必须排序后用前缀和 —— 早期版本假定 add() 的位置单调递增并据此二分，
        # 一旦乱序就会把位置映射错几百字符，导致 @Composable 判定整体随机化。
        self.marks.sort(key=lambda x: x[0])
        self._keys = [m[0] for m in self.marks]
        acc = 0
        pref = []
        for _, d in self.marks:
            acc += d
            pref.append(acc)
        self._pref = pref
        self._sorted = True

    def to_src(self, pos):
        if not self.marks:
            return pos
        if not self._sorted:
            self._build()
        # 二分：最后一个 keys <= pos 的下标
        lo, hi = 0, len(self._keys)
        while lo < hi:
            mid = (lo + hi) // 2
            if self._keys[mid] <= pos:
                lo = mid + 1
            else:
                hi = mid
        d = self._pref[lo - 1] if lo > 0 else 0
        return pos - d


PMAP = _PosMap()


def pick_expr(k, arg_str, pos):
    """根据当前位置是否为 Composable 上下文，选择 stringResource 或降级为 {ctx}.getString。
    - Composable 作用域 → stringResource(R.string.x)（可随语言切换重组）
    - 非 Composable 作用域 → 用「该位置词法可见的 Context 变量」.getString(R.string.x)
      （由 i18n_ctx.ctxvar_at 按外层函数链查找 val ctx = LocalContext.current / 形参 ctx: Context）
    - 两者都不可用 → None（保持原样，避免编译失败）"""
    _sp = PMAP.to_src(pos)
    _c = IS_COMPOSABLE(_sp)
    if _DBG:
        print('  [pick] k=%s new_pos=%d src_pos=%d comp=%s | %r'
              % (k, pos, _sp, _c, SRC[max(0, _sp - 40):_sp + 20]), file=sys.stderr)
    if _c:
        return 'stringResource(R.string.%s%s)' % (k, arg_str)
    # 非 @Composable：统一走全局 qstr()（QuroI18nRt 持有 ApplicationContext）。
    # 不再推断「此处是否可见 Context 变量」—— 那种推断在真实工程里误判率极高
    # （ctx / getString 未解析、context(...) 被当函数调用等），代价远高于收益。
    return 'qstr(R.string.%s%s)' % (k, arg_str)


# ---------- Kotlin 三引号原始字符串保护 ----------
_RAW_CACHE = {}


def _compute_lit_ranges(s):
    """扫描所有 Kotlin 原始字符串（三引号 ... 三引号）的 [start, end) 区间。
    这些区间内的内容（Regex / SQL / 多行提示词）绝不能做字面量替换：一旦把
    ctx.getString(...) / stringResource(...) 插进去，就会生成语法崩坏的代码，
    导致函数体不闭合、后续顶层声明被吞进函数体，连锁产生数百条编译错误。"""
    n = len(s)
    out = []
    i = 0
    while i < n:
        c = s[i]
        if c == '/' and s[i + 1:i + 2] == '/':
            k = s.find('\n', i)
            i = n if k < 0 else k + 1
            continue
        if c == '/' and s[i + 1:i + 2] == '*':
            k = s.find('*/', i + 2)
            i = n if k < 0 else k + 2
            continue
        if c == '"':
            if s[i:i + 3] == '"' * 3:
                k = s.find('"' * 3, i + 3)
                k = n if k < 0 else k + 3
                out.append((i, k))
                i = k
                continue
            j = _scan_string(s, i, n)
            out.append((i, j))
            i = j
            continue
        if c == "'":
            i = _scan_char(s, i, n)
            continue
        i += 1
    return out


def lit_ranges(s):
    h = hash(s)
    v = _RAW_CACHE.get(h)
    if v is None:
        v = _compute_lit_ranges(s)
        if len(_RAW_CACHE) > 128:
            _RAW_CACHE.clear()
        _RAW_CACHE[h] = v
    return v


def in_literal_body(s, pos):
    # pos 是否落在某个字符串字面量的【内容】里（严格内部，不含起止引号）。
    # 这是防止跨串误伤的关键护栏：源码里 Text("${a.ifBlank { "-" }} · ${b.size} 个共享服务")
    # 这种嵌套串，若抽取阶段产出一条畸形条目，兜底模式会在 "-" 的【闭引号】处起匹配，
    # 把跨串的一大段代码整段换成 qstr(...)，生成语法崩坏的代码（函数体不闭合、
    # 后续顶层声明被吞进函数体），连锁产生数百条编译错误。
    # 判定「匹配起点落在别的字面量内部」即可准确拦下这类误伤。
    for a, b in lit_ranges(s):
        if a < pos:
            if pos < b - 1:
                return True
        else:
            break
    return False


def _line_head(new, pos):
    ls = new.rfind('\n', 0, pos) + 1
    return new[ls:pos]


def _on_const_val(new, pos):
    """pos 所在行是否为 `const val X = ...`。编译期常量初始化器不接受
    stringResource/qstr（"Const 'val' initializer must be a constant value"），必须跳过。"""
    # 注意要匹配 'private const val' / 'internal const val' 等前缀写法。
    return re.search(r'const\s+val', _line_head(new, pos)) is not None


def do_replace(new, t, k):
    """对单个已翻译串 t，在 new 文本中替换各种显示文案槽位。返回 (new, count)。
    所有模式都以「成对引号 + 完整字面量」锚定，绝不会把 t 作为子串误伤其它长串；
    命中行若为注释、或位于非 Composable lambda 作用域内，则跳过（或降级为 ctx.getString）。"""
    has_dollar = '$' in t
    if has_dollar:
        ct = convert_template(t)
        if not ct:
            return new, 0
        res_text, args = ct
        # 模板参数统一经 (arg).toString() 转成「非空 String」再传入 formatArgs：
        #  - 可空参数（如 it.message / loadErr 委托属性）不再触发 "Smart cast to 'Any' is impossible"
        #    或 "actual type is 'String?', but 'Any' was expected" 编译错；
        #  - 任意类型（Int/Boolean/表达式）都可安全 .toString()，%s 显示结果与原来 ${} 完全一致。
        arg_str = ', ' + ', '.join('(%s).toString()' % a for a in args) if args else ''
    else:
        res_text, args = t, []
        arg_str = ''
    te = re.escape(t)
    if t not in new:
        return new, 0          # 快速早退：省掉后续十来次全区扫描
    c = [0]

    def guarded(repl):
        def f(m):
            if _is_comment(new, m.start()):
                return m.group(0)
            if in_literal_body(new, m.start()) or _on_const_val(new, m.start()):
                return m.group(0)
            expr = pick_expr(k, arg_str, m.start())
            if expr is None:
                return m.group(0)
            c[0] += 1
            _r = repl(m, expr)
            PMAP.add(m.start(), len(m.group(0)), len(_r))
            return _r
        return f

    # 1) Text(...) 直接字面量 / 带逗号 / text= 形式（Text 本身是 Composable；若处于非 Composable lambda 内则降级 getString）
    new = re.sub(r'Text\(\s*([\'"])' + te + r'\1\s*\)', guarded(lambda m, e: 'Text(' + e + ')'), new)
    new = re.sub(r'Text\(\s*([\'"])' + te + r'\1\s*,', guarded(lambda m, e: 'Text(' + e + ','), new)
    new = re.sub(r'Text\(\s*text\s*=\s*([\'"])' + te + r'\1\s*\)', guarded(lambda m, e: 'Text(text = ' + e + ')'), new)
    new = re.sub(r'Text\(\s*text\s*=\s*([\'"])' + te + r'\1\s*,', guarded(lambda m, e: 'Text(text = ' + e + ','), new)
    # 1b) Text(modifier, "x") 之类：首个参数之后、逗号后的字面量（仍在 Text 调用内）
    new = re.sub(r'(\bText\s*\([^()]*?,\s*)([\'"])' + te + r'\2', guarded(lambda m, e: m.group(1) + e), new)

    # 2) 显示参数 name=/title=/label=/sub=/caption=/summary=/placeholder=/hint=/contentDescription= 等。
    PARAM_RE = re.compile(r'\b(name|sub|title|label|caption|summary|placeholder|hint|subtitle|headline|contentDescription|supporting|subhead|overline)\s*=\s*([\'"])' + te + r'\2')
    new = PARAM_RE.sub(guarded(lambda m, e: m.group(1) + ' = ' + e), new)

    # 3) Toast.makeText(ctx, "msg", dur) -> ctx.getString(R.string.x)（getString 非 Composable，lambda 内也安全）。
    def guarded_toast(repl):
        def f(m):
            if _is_comment(new, m.start()):
                return m.group(0)
            if in_literal_body(new, m.start()) or _on_const_val(new, m.start()):
                return m.group(0)
            c[0] += 1
            return repl(m)
        return f
    TOAST_RE = re.compile(r'(Toast\s*\.\s*makeText\s*\(\s*([^,]+?)\s*,\s*)([\'"])' + te + r'\3\s*,')
    def _toast_repl(m):
        # Toast.makeText 的第一个参数本身就是 Context，直接用它做 receiver（Activity/Service 场景
        # 可能是 this / appCtx / context / ctx …）。绝不能替换成别的变量名，否则会 Unresolved reference。
        gsr = 'qstr(R.string.%s%s)' % (k, arg_str)
        _r = m.group(1) + gsr + ','
        PMAP.add(m.start(), len(m.group(0)), len(_r))
        return _r
    new = TOAST_RE.sub(guarded_toast(_toast_repl), new)

    # 4) 已知 UI 组合项首参字符串（均为 Composable，注入 stringResource 安全）：
    #    LucideIcon("icon", "cd", ...) 的 2nd 位置、Icon(..., "cd", ...) 的 2nd 位置、
    #    GroupCaption("x") / SectionTitle("x") / InfoBox("x") / SetGroup("x") / KaleidoTagChip("x", ...) 的首参。
    new = re.sub(r'(LucideIcon\s*\([^,]*?,\s*)([\'"])' + te + r'\2', guarded(lambda m, e: m.group(1) + e), new)
    new = re.sub(r'(\bIcon\s*\([^()]*?,\s*)([\'"])' + te + r'\2', guarded(lambda m, e: m.group(1) + e), new)
    new = re.sub(r'\b(GroupCaption|SectionTitle|InfoBox|SetGroup|KaleidoTagChip)\s*\(\s*([\'"])' + te + r'\2', guarded(lambda m, e: m.group(1) + '(' + e), new)

    # 5) when 分支字符串返回： `-> "x"` / `-> "x",` / `-> "x"\n`（Composable 上下文用 stringResource，否则 getString）
    new = re.sub(r'(->\s*)([\'"])' + te + r'\2(?=\s*[,\n])', guarded(lambda m, e: m.group(1) + e), new)

    # 6) val/var 直接赋值中文串： `val x = "中文"` / `var x = "中文"`（局部 String 变量；上下文决定 stringResource/getString）
    new = re.sub(r'(\b(?:val|var)\s+[\w.]+\s*[:=]\s*)([\'"])' + te + r'\2(?!\s*\+)', guarded(lambda m, e: m.group(1) + e), new)

    # 7) 字符串拼接： `"中文" + EXPR` 或 `EXPR + "中文"`（中文串作为可拼接片段，上下文决定 stringResource/getString）
    new = re.sub(r'([\'"])' + te + r'\1\s*\+', guarded(lambda m, e: e + ' +'), new)
    new = re.sub(r'\+\s*([\'"])' + te + r'\1', guarded(lambda m, e: '+ ' + e), new)

    # 8) JSONObject / Bundle 等 put 的「值」位置： `.put("key", "中文值")` / `.putString("key", "中文值")`
    #    第一个参数是键（必须保留），第二个参数是值（需翻译）。仅当值位置命中时替换。
    new = re.sub(r'(\.(?:put|putString|putInt)\s*\(\s*[\'"]<KEY>[\'"]\s*,\s*)([\'"])' + te + r'\2'
                 .replace('<KEY>', r'[^"\'\n]+'), guarded(lambda m, e: m.group(1) + e), new)

    # 9) 非 Composable 标准库扩展 lambda 体： `x.ifBlank { "中文" }` / `.getOrElse { e -> "中文" }`
    #    ifBlank/getOrElse 等 lambda 不是 Composable（已由 NC_TRIGGER 标记），必须降级为 ctx.getString。
    new = re.sub(r'\b(ifBlank|ifEmpty|getOrElse|getOrNull|onSuccess|onFailure|onComplete)\s*\{\s*((?:[\w]+\s*->\s*)?)([\'"])' + te + r'\3',
                 guarded(lambda m, e: m.group(1) + ' { ' + m.group(2) + e), new)

    # 10) 兜底：任何「完整中文串字面量」（前面精确模式都没命中时）。
    #     排除明显是「键」的位置：紧邻前面是 put(/getString(/containsKey( 等第一个参数，或后面接 `to`（Map 键）。
    #     只处理显示用途；上下文决定 stringResource / getString，否则保持原样。
    KEYCALL = re.compile(r'\.\s*(?:put|putString|putInt|putBoolean|putLong|getString|getInt|'
                         r'getBoolean|getLong|getFloat|optString|optInt|optBoolean|containsKey|'
                         r'contains|remove|has|get)\s*\(\s*$')

    def guarded_generic(repl):
        def f(m):
            if _is_comment(new, m.start()):
                return m.group(0)
            if in_literal_body(new, m.start()) or _on_const_val(new, m.start()):
                return m.group(0)
            pre = new[max(0, m.start() - 80):m.start()]
            if KEYCALL.search(pre):
                return m.group(0)
            post = new[m.end():m.end() + 8]
            if re.match(r'\s*(?:to)\b', post):   # Map 键 `"k" to v`：跳过
                return m.group(0)
            expr = pick_expr(k, arg_str, m.start())
            if expr is None:
                return m.group(0)
            c[0] += 1
            _r = repl(m, expr)
            PMAP.add(m.start(), len(m.group(0)), len(_r))
            return _r
        return f
    new = re.sub(r'([\'"])' + te + r'\1', guarded_generic(lambda m, e: e), new)
    return new, c[0]


def add_imports(src):
    need_sr = 'stringResource(' in src and 'import androidx.compose.ui.res.stringResource' not in src
    need_r = 'R.string.' in src and 'import com.ai.assistance.quro.R' not in src
    need_q = 'qstr(' in src and 'import com.ai.assistance.quro.util.qstr' not in src \
             and 'package com.ai.assistance.quro.util' not in src
    pre = ''
    if need_sr:
        pre += 'import androidx.compose.ui.res.stringResource\n'
    if need_r:
        pre += 'import com.ai.assistance.quro.R\n'
    if need_q:
        pre += 'import com.ai.assistance.quro.util.qstr\n'
    if pre:
        lines = src.splitlines()
        # 注意：部分文件首行带 UTF-8 BOM（\ufeff），startswith('package ') 会失配，
        # 导致 import 被插到文件最前、package 行被挤到第 3 行 —— 直接语法崩坏
        # （"imports are only allowed in the beginning of file"）。
        pkg_idx = next((i for i, l in enumerate(lines) if l.lstrip('\ufeff').startswith('package ')), -1)
        if pkg_idx >= 0:
            lines[pkg_idx] = lines[pkg_idx].lstrip('\ufeff')
            insert_at = pkg_idx + 1
        else:
            insert_at = 0
        lines.insert(insert_at, pre.strip())
        src = '\n'.join(lines)
    return src


_ONLY = os.environ.get('I18N_ONLY', '')
_DBG = os.environ.get('I18N_DEBUG', '')

for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if not fn.endswith('.kt'):
            continue
        if fn in VIEW_MODEL_FILES or fn in LOGIC_SKIP_FILES:
            continue
        p = os.path.join(dp, fn)
        if _ONLY and _ONLY not in p.replace('\\', '/'):
            continue
        src = open(p, encoding='utf-8').read()
        PMAP.reset()
        new = src
        IS_COMPOSABLE = make_composable_checker(src)
        CTXVAR = detect_ctxvar(src)
        SRC = src
        for t, k in key_of.items():
            if not template_ok(t):
                continue
            if t not in new:
                continue
            new, n = do_replace(new, t, k)
            REPLACED_TOTAL += n
        if new != src:
            new = add_imports(new)
            open(p, 'w', encoding='utf-8').write(new)
            FILES_TOUCHED += 1

print('替换完成：%d 个文件被修改，%d 处字面量改为 stringResource' % (FILES_TOUCHED, REPLACED_TOTAL))

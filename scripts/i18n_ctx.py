# -*- coding: utf-8 -*-
"""字符串/注释感知的括号匹配与 @Composable 上下文分析。

i18n_build / i18n_patterns2 之前的括号匹配没有跳过字符串字面量与注释，
导致函数体内若含有 '{' / '}' 的字符串（JSON、模板、正则等）时括号深度计数错位，
函数体区间溢出到相邻的非 @Composable 函数，进而把 stringResource 错误注入非 Composable 上下文。

本模块提供「跳过字符串/注释/字符字面量」的括号匹配，并据此给出可靠的：
  - composable_ranges(src)        所有 @Composable 函数体区间
  - noncomposable_zones(src)       已知的非 Composable lambda 区间
  - is_composable(src, pos)         位置 pos 是否处于 Composable 上下文
"""
import re

# ---------- 跳过注释 / 字符串 / 字符字面量 ----------
def _scan_string(src, i, n):
    """i 指向普通字符串的起始 '"'；返回闭引号之后的下标。
    正确处理 Kotlin 字符串模板：$name 属于字符串；${...} 里的花括号/嵌套字符串按平衡跳过，
    这样 '"${if (x) "a" else "b"}"' 不会被内层引号提前截断（这正是此前函数区间溢出的根因）。"""
    if src[i:i + 3] == '"""':
        # Kotlin 三引号原始字符串：内部不做反斜杠转义，遇下一个三引号才结束。
        # 必须整体跳过 —— 否则里面的 '{' / '}'（正则、SQL、多行文本）会让括号深度错位，
        # 使函数体区间溢出到相邻的非 @Composable 函数（连锁产生上百条编译错误）。
        k = src.find('"""', i + 3)
        return n if k < 0 else k + 3
    j = i + 1
    while j < n:
        c = src[j]
        if c == '\\':
            j += 2
            continue
        if c == '"':
            return j + 1
        if c == '$' and j + 1 < n and src[j + 1] == '{':
            j = _scan_template(src, j + 1, n)
            continue
        j += 1
    return n


def _scan_template(src, i, n):
    """i 指向 ${...} 的 '{'；返回与之匹配的 '}' 之后的下标（字符串/注释/外层字符感知）。"""
    depth = 0
    j = i
    while j < n:
        c = src[j]
        if c == '{':
            depth += 1
            j += 1
        elif c == '}':
            depth -= 1
            j += 1
            if depth == 0:
                return j
        elif c == '"':
            if src[j:j + 3] == '"""':
                k = src.find('"""', j + 3)
                j = n if k < 0 else k + 3
            else:
                j = _scan_string(src, j, n)
        elif c == "'":
            j = _scan_char(src, j, n)
        elif c == '/' and j + 1 < n and src[j + 1] == '/':
            k = src.find('\n', j)
            j = n if k < 0 else k + 1
        elif c == '/' and j + 1 < n and src[j + 1] == '*':
            k = src.find('*/', j + 2)
            j = n if k < 0 else k + 2
        else:
            j += 1
    return n


def _scan_char(src, i, n):
    """i 指向字符字面量的起始 "'"；返回闭引号之后的下标。"""
    j = i + 1
    while j < n:
        if src[j] == '\\':
            j += 2
            continue
        if src[j] == "'":
            return j + 1
        j += 1
    return n


def _scan(src, i, n):
    """从 i 起跳过分隔符（注释、字符串、字符字面量），返回下一个有意义字符的位置。"""
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n:
            if src[i + 1] == '/':
                j = src.find('\n', i)
                i = n if j < 0 else j + 1
                continue
            if src[i + 1] == '*':
                j = src.find('*/', i + 2)
                i = n if j < 0 else j + 2
                continue
        if c == '"':
            if src[i:i + 3] == '"""':
                j = src.find('"""', i + 3)
                i = n if j < 0 else j + 3
                continue
            i = _scan_string(src, i, n)
            continue
        if c == "'":
            i = _scan_char(src, i, n)
            continue
        break
    return i


def _match_brace_from(src, open_pos):
    """open_pos 指向一个 '{'；返回与之匹配的 '}' 位置（带字符串/注释跳过）。"""
    n = len(src)
    depth = 1
    j = open_pos + 1
    while j < n:
        j = _scan(src, j, n)
        if j >= n:
            break
        c = src[j]
        if c == '{':
            depth += 1
            j += 1
        elif c == '}':
            depth -= 1
            j += 1
            if depth == 0:
                return j - 1
        else:
            j += 1
    return -1


def _match_paren_from(src, open_pos):
    """open_pos 指向一个 '('；返回匹配 ')' 的位置（带字符串/注释跳过）。"""
    n = len(src)
    depth = 1
    j = open_pos + 1
    while j < n:
        j = _scan(src, j, n)
        if j >= n:
            break
        c = src[j]
        if c == '(':
            depth += 1
            j += 1
        elif c == ')':
            depth -= 1
            j += 1
            if depth == 0:
                return j - 1
        else:
            j += 1
    return -1


def _is_composable_fun_here(src, fun_start):
    """判断 fun_start 处的 fun 是否为 @Composable：向前查找，跳过注释后最近的 '@Composable'。"""
    pre = src[max(0, fun_start - 800):fun_start]
    pre_clean = re.sub(r'/\*.*?\*/', '', re.sub(r'//[^\n]*', '', pre, flags=re.S), flags=re.S)
    # 在清洗后的前缀中，找最后一个 '@Composable' 且其后到 fun 之间没有其它 'fun'
    idx = pre_clean.rfind('@Composable')
    if idx < 0:
        return False
    between = pre_clean[idx + len('@Composable'):]
    # 允许中间只有空白/注解/类型参数，不允许出现另一个 'fun'
    return 'fun' not in between


def fun_ranges(src):
    """返回所有 fun 函数体区间并标记是否 @Composable（字符串/注释/嵌套感知）。
    即使函数嵌套在另一个函数内部，也会各自独立标记——内层非 Composable 函数体内不可注入。"""
    ranges = []
    n = len(src)
    for m in re.finditer(r'\bfun\b', src):
        s = m.start()
        is_comp = _is_composable_fun_here(src, s)
        ob = src.find('(', s)
        if ob < 0 or ob - s > 300:
            continue
        cb = _match_paren_from(src, ob)
        if cb < 0:
            continue
        k = src.find('{', cb)
        if k < 0:
            continue  # 表达式体 fun = ... 跳过
        e = _match_brace_from(src, k)
        if e < 0:
            continue
        ranges.append((k, e, is_comp))
    return ranges


# 非 Composable lambda 触发：集合类 HOF、协程/副作用 lambda、回调 lambda、标准库「非 Composable」扩展。
# 注意：remember / rememberSaveable / derivedStateOf / produceState 的 lambda 是
# @DisallowComposableCalls（calculation 参数），内部【不能】调用 stringResource ——
# 项目里大量 `remember { XxxModel(name = "...") }` 就栽在这里，必须算作非 Composable 作用域。
# 名字前允许「可选接收者」：带 '.' 的 .withContext(...) { 与无接收者的顶层 withContext(...) { 都覆盖。
NC_TRIGGER = re.compile(
    r'(?:\.|(?<![.\w]))'
    r'(?:map|forEach|filter|mapNotNull|let|apply|run|also|takeIf|takeUnless|fold|'
    r'forEachIndexed|firstOrNull|find|any|count|sumOf|reduce|flatMap|sortedBy|groupBy|'
    r'toSet|toList|zip|windowed|runCatching|onEach|distinctBy|filterNotNull|'
    r'associate|partition|chunked|take|drop|reversed|shuffled|sorted|'
    r'withContext|launch|async|coroutineScope|snapshotFlow|'
    r'LaunchedEffect|DisposableEffect|SideEffect|ComposeSideEffect|'
    r'remember|rememberSaveable|derivedStateOf|produceState|ifBlank|ifEmpty|getOrElse|getOrNull|onSuccess|onFailure)\s*(\([^()]*\))?\s*\{'
    r'|((?:factory|onRelease|onClick|onLongClick|onValueChange|onCheckedChange|onLongPress|'
    r'update|builder|onDismiss|onConfirm|onTap|onClickLabel|action|listener|'
    r'onClick|onHover|onFocusChange|onKeyEvent|transform|toast|snackbar|'
    r'setContent|contentDescription|onBack|onPreviewKeyEvent|keyInputFilter)\s*=\s*\{)'
)


def noncomposable_zones(src):
    """标记常见的非 Composable lambda 体（集合类 HOF、回调、协程/副作用 lambda）。"""
    zones = []
    n = len(src)
    for m in NC_TRIGGER.finditer(src):
        ob = m.end() - 1
        if src[ob] != '{':
            continue
        e = _match_brace_from(src, ob)
        if e < 0:
            continue
        zones.append((ob, e))
    return zones


def _is_comment(new, pos):
    ls = new.rfind('\n', 0, pos) + 1
    st = new[ls:pos].lstrip()
    return st.startswith('//') or st.startswith('*')


def make_composable_checker(src):
    """返回一个 is_composable(pos) 判定器。

    判定规则（字符串/注释/嵌套感知）：
      - 注释内：False
      - 取「包含 pos 的最内层作用域」：可能是函数体（@Composable 或非 @Composable）
        或非 Composable lambda。该最内层作用域为 @Composable 时返回 True，否则 False。
    这样嵌套在非 @Composable 函数里的 stringResource 注入会被正确拒绝。
    """
    FUNS = fun_ranges(src)
    NC = noncomposable_zones(src)
    scopes = [(a, b, comp) for (a, b, comp) in FUNS] + [(a, b, False) for (a, b) in NC]

    def is_composable(pos):
        if _is_comment(src, pos):
            return False
        innermost = None
        for (a, b, comp) in scopes:
            if a <= pos <= b:
                if innermost is None or a > innermost[0]:
                    innermost = (a, b, comp)
        return innermost is not None and innermost[2]

    return is_composable


# 「val xxx = LocalContext.current / ...applicationContext / this」形式的 Context 变量声明
_CTX_DECL = re.compile(
    r'\bval\s+([A-Za-z_]\w*)\s*(?::\s*Context\s*)?=\s*'
    r'(?:LocalContext\s*\.\s*current|[\w.]*applicationContext|this)')
# 「xxx: Context」形参 / 局部声明
_CTX_PARAM = re.compile(r'\b([A-Za-z_]\w*)\s*:\s*Context\b')


def ctxvar_at(src, pos):
    """判定位置 pos 处（通常是非 Composable 作用域）是否有可用的 Context 变量名可用。

    做法：取包含 pos 的所有函数作用域（由内层到外层），在每个作用域「起始→pos」的片段里
    找最后出现的 Context 变量声明（val x = LocalContext.current / val x: Context / 形参 x: Context）。
    因为 Kotlin 闭包可捕获外层局部变量，内层 lambda/局部函数可直接用外层的 ctx 变量。
    找不到返回 None（调用方应保持原样，避免 Unresolved reference）。
    """
    funs = fun_ranges(src)
    enc = sorted([(a, b) for (a, b, _c) in funs if a <= pos <= b], key=lambda t: t[0], reverse=True)
    for (a, _b) in enc:
        seg = src[a:pos]
        m = None
        for mm in _CTX_DECL.finditer(seg):
            m = mm
        if m:
            return m.group(1)
        for mm in _CTX_PARAM.finditer(seg):
            m = mm
        if m:
            return m.group(1)
    return None

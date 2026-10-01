# -*- coding: utf-8 -*-
r"""JNI 方法表 ↔ Kotlin `external fun` 一致性核对。

背景：native 侧 `JNINativeMethod` 数组里的 name/signature 与 Kotlin `external fun`
是**两处独立声明**，编译器不会交叉检查。名字写错 → `RegisterNatives` 返回非 0 →
运行期第一次调用才 `UnsatisfiedLinkError`；签名写错 → 同样在运行期炸。
「构建通过」完全不能证明二者一致，所以需要这个脚本。

解析上的三个坑（都踩过，别再"简化"掉）：
1. Kotlin 的 `external fun f(x: Long)` **可以省略返回类型**（= Unit = 'V'）。
   正则若强制要求 `: R` 就会把这些方法漏掉，误报成「native 表里的死代码」。
   —— 上一版脚本就这么误报了 nativeReleaseLlm / nativeReset / nativeCancel。
2. `List<Pair<String, String>>` 里的逗号是**泛型内部**的，不能按逗号裸切参数；
   而且 JNI 侧泛型被擦除，签名里只剩 `Ljava/util/List;`。
3. Kotlin 嵌套接口 `MNNLlmNative.GenerationCallback` 在 JNI 里写作
   `Lcom/ai/assistance/mnn/MNNLlmNative$GenerationCallback;` —— 包名无法从
   Kotlin 源码推断，外部类用 `$` 连接。所以对「非基础类型」不硬比字符串，
   而是先把期望值编译成**正则**（类类型部分放开为任意包 + `$` 形状），再整体比对。
   上一版对已构造好的描述符做转义再反转义，把正则本身也转义坏了，于是 4 个
   回调类参数全部误报。
4. **参数列表中间可以夹 KDoc 注释**（本仓 `LlamaNative.nativeCreateSession` 就是）。
   注释里出现一对 `` `(...)` `` 时，非贪婪的 `([\s\S]*?)` 会在那个 `)` 提前收尾，
   于是 params 被截断、返回类型被当成注释文本解析 → 误报签名不一致。
   所以解析前**必须先剥掉注释**（块注释与行注释都要）。

用法：
    python scripts/check_jni_binding.py <cpp文件> <kt文件> [更多kt文件...]
退出码 0 = 完全一致。
"""
import io
import re
import sys


# ── C++ 侧 ──────────────────────────────────────────────────────────────────
# 表项两种常见写法（第三条字段常跨行）：
#   {"name", "(sig)", reinterpret_cast<void*>(Java_..._name)},
#   {"name", "(sig)", (void*) &quro::llm::jni::name},
CPP_ENTRY = re.compile(r'\{\s*"([A-Za-z0-9_]+)"\s*,\s*"([^"]*)"\s*,\s*([^}]*?)\}\s*,', re.S)


# ── Kotlin 侧 ───────────────────────────────────────────────────────────────
# 返回类型整段可选：不写 = Unit = 'V'。
KT_FUN = re.compile(r'external\s+fun\s+([A-Za-z0-9_]+)\s*\(([\s\S]*?)\)\s*(?::\s*([^\n=]+))?')

KT_PRIM = {
    'String': 'Ljava/lang/String;',
    'Long': 'J',
    'Int': 'I',
    'Boolean': 'Z',
    'Float': 'F',
    'Double': 'D',
    'Byte': 'B',
    'Short': 'S',
    'Char': 'C',
    'ByteArray': '[B',
    'IntArray': '[I',
    'LongArray': '[J',
    'FloatArray': '[F',
    'DoubleArray': '[D',
    'Any': 'Ljava/lang/Object;',
}

PRIM_BY_RET = dict(KT_PRIM)
PRIM_BY_RET['Unit'] = 'V'
PRIM_BY_RET[''] = 'V'


def split_top_level(s):
    """按顶层逗号切分，忽略 <> 与 () 内部的逗号。"""
    out, depth, cur = [], 0, []
    for ch in s:
        if ch in '<(':
            depth += 1
        elif ch in '>)':
            depth -= 1
        if ch == ',' and depth == 0:
            out.append(''.join(cur))
            cur = []
        else:
            cur.append(ch)
    if ''.join(cur).strip():
        out.append(''.join(cur))
    return out


def kotlin_type_regex(t):
    """把 Kotlin 类型编译成 JNI 描述符的**正则**（返回片段，未转义外层结构）。"""
    t = t.strip().rstrip('?')
    if not t:
        return ''
    if t in KT_PRIM:
        return re.escape(KT_PRIM[t])
    outer = t.split('<')[0].strip()
    if outer in KT_PRIM:
        return re.escape(KT_PRIM[outer])
    if outer in ('List', 'MutableList', 'ArrayList'):
        return re.escape('Ljava/util/List;')
    if outer in ('Map', 'MutableMap', 'HashMap'):
        return re.escape('Ljava/util/Map;')
    if outer in ('Set', 'MutableSet'):
        return re.escape('Ljava/util/Set;')
    if outer == 'Array':
        inner = t[t.index('<') + 1:t.rindex('>')]
        return re.escape('[') + kotlin_type_regex(inner)
    base = re.escape(outer.split('.')[-1])
    # 任意包 + 任意层外部类（以 $ 连接），末段必须是这个简单类名。
    return r'L(?:[A-Za-z0-9_]+/)*[A-Za-z0-9_$]*[/$]?' + base + ';'


def kotlin_sig_regex(params, ret):
    parts = [re.escape('(')]
    for p in split_top_level(params):
        p = p.strip()
        if not p:
            continue
        t = (p.split(':', 1)[1] if ':' in p else p).strip()
        if t.startswith('vararg '):
            t = t[len('vararg '):]
            parts.append(re.escape('[') + kotlin_type_regex(t))
            continue
        if t.endswith('...'):
            parts.append(re.escape('[') + kotlin_type_regex(t[:-3]))
            continue
        parts.append(kotlin_type_regex(t))
    parts.append(re.escape(')'))
    r = (ret or '').strip()
    key = r.rstrip('?')
    if key in PRIM_BY_RET:
        parts.append(re.escape(PRIM_BY_RET[key]))
    else:
        parts.append(kotlin_type_regex(r))
    return ''.join(parts)


KT_BLOCK_COMMENT = re.compile(r'/\*[\s\S]*?\*/')
KT_LINE_COMMENT = re.compile(r'//[^\n]*')


def strip_comments(src):
    """必须先剥注释：参数列表里夹 KDoc 时，注释里的 `)` 会截断参数匹配。"""
    return KT_LINE_COMMENT.sub('', KT_BLOCK_COMMENT.sub('', src))


def parse_kotlin(path, kt):
    src = strip_comments(io.open(path, encoding='utf-8').read())
    for m in KT_FUN.finditer(src):
        name, params, ret = m.group(1), m.group(2), m.group(3) or ''
        kt[name] = kotlin_sig_regex(params, ret)


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2

    cpp, kts = argv[1], argv[2:]
    text = io.open(cpp, encoding='utf-8').read()

    native, bad_impl = {}, []
    for name, sig, impl in CPP_ENTRY.findall(text):
        native[name] = sig
        short = impl.strip().split('::')[-1].rstrip(')').strip()
        if short and short != name and not short.endswith('_' + name):
            bad_impl.append((name, short))

    kt = {}
    for k in kts:
        parse_kotlin(k, kt)

    print('native 方法表条目 = %d' % len(native))
    print('Kotlin external fun = %d' % len(kt))
    print('')

    only_kt = [n for n in kt if n not in native]
    only_cpp = [n for n in native if n not in kt]
    if only_kt:
        print('!! Kotlin 有、native 表里没有（调用必 UnsatisfiedLinkError）：')
        for n in only_kt:
            print('   %s' % n)
    if only_cpp:
        print('!! native 表里有、Kotlin 没声明（死代码）：')
        for n in only_cpp:
            print('   %s' % n)

    mismatch = []
    for n in kt:
        if n in native and re.fullmatch(kt[n], native[n]) is None:
            mismatch.append((n, kt[n], native[n]))

    if mismatch:
        print('')
        print('!! 签名不一致：')
        for n, e, a in mismatch:
            print('   %s' % n)
            print('      Kotlin 期望 %s' % e)
            print('      native 实际 %s' % a)

    if bad_impl:
        print('')
        print('!! 表项第三字段指的实现函数与条目名不匹配：')
        for n, s in bad_impl:
            print('   %-38s -> %s' % (n, s))

    print('')
    print('缺失声明 = %d，死代码 = %d，签名不一致 = %d，实现名不匹配 = %d'
          % (len(only_kt), len(only_cpp), len(mismatch), len(bad_impl)))
    ok = not (only_kt or only_cpp or mismatch or bad_impl)
    print('结论：%s' % ('一致' if ok else '需修'))
    return 0 if ok else 1


if __name__ == '__main__':
    raise SystemExit(main(sys.argv))

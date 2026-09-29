# -*- coding: utf-8 -*-
"""审计「会被 String.format 解析的字符串资源」里的畸形 %，防止
java.util.FormatFlagsConversionMismatchException / UnknownFormatConversionException 崩溃。

背景（真机崩溃）：
  `Resources.getString(id, *args)` **总是**执行 String.format —— 与 xml 里的
  formatted="false" 无关。所以任何「带参调用」的字符串里，只要出现**裸 %**
  （例如 "100% 同源"），Java 会把 `% ` 解析成「空格 flag + 转换符」，抛
  FormatFlagsConversionMismatchException 直接崩（QuroMcpSettingsScreen 的 MCP 页即此）。

本脚本：
  1) 从 Kotlin 源码找出**带参数**调用 stringResource/qstr/ctx.getString 的资源名；
  2) 在 values/ 与 values-*/ 的所有 xml 里取这些资源的值，按 Java Formatter 规则校验；
  3) 报出非法项（文件 + 资源名 + 值）。
"""
import io, os, re, sys, glob

ROOT = 'app/src/main/java/com/ai/assistance/quro'
RES = 'app/src/main/res'

# 带参调用：stringResource(R.string.X, ...) / qstr(R.string.X, ...) / ctx.getString(R.string.X, ...)
CALL_RE = re.compile(
    r'(?:stringResource|qstr|getString|\.getString)\s*\(\s*(?:com\.ai\.assistance\.quro\.)?R\.string\.(\w+)\s*,'
)

# 「合法格式规格」白名单（与 i18n_build.escape_stray_percent 同一口径）：
#   %%       字面百分号
#   %[N$]s   字符串占位
# 其余任何 %（含 `100% same` 的 `% s`、`%1$s% 下载` 的尾部裸 %）都会让
# Resources.getString(id, *args) 里的 String.format 抛异常 → 真机崩溃。
LEGAL_SPEC = re.compile(r'%%|%(?:\d+\$)?s')


def scan_used_with_args():
    names = {}
    for dp, _, fs in os.walk(ROOT):
        for fn in fs:
            if not fn.endswith('.kt'):
                continue
            p = os.path.join(dp, fn)
            for i, line in enumerate(io.open(p, encoding='utf-8').read().split('\n'), 1):
                if line.lstrip().startswith('//') or line.lstrip().startswith('*'):
                    continue
                for m in CALL_RE.finditer(line):
                    names.setdefault(m.group(1), (p, i))
    return names


def check_value(v):
    """返回错误描述列表（空 = 合法）。"""
    errs = []
    i = 0
    n = len(v)
    while i < n:
        if v[i] != '%':
            i += 1
            continue
        m = LEGAL_SPEC.match(v, i)
        if m:
            i = m.end()
            continue
        errs.append('裸 %% / 非法规格（%r）' % v[i:i + 8].replace('\n', '\\n'))
        i += 1
    return errs


STRAY_PCT = re.compile(r'(?<!%)%(?!%|\d+\$|[-#+ 0,(<]*\d*(?:\.\d+)?[bBhHsScCdoxXeEfgGaAn])')


def parse_xml(path):
    out = {}
    s = io.open(path, encoding='utf-8').read()
    for m in re.finditer(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', s, re.S):
        out[m.group(1)] = m.group(2)
    return out


def main():
    used = scan_used_with_args()
    print('带参调用的资源名：%d 个' % len(used))
    files = sorted(glob.glob(os.path.join(RES, 'values*', '*.xml')))
    bad = 0
    checked = 0
    for f in files:
        vals = parse_xml(f)
        for name in used:
            if name not in vals:
                continue
            checked += 1
            errs = check_value(vals[name])
            if errs:
                bad += 1
                src, ln = used[name]
                print('  ✗ %s :: %s' % (f.replace(os.sep, '/'), name))
                print('      值: %s' % vals[name][:150])
                print('      错: %s' % '; '.join(errs))
                print('      用: %s:%d' % (src.replace(os.sep, '/').split('quro/')[-1], ln))
    print('检查 %d 条（去重前），非法 %d 条' % (checked, bad))
    return bad


sys.exit(1 if main() else 0)

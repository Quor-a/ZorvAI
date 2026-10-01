# -*- coding: utf-8 -*-
"""
llm/core 符号完整性审计：头文件里声明了、但静态库里没有定义的成员函数。

为什么需要它：
  libquro_llm_core.a 是**静态库**，静态库不解析符号。头文件声明了 `instance()`
  而源文件忘记实现，编译期、归档期都不会报错 —— 只有等到某个 SHARED 目标
  （libLlamaWrapper.so / libMNNWrapper.so）第一次真正引用它，才在链接期
  以 `undefined symbol` 的形式爆炸。本次 ThermalGovernor::instance() 漏实现
  就是这么漏过去的：「core 单独编译通过」完全不能证明 core 是完整的。

用法：
  python scripts/audit_llm_core_symbols.py [.a 路径]
  .a 路径可选，默认在 llm/{llama,mnn}/.cxx/**/arm64-v8a/quro_llm_core/ 下自动找。

退出码：0 = 无缺失；1 = 有缺失（已列出）。
"""
import glob
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE_INC = os.path.join(ROOT, "llm", "core", "include", "quro")

NDK_BIN = r"D:\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin"
NM = os.path.join(NDK_BIN, "llvm-nm.exe")

# 一行式成员/自由函数声明：以 ; 结尾、含一对圆括号、无函数体。
# 只关心这些 —— 带头文件内联体的定义（含 `{`）天然不会以 `;` 收尾，自动排除。
DECL_RE = re.compile(
    r"^[ \t]*"
    r"(?:static[ \t]+|virtual[ \t]+|explicit[ \t]+|constexpr[ \t]+|inline[ \t]+|friend[ \t]+)*"
    r"(?:[A-Za-z_][\w:<>,\s]*?[ \t*&]+)?"      # 返回类型（可缺省：构造函数）
    r"(~?[A-Za-z_]\w*)[ \t]*"                   # 方法名
    r"\(([^()]*)\)"                             # 参数列表（不支持嵌套括号的默认值）
    r"[ \t]*(?:const[ \t]*)?(?:noexcept[ \t]*)?(?:override[ \t]*)?"
    r";[ \t]*$"
)

# class 上下文跟踪
CLASS_OPEN_RE = re.compile(r"^[ \t]*(?:class|struct)[ \t]+([A-Za-z_]\w*)\b")
CLASS_CLOSE_RE = re.compile(r"^[ \t]*\};[ \t]*$")

# 不产出独立符号的写法
SKIP_SUFFIX = ("= 0", "= default", "= delete")


def find_archive():
    pats = [
        os.path.join(ROOT, "llm", "*", ".cxx", "**", "arm64-v8a", "quro_llm_core", "libquro_llm_core.a"),
        os.path.join(ROOT, "llm", "**", "libquro_llm_core.a"),
    ]
    for p in pats:
        hits = glob.glob(p, recursive=True)
        if hits:
            return max(hits, key=os.path.getmtime)
    return None


def defined_symbols(archive):
    out = subprocess.run(
        [NM, "--defined-only", "--demangle", archive],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False,
    ).stdout.decode("utf-8", "replace")
    syms = set()
    for line in out.splitlines():
        parts = line.split(None, 2)
        if len(parts) == 3 and parts[1] in ("T", "W", "D", "B", "R", "V"):
            syms.add(parts[2].strip())
    return syms


def collect_decls():
    """返回 [(header, lineno, class_name, method_name)]"""
    found = []
    for path in sorted(glob.glob(os.path.join(CORE_INC, "*.h"))):
        cls = None
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for i, raw in enumerate(f, 1):
                line = raw.rstrip("\n").rstrip("\r")
                stripped = line.strip()
                if not stripped or stripped.startswith("//") or stripped.startswith("*"):
                    continue

                m = CLASS_OPEN_RE.match(line)
                if m and ";" not in line:
                    cls = m.group(1)
                    continue
                if CLASS_CLOSE_RE.match(line):
                    cls = None
                    continue

                if any(s in line for s in SKIP_SUFFIX):
                    continue
                d = DECL_RE.match(line)
                if not d:
                    continue
                name, args = d.group(1), d.group(2)
                if not cls and not name[0].islower():
                    # 无 class 上下文时只收自由函数（首字母小写的常规约定）
                    continue
                found.append((os.path.basename(path), i, cls, name))
    return found


def main():
    archive = sys.argv[1] if len(sys.argv) > 1 else find_archive()
    if not archive or not os.path.isfile(archive):
        print("找不到 libquro_llm_core.a；请先构建一次（:llama 或 :mnn）。")
        return 1
    print("审计目标：%s" % archive)

    syms = defined_symbols(archive)
    if not syms:
        print("符号表为空，异常退出。")
        return 1

    missing, checked = [], 0
    for header, lineno, cls, name in collect_decls():
        if cls is None:
            continue
        # 构造函数：ClassName::ClassName( ；其余：ClassName::Name(
        qualified = "%s::%s(" % (cls, cls if name == cls else name)
        checked += 1
        if not any(qualified in s for s in syms):
            missing.append((header, lineno, cls, name, qualified))

    print("已核对类内声明 %d 条，弹掉 %d 条（模板/内联/未使用不影响）。" % (checked, len(missing)))
    if not missing:
        print("✅ 无缺失实现。")
        return 0

    print("\n❌ 以下声明在静态库里没有对应定义 —— 一旦被引擎引用就会链接失败：")
    for header, lineno, cls, name, qualified in missing:
        print("   %s:%d  %s   （找不到符号片段 %s）" % (header, lineno, cls + "::" + name, qualified))
    return 1


if __name__ == "__main__":
    sys.exit(main())

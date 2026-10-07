# -*- coding: utf-8 -*-
"""修 CardFence 别名区：删掉旧 KNOWN_SNAKE 与重复的旧 map 声明，并去掉 mapOf 里的重复键。

背景：上一轮脚本只替换了别名表的**注释行**，旧声明留在原地，于是出现
两个 `private val COMPONENT_ALIASES`；旧表与新表还有几个键重了（Switch/TextBox/
Stepper/BarChart/Markdown/List），mapOf 里重键要么编译不过要么静默取最后一个，
必须清干净。
"""
import io
import os
import sys

P = "app/src/main/java/com/ai/assistance/quro/core/cards/CardFence.kt"


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    tmp = p + ".tmp_%d" % os.getpid()
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(s)
    os.replace(tmp, p)


def main():
    src = read(P)
    lines = src.split("\n")

    # 1) 删旧 KNOWN_SNAKE（含它上面的注释）
    start = next((i for i, l in enumerate(lines) if "private val KNOWN_SNAKE" in l), None)
    if start is None:
        raise SystemExit("找不到 KNOWN_SNAKE")
    end = next(i for i in range(start, len(lines)) if lines[i].strip() == ")")
    del lines[start - 1:end + 1]  # 含注释行

    # 2) 删旧 map 声明（第二个 COMPONENT_ALIASES，它紧跟在剩下的旧注释后）
    decl = next((i for i, l in enumerate(lines)
                 if "private val COMPONENT_ALIASES: Map<String, String> = mapOf(" in l), None)
    if decl is None:
        raise SystemExit("找不到旧 map 声明")
    cmt = decl - 1
    if lines[cmt].strip().startswith("/**"):
        del lines[cmt:decl + 1]

    src2 = "\n".join(lines)

    # 3) 去重：保留后出现的（新表），删掉先出现的（旧表）
    out_lines = src2.split("\n")
    keep = []
    seen = set()
    for l in out_lines:
        s = l.strip()
        if s.startswith('"') and ' to "' in s and s.endswith('",') or (s.startswith('"') and ' to "' in s and s.rstrip().endswith(',')):
            key = s.split(' to ')[0].strip('",')
            if key in seen:
                continue
            seen.add(key)
        keep.append(l)
    src3 = "\n".join(keep)

    write(P, src3)

    out = read(P)
    if out.count("private val COMPONENT_ALIASES") != 1:
        raise SystemExit("别名表声明应有且仅有一处，实际 %d" % out.count("private val COMPONENT_ALIASES"))
    if "KNOWN_SNAKE" in out:
        raise SystemExit("KNOWN_SNAKE 没删干净")
    if "private val COMPONENT_ALIASES" in out.replace(out.split("private val COMPONENT_ALIASES")[0], "", 1):
        pass
    entries = [l.strip() for l in out.split("\n") if l.strip().startswith('"') and ' to "' in l]
    keys = [e.split(" to ")[0].strip('",') for e in entries]
    if len(keys) != len(set(keys)):
        dup = sorted({k for k in keys if keys.count(k) > 1})
        raise SystemExit("仍有重复键：%s" % dup)
    print("[OK] 别名表清理完成，共 %d 条映射" % len(keys))


if __name__ == "__main__":
    sys.exit(main())

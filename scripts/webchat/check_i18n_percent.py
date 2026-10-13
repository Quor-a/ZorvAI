#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验所有 qk_ 文案的 printf 格式符：除 `%1$s` / `%2$d` / `%%` 之外的 `%` 都是裸 `%`。

本仓铁律：`qstr(id, *args)` 无条件走 `getString(id, *args)` 的 format，
字符串里出现裸 `%` 会在**真机直接崩**（且报错指向性极差）。所以每次动 i18n 都跑一遍。
"""
import glob
import io
import os
import re
import sys

OK = re.compile(r"%[0-9]*\$?[sd]|%%")


def main():
    bad, total = [], 0
    for p in sorted(glob.glob("app/src/main/res/values*/strings_i18n.xml")):
        s = io.open(p, encoding="utf-8").read()
        for m in re.finditer(
            r'<string name="(qk_[0-9]+)"[^>]*>(.*?)</string>', s, re.S
        ):
            name, val = m.group(1), m.group(2)
            total += 1
            stripped = OK.sub("", val)
            if "%" in stripped:
                bad.append(
                    (os.path.basename(os.path.dirname(p)), name, val[:80].replace("\n", " "))
                )
    print("扫描 %d 条 qk_ 文案" % total)
    print("裸 %% 违规: %d" % len(bad))
    for b in bad[:20]:
        print("   ", b)
    if bad:
        print(
            "\n注意：既有文案里有一批**字面量百分比**（如 '100%% open-source'、'100%% proactive use'），\n"
            "      集中在 qk_02477 / qk_03564 / qk_03724 / qk_03740。经核对这四条都是\n"
            "      **无参调用**（`qstr(id)` / `stringResource(id)`），不走 String.format，因此安全。\n"
            "      判断标准不是「这个文件里有没有裸 %%」，而是「这一条会不会被带参格式化」。\n"
            "      真正要拦住的是**新增**文案里的裸 %% —— 上面列出的条目里若出现新键，就是真问题。"
        )
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

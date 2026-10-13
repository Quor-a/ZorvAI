#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 qk_04073 与两处注释里残留的「Web UI 卡片」改成正确术语。

qk_04073 是 zx_web_card（zxwidget 单卡片对话框）的参数缺失提示，
跟第三个对话框 Web UI 没有关系，说成「Web UI 卡片」会把人带错。
全部原子写。
"""
import io
import os
import re
import sys
import glob

ROOT = r"D:/Calw OS-project/QuroAI"

# 每语言 qk_04073 里那个「Web UI 卡片」token 的原文 -> 替换
CARD_TOKEN = [
    ("Web UI 卡片", "zxwidget 卡片"),        # zh
    ("Web-UI-Kartenaktion", "zxwidget-Kartenaktion"),   # de
    ("بطاقة Web UI", "بطاقة zxwidget"),      # ar
    ("Web UI card action", "zxwidget card action"),     # en
    ("tarjeta Web UI", "tarjeta zxwidget"),  # es
    ("carte Web UI", "carte zxwidget"),      # fr
    ("Web UI कार्ड एक्शन", "zxwidget कार्ड एक्शन"),      # hi
    ("Web UI カードアクション", "zxwidget カードアクション"),  # ja
    ("Web UI 카드 액션", "zxwidget 카드 액션"),          # ko
    ("cartão Web UI", "cartão zxwidget"),    # pt
    ("карточки Web UI", "карточки zxwidget"),  # ru
]

COMMENT_FIX = [
    ("<!-- ZorvAI 第三个对话框：Web UI 卡片（zx 交互式卡片族） -->",
     "<!-- ZorvAI 第三个对话框：Web UI（ZorvAI 对话框的 Web 复制品） -->"),
    ("<!-- Web UI 卡片（第三个对话框）动作参数缺失提示 -->",
     "<!-- zxwidget 单卡片对话框：动作参数缺失提示 -->"),
]


def atomic_write(path, text):
    tmp = path + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(text)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def main():
    files = sorted(glob.glob(os.path.join(ROOT, "app/src/main/res/values*/strings_i18n.xml")))
    total = 0
    for path in files:
        with io.open(path, "r", encoding="utf-8", newline="") as f:
            src = f.read()
        out = src

        # 1) qk_04073 行内替换（只在该 string 行上做，避免误伤 qk_04072/04074）
        def fix_073(m):
            line = m.group(0)
            for a, b in CARD_TOKEN:
                if a in line:
                    return line.replace(a, b)
            return line

        out = re.sub(r'<string name="qk_04073".*?</string>', fix_073, out, flags=re.S)

        # 2) 注释
        for a, b in COMMENT_FIX:
            out = out.replace(a, b)

        if out != src:
            atomic_write(path, out)
            n = sum(1 for a, _ in CARD_TOKEN if a in src) + sum(1 for a, _ in COMMENT_FIX if a in src)
            print("FIXED %-52s  (%d 处)" % (os.path.relpath(path, ROOT).replace("\\", "/"), n))
            total += 1
        else:
            print("SKIP  %s" % os.path.relpath(path, ROOT).replace("\\", "/"))

    print("\n共修改 %d 个文件 / 扫描 %d 个" % (total, len(files)))
    return 0


if __name__ == "__main__":
    sys.exit(main())

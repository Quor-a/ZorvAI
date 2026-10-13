#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给 qk_04069（「本轮用 zxwidget 回答」的提示词）补一句交付形态。

为什么要补：模型此前不知道 zxwidget 长在哪里，措辞里又带着「画成可交互控件」这种
模糊说法，容易理解成「返回一段 JSON / 画张卡片」。用户 2026-10-13 拍板：
**zxwidget 不是卡片方式，是直接渲染在对话框的**。这句话必须进提示词，
否则模型很容易改用自己熟悉的 ui_card/ui_widget。

⚠ Android 字符串里的撇号必须写成 \\'（formatted="false" 不豁免）。
原子写。
"""
import io
import os
import re
import sys
import glob

ROOT = r"D:/Calw OS-project/QuroAI"

# 语言 → 追加在 qk_04069 末尾的那一句（撇号已转义）
ADD = {
    "values": "界面会**直接渲染在当前对话框里**（不是弹窗、不是卡片），一块只画一次。",
    "values-en": "It renders **directly inside the current dialog** (not a popup, not a card); draw each block once.",
    "values-de": "Die Oberfl\\u00e4che wird **direkt im aktuellen Dialog** gerendert (kein Popup, keine Karte); jedes Element nur einmal zeichnen.",
    "values-es": "La interfaz se renderiza **directamente en el di\\u00e1logo actual** (no es una ventana emergente ni una tarjeta); dibuja cada bloque una sola vez.",
    "values-fr": "L\\'interface s\\'affiche **directement dans le dialogue courant** (ni fen\\u00eatre modale, ni carte) ; ne dessine chaque bloc qu\\'une fois.",
    "values-pt": "A interface \\u00e9 renderizada **diretamente no di\\u00e1logo atual** (n\\u00e3o \\u00e9 popup nem cart\\u00e3o); desenhe cada bloco uma s\\u00f3 vez.",
    "values-ru": "\\u0418\\u043d\\u0442\\u0435\\u0440\\u0444\\u0435\\u0439\\u0441 \\u043e\\u0442\\u0440\\u0438\\u0441\\u043e\\u0432\\u044b\\u0432\\u0430\\u0435\\u0442\\u0441\\u044f **\\u043f\\u0440\\u044f\\u043c\\u043e \\u0432 \\u0442\\u0435\\u043a\\u0443\\u0449\\u0435\\u043c \\u0434\\u0438\\u0430\\u043b\\u043e\\u0433\\u0435** (\\u043d\\u0435 \\u0432\\u0441\\u043f\\u043b\\u044b\\u0432\\u0430\\u044e\\u0449\\u0435\\u0435 \\u043e\\u043a\\u043d\\u043e \\u0438 \\u043d\\u0435 \\u043a\\u0430\\u0440\\u0442\\u043e\\u0447\\u043a\\u0430); \\u043a\\u0430\\u0436\\u0434\\u044b\\u0439 \\u0431\\u043b\\u043e\\u043a \\u0440\\u0438\\u0441\\u0443\\u0439 \\u043e\\u0434\\u0438\\u043d \\u0440\\u0430\\u0437.",
    "values-ja": "\\u754c\\u9762\\u306f**\\u73fe\\u5728\\u306e\\u30c0\\u30a4\\u30a2\\u30ed\\u30b0\\u5185\\u306b\\u76f4\\u63a5\\u63cf\\u753b\\u3055\\u308c\\u307e\\u3059**\\uff08\\u30dd\\u30c3\\u30d7\\u30a2\\u30c3\\u30d7\\u3067\\u3082\\u30ab\\u30fc\\u30c9\\u3067\\u3082\\u3042\\u308a\\u307e\\u305b\\u3093\\uff09\\u3002\\u5404\\u30d6\\u30ed\\u30c3\\u30af\\u306f\\u4e00\\u5ea6\\u3060\\u3051\\u63cf\\u753b\\u3057\\u3066\\u304f\\u3060\\u3055\\u3044\\u3002",
    "values-ko": "\\uc778\\ud130\\ud398\\uc774\\uc2a4\\ub294 **\\ud604\\uc7ac \\ub300\\ud654\\ucc3d \\uc548\\uc5d0 \\uc9c1\\uc811 \\ub80c\\ub354\\ub9c1\\ub429\\ub2c8\\ub2e4** (\\ud31d\\uc5c5\\ub3c4 \\uce74\\ub4dc\\ub3c4 \\uc544\\ub2d9\\ub2c8\\ub2e4). \\uac01 \\ube14\\ub85d\\uc740 \\ud55c \\ubc88\\ub9cc \\uadf8\\ub9ac\\uc138\\uc694.",
    "values-ar": "\\u062a\\u064f\\u0639\\u0631\\u0636 \\u0627\\u0644\\u0648\\u0627\\u062c\\u0647\\u0629 **\\u0645\\u0628\\u0627\\u0634\\u0631\\u0629 \\u062f\\u0627\\u062e\\u0644 \\u0627\\u0644\\u062d\\u0648\\u0627\\u0631 \\u0627\\u0644\\u062d\\u0627\\u0644\\u064a** (\\u0644\\u064a\\u0633\\u062a \\u0646\\u0627\\u0641\\u0630\\u0629 \\u0645\\u0646\\u0628\\u062b\\u0642\\u0629 \\u0648\\u0644\\u0627 \\u0628\\u0637\\u0627\\u0642\\u0629)\\u060c \\u0648\\u0627\\u0631\\u0633\\u0645 \\u0643\\u0644 \\u0643\\u062a\\u0644\\u0629 \\u0645\\u0631\\u0629 \\u0648\\u0627\\u062d\\u062f\\u0629.",
    "values-hi": "\\u0907\\u0902\\u091f\\u0930\\u092b\\u093c\\u0947\\u0938 **\\u092e\\u094c\\u091c\\u0942\\u0926\\u093e \\u0921\\u093e\\u092f\\u0932\\u0949\\u0917 \\u0915\\u0947 \\u0905\\u0902\\u0926\\u0930 \\u0938\\u0940\\u0927\\u0947 \\u0930\\u0947\\u0902\\u0921\\u0930 \\u0939\\u094b\\u0924\\u093e \\u0939\\u0948** (\\u092a\\u0949\\u092a\\u0905\\u092a \\u092f\\u093e \\u0915\\u093e\\u0930\\u094d\\u0921 \\u0928\\u0939\\u0940\\u0902), \\u0939\\u0930 \\u092c\\u094d\\u0932\\u0949\\u0915 \\u090f\\u0915 \\u0939\\u0940 \\u092c\\u093e\\u0930 \\u092c\\u0928\\u093e\\u090f\\u0901\\u0964",
}


def atomic_write(path, text):
    tmp = path + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(text)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def main():
    changed = 0
    for path in sorted(glob.glob(os.path.join(ROOT, "app/src/main/res/values*/strings_i18n.xml"))):
        folder = os.path.basename(os.path.dirname(path))
        add = ADD.get(folder)
        if add is None:
            print("SKIP  %s（无该语言文案）" % folder)
            continue

        with io.open(path, "r", encoding="utf-8", newline="") as f:
            src = f.read()

        m = re.search(r'<string name="qk_04069"[^>]*>(.*?)</string>', src, re.S)
        if not m:
            print("SKIP  %s（没有 qk_04069）" % folder)
            continue
        cur = m.group(1)
        if add.strip() in cur:
            print("SKIP  %s（已含该句）" % folder)
            continue

        # 解码已有的 \uXXXX 转义后再判断/追加，避免重复或截断既有转义序列
        decoded = re.sub(r"\\u([0-9a-fA-F]{4})", lambda x: chr(int(x.group(1), 16)), cur)
        sep = " " if not decoded.endswith(("。", "！", "？", ".", "!", "?", "؛")) else ""
        newval = cur + sep + add
        src = src[:m.start(1)] + newval + src[m.end(1):]
        atomic_write(path, src)
        print("FIXED %s" % folder)
        changed += 1

    print("\n共修改 %d 个文件" % changed)
    return 0


if __name__ == "__main__":
    sys.exit(main())

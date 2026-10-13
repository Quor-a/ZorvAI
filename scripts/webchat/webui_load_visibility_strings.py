#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Web UI 对话框加载可见性文案：追加 qk_04077 / qk_04078，并去掉 qk_04073 的「卡片」措辞。

背景（用户 2026-10-13 第二次纠偏「webui对话框妈还没搞出来」）：
宿主 WebView 原先没有任何失败可见性 —— 背景透明 + 无 WebViewClient，
页面一旦加载失败就是一个**空白色块**，和「功能没做」在观感上完全一样。
本脚本落的就是那两条把失败/加载中搬上界面的文案。

铁律（本仓 i18n）：
  · 键 `qk_%05d` **只能追加**，绝不重排/复用；
  · `getString(id, *args)` 会**无条件** format，所以带参数字符串必须写全
    `%1$s` / `%2$s`，且绝不能让裸 `%` 落在文案里（真机直接崩）；
  · Android 字符串里 `'` 必须写成 `\'`，`formatted="false"` **不豁免**。
    本次所有文案刻意**不含撇号**，从源头绕开这个坑。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# 语言 -> (qk_04077 加载失败, qk_04078 加载中, qk_04073 去卡片措辞)
T = {
    "values": (
        "Web UI 页面加载失败（%1$s）：%2$s",
        "正在从 %1$s 加载 Web UI…",
        "zxwidget 缺少 component 或 dsl 参数",
    ),
    "values-en": (
        "Web UI page failed to load (%1$s): %2$s",
        "Loading the Web UI from %1$s…",
        "zxwidget is missing component or dsl",
    ),
    "values-de": (
        "Web-UI-Seite konnte nicht geladen werden (%1$s): %2$s",
        "Web-UI wird von %1$s geladen…",
        "zxwidget: component oder dsl fehlt",
    ),
    "values-fr": (
        "Echec du chargement de la page Web UI (%1$s) : %2$s",
        "Chargement de la Web UI depuis %1$s…",
        "zxwidget : component ou dsl manquant",
    ),
    "values-es": (
        "No se pudo cargar la página Web UI (%1$s): %2$s",
        "Cargando la Web UI desde %1$s…",
        "zxwidget: falta component o dsl",
    ),
    "values-pt": (
        "Falha ao carregar a página Web UI (%1$s): %2$s",
        "Carregando a Web UI de %1$s…",
        "zxwidget: falta component ou dsl",
    ),
    "values-ru": (
        "Не удалось загрузить страницу Web UI (%1$s): %2$s",
        "Загрузка Web UI из %1$s…",
        "У zxwidget отсутствует component или dsl",
    ),
    "values-ja": (
        "Web UI ページの読み込みに失敗しました（%1$s）：%2$s",
        "%1$s から Web UI を読み込んでいます…",
        "zxwidget に component または dsl がありません",
    ),
    "values-ko": (
        "Web UI 페이지를 불러오지 못했습니다 (%1$s): %2$s",
        "%1$s에서 Web UI를 불러오는 중…",
        "zxwidget에 component 또는 dsl이 없습니다",
    ),
    "values-ar": (
        "فشل تحميل صفحة Web UI (%1$s): %2$s",
        "جارٍ تحميل Web UI من %1$s…",
        "zxwidget يفتقد component أو dsl",
    ),
    "values-hi": (
        "Web UI पृष्ठ लोड नहीं हो सका (%1$s): %2$s",
        "%1$s से Web UI लोड हो रहा है…",
        "zxwidget में component या dsl नहीं है",
    ),
    # values-zh 是空占位目录，无 strings_i18n.xml —— 跳过（同前几轮）
}


def atomic_write(path, text):
    """原子写：先写 .tmp 再 os.replace。本仓用 io.open(p,'w') 会先把文件截断成 0 B，
    中途失败就只剩一个空文件（踩过）。"""
    tmp = path + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    os.replace(tmp, path)


def esc(s):
    """XML 文本转义。这里刻意只处理 & < >（文案里没有撇号，见文件头说明）。"""
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def line_for(key, value, formatted_false=False):
    attr = ' formatted="false"' if formatted_false else ""
    return '<string name="%s"%s>%s</string>' % (key, attr, esc(value))


def main():
    fixed, skipped = [], []
    for lang, (load_fail, loading, no_card) in T.items():
        path = os.path.join(RES, lang, "strings_i18n.xml")
        if not os.path.exists(path):
            skipped.append(lang)
            continue
        src = io.open(path, encoding="utf-8").read()
        orig = src

        # ── 1. qk_04073 去掉「卡片」框架 ──
        # 用正则换整条，避免踩到各语言不同的 `>` 与转义
        m = re.search(r'<string name="qk_04073"([^>]*)>.*?</string>', src, re.S)
        if not m:
            # 没有就补一条（不该发生，出现即说明该语言缺键，必须报出来）
            print("WARN %s: 未找到 qk_04073，跳过改写" % lang)
        else:
            new = '<string name="qk_04073"%s>%s</string>' % (m.group(1), esc(no_card))
            if new != m.group(0):
                src = src[:m.start()] + new + src[m.end():]

        # ── 2. 追加 qk_04077 / qk_04078（只能追加）──
        for key, val in (("qk_04077", load_fail), ("qk_04078", loading)):
            if re.search(r'<string name="%s"' % key, src):
                # 已在则原地更新，保证可重复执行
                src = re.sub(
                    r'<string name="%s"[^>]*>.*?</string>' % key,
                    lambda _: line_for(key, val),
                    src, count=1, flags=re.S)
            else:
                idx = src.rfind("</resources>")
                if idx < 0:
                    print("ERROR %s: 没有 </resources>，放弃" % lang)
                    sys.exit(1)
                src = src[:idx] + "    " + line_for(key, val) + "\n" + src[idx:]

        if src != orig:
            atomic_write(path, src)
            fixed.append(lang)
        else:
            skipped.append(lang)

    print("FIXED", " ".join(fixed) if fixed else "(none)")
    print("SKIP ", " ".join(skipped) if skipped else "(none)")
    print("共修改 %d 个文件" % len(fixed))


if __name__ == "__main__":
    main()

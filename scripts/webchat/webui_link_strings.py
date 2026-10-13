#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Web UI 投影顶栏文案：追加 qk_04079..qk_04083。

这五条服务的只有一件事：**让投影有一个能拿到手的对外链接**
（用户原话：「外部怎么端口 web ui 链接都没有」）。没有它，
「Web UI 是 ZorvAI 的投影」就退化成「只能在这台手机里看的 WebView」。

铁律同 `webui_load_visibility_strings.py`：键只能追加；带参必须写全 %1$s；
所有文案刻意不含撇号（Android 字符串里 `'` 必须 \\' 转义，formatted="false" 不豁免）。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# 语言 -> [qk_04079 .. qk_04083]
T = {
    "values": [
        "同一 Wi-Fi 下，可用这个链接在别的设备打开",
        "正在启动…",
        "复制链接",
        "已复制",
        "在浏览器打开",
    ],
    "values-en": [
        "On the same Wi-Fi, open this link on another device",
        "Starting…",
        "Copy link",
        "Copied",
        "Open in browser",
    ],
    "values-de": [
        "Im selben WLAN diesen Link auf einem anderen Gerät öffnen",
        "Wird gestartet…",
        "Link kopieren",
        "Kopiert",
        "Im Browser öffnen",
    ],
    "values-fr": [
        "Sur le même Wi-Fi, ouvrez ce lien sur un autre appareil",
        "Démarrage…",
        "Copier le lien",
        "Copié",
        "Ouvrir dans le navigateur",
    ],
    "values-es": [
        "En la misma Wi-Fi, abre este enlace en otro dispositivo",
        "Iniciando…",
        "Copiar enlace",
        "Copiado",
        "Abrir en el navegador",
    ],
    "values-pt": [
        "No mesmo Wi-Fi, abra este link em outro dispositivo",
        "Iniciando…",
        "Copiar link",
        "Copiado",
        "Abrir no navegador",
    ],
    "values-ru": [
        "В той же сети Wi-Fi откройте эту ссылку на другом устройстве",
        "Запуск…",
        "Скопировать ссылку",
        "Скопировано",
        "Открыть в браузере",
    ],
    "values-ja": [
        "同じ Wi-Fi 内なら、このリンクを別の端末で開けます",
        "起動中…",
        "リンクをコピー",
        "コピーしました",
        "ブラウザで開く",
    ],
    "values-ko": [
        "같은 Wi-Fi에서 이 링크를 다른 기기로 열 수 있습니다",
        "시작 중…",
        "링크 복사",
        "복사됨",
        "브라우저에서 열기",
    ],
    "values-ar": [
        "على نفس شبكة Wi-Fi، افتح هذا الرابط على جهاز آخر",
        "جارٍ البدء…",
        "نسخ الرابط",
        "تم النسخ",
        "فتح في المتصفح",
    ],
    "values-hi": [
        "उसी Wi-Fi पर, इस लिंक को किसी अन्य डिवाइस पर खोलें",
        "शुरू हो रहा है…",
        "लिंक कॉपी करें",
        "कॉपी हो गया",
        "ब्राउज़र में खोलें",
    ],
}

KEYS = ["qk_04079", "qk_04080", "qk_04081", "qk_04082", "qk_04083"]


def atomic_write(path, text):
    tmp = path + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    os.replace(tmp, path)


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main():
    fixed, missing = [], []
    for lang, vals in T.items():
        path = os.path.join(RES, lang, "strings_i18n.xml")
        if not os.path.exists(path):
            missing.append(lang)
            continue
        src = io.open(path, encoding="utf-8").read()
        orig = src
        for key, val in zip(KEYS, vals):
            line = '<string name="%s">%s</string>' % (key, esc(val))
            if re.search(r'<string name="%s"' % key, src):
                src = re.sub(
                    r'<string name="%s"[^>]*>.*?</string>' % key,
                    lambda _: line, src, count=1, flags=re.S)
            else:
                idx = src.rfind("</resources>")
                if idx < 0:
                    print("ERROR %s: 没有 </resources>" % lang)
                    sys.exit(1)
                src = src[:idx] + "    " + line + "\n" + src[idx:]
        if src != orig:
            atomic_write(path, src)
            fixed.append(lang)
    print("FIXED", " ".join(fixed) if fixed else "(none)")
    print("SKIP ", " ".join(missing) if missing else "(none)")
    print("共修改 %d 个文件" % len(fixed))


if __name__ == "__main__":
    main()

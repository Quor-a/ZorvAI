# -*- coding: utf-8 -*-
"""追加 qk_04075（Web UI 服务启动失败）到全部语言。

为什么必须新建而不是复用 qk_04068：那条是「卡片运行时资源缺失」，
拿它显示「服务起不来」是错的文案，用户看到的报错会和真实原因对不上。
i18n 铁律：键只能追加、不能改已有含义；getString(id,*args) 无条件 format，
所以占位符必须是 %1$s 这种带序号的，裸 % 会真机崩。
"""
import io
import os
import re

ROOT = r"D:/Calw OS-project/QuroAI/app/src/main/res"
ANCHOR_NAME = "qk_04074"
NEW_NAME = "qk_04075"

TEXT = {
    "values": "Web UI 服务启动失败：%1$s",
    "values-en": "Failed to start the Web UI server: %1$s",
    "values-ar": "فشل تشغيل خدمة Web UI: %1$s",
    "values-de": "Web-UI-Dienst konnte nicht gestartet werden: %1$s",
    "values-es": "No se pudo iniciar el servicio Web UI: %1$s",
    "values-fr": "Échec du démarrage du service Web UI : %1$s",
    "values-hi": "Web UI सेवा शुरू नहीं हो सकी: %1$s",
    "values-ja": "Web UI サービスを起動できませんでした：%1$s",
    "values-ko": "Web UI 서비스를 시작하지 못했습니다: %1$s",
    "values-pt": "Falha ao iniciar o serviço Web UI: %1$s",
    "values-ru": "Не удалось запустить службу Web UI: %1$s",
}

anchor_re = re.compile(
    r'([ \t]*<string name="%s"[^>]*>.*?</string>\n)' % re.escape(ANCHOR_NAME), re.S)

done, skipped = 0, []
for dirpath, _, filenames in os.walk(ROOT):
    if "strings_i18n.xml" not in filenames:
        continue
    folder = os.path.basename(dirpath)
    if folder not in TEXT:
        continue
    path = os.path.join(dirpath, "strings_i18n.xml")
    src = io.open(path, encoding="utf-8").read()
    if ('name="%s"' % NEW_NAME) in src:
        skipped.append(folder)
        continue
    line = '    <string name="%s">%s</string>\n' % (NEW_NAME, TEXT[folder])
    new, n = anchor_re.subn(lambda m: m.group(1) + line, src, count=1)
    if n == 0:
        print("!! 锚点未命中:", folder)
        continue
    tmp = path + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(new)
    os.replace(tmp, path)
    done += 1

print("已写入", done, "个语言；已存在跳过:", skipped)

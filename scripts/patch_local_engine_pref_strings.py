#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
给 12 套 strings_i18n.xml 添加「本地引擎独立进程」开关的两个键
（qk_03905 标题 / qk_03906 说明）。

背景：本地推理跨进程隔离（:llm）是在 QuroModelConfigScreen 的
「本地离线模型」区块里由用户自己开关的 —— 因为它是运行期行为变更，
默认关闭，需要真机验证后才能把默认值改成 true。

幂等：已存在 qk_03905 的文件直接跳过。
换行：本仓各文件均为 LF（实测 CRLF=0），读写全程按字节保真。

Android 资源坑：字符串里的单引号必须转义成 \\' ，否则 aapt 报
"Apostrophe not preceded by \\"。法语 / 西班牙语文案里都有撇号。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

KEY_TITLE = "qk_03905"
KEY_DESC = "qk_03906"

# 语言目录 → (标题, 说明)
STRINGS = {
    "values": (
        "本地引擎独立进程",
        "在独立进程中运行本地推理：加载大模型时不会再被系统回收整个应用，界面始终可用。"
        "关闭则沿用旧版进程内行为。",
    ),
    "values-zh": (
        "本地引擎独立进程",
        "在独立进程中运行本地推理：加载大模型时不会再被系统回收整个应用，界面始终可用。"
        "关闭则沿用旧版进程内行为。",
    ),
    "values-en": (
        "Isolated engine process",
        "Run local inference in a separate process, so loading a large model can no longer get "
        "the whole app killed by the system and the interface stays usable. "
        "Turn off to keep the previous in-process behaviour.",
    ),
    "values-ja": (
        "ローカルエンジンを別プロセス化",
        "ローカル推論を独立したプロセスで実行します。大きなモデルを読み込んでも"
        "アプリ全体がシステムに回収されず、画面はそのまま使えます。"
        "オフにすると従来の同一プロセス動作に戻ります。",
    ),
    "values-ko": (
        "로컬 엔진 별도 프로세스",
        "로컬 추론을 별도 프로세스에서 실행합니다. 큰 모델을 로드해도 앱 전체가 "
        "시스템에 의해 종료되지 않아 화면은 계속 사용할 수 있습니다. "
        "끄면 이전과 동일한 프로세스 내 동작으로 돌아갑니다.",
    ),
    "values-de": (
        "Lokale Engine als eigener Prozess",
        "Führt die lokale Inferenz in einem separaten Prozess aus. Das Laden eines großen "
        "Modells kann so nicht mehr die gesamte App vom System beenden, die Oberfläche bleibt "
        "nutzbar. Ausschalten behält das bisherige Verhalten im Hauptprozess bei.",
    ),
    # 法语撇号按 Android 规则转义为 \'
    "values-fr": (
        "Moteur local en processus isolé",
        "Exécute l\\'inférence locale dans un processus séparé : charger un grand modèle ne peut "
        "plus faire tuer toute l\\'application par le système, l\\'interface reste utilisable. "
        "Désactivez pour revenir au comportement précédent.",
    ),
    "values-es": (
        "Motor local en proceso aislado",
        "Ejecuta la inferencia local en un proceso separado: cargar un modelo grande ya no puede "
        "hacer que el sistema cierre toda la aplicación, y la interfaz sigue disponible. "
        "Desactívalo para volver al comportamiento anterior.",
    ),
    "values-pt": (
        "Motor local em processo isolado",
        "Executa a inferência local num processo separado: carregar um modelo grande já não faz "
        "o sistema encerrar toda a aplicação e a interface continua disponível. "
        "Desative para voltar ao comportamento anterior.",
    ),
    "values-ru": (
        "Локальный движок в отдельном процессе",
        "Выполняет локальный вывод в отдельном процессе: загрузка большой модели больше не "
        "приводит к завершению всего приложения системой, интерфейс остаётся доступным. "
        "Отключите, чтобы вернуться к прежнему поведению.",
    ),
    "values-ar": (
        "محرك محلي في عملية معزولة",
        "ينفّذ الاستدلال المحلي في عملية منفصلة: لن يؤدي تحميل نموذج كبير إلى إنهاء النظام "
        "للتطبيق بالكامل، وتبقى الواجهة قابلة للاستخدام. أوقفه للعودة إلى السلوك السابق.",
    ),
    "values-hi": (
        "स्थानीय इंजन अलग प्रक्रिया में",
        "स्थानीय अनुमान को अलग प्रक्रिया में चलाता है: बड़ा मॉडल लोड करने पर सिस्टम पूरे ऐप को "
        "बंद नहीं करेगा और इंटरफ़ेस उपलब्ध रहेगा। बंद करने पर पुराना व्यवहार बना रहेगा।",
    ),
}


def patch(path: str, title: str, desc: str) -> str:
    """返回 'added' / 'skipped' / 'failed:原因'。"""
    try:
        with open(path, "r", encoding="utf-8", newline="") as f:
            text = f.read()
    except FileNotFoundError:
        return "failed:文件不存在"

    # 幂等：任一键已存在就整体跳过（避免半套状态）
    if KEY_TITLE in text or KEY_DESC in text:
        return "skipped"

    marker = "</resources>"
    idx = text.rfind(marker)
    if idx < 0:
        return "failed:找不到 </resources>"

    block = (
        f'    <string name="{KEY_TITLE}" formatted="false">{title}</string>\n'
        f'    <string name="{KEY_DESC}" formatted="false">{desc}</string>\n'
    )
    text = text[:idx] + block + text[idx:]

    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    return "added"


def main() -> int:
    added = skipped = 0
    problems = []
    for lang, (title, desc) in STRINGS.items():
        path = os.path.join(RES, lang, "strings_i18n.xml")
        result = patch(path, title, desc)
        if result == "added":
            added += 1
            print(f"  + {lang}")
        elif result == "skipped":
            skipped += 1
            print(f"  = {lang} (已存在)")
        else:
            problems.append(f"{lang}: {result}")
            print(f"  ! {lang} {result}")

    print(f"\nadded={added} skipped={skipped} failed={len(problems)}")
    for p in problems:
        print("  " + p)
    return 0


if __name__ == "__main__":
    sys.exit(main())

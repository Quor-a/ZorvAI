# -*- coding: utf-8 -*-
"""把「打开 Web UI」和「zxwidget 能力开关」在提示词层彻底拆开。

缺陷（2026-10-13 用户指出「为什么你给 web UI 加了什么乱七八糟东西」）：
  onSelectZxWebCard 一个动作干了两件事——打开 Web UI 对话框，
  **同时**翻转 pendingZxWebCard，往原生对话框下一条消息注入
  「本轮必须用 Web UI 卡片对话框回答」。
  于是「打开一个只读的 Web 复制品」会顺手改掉主对话框的模型行为。

本脚本负责文案侧：
  · qk_04069/04070/04071 的措辞从「Web UI 卡片」改回「zxwidget 动态交互界面」
    （旧措辞本身就是把两个概念焊死的元凶之一，留着模型会继续按旧概念幻觉）；
  · 新增 qk_04076 作为独立开关行的标签。
键只能追加；已有键只改文本，不改含义、不改编号。原子写。
"""
import io
import os
import re

ROOT = r"D:/Calw OS-project/QuroAI/app/src/main/res"

T = {
    "values": {
        "qk_04069": "本轮请用 zxwidget 动态交互界面回答：调用 zx_web_card（component 取 zx_ 开头的组件名，dsl 填该组件的 JSON），把结构化结果画成可交互控件，不要用纯文本罗列。组件名与字段先查 zx_catalog。",
        "qk_04070": "已开启 zxwidget 动态交互界面：本轮将用可交互卡片回答",
        "qk_04071": "已关闭 zxwidget 动态交互界面",
        "qk_04076": "zxwidget 动态交互界面",
    },
    "values-en": {
        "qk_04069": "For this turn answer with the zxwidget dynamic UI: call zx_web_card (component = a name starting with zx_, dsl = that component's JSON) and render the structured result as interactive controls instead of listing it as plain text. Look up component names and fields via zx_catalog first.",
        "qk_04070": "zxwidget dynamic UI enabled: this turn will answer with interactive cards",
        "qk_04071": "zxwidget dynamic UI disabled",
        "qk_04076": "zxwidget dynamic UI",
    },
    "values-ar": {
        "qk_04069": "في هذا الدور أجب بواجهة zxwidget التفاعلية الديناميكية: استدعِ zx_web_card (component = اسم يبدأ بـ zx_، وdsl = JSON الخاص بهذا المكوّن) واعرض النتيجة المنظمة كعناصر تحكم تفاعلية بدل سردها كنص عادي. ابحث أولاً عن أسماء المكوّنات وحقولها عبر zx_catalog.",
        "qk_04070": "تم تفعيل واجهة zxwidget التفاعلية: سيجيب هذا الدور ببطاقات تفاعلية",
        "qk_04071": "تم إيقاف واجهة zxwidget التفاعلية",
        "qk_04076": "واجهة zxwidget التفاعلية الديناميكية",
    },
    "values-de": {
        "qk_04069": "Antworte in dieser Runde mit der dynamischen zxwidget-Oberfläche: rufe zx_web_card auf (component = Name mit Präfix zx_, dsl = JSON dieser Komponente) und stelle das strukturierte Ergebnis als interaktive Bedienelemente dar statt als reinen Text. Komponenten- und Feldnamen zuerst über zx_catalog nachschlagen.",
        "qk_04070": "Dynamische zxwidget-Oberfläche aktiviert: Diese Runde antwortet mit interaktiven Karten",
        "qk_04071": "Dynamische zxwidget-Oberfläche deaktiviert",
        "qk_04076": "zxwidget dynamische Oberfläche",
    },
    "values-es": {
        "qk_04069": "En este turno responde con la interfaz dinámica de zxwidget: llama a zx_web_card (component = nombre que empiece por zx_, dsl = JSON de ese componente) y muestra el resultado estructurado como controles interactivos en lugar de texto plano. Consulta antes los nombres de componente y campo con zx_catalog.",
        "qk_04070": "Interfaz dinámica zxwidget activada: este turno responderá con tarjetas interactivas",
        "qk_04071": "Interfaz dinámica zxwidget desactivada",
        "qk_04076": "Interfaz dinámica zxwidget",
    },
    "values-fr": {
        "qk_04069": "Pour ce tour, réponds avec l'interface dynamique zxwidget : appelle zx_web_card (component = un nom commençant par zx_, dsl = le JSON de ce composant) et affiche le résultat structuré sous forme de contrôles interactifs plutôt qu'en texte brut. Vérifie d'abord les noms de composant et de champ avec zx_catalog.",
        "qk_04070": "Interface dynamique zxwidget activée : ce tour répondra avec des cartes interactives",
        "qk_04071": "Interface dynamique zxwidget désactivée",
        "qk_04076": "Interface dynamique zxwidget",
    },
    "values-hi": {
        "qk_04069": "इस बार zxwidget डायनामिक इंटरफ़ेस से उत्तर दें: zx_web_card कॉल करें (component = zx_ से शुरू होने वाला नाम, dsl = उस कंपोनेंट का JSON) और संरचित परिणाम को सादे टेक्स्ट की जगह इंटरैक्टिव कंट्रोल के रूप में दिखाएँ। कंपोनेंट और फ़ील्ड नाम पहले zx_catalog से देखें।",
        "qk_04070": "zxwidget डायनामिक इंटरफ़ेस चालू: इस बार इंटरैक्टिव कार्ड से उत्तर मिलेगा",
        "qk_04071": "zxwidget डायनामिक इंटरफ़ेस बंद",
        "qk_04076": "zxwidget डायनामिक इंटरफ़ेस",
    },
    "values-ja": {
        "qk_04069": "今回は zxwidget の動的インタラクティブ界面で回答してください：zx_web_card を呼び出し（component は zx_ で始まるコンポーネント名、dsl はそのコンポーネントの JSON）、構造化された結果をプレーンテキストではなく操作可能なコントロールとして描画します。コンポーネント名とフィールドは先に zx_catalog で確認してください。",
        "qk_04070": "zxwidget 動的インタラクティブ界面を有効化：今回は操作可能なカードで回答します",
        "qk_04071": "zxwidget 動的インタラクティブ界面を無効化",
        "qk_04076": "zxwidget 動的インタラクティブ界面",
    },
    "values-ko": {
        "qk_04069": "이번에는 zxwidget 동적 인터랙티브 인터페이스로 답하세요: zx_web_card를 호출하고(component는 zx_로 시작하는 컴포넌트 이름, dsl은 해당 컴포넌트의 JSON) 구조화된 결과를 일반 텍스트가 아닌 조작 가능한 컨트롤로 그립니다. 컴포넌트와 필드 이름은 먼저 zx_catalog로 확인하세요.",
        "qk_04070": "zxwidget 동적 인터랙티브 인터페이스 켜짐: 이번에는 조작 가능한 카드로 답합니다",
        "qk_04071": "zxwidget 동적 인터랙티브 인터페이스 꺼짐",
        "qk_04076": "zxwidget 동적 인터랙티브 인터페이스",
    },
    "values-pt": {
        "qk_04069": "Neste turno responda com a interface dinâmica zxwidget: chame zx_web_card (component = nome começando com zx_, dsl = o JSON desse componente) e mostre o resultado estruturado como controles interativos em vez de texto simples. Consulte antes os nomes de componente e campos com zx_catalog.",
        "qk_04070": "Interface dinâmica zxwidget ativada: este turno responderá com cartões interativos",
        "qk_04071": "Interface dinâmica zxwidget desativada",
        "qk_04076": "Interface dinâmica zxwidget",
    },
    "values-ru": {
        "qk_04069": "В этом ходе отвечай через динамический интерфейс zxwidget: вызови zx_web_card (component = имя, начинающееся с zx_, dsl = JSON этого компонента) и отрисуй структурированный результат как интерактивные элементы, а не простым текстом. Имена компонентов и полей сначала смотри через zx_catalog.",
        "qk_04070": "Динамический интерфейс zxwidget включён: этот ход ответит интерактивными карточками",
        "qk_04071": "Динамический интерфейс zxwidget отключён",
        "qk_04076": "Динамический интерфейс zxwidget",
    },
}


def set_text(src, name, text):
    """保留原标签上的属性（formatted="false" 之类），只换文本。"""
    pat = re.compile(r'(<string name="%s")([^>]*>)(.*?)(</string>)' % re.escape(name), re.S)

    def rep(m):
        return m.group(1) + m.group(2) + text + m.group(4)

    new, n = pat.subn(rep, src, count=1)
    return new, n


def main():
    changed, added, missing = 0, 0, []
    for dirpath, _, filenames in os.walk(ROOT):
        if "strings_i18n.xml" not in filenames:
            continue
        folder = os.path.basename(dirpath)
        if folder not in T:
            continue
        path = os.path.join(dirpath, "strings_i18n.xml")
        src = io.open(path, encoding="utf-8").read()

        for key in ("qk_04069", "qk_04070", "qk_04071"):
            src, n = set_text(src, key, T[folder][key])
            if n == 0:
                missing.append((folder, key))
            else:
                changed += n

        if 'name="qk_04076"' not in src:
            anchor = re.compile(r'([ \t]*<string name="qk_04072"[^>]*>.*?</string>\n)', re.S)
            src, n = anchor.subn(
                lambda m: m.group(1) + '    <string name="qk_04076">%s</string>\n' % T[folder]["qk_04076"],
                src, count=1)
            if n == 0:
                missing.append((folder, "qk_04076-anchor"))
            else:
                added += 1

        tmp = path + ".tmp"
        with io.open(tmp, "w", encoding="utf-8", newline="") as f:
            f.write(src)
        os.replace(tmp, path)

    print("改写", changed, "条；新增 qk_04076 到", added, "个语言")
    if missing:
        print("!! 未命中:", missing)


if __name__ == "__main__":
    main()

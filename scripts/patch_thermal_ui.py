# -*- coding: utf-8 -*-
"""
L5 温控自适应 —— UI 接线补丁。

为什么必须做这一步：
  thermalPollMs 在原生层默认是 0（禁用），LlamaSession.Config 的默认值也是 0。
  如果不接线，前面在 llama_jni_stub.cpp 里写的整条温控链路**永远不会被调用** ——
  功能是完整的，但对用户零效果，属于典型的「改了但没生效」。

本次改动把开关从「只有原生层知道」打通到「用户在模型配置页能开」：
  1. QuroLocalEnginePrefs 增加温控键 + 无 Context 读取通道
  2. 三处 LlamaSession.Config(...) 传 thermalPollMs
  3. 模型配置页加开关（紧邻进程隔离开关）
  4. 12 套 strings_i18n.xml 各加 2 键（qk_03907 标题 / qk_03908 说明）

关于「无 Context 读取」：
  三个 Config 调用点（LocalModelSessionHolder / QuroLocalEngineNative.runLlama /
  QuroLlmEngineHost.createLlama）都在拿不到 Context 的路径上，而 QuroLlmEngineHost
  更是跑在 `:llm` 子进程里 —— 恰恰是最需要读这个开关的地方。
  QuroApplication.appCtx 在 attachBaseContext 阶段赋值，早于 isMainProcess() 守卫，
  因此**每个进程**都拿得到，用它作为回退。

幂等：以 MARKER 探测，已打过则整段跳过。
原子性：任一锚点未命中唯一位置 → 该文件不落盘并 sys.exit(1)。
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MARKER = "local_engine_thermal_adaptive"

FAILS = []


def read(path):
    with open(path, "rb") as f:
        raw = f.read()
    crlf = b"\r\n" in raw
    # 必须归一 CRLF→LF 后再匹配锚点：锚点字符串用的是 \n。
    # 少了这一步，CRLF 文件上所有锚点都会「命中 0 次」——
    # 而 grep 是按行匹配、感知不到行尾，所以人工核对时看不出差别。
    return raw.decode("utf-8").replace("\r\n", "\n"), crlf


def write(path, text, crlf):
    out = text.replace("\n", "\r\n") if crlf else text
    with open(path, "wb") as f:
        f.write(out.encode("utf-8"))


def sub_once(text, anchor, replacement, label):
    """锚点必须恰好命中 1 次；否则记失败并原样返回。"""
    n = text.count(anchor)
    if n != 1:
        FAILS.append("%s：锚点命中 %d 次（期望 1）" % (label, n))
        return text
    print("  + %s" % label)
    return text.replace(anchor, replacement, 1)


# ═══════════════════════ 1. QuroLocalEnginePrefs 扩展 ═══════════════════════

PREF_REL = "app/src/main/java/com/ai/assistance/quro/core/network/QuroLocalEngine.kt"

# 取 object 的末尾：KEY_ISOLATED_PROCESS 的 set() 之后就是 object 的右花括号。
PREF_ANCHOR = """                .edit().putBoolean(KEY_ISOLATED_PROCESS, enabled).apply()
        }
    }
}
"""

PREF_NEW = """                .edit().putBoolean(KEY_ISOLATED_PROCESS, enabled).apply()
        }
    }

    // ══════════════════ L5 · 温控自适应开关 ══════════════════
    //
    // 为什么做成用户开关而不是直接默认打开：
    //   温控会在 decode 循环里**主动下调线程数**。这是运行期行为变更 ——
    //   在温控策略激进的机型上，可能出现「一直跑在低档」的体感回落。
    //   默认关闭 + 用户可开，等于给了一条不用等下一版的退路。
    //
    // 与进程隔离开关的风险等级不同，所以默认值也不同：
    //   隔离路径出问题会**崩**，一眼可见、必须回退；
    //   温控路径出问题只是**变慢**，极难归因，且不会有人来报。
    //   越是无声的失败，默认值越要保守。

    /** 键名：true = 本地推理启用温控自适应（L5 主动降档）。 */
    const val KEY_THERMAL_ADAPTIVE = "local_engine_thermal_adaptive"

    /** 默认关闭。理由见上。 */
    const val DEFAULT_THERMAL_ADAPTIVE = false

    /** 采样间隔：每 2 秒一次，兼顾及时性与开销（规格建议值）。 */
    const val THERMAL_POLL_MS = 2000

    /** 当前是否启用温控自适应。 */
    fun isThermalAdaptive(): Boolean = runCatching {
        prefs()?.getBoolean(KEY_THERMAL_ADAPTIVE, DEFAULT_THERMAL_ADAPTIVE)
            ?: DEFAULT_THERMAL_ADAPTIVE
    }.getOrDefault(DEFAULT_THERMAL_ADAPTIVE)

    fun setThermalAdaptive(enabled: Boolean) {
        runCatching {
            prefs()?.edit()?.putBoolean(KEY_THERMAL_ADAPTIVE, enabled)?.apply()
        }
    }

    /**
     * 传给原生层的采样间隔：**0 = 禁用**。
     *
     * 刻意不抛异常：0 是原生层的「不干预」契约，任何时候都必须能安全降级到它。
     * 读偏好失败也返回 0（宁可没有温控，也不要因为一个开关读不到就影响推理）。
     */
    fun thermalPollMs(): Int = if (isThermalAdaptive()) THERMAL_POLL_MS else 0

    /**
     * 取偏好文件，**不需要调用方持有 Context**。
     *
     * 三个调用点（LocalModelSessionHolder / QuroLocalEngineNative.runLlama /
     * QuroLlmEngineHost.createLlama）都在无 Context 的路径上，后者还运行在
     * `:llm` 子进程 —— 恰是最需要读这个开关的地方。
     *
     * QuroApplication.appCtx 在 attachBaseContext 阶段就已赋值，且**早于**
     * isMainProcess() 守卫，所以主进程与所有副进程都拿得到。
     */
    private fun prefs(): android.content.SharedPreferences? =
        com.ai.assistance.quro.activity.QuroApplication.appCtx
            ?.getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE)
}
"""


# ═══════════════════════ 2. 三处 LlamaSession.Config ═══════════════════════

HOLDER_REL = "app/src/full/java/com/ai/assistance/quro/core/network/LocalModelSessionHolder.kt"
HOLDER_ANCHOR = """                        useMmap = model.useMmap,     // 默认 false —— 外部存储上的 GGUF 用 mmap 会卡死加载
                        kvUnified = model.kvUnified, // 默认 true  —— 单序列统一 KV，少分配、加载快
                    )"""
HOLDER_NEW = """                        useMmap = model.useMmap,     // 默认 false —— 外部存储上的 GGUF 用 mmap 会卡死加载
                        kvUnified = model.kvUnified, // 默认 true  —— 单序列统一 KV，少分配、加载快
                        // L5 · 温控自适应：0 = 关闭（默认值，由模型配置页的开关决定）。
                        thermalPollMs = QuroLocalEnginePrefs.thermalPollMs(),
                    )"""

NATIVE_REL = "app/src/full/java/com/ai/assistance/quro/core/network/QuroLocalEngineNative.kt"
NATIVE_ANCHOR = """            kvUnified = model.kvUnified,
        )"""
NATIVE_NEW = """            kvUnified = model.kvUnified,
            // L5 · 温控自适应：0 = 关闭（默认值，由模型配置页的开关决定）。
            thermalPollMs = QuroLocalEnginePrefs.thermalPollMs(),
        )"""

HOST_REL = "app/src/full/java/com/ai/assistance/quro/llm/QuroLlmEngineHost.kt"
HOST_ANCHOR = """            offloadKqv = opts.optBoolean("offloadKqv", false)
        )"""
HOST_NEW = """            offloadKqv = opts.optBoolean("offloadKqv", false),
            // L5 · 温控自适应：0 = 关闭。这条路径跑在 `:llm` 子进程，
            // 无 Context 可传 —— QuroLocalEnginePrefs 内部走 QuroApplication.appCtx。
            thermalPollMs = QuroLocalEnginePrefs.thermalPollMs()
        )"""

HOST_IMPORT_ANCHOR = """import com.ai.assistance.mnn.MnnSamplerTuning
import org.json.JSONArray"""
HOST_IMPORT_NEW = """import com.ai.assistance.mnn.MnnSamplerTuning
import com.ai.assistance.quro.core.network.QuroLocalEnginePrefs
import org.json.JSONArray"""


# ═══════════════════════ 3. 模型配置页开关 ═══════════════════════

UI_REL = "app/src/main/java/com/ai/assistance/quro/ui/QuroModelConfigScreen.kt"
UI_ANCHOR = """            Spacer(Modifier.height(10.dp))

            Text(stringResource(R.string.qk_02121),"""
UI_NEW = """            Spacer(Modifier.height(10.dp))

            // L5 · 温控自适应开关。
            //
            // 与上面的进程隔离放在一起是有意的：两者都是「改变本地推理运行期行为」
            // 的开关，用户要能在一个地方同时看到、同时回退。区别在于出问题的表现：
            // 隔离失败会崩，温控失败只是变慢 —— 所以这里默认关闭。
            val thermalNow = remember { mutableStateOf(QuroLocalEnginePrefs.isThermalAdaptive()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = thermalNow.value,
                    onCheckedChange = {
                        thermalNow.value = it
                        QuroLocalEnginePrefs.setThermalAdaptive(it)
                    },
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.qk_03907), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.qk_03908),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            Text(stringResource(R.string.qk_02121),"""


# ═══════════════════════ 4. 12 套资源真译 ═══════════════════════

# 说明文案刻意**不含撇号**：法语/西班牙语/葡萄牙语的不定冠词撇号在 Android
# 资源里必须写成 \'，否则 aapt 直接报 "Apostrophe not preceded by \" 编译失败。
# 与其每套语言都去数撇号，不如在写文案时就选不含撇号的措辞。
I18N = {
    "": ("温控自适应调度",
         "持续推理时每 2 秒读取一次 SoC 温控余量，提前小幅下调线程数，避免被系统断崖式降频。关闭时不做任何干预。"),
    "zh": ("温控自适应调度",
           "持续推理时每 2 秒读取一次 SoC 温控余量，提前小幅下调线程数，避免被系统断崖式降频。关闭时不做任何干预。"),
    "en": ("Thermal-adaptive scheduling",
           "During long generations it reads the SoC thermal headroom every 2 seconds and trims the thread count slightly ahead of time, avoiding the cliff-edge throttling the system would otherwise apply. When off, nothing is intervened."),
    "ja": ("温度適応スケジューリング",
           "長時間の推論中に 2 秒ごとに SoC のサーマル余裕を読み取り、スレッド数を事前に小幅に下げることで、OS による急激なクロック制限を回避します。オフのときは一切介入しません。"),
    "ko": ("온도 적응형 스케줄링",
           "장시간 추론 중 2초마다 SoC 열 여유를 읽고 스레드 수를 미리 소폭 낮춰 시스템의 급격한 스로틀링을 피합니다. 끄면 아무런 개입도 하지 않습니다."),
    "de": ("Thermisch adaptives Scheduling",
           "Liest während langer Inferenz alle 2 Sekunden den SoC-Thermalspielraum und reduziert die Thread-Anzahl frühzeitig leicht, um abruptes Throttling durch das System zu vermeiden. Aus: keine Eingriffe."),
    "fr": ("Ordonnancement thermique adaptatif",
           "Lit la marge thermique du SoC toutes les 2 secondes pendant les inférences longues et réduit légèrement le nombre de threads par anticipation, ce qui évite un bridage brutal du système. Désactivé : aucune intervention."),
    "es": ("Programación térmica adaptativa",
           "Lee el margen térmico del SoC cada 2 segundos durante inferencias largas y reduce ligeramente los hilos con antelación, evitando el estrangulamiento brusco del sistema. Desactivado: sin intervención."),
    "pt": ("Agendamento térmico adaptativo",
           "Lê a margem térmica do SoC a cada 2 segundos durante inferências longas e reduz levemente as threads com antecedência, evitando o throttling abrupto do sistema. Desativado: nenhuma intervenção."),
    "ru": ("Адаптивное тепловое планирование",
           "Каждые 2 секунды считывает тепловой запас SoC при длительном выводе и заранее немного снижает число потоков, избегая резкого троттлинга со стороны системы. Выключено — без вмешательства."),
    "ar": ("جدولة حرارية تكيفية",
           "يقرأ الهامش الحراري للمعالج كل ثانيتين أثناء الاستدلال الطويل ويقلّل عدد الخيوط مسبقًا لتجنّب الخفض الحاد من النظام. عند الإيقاف لا يوجد أي تدخّل."),
    "hi": ("ताप-अनुकूली शेड्यूलिंग",
           "लंबे अनुमान के दौरान हर 2 सेकंड में SoC का थर्मल बफ़र पढ़ता है और थ्रेड संख्या पहले से थोड़ी घटा देता है, जिससे सिस्टम की अचानक थ्रॉटलिंग से बचा जा सके। बंद होने पर कोई हस्तक्षेप नहीं।"),
}


def patch_prefs():
    print("1. QuroLocalEnginePrefs 扩展")
    path = os.path.join(ROOT, PREF_REL)
    text, crlf = read(path)
    if MARKER in text:
        print("  = 已打过，跳过")
        return
    new = sub_once(text, PREF_ANCHOR, PREF_NEW, "温控键 + 无 Context 读取通道")
    if len(FAILS) == 0:
        write(path, new, crlf)


def patch_callsites():
    print("2. 三处 LlamaSession.Config 传参")
    for rel, anchor, new, label in (
        (HOLDER_REL, HOLDER_ANCHOR, HOLDER_NEW, "LocalModelSessionHolder 常驻会话"),
        (NATIVE_REL, NATIVE_ANCHOR, NATIVE_NEW, "QuroLocalEngineNative 单次会话"),
        (HOST_REL, HOST_ANCHOR, HOST_NEW, "QuroLlmEngineHost（:llm 进程）"),
    ):
        path = os.path.join(ROOT, rel)
        text, crlf = read(path)
        # 幂等必须按**本次改动引入的内容**判断，不能按 PREF 那段的键名判断：
        # 这三处新增的是 thermalPollMs = ... 调用，不含 "local_engine_thermal_adaptive"
        # 字面量。用错标记会让「已打过补丁」的文件被误判成未打，进而在锚点已被消耗后
        # 报一个假失败（本次就踩到了）。
        if "thermalPollMs = QuroLocalEnginePrefs.thermalPollMs()" in text:
            print("  = %s 已打过，跳过" % label)
            continue
        n0 = len(FAILS)
        out = sub_once(text, anchor, new, label)
        if len(FAILS) == n0:
            write(path, out, crlf)


def patch_host_import():
    print("3. QuroLlmEngineHost 导入")
    path = os.path.join(ROOT, HOST_REL)
    text, crlf = read(path)
    if "com.ai.assistance.quro.core.network.QuroLocalEnginePrefs" in text:
        print("  = 已有导入，跳过")
        return
    out = sub_once(text, HOST_IMPORT_ANCHOR, HOST_IMPORT_NEW, "import QuroLocalEnginePrefs")
    if not FAILS:
        write(path, out, crlf)


def patch_ui():
    print("4. 模型配置页开关")
    path = os.path.join(ROOT, UI_REL)
    text, crlf = read(path)
    if "qk_03907" in text:
        print("  = 已打过，跳过")
        return
    out = sub_once(text, UI_ANCHOR, UI_NEW, "温控开关 Row")
    if not FAILS:
        write(path, out, crlf)


def patch_i18n():
    print("5. 12 套 strings_i18n.xml")
    added = skipped = 0
    for lang, (title, desc) in sorted(I18N.items()):
        d = "values" + (("-" + lang) if lang else "")
        path = os.path.join(ROOT, "app", "src", "main", "res", d, "strings_i18n.xml")
        if not os.path.isfile(path):
            print("  ! 缺文件 %s" % path)
            FAILS.append("缺文件 " + d)
            continue
        text, crlf = read(path)
        if 'name="qk_03907"' in text:
            skipped += 1
            continue
        lines = text.split("\n")
        out, done = [], False
        for line in lines:
            out.append(line)
            if not done and 'name="qk_03906"' in line:
                out.append('    <string name="qk_03907" formatted="false">%s</string>' % title)
                out.append('    <string name="qk_03908" formatted="false">%s</string>' % desc)
                done = True
        if not done:
            print("  ! %s 未找到 qk_03906 锚点" % d)
            FAILS.append("锚点缺失 " + d)
            continue
        write(path, "\n".join(out), crlf)
        added += 1
    print("  新增 %d 套，跳过 %d 套" % (added, skipped))


def main():
    patch_prefs()
    patch_callsites()
    patch_host_import()
    patch_ui()
    patch_i18n()

    if FAILS:
        print("\n❌ 有 %d 项失败，相关文件**未落盘**：" % len(FAILS))
        for f in FAILS:
            print("   - %s" % f)
        return 1
    print("\n✅ 全部改动已写入。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

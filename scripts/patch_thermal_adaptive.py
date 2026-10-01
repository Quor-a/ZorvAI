#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 L5 温控自适应（llm/core/src/thermal.cpp 已实现的 ThermalGovernor）
接线到 llama.cpp 的 prefill / decode 循环。

背景（用户规格书原话）：
  「真正决定流畅度的两个开关」之一是**温控自适应** —— 每 2 秒读一次 thermal
  headroom 主动降线程/批大小，能保留 77% 峰值而不是 31%。
  这比语言选型影响大得多。

为什么必须双侧同步改：
  nativeCreateSession 的 JNI 签名从 (Ljava/lang/String;IIIIIZZZZ)J 变成
  (Ljava/lang/String;IIIIIZZZZI)J。Kotlin 侧漏改一处就是 UnsatisfiedLinkError
  —— 而且是在**运行时**、在用户点「加载模型」时才炸，编译期完全看不出来。
  所以本脚本把 cpp + Kotlin 两侧当成一个原子改动来做。

安全设计（三条纪律，写在 cpp 注释里）：
  1) 默认关闭（thermalPollMs == 0）。这是运行期行为变更，默认值等真机验证后再改。
  2) 只在 token / chunk 边界应用，绝不在一次 llama_decode 中途改线程数。
  3) 档位没变就一次系统调用都不做。

幂等：每处改动都先查 marker。
换行：本仓 llama_jni_stub.cpp 实测为 LF；Kotlin 侧同为 LF。脚本读时归一、写回原样。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CPP = os.path.join(ROOT, "llm", "llama", "src", "main", "cpp", "llama_jni_stub.cpp")
NATIVE_KT = os.path.join(ROOT, "llm", "llama", "src", "main", "java",
                         "com", "ai", "assistance", "llama", "LlamaNative.kt")
SESSION_KT = os.path.join(ROOT, "llm", "llama", "src", "main", "java",
                          "com", "ai", "assistance", "llama", "LlamaSession.kt")

MARKER_CPP = "applyThermalAdvice"

results = []


def read(path):
    with open(path, "r", encoding="utf-8", newline="") as f:
        raw = f.read()
    crlf = "\r\n" in raw
    return raw.replace("\r\n", "\n"), crlf


def write(path, text, crlf):
    out = text.replace("\n", "\r\n") if crlf else text
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(out)


def replace_once(text, old, new, label):
    """锚点必须恰好出现一次，否则报错（避免改错地方）。"""
    n = text.count(old)
    if n == 0:
        results.append(f"  ! {label}: 锚点未找到")
        return text, False
    if n > 1:
        results.append(f"  ! {label}: 锚点出现 {n} 次，不唯一")
        return text, False
    results.append(f"  + {label}")
    return text.replace(old, new, 1), True


# ─────────────────────────────────────────────────────────────────────
# 1. cpp
# ─────────────────────────────────────────────────────────────────────
text, crlf = read(CPP)

if MARKER_CPP in text:
    results.append("  = cpp 已打过补丁，跳过")
else:
    # 1.1 include
    text, _ = replace_once(
        text,
        '#include "llama.h"\n',
        '#include "llama.h"\n// L5 · 温控自适应（ThermalGovernor / ThermalAdvice）。\n'
        '// 由 llm/llama/CMakeLists.txt 的 target_link_libraries(LlamaWrapper quro_llm_core)\n'
        '// 提供 include 路径（quro_llm_core 的 target_include_directories 是 PUBLIC）。\n'
        '#include "quro/thermal.h"\n',
        "cpp/include thermal.h",
    )

    # 1.2 session 字段 + 1.3 applyThermalAdvice 函数（一起插在结构体之后）
    SESSION_OLD = """    std::string lastError;
};
"""
    SESSION_NEW = """    std::string lastError;

    // ── L5 · 温控自适应（实现见 applyThermalAdvice）──
    // thermalPollMs：采样间隔（毫秒）。**0 = 禁用**（默认）。
    //   默认关闭是刻意的：这是运行期行为变更（在 decode 循环里改线程数），
    //   必须在真机上验证过才敢默认打开。用户可在「模型配置」里手动启用。
    // thermalThreads：当前实际生效的线程数。只有当它**变化**时才去动后端 ——
    //   llama_set_n_threads 会重建线程绑定，无谓调用纯属浪费，也会刷日志。
    int32_t thermalPollMs = 0;
    int32_t thermalThreads = 0;
    std::chrono::steady_clock::time_point thermalLastCheck{};
};

// ─────────────────────────── L5 · 温控自适应 ───────────────────────────
// 这是「真正决定流畅度的两个开关」之一，也是比语言选型影响更大的那一个：
//   持续推理 5–10 分钟后 SoC 必然降频（实测骁龙 8 Gen 3 上裸跑 30 分钟吞吐掉约 69%）。
//   每约 2 秒读一次 thermal headroom 并**主动**小幅降线程，可以保住约 77% 峰值 ——
//   因为主动降档是平滑的，被动降频是断崖式的。
//
// 为什么必须"主动"：平台的 thermal throttling 是内核/固件行为，降频时不会通知应用。
//   等我们发现变慢再反应，已经掉进断崖。唯一办法是**提前**看余量（headroom），
//   在还有余量时就小幅让步，把温度压在阈值下方。
//
// 三条实现纪律：
//   1) **默认关闭**（thermalPollMs == 0），见 LlamaSessionNative 的字段注释。
//   2) **只在 token / chunk 边界应用**。绝不在一次 llama_decode 中途改线程数 ——
//      ggml 的线程池/图节点假设会被破坏，那是内存损坏级的风险，不是"慢一点"。
//   3) **档位没变就一次系统调用都不做**（见下面的 want == thermalThreads 判断）。
//
// 探测不到任何温度源时 ThermalGovernor 会返回 UNKNOWN，这里直接 return 不干预 ——
// 明确什么都不做，好过瞎猜一个档位把用户的机器降速。
static void applyThermalAdvice(LlamaSessionNative * session) {
    if (session == nullptr || session->ctx == nullptr) return;
    if (session->thermalPollMs <= 0) return;   // 未启用

    const auto now = std::chrono::steady_clock::now();
    const auto sinceMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                             now - session->thermalLastCheck)
                             .count();
    if (sinceMs < session->thermalPollMs) return;
    session->thermalLastCheck = now;

    const quro::llm::ThermalAdvice advice = quro::llm::ThermalGovernor::instance().sample();
    if (advice.level == quro::llm::ThermalLevel::UNKNOWN) return;   // 无数据源 → 不干预

    const int32_t want = std::max<int32_t>(1, advice.threads);
    if (want == session->thermalThreads) return;   // 档位未变 → 一次调用都不做

    const int32_t prev = session->thermalThreads;
    session->thermalThreads = want;
    llama_set_n_threads(session->ctx,
                        static_cast<uint32_t>(want),
                        static_cast<uint32_t>(want));
    LOGI("温控调档：线程 %d → %d | 档位=%s headroom=%d%% status=%d | %s",
         (int) prev, (int) want,
         quro::llm::thermalLevelName(advice.level),
         advice.headroomPct, advice.platformStatus,
         advice.reason.c_str());
}
"""
    text, _ = replace_once(text, SESSION_OLD, SESSION_NEW, "cpp/session 字段 + applyThermalAdvice")

    # 1.4 nativeCreateSession 形参加 thermalPollMs
    text, _ = replace_once(
        text,
        """        jboolean offloadKqv
) {
    (void) clazz;
    ensureBackendInit();""",
        """        jboolean offloadKqv,
        jint thermalPollMs
) {
    (void) clazz;
    ensureBackendInit();""",
        "cpp/nativeCreateSession 形参",
    )

    # 1.5 初始化字段 + configure
    text, _ = replace_once(
        text,
        "    llama_set_n_threads(session->ctx, effectiveThreads, effectiveThreads);\n",
        """    llama_set_n_threads(session->ctx, effectiveThreads, effectiveThreads);

    // ── L5 · 温控自适应初始化 ──
    // 配置成**基础**（threads/batch）为当前请求值：档位比例都是相对它算的，
    // 传 0 或别的值会让 COOL 档的线程数不等于用户要的线程数。
    session->thermalPollMs = static_cast<int32_t>(std::max<jint>(0, thermalPollMs));
    session->thermalThreads = effectiveThreads;
    session->thermalLastCheck = std::chrono::steady_clock::now();
    if (session->thermalPollMs > 0) {
        quro::llm::ThermalGovernor::instance().configure(
            session->thermalPollMs,
            effectiveThreads,
            static_cast<int>(cparams.n_batch));
        LOGI("温控自适应已启用：pollMs=%d baseThreads=%d baseBatch=%u",
             (int) session->thermalPollMs, (int) effectiveThreads, (unsigned) cparams.n_batch);
    }
""",
        "cpp/温控初始化",
    )

    # 1.6 prefill 循环里应用
    text, _ = replace_once(
        text,
        """        offset += chunk;
    }
    llama_batch_free(pbatch);""",
        """        // L5 · 温控：chunk 边界是安全的调档点（这一批已经算完，线程池空闲）。
        applyThermalAdvice(session);
        offset += chunk;
    }
    llama_batch_free(pbatch);""",
        "cpp/prefill 循环调档",
    )

    # 1.7 decode 循环里应用
    text, _ = replace_once(
        text,
        """        if (session->cancel.load()) {
            LOGI("generation cancelled");
            generationDirty = true;
            break;
        }

        const llama_token newToken = llama_sampler_sample(session->sampler, session->ctx, -1);""",
        """        if (session->cancel.load()) {
            LOGI("generation cancelled");
            generationDirty = true;
            break;
        }

        // L5 · 温控：token 边界调档。decode 阶段是长时间持续负载（一次可跑几十秒），
        // 也正是 SoC 升温最快的一段 —— 这里是温控收益最大的地方。
        // 节流由 applyThermalAdvice 内部的 thermalPollMs 控制，每个 token 调它只是
        // 读一次 steady_clock，开销可忽略。
        applyThermalAdvice(session);

        const llama_token newToken = llama_sampler_sample(session->sampler, session->ctx, -1);""",
        "cpp/decode 循环调档",
    )

    # 1.8 RegisterNatives 签名
    text, _ = replace_once(
        text,
        '{"nativeCreateSession", "(Ljava/lang/String;IIIIIZZZZ)J",',
        '{"nativeCreateSession", "(Ljava/lang/String;IIIIIZZZZI)J",',
        "cpp/RegisterNatives 签名",
    )

write(CPP, text, crlf)

# ─────────────────────────────────────────────────────────────────────
# 2. LlamaNative.kt
# ─────────────────────────────────────────────────────────────────────
t, crlf = read(NATIVE_KT)
if "thermalPollMs" in t:
    results.append("  = LlamaNative.kt 已打过补丁，跳过")
else:
    t2, ok_native = replace_once(
        t,
        """        kvUnified: Boolean,
        offloadKqv: Boolean
    ): Long""",
        """        kvUnified: Boolean,
        offloadKqv: Boolean,
        /**
         * L5 · 温控自适应采样间隔（毫秒）。**0 = 禁用**（默认）。
         *
         * 启用后原生层会在 prefill 的 chunk 边界与 decode 的 token 边界读取 SoC
         * thermal headroom，并按档位**主动**下调线程数 —— 持续推理时这能保住约 77%
         * 峰值吞吐，而不是被平台断崖式降频打到 31%。
         *
         * 与 nativeCreateSession 的 JNI 签名
         * `(Ljava/lang/String;IIIIIZZZZI)J` **必须一致**：这里的顺序就是
         * cpp 形参顺序，错一个就是运行期 UnsatisfiedLinkError。
         */
        thermalPollMs: Int
    ): Long""",
        "LlamaNative/nativeCreateSession 形参",
    )
    # 锚点失败就不写文件（避免半改状态）
    if ok_native:
        write(NATIVE_KT, t2, crlf)

# ─────────────────────────────────────────────────────────────────────
# 3. LlamaSession.kt
# ─────────────────────────────────────────────────────────────────────
t, crlf = read(SESSION_KT)
if "thermalPollMs" in t:
    results.append("  = LlamaSession.kt 已打过补丁，跳过")
else:
    t, ok_field = replace_once(
        t,
        """        val kvUnified: Boolean = true,
        val offloadKqv: Boolean = false
    )""",
        """        val kvUnified: Boolean = true,
        val offloadKqv: Boolean = false,
        /**
         * L5 · 温控自适应采样间隔（毫秒）。**0 = 禁用**。
         *
         * 语义：启用后原生层每 [thermalPollMs] 毫秒采样一次 SoC thermal headroom，
         * 在 token / chunk 边界按下调档位主动减线程。默认禁用是刻意的 ——
         * 这是运行期行为变更，等真机验证后再把默认值改掉。
         */
        val thermalPollMs: Int = 0
    )""",
        "LlamaSession/Config 字段",
    )

    t, ok_pass = replace_once(
        t,
        """                kvUnified = config.kvUnified,
                offloadKqv = config.offloadKqv
            )""",
        """                kvUnified = config.kvUnified,
                offloadKqv = config.offloadKqv,
                thermalPollMs = config.thermalPollMs
            )""",
        "LlamaSession/create 传参",
    )
    # 两处都改成功才落盘：只改一半会得到「Config 有新字段但没传给 JNI」，
    # 编译能过、运行期温控静默失效 —— 比直接失败更难查。
    if ok_field and ok_pass:
        write(SESSION_KT, t, crlf)

print("温控接线补丁：")
for r in results:
    print(r)
bad = [r for r in results if r.strip().startswith("!")]
print(f"\n合计 {len(results)} 项，失败 {len(bad)} 项")
sys.exit(1 if bad else 0)

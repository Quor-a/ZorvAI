# 本地 LLM 功能完整性与逻辑对账报告（QuroAI vs PocketPal）

**日期**：2026-08-03
**场景**：调试复盘 / 功能完整性 & 逻辑对账
**参与成员**：排障手（调查员，本环境团队编排不可用，由主理人直干并汇编）

---

## 📌 TL;DR（执行摘要）

- **我之前说错了什么**：上一轮我说"和 PocketPal 整条链闭合 / 对的上"是**过头且未经证实的**——当时我只读了 STUB 实现 + `nativeCreateSession`，**真实实现块（`nativeApplyChatTemplate`/`nativeGenerateStream`/`nativeSetSamplingParams`）和 PocketPal 的 ModelStore 全文都没读**。这次我把两边真实代码整条读完了。
- **生成链路代码本身是对的**：我们 native 的 prefill/解码/去码/增量回调循环，以及 Kotlin 层的温度、`addAssistant=true`、流式、`GGUF` 预检、`release()`，都是**结构正确**的。用户说"逻辑也不对"——在**生成路径**上我找不到硬逻辑 bug。
- **用户说"功能不完整"——这是对的，而且是大幅不完整**：我们没有"模型加载"这个一等公民功能（无独立模型页、无 load/unload、无激活指示、无聊天门禁），且**每条消息都重新加载 GGUF + 重建 context + 重建 sampler**（无持久化会话），MNN 温度未接、工具调用未接、无 token 计量、无自动卸载、无多模态/基准测试。
- **"一直进行中但不出字"仍是运行时问题，不是代码缺功能**：代码路径正确，所以根因在设备侧（模型文件未真正落盘 / 原生层在设备上死循环或崩）。**没有 `Download/QuroAI_logs/` 的 tombstone + LocalEngine 诊断日志，我无法定责**——这是我的盲区，不是我能拍脑袋下结论的。
- 阻塞项数量：**0 个代码级硬阻塞**；**1 个证据阻塞**（缺设备日志）；**N 个功能缺口**（见 §3）。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟡 条件通过（生成代码可工作；功能完整性严重缺失，需补模型管理架构） |
| 严重度分布 | 🔴 0（无崩溃级逻辑 bug） / 🟠 1（无"已加载模型"生命周期，属架构缺陷）/ 🟡 7（功能缺口清单）/ 🟢 已修复（流式 #1112、addAssistant #1112、GGUF 预检） |
| 关键行动项 | 9 条（见行动清单） |
| 建议负责人 | 主程序工程（本地引擎 + UI 模型管理页） |

---

## 0. 我之前到底错在哪（先认错，再对账）

上一轮我口头的"和 PocketPal 逐行等价、整条链闭合"是**空话**，因为：

1. 只读了 `llama_jni_stub.cpp` 的 **STUB 块（207–370）** 和 `nativeCreateSession` 的 REAL 头几行；
2. **REAL 实现块（786–1529）里的 `nativeApplyChatTemplate`(1095)、`nativeGenerateStream`(1266)、`nativeSetSamplingParams`(972) 根本没读**；
3. **PocketPal 的 `ModelStore.ts`（2230 行）全文没读**，只凭截图脑补了"它有模型页"。

这一轮我把上述全部读完了，下面是对账结果。结论和我之前吹的相反：**生成代码没问题，但"模型加载"这件事在我们这里根本不存在（只有懒加载），这就是用户说的"模型加载没有这个功能"的真相。**

---

## 1. 我们真实代码链（本次整条读完）逐文件结论

### 1.1 Native 真实实现块 `llama_jni_stub.cpp:786–1529`（OPERIT_HAS_LLAMA_CPP=1 编入）
- `nativeCreateSession`(801)：`ensureBackendInit` → `llama_model_load_from_file` → `initializeChatTemplatesForSession` → `llama_init_from_model`(n_seq_max=1, n_threads, flash_attn, kv_unified, offload_kqv) → `rebuildSamplerForSession` → 返回 ptr。**结构正确**。
- `nativeSetSamplingParams`(971)：写入 temp/topP/topK/penalty → `rebuildSamplerForSession`。⚠️ 第 998 行 `seed = std::rand()` **每次调用都重新随机播种**（见 §4-B）。
- `nativeApplyChatTemplate`(1095)：`buildChatMessages` → `common_chat_templates_apply(use_jinja=true)`。**正确**。
- `nativeGenerateStream`(1266)：
  - 第 1275–1284：**每次生成前清空 KV + reset sampler**（见 §4-A）；
  - 第 1363–1404：**显式 chunked prefill**，手动设 `pos=i`/`seq_id`，不依赖 `llama_batch_validate` 默认值——注释明说"matches PocketPal's chunk-prefill范式"，且修掉了 KV 错位导致首 token 直接 EOG 的坑；
  - 第 1423–1523：sample→accept→EOG 检查→去码→算增量 delta→`onToken(delta)`→decode 下一 token。**循环结构正确**。
- `nativeReleaseSession`(923) / `nativeCancel`(951) / `nativeCountTokens`(960)：均正确。

### 1.2 Kotlin 引擎 `QuroLocalEngineNative.kt`（full 风味）
- `runLlama`(183)：`resolveLlamaModelFile` → `precheckLlamaFile`(GGUF 魔术字拦截脏文件) → 按实际负载算 `nCtx=((needed+1023)/1024*1024).coerceIn(2048,8192)`、`nThreads=coerceIn(2,8)` → `LlamaSession.create` → `setSamplingParams(temperature, topP=0.9, topK=40, repPenalty=1.1)` → `applyChatTemplate(roles, contents, addAssistant=true)` → `generateStream(prompt, effMaxTokens){ sb.append; cb(sb.toString()); true }` → **finally `session.release()`**。
- **关键确认**：温度**已接入**（llama 侧）；`addAssistant=true` 是 #1112 修复；流式已接；GGUF 预检已接；会话**每轮 release**。**生成路径无硬 bug**。
- `runMnn`(79)：`resolveMnnDir` → 校验 `llm_config.json` → `MNNLlmSession.create` → `generateStream`。**MNN 温度未接**（注释 #25 自承；`MNNLlmSession.create` 不收温度）。

### 1.3 上游调度 `QuroAssistant.kt`
- `ask`(119)：local 走 `routeLocal`，流式占位"⏳ 正在加载本地模型并处理上下文…"(127) → `routeLocal(..., emitStreamToken)`。
- `emitStreamToken`(93)：累计文本增量上屏，节流 100ms。**STREAMFIX #1112 已生效，本地确实流式**。
- `routeLocal`(348)：`repo.loadAll()` → 按 provider+path 找模型 → `resolveLocalEngine().run(...)`。`resolveLocalEngine`(433) 每次**反射 new 一个引擎实例**（引擎无状态，OK）。
- `compactForLocal`(389)：system≤4000 字、总量≤9000 字、保头丢尾。**上下文裁剪正确**。

### 1.4 UI `ChatScreen.kt`
- `__local__` 分支(978)：仅 `copy(provider=lm.type.name, localModelPath=lm.path, model=...)`，**无任何 load/unload 按钮、无激活指示、无"请先激活模型"门禁**。选了就用，发送时才懒加载。

### 1.5 全局确认（grep `app/src`）
- `LlamaSession` / `MNNLlmSession` **只**出现在 `QuroLocalEngineNative.kt`（create 231/103，release 309/139）。**没有任何单例/会话池持有会话** → 每条消息都走 create→generate→release 全流程。

---

## 2. PocketPal 真实架构（本次读了它的 ModelStore/推理链）

来源：a-ghorbani/pocketpal-ai `src/store/ModelStore.ts`（2230 行）+ deepwiki 架构页。核心事实：

- **`ModelStore` 中央类**持有 `models: Model[]`、`activeModelId`、`context: LlamaContext | undefined`（**单一持久上下文**）、`isContextLoading`、`loadingModel`。
- **`initContext()` / `releaseContext()`**：把模型显式加载进内存 / 释放。加载后 `LlamaContext` **常驻并跨多轮聊天复用**——不是每条消息重建。
- **Auto-release 系统**：App 退后台自动 `releaseContext`，回前台按策略恢复。
- **持久化**（MobX + AsyncStorage）：模型集合、n_gpu_layers/n_context 等配置、autoRelease 偏好跨重启保留。
- **ModelsScreen + ModelCard**：每个模型卡有下载/加载/卸载按钮、激活绿点、加载状态。
- **聊天门禁**：聊天页用的是 `activeModel` 的 context，无激活模型时提示先加载。
- 另含：多模态（mmproj）、benchmark、工具/Talent 系统、token 计量。

**一句话对账**：PocketPal 把"模型"当**一等公民**管理（显式加载/卸载 + 常驻会话复用 + 门禁）；我们只有"注册路径 + 发送时懒加载"，**没有"已加载模型"这个概念本身**。这就是用户反复说的"模型加载没有这个功能"——他是对的，不是代码 bug，是**功能/架构根本没做**。

---

## 3. 具体「不完整」清单（缺的功能，带证据）

| # | 缺口 | 证据 | 影响 |
|---|------|------|------|
| 1 | **无独立模型管理页 / load-unload / 激活指示** | `QuroModelConfigScreen` 仅"注册路径"；`ChatScreen.kt:978` 仅存 provider/path | 用户无法主动加载/卸载，无"哪个模型在内存里"的可见状态 |
| 2 | **无持久化加载会话（每条消息重建 GGUF+context+sampler）** | grep 确认无单例；`QuroLocalEngineNative.kt:231` create / `:309` release 每轮执行 | 每次发消息重读 GGUF + 重建 context（手机上 5–60s 冷启动），且 KV 不跨轮复用（靠重喂全文历史弥补，慢） |
| 3 | **无聊天门禁（"请先激活模型"）** | `ChatScreen.kt:978` 无 gate | 选了路径但模型未就绪时直接进懒加载，用户体验=黑盒等待 |
| 4 | **MNN 温度未接入** | `QuroLocalEngineNative.kt:25` 自承；`MNNLlmSession.create` 不收温度 | MNN 模型采样温度恒为默认，用户调不了 |
| 5 | **工具/grammar 调用未接（尽管原生已支持）** | 原生 `nativeApplyStructuredChatTemplate`(1095)/`parseToolCallResponse`(1230) 已实现；但 `runLlama` 只用 `applyChatTemplate`（非 structured），从不调工具 | 本地模型无法用工具/function-calling |
| 6 | **无 token 计量 / 上下文窗口实时管理** | 原生 `nativeCountTokens`(960) + Kotlin `countTokens`(58) 已实现但**无调用方** | 无"已用/剩余 token"展示，长对话只能靠 `compactForLocal` 硬裁 |
| 7 | **无自动卸载 / 内存管理** | 无 `releaseContext` 等价物；会话仅随消息结束释放 | 无后台释放、无省内存策略 |
| 8 | **无多模态（mmproj）** | 引擎层无 vision 分支 | 本地视觉模型不可用 |
| 9 | **无基准测试 / 性能指标** | 无 | 用户看不到 tok/s、首 token 时延 |

---

## 4. 具体「逻辑不对」清单（真 bug vs 架构错误，分开）

### 4-A. 架构级"逻辑不对"（用户直觉的对的部分）
- **没有"已加载模型"生命周期**：`ChatScreen` 选中模型只是存路径，`QuroAssistant.ask`(127) 在发送协程里同步阻塞加载 GGUF 5–60s，期间只有一个占位文案。PocketPal 是先 `initContext` 显式加载、再聊天。这不是崩溃，但**设计上"模型加载"这个动作不存在**，所以用户觉得"没有这个功能"——他的判断成立。

### 4-B. 真实但轻微的代码级逻辑瑕疵（非"生成不出字"的根因）
- **`nativeSetSamplingParams:998` 每次调用 `seed = std::rand()`**：`runLlama` 每条消息调一次 → 每次回答随机种子，**不可复现**，且与 `nativeCreateSession:908` 的初始播种重复。不改也能跑，建议固定/可控种子。
- **`nativeGenerateStream:1275–1284` 每次生成清空 KV + reset sampler**：对"每轮重建会话"的当前架构是正确的（因为历史已重喂），但这是一颗**定时炸弹**——一旦将来做持久会话（§3-2），这里会把跨轮 KV 清掉，导致上下文丢失。应改为"仅在会话首次或显式 reset 时清空"。
- **`nativeGenerateStream:1474–1480` 增量 delta 计算**：当 `decodedNow` 不以 `prevDecoded` 为前缀时（分词器边界/多字节断字边缘情况），`delta = decodedNow` → **重复输出整段**。罕见但真实的正确性 bug。

### 4-C. 我**没有**找到的（澄清，避免再乱下结论）
- 生成主循环、**没有任何**会导致"永远不出字且死循环"的硬逻辑错误；`maxNew` 上限 `effMaxTokens≤1024`，循环必然有界终止。
- 因此"一直进行中但不出字"**不是生成代码的逻辑错**，而是**运行时/设备侧**问题（见 §5）。

---

## 5. 「一直进行中但不出字」运行时定责（仍需设备证据）

代码路径正确 → 这是**运行时**问题。三个假设，需设备日志才能定责：

1. **模型文件未真正落盘 / 不是合法 GGUF**：`precheckLlamaFile` 拦的是魔术字，但文件若半截/损坏仍可能过预检后在 `llama_model_load_from_file` 处**卡死或 abort**。
2. **原生层在设备上死循环/极慢**：3B@Q4 在弱 CPU 上 prefill+decode 可达数分钟；若每 token 数百 ms × 1024，就是"一直进行中"。**但只要流式通，首 token 后应有字**——"不出字"更指向下面第 3 种。
3. **原生层崩（tombstone）致协程挂起**：native crash 抓不到 Java 异常，`routeLocal` 的 `runCatching` 包不住 → 占位"⏳…"永远不被替换 = 用户眼里的"一直进行中但不出字"。

**我需要但拿不到的证据**：`Download/QuroAI_logs/` 下的 tombstone + `LocalEngine` 诊断日志（含 `▶ run start` / `▶ llama createSession` / `▶ llama generateStream start` / `· llama first token @Xms` 等打点）。用户此前未上传，我无法替他 adb 取。

> ⚠️ 我不会在没有上述日志的情况下再赌"是同款 GGUF 在它那正常所以咱也正常"——上一轮这种话就是空话。

---

## ✅ 行动清单（按优先级）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | **建独立模型管理页**（列表 / 显式 load / unload / 激活绿点 / 加载态），把"模型"做成一等公民 | UI + 引擎 | P0 | 补架构缺口 #1/#3 |
| 2 | **做持久化已加载会话**：加载一次后常驻 `LlamaContext` 复用，跨轮不重建 GGUF；配套 **auto-release**（退后台释放、回前台恢复） | 引擎 | P0 | 补 #2/#7，性能质变 |
| 3 | **聊天门禁**：无激活模型时提示"请先加载模型"，不发懒加载 | UI | P1 | 补 #3 |
| 4 | **接 MNN 温度**：`MNNLlmSession.create` 增加 temperature 入参并下传原生 | 引擎 | P1 | 补 #4 |
| 5 | **接工具/grammar**：`runLlama` 改走 `applyStructuredChatTemplate` + `parseToolCallResponse`，启用本地 function-calling | 引擎 | P2 | 补 #5 |
| 6 | **接 token 计量 + 上下文窗口实时展示**（复用已有 `countTokens`） | 引擎 + UI | P2 | 补 #6 |
| 7 | **修 §4-B 三处瑕疵**：固定采样种子、KV 清空改为会话级可控、增量 delta 前缀不一致时防重复 | 引擎(native) | P2 | 修逻辑瑕疵 |
| 8 | **要设备日志定责**：让用户从手机 `Download/QuroAI_logs/` 取 tombstone + LocalEngine 日志发我，再定"不出字"根因 | 用户 + 排障手 | P0（阻塞诊断） | 解 §5 |
| 9 | 多模态(mmproj) / 基准测试 → 列入后续版本，本次不阻塞 | — | P3 | 补 #8/#9 |

---

## ⚠️ 待完善 / 已知局限

- **证据阻塞**：缺设备侧 `Download/QuroAI_logs/` 日志，§5 无法定责。
- **PocketPal 对比深度**：我读了它的 `ModelStore` 架构与推理生命周期，但未逐行读其 `completion`/AgentRunner 的 token 循环（其原生走 `llama.rn` 而非 C++ 直写，逐行对齐意义有限）；架构级结论已足够支撑本对账。
- **原生层未编译验证**：仅静态读码，未重新出包跑真机；"生成路径正确"是读码结论，最终以 #8 的设备日志为准。

---

## 📚 成员产出索引

- 排障手（调查员）原始产出：本次整链读码结论，见本文件 §1–§5（本环境团队编排工具不可用，由主理人直干并落盘，未另起子 agent）。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
> 特别说明：上一轮"整条链闭合/对的上"为未经证实的口头结论，本次已整链重读并更正。

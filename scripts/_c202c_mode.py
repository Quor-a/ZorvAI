import io, os

p = 'app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

def sub(old, new, tag):
    global src
    assert src.count(old) == 1, 'anchor %s count=%d' % (tag, src.count(old))
    src = src.replace(old, new, 1)

# ═══════════════ 1) 注入点注释：说明默认已改为备用 ═══════════════
sub(
"""        // ══════════════ 系统提示词 RAG：按本轮意图追加「进阶段规则」 ══════════════
        // 旧架构一行未删：render() 无论开关如何都先拼完整基座，
        // RAG 只决定「额外的进阶段段落要不要跟」。FULL_ALWAYS 时进阶段全量跟上，
        // SELECTIVE 时按 userQuery 检索，挑不出就一个不加（宁缺毋滥——
        // 塞一堆当前无关的规则只会稀释注意力，让模型漏掉真正该守的那条）。""",
"""        // ══════════════ 系统提示词 RAG：**备用方案**，按本轮意图追加「进阶段规则」══
        // 🔴 2026-10-08 默认从 FULL_ALWAYS 改成 SELECTIVE（实测依据）：
        // FULL_ALWAYS 实测每轮追加 **3182 字符（≈2100 tokens）**，其中
        // cluster_orchestration 一段独占 1416 字 —— 那就是「把 RAG 当主要方案」，
        // 用户明确要求「改成 AI 被动使用，RAG 是备用方案不是主要方案」。
        //
        // 现在的主路径是**AI 自己**调 rag_search(domain="prompts") 主动取规则，
        // 每轮只常驻两块真正必须守的基础段（工具调用纪律 + 输出格式纪律，见
        // PromptRagIndex 的 alwaysSticky）。FULL_ALWAYS 仍可通过
        // SharedPreferences("quro_rag", "prompt_rag_mode"="FULL_ALWAYS") 显式打开。
        //
        // 旧架构一行未删：render() 无论开关如何都先拼完整基座。""",
'inject-comment')

# ═══════════════ 2) promptRagMode()：默认改为 SELECTIVE ═══════════════
sub(
"""    /**
     * 系统提示词 RAG 注入模式。
     *
     * 默认 [PromptRagIndex.Mode.FULL_ALWAYS]：与改动前**逐字一致**，
     * 这样即便选段逻辑出任何问题，提示词也只是多了几段而不会缺段。
     * 用户主动切到 SELECTIVE 才享受「只注入相关段落」的省 token 收益。
     */
    private fun promptRagMode(): PromptRagIndex.Mode {
        val raw = runCatching {
            appContext.getSharedPreferences("quro_rag", Context.MODE_PRIVATE)
                .getString("prompt_rag_mode", null)
        }.getOrNull()
        return if (raw == PromptRagIndex.Mode.SELECTIVE.name) {
            PromptRagIndex.Mode.SELECTIVE
        } else {
            PromptRagIndex.Mode.FULL_ALWAYS
        }
    }""",
"""    /**
     * 系统提示词 RAG 注入模式。**默认 [PromptRagIndex.Mode.SELECTIVE]（备用方案）**。
     *
     * ## 🔴 为什么默认从 FULL_ALWAYS 改成 SELECTIVE（2026-10-08）
     *
     * 用户要求「改成 AI 被动使用，系统提示词 RAG 是**备用方案不是主要方案**」。
     * FULL_ALWAYS 实测每轮追加 3182 字符（≈2100 tokens）——
     * 相当于每轮无条件给模型灌一份规则手册，而规则里真正当前相关的可能只有一段。
     * 这不只是 token 浪费，更是**注意力被稀释**：
     * 模型在一堆当前无关的规则里，容易漏掉真正该守的那条。
     *
     * 改后每轮只常驻两块基础段（工具调用纪律 + 输出格式纪律），
     * 其余规则由 **AI 自己**调 `rag_search(domain="prompts")` 主动取——
     * 检索从「宿主塞给 AI」变成「AI 按需拉」，这才是被动使用。
     *
     * 显式切回全量：写入 SharedPreferences("quro_rag") 的键 `prompt_rag_mode` = `FULL_ALWAYS`。
     */
    private fun promptRagMode(): PromptRagIndex.Mode {
        val raw = runCatching {
            appContext.getSharedPreferences("quro_rag", Context.MODE_PRIVATE)
                .getString("prompt_rag_mode", null)
        }.getOrNull()
        return if (raw == PromptRagIndex.Mode.FULL_ALWAYS.name) {
            PromptRagIndex.Mode.FULL_ALWAYS
        } else {
            PromptRagIndex.Mode.SELECTIVE
        }
    }""",
'mode-default')

assert src != orig
tmp = p + '.tmp'
io.open(tmp, 'w', encoding='utf-8', newline='').write(src)
os.replace(tmp, p)
print('OK  bytes %d -> %d' % (len(orig), len(src)))
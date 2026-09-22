package com.ai.assistance.quro.genui.aiapp.data

/**
 * 渲染通道 —— 一次生成最终落到画布上，走的是哪条管线。
 *
 * 这同时就是历史记录里的「渲染类型」：回放一条作品时必须知道它当初是谁生成的，
 * 否则 A2UI 的扁平 JSON 会被当成 GenUI DSL 解析（或者反过来），回放必崩。
 *
 * 四条通道的边界：
 * - [GENUI]    GenUI SDK 原生 DSL（` ```genui ` + `UISpec`），组件与交互最全，默认主力
 * - [A2UI]     扁平邻接表 JSON（` ```a2ui `），结构简单、省 token
 * - [MARKDOWN] Markwon 原生渲染（` ```markdown `），纯文章/长文
 * - [HTML]     内联 WebView（` ```html `），独立网页/小游戏
 */
enum class RenderChannel(
    /** 落库与传参用的稳定键（不要改，改了老数据就认不出来） */
    val key: String,
    /** 界面展示名 */
    val label: String,
    /** 一句话说明（询问弹窗里给用户看） */
    val desc: String
) {
    GENUI("genui", "GenUI SDK", "原生 DSL · 组件/交互最全，默认主力"),
    A2UI("a2ui", "A2UI", "扁平 JSON · 省 token · 结构化展示"),
    MARKDOWN("markdown", "Markdown", "纯文章 / 攻略 / 长文"),
    HTML("html", "HTML", "独立网页 / 复杂样式 / 小游戏");

    /** 询问弹窗里的选项文案：既要用户看得懂，又要能被 [parse] 原样反解回通道 */
    val option: String get() = "$label · $desc"

    companion object {
        /** 兜底通道：用户取消询问 / 无法判定时用 */
        val DEFAULT = GENUI

        /** 严格按键取 */
        fun fromKey(k: String?): RenderChannel? {
            if (k.isNullOrBlank()) return null
            val s = k.trim().lowercase()
            return values().firstOrNull { it.key == s }
        }

        /**
         * 从自由文本里认通道：用户点名、询问弹窗的选项文案、历史里的旧标签都走这里。
         *
         * 两个坑：
         * ① `genui` 必须排在 `a2ui` 之前判断，且二者互不包含（"a2ui" 里没有 "genui"），
         *    所以纯 a2ui 的文本不会被 GENUI 分支吃掉；
         * ② 一句话里可能同时提到两条通道 ——「别用 genui，改用 a2ui」。
         *    直接按顺序取第一个会选到被否掉的那条，所以先扫否定词把被排除的通道踢出去。
         */
        fun parse(text: String?): RenderChannel? {
            if (text.isNullOrBlank()) return null
            val s = text.trim().lowercase()

            // 「别用 genui」「不要 markdown」「不用 html」→ 这些通道出局
            val negated = Regex("(别用|不要用|不要|不用|禁止用)\\s*([a-z0-9 ]+)")
                .findAll(s)
                .mapNotNull { nameToChannel(it.groupValues[2]) }
                .toSet()

            val candidates = listOfNotNull(
                nameToChannel(s),
                // nameToChannel 只返回一个，这里补齐其余命中项，供否定过滤后回退选取
                if (s.contains("a2ui")) A2UI else null,
                if (s.contains("markdown") || Regex("(^|[^a-z])md([^a-z]|$)").containsMatchIn(s)) MARKDOWN else null,
                if (s.contains("html") || s.contains("网页") || s.contains("小游戏")) HTML else null,
                if (s.contains("genui") || s.contains("生成式界面") || s.contains("原生 dsl")) GENUI else null
            ).distinct()
            if (candidates.isEmpty()) return null

            // 优先选没被否掉的那条；全被否掉时退回第一条（总比返回 null 让上层瞎猜好）
            return candidates.firstOrNull { it !in negated } ?: candidates.first()
        }

        /** 单个片段 → 通道（不做否定处理，供 [parse] 内部复用） */
        private fun nameToChannel(s: String): RenderChannel? = when {
            s.contains("genui") || s.contains("生成式界面") || s.contains("原生 dsl") -> GENUI
            s.contains("a2ui") -> A2UI
            s.contains("markdown") || Regex("(^|[^a-z])md([^a-z]|$)").containsMatchIn(s) -> MARKDOWN
            s.contains("html") || s.contains("网页") || s.contains("小游戏") -> HTML
            else -> null
        }

        /**
         * 老数据的兼容推断：早期作品只存了 payload 没存渲染类型，
         * 直接看存下的原文长什么样（围栏头 / 顶层键 / 首字符）来还原。
         * 新数据一律显式落盘，不走这里。
         */
        fun infer(json: String?): RenderChannel {
            val t = json?.trim().orEmpty()
            if (t.isEmpty()) return DEFAULT
            return when {
                t.startsWith("```html") -> HTML
                t.startsWith("```markdown") || t.startsWith("```md") -> MARKDOWN
                t.startsWith("```a2ui") -> A2UI
                t.startsWith("```genui") -> GENUI
                t.startsWith("```json") -> if (looksLikeGenUiDsl(t)) GENUI else A2UI
                t.startsWith("{") || t.startsWith("[") -> if (looksLikeGenUiDsl(t)) GENUI else A2UI
                t.contains("<html") || t.contains("<!DOCTYPE") -> HTML
                t.startsWith("#") -> MARKDOWN
                else -> DEFAULT
            }
        }

        /**
         * 裸 JSON 是 GenUI DSL 还是 A2UI 扁平表？
         *
         * ⚠️ 不能简单 `contains("\"root\"")`：A2UI 的扁平邻接表里 root 是**值**
         * （`"id":"root"`），GenUI DSL 里 root 才是**键**（`"root":{…}`）。
         * 只判字符串会把每一条 A2UI 都认成 GenUI —— 回放就走错管线了。
         */
        private fun looksLikeGenUiDsl(t: String): Boolean =
            t.contains("\"properties\"") || Regex("\"root\"\\s*:").containsMatchIn(t)
    }
}

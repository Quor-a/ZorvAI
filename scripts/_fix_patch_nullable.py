import io, os, sys

def patch(path, pairs):
    s = io.open(path, encoding="utf-8").read()
    for old, new in pairs:
        n = s.count(old)
        if n != 1:
            sys.stderr.write("锚点不唯一(%d): %r\n---\n%s\n" % (n, old[:90], s[:0]))
            raise SystemExit("ABORT at %s" % path)
        s = s.replace(old, new, 1)
    tmp = path + ".tmp"
    io.open(tmp, "w", encoding="utf-8", newline="\n").write(s)
    os.replace(tmp, path)
    print("OK", path)

CP = "app/src/main/java/com/ai/assistance/quro/core/cards/CardPatch.kt"
patch(CP, [
    # card 改可空：占位卡方案有漏洞（填真实卡可能被误渲染），从类型上杜绝
    ("""    data class Result(
        /** 生效后的卡片；失败或无补丁时是**原卡引用**（未新建）。 */
        val card: QuroChatCard,""",
     """    data class Result(
        /**
         * 生效后的卡片。
         *
         * 刻意**可空**：唯一为 null 的情形是「按 id 没找到这张卡」。
         * 早先用一张占位卡顶上，但那样调用方一个不留神就会把占位卡渲染出去 ——
         * 可空让这个 misuse 在编译期就不成立。找不到时看 [errors]。
         */
        val card: QuroChatCard?,"""),

    ("""        val ok: Boolean get() = errors.isEmpty()
""",
     """        val ok: Boolean get() = errors.isEmpty()

        /**
         * 改动后的卡片，失败时抛。
         *
         * 给「已经确认卡存在、只想拿新卡」的调用方用，省一次判空；
         * 不确定时读 [card] 与 [changed]。
         */
        val newCard: QuroChatCard get() = card ?: error("补丁未生效（card 为 null）：" + errors.joinToString("; "))
"""),

    # feedback：card 可空后，合法路径清单在 null 时不能列
    ("""            val legal = CardPatch.describe(card).take(40)""",
     """            val legal = card?.let { CardPatch.describe(it) }.orEmpty().take(40)"""),

    ("""            if (ok && !changed) return "card_patch：卡片 $cardId 无改动（补丁为空）\"""",
     """            if (ok && !changed) return "card_patch：卡片 $cardId 无改动（补丁为空）\""""),
])

QC = "app/src/main/java/com/ai/assistance/quro/core/cards/QuroChatCard.kt"
patch(QC, [
    ("""        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) {
            return CardPatch.Result(
                card = NoCardPlaceholder,
                changed = false,
                applied = emptyList(),
                errors = listOf("卡片 id=$cardId 不在卡片栏里（可能已在底部栏外渲染，或 id 写错）")
            )
        }
        val r = CardPatch.apply(_cards[idx], spec)
        if (r.changed) _cards[idx] = r.card
        return r
    }""",
     """        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) {
            return CardPatch.Result(
                card = null,
                changed = false,
                applied = emptyList(),
                errors = listOf("卡片 id=$cardId 不在卡片栏里（可能已在气泡内渲染，或 id 写错）")
            )
        }
        val r = CardPatch.apply(_cards[idx], spec)
        if (r.changed && r.card != null) _cards[idx] = r.card!!
        return r
    }"""),

    # 删掉占位卡（可空方案后不需要了）
    ("""    fun newId(): String = "card_" + UUID.randomUUID().toString().take(8)

    /**
     * 「卡片不存在」时 [CardPatch.Result.card] 的占位。
     *
     * [CardPatch.Result.card] 不可空是为了让成功路径的调用方不必判空，
     * 但失败路径总要有个值可填 —— 拿一张真实的占位卡顶上，
     * 免得填一个全局 dummy 让调用方不小心把它渲染出去。
     */
    private val NoCardPlaceholder: QuroChatCard = NoteCard(
        id = "card_missing", title = "", body = "", lang = null
    )
}""",
     """    fun newId(): String = "card_" + UUID.randomUUID().toString().take(8)
}"""),
])

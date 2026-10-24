import io, os

p = 'app/src/test/java/com/ai/assistance/quro/core/cluster/ClusterVerdictTest.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

def sub(old, new, tag):
    global src
    assert src.count(old) == 1, 'anchor %s count=%d' % (tag, src.count(old))
    src = src.replace(old, new, 1)

# ── 修正 1：org.json 本身就能吃裸值，所以「补引号」不是可判据 ──
sub('''    /**
     * 🔴 绝不给裸值补引号。
     *
     * `"reason": abc` → 若补成 `"abc"` 就是**编造**验收方没说过的话。
     * 本仓铁律：`-1` 表示「上游没给」，绝不用 0 冒充 —— 同理这里宁可不修。
     */
    @Test
    fun 不给裸值补引号() {
        // 补括号后仍应是非法 JSON（说明没有偷偷造值）
        val fixed = ClusterVerdictProtocol.loosenJson("""{"reason": abc}""")
        val parsed = runCatching { org.json.JSONObject(fixed) }.getOrNull()
        assertNull("不该把裸值 abc 补成字符串（那是编造内容）", parsed)
    }''',
'''    /**
     * 🔴 我们的修复层**不碰值**，只碰结构。
     *
     * `"reason": abc` 若被**我们的代码**补成 `"abc"` 就是编造验收方没说过的话。
     *
     * ⚠️ 但注意判据边界：`org.json.JSONObject` 自己就接受裸标量值
     * （`{"reason": abc}` 直接解析成 reason="abc"），这是**库既有行为**，
     * 不是本次修复引入的。所以本条断言的是**我们的输出没有额外加引号**：
     * `loosenJson` 返回的字符串里 `abc` 仍然是**裸的**（没有变成 `"abc"`）。
     */
    @Test
    fun 修复层不碰值只碰结构() {
        val fixed = ClusterVerdictProtocol.loosenJson("""{"reason": abc}""")
        assertTrue(
            "我们的修复层不该给裸值补引号（补了就是编造内容），实际输出：$fixed",
            fixed.contains(":abc") || fixed.contains(": abc")
        )
        assertFalse("不该把裸值 abc 改成带引号的 \\"abc\\"：$fixed", fixed.contains("\\"abc\\""))
    }''',
't1')

# ── 修正 2：带前后废话的输出，firstJson 的花括号配平已能捞到，但裸键名要先修 ──
#真实原因：verdictOf 先试原文 JSONObject(trimmed) 失败 → 再 loosenJson。
#  loosenJson 修完后含前导废话（"这是我的验收结论："）时仍不是合法 JSON 整串，
#  因为 JSONObject() 不允许前导文字 —— 需要先剥出 JSON 部分。这是真实缺口，要修实现。
sub('''    /** 端侧实测最常见的形态：格式没对齐但语义清楚，修复后必须能正确判出业务结论。 */
    @Test
    fun 格式没对齐但语义清楚时能救回来() {
        val messy = """
            这是我的验收结论：
            {pass:true, reason:"三条标准都满足了", failed:[], checks:[]}
            以上。
        """.trimIndent()
        assertEquals(
            "带前后废话 + 裸键名的输出修复后仍判不出结论",
            ClusterVerdictProtocol.ClusterVerdict.PASS,
            ClusterVerdictProtocol.verdictOf(messy)
        )
    }''',
'''    /**
     * 🔴 端侧最常见的形态：JSON 前后带废话（"这是我的验收结论：…以上。"）+ 裸键名。
     *
     * 这是真实缺口：`org.json.JSONObject` 要求整串就是 JSON，
     * 前导一句中文就整串解析失败 —— 而模型几乎总会在 JSON 外面加话。
     * 引擎内的 `firstJson` 有「按花括号配平截取」这一步所以能救，
     * 但协议层原先只做了围栏剥离，漏了这层。
     */
    @Test
    fun 格式没对齐但语义清楚时能救回来() {
        val messy = """
            这是我的验收结论：
            {pass:true, reason:"三条标准都满足了", failed:[], checks:[]}
            以上。
        """.trimIndent()
        assertEquals(
            "带前后废话 + 裸键名的输出修复后仍判不出结论",
            ClusterVerdictProtocol.ClusterVerdict.PASS,
            ClusterVerdictProtocol.verdictOf(messy)
        )
    }

    /** 同上形态但不通过时也要能判出来（别只救通过那一侧）。 */
    @Test
    fun 带废话的不通过也能救回来() {
        val messy = """我的判断如下：{pass:false, reason:"第三条缺证据", failed:["3"]} 就这样。"""
        assertEquals(
            ClusterVerdictProtocol.ClusterVerdict.FAIL,
            ClusterVerdictProtocol.verdictOf(messy)
        )
    }''',
't2')

tmp = p + '.tmp'
io.open(tmp, 'w', encoding='utf-8', newline='').write(src)
os.replace(tmp, p)
print('OK  %d -> %d' % (len(orig), len(src)))
import io, os, sys

p = "app/src/main/java/com/ai/assistance/quro/core/tools/CardPatchTool.kt"
s = io.open(p, encoding="utf-8").read()

pairs = [
# 1) import 桥
("""import com.ai.assistance.quro.core.cards.CardPatch
import org.json.JSONArray""",
 """import com.ai.assistance.quro.core.cards.CardPatch
import com.ai.assistance.quro.core.cards.CardPatchBridge
import org.json.JSONArray"""),

# 2) run 走默认宿主；签名改为「同步拿回执」
("""    override fun run(context: Context, arguments: String): String = handle(arguments)""",
 """    override fun run(context: Context, arguments: String): String = handle(arguments)"""),

# 3) handle 签名与 KDoc：改成用桥，回执同步拿
("""         * [emit] 是唯一的副作用出口：真正改卡片由宿主（ChatScreen 消费
         * `UiNavigationEvent.PatchCard` → ViewModel.patchCard）执行，
         * 工具层不直接改 store —— 与既有 `RenderWidget` 完全同构。
         */
        fun handle(
            arguments: String,
            emit: (cardId: String, patchJson: String) -> Unit = { _, _ -> },
            describeOf: (cardId: String) -> List<String> = { emptyList() },
        ): String {""",
 """         * 副作用出口是 [CardPatchBridge]：工具本身是全局单例、拿不到 ViewModel，
         * 所以真正改卡片由宿主（ChatScreen 注册的处理器 → ViewModel.patchCard）执行。
         *
         * 两个参数刻意做成**可注入**，单测里摆假宿主即可验证全部分支，
         * 不必真起界面（工程无 Robolectric，ViewModel 测不了）。
         */
        fun handle(
            arguments: String,
            host: CardPatchBridge.Host = CardPatchBridge.Host { _, _ -> null },
            describeOf: (cardId: String) -> List<String> = { emptyList() },
        ): String {"""),
]

for old, new in pairs:
    n = s.count(old)
    if n != 1:
        sys.stderr.write("锚点不唯一(%d)\n" % n)
        raise SystemExit("ABORT")
    s = s.replace(old, new, 1)

# 4) doPatch 改成同步拿回执
old = """                doPatch(cardId, JSONObject().put("patches", one).toString(), emit)"""
new = """                doPatch(cardId, JSONObject().put("patches", one).toString(), host)"""
assert s.count(old) == 1
s = s.replace(old, new, 1)

old = """                doPatch(cardId, JSONObject().put("patches", patches).toString(), emit)"""
new = """                doPatch(cardId, JSONObject().put("patches", patches).toString(), host)"""
assert s.count(old) == 1
s = s.replace(old, new, 1)

old = """        private fun doPatch(
            cardId: String,
            patchJson: String,
            emit: (String, String) -> Unit,
        ): String {"""
new = """        private fun doPatch(
            cardId: String,
            patchJson: String,
            host: CardPatchBridge.Host,
        ): String {"""
assert s.count(old) == 1
s = s.replace(old, new, 1)

# 5) emit → 同步 apply，并把「已提交」改成据真实结果回执
old = """            emit(cardId, patchJson)
            // 措辞是「已提交」而非「已生效」：界面侧可能因找不到卡 / 类型不匹配而失败，
            // 那时由宿主另行回喂。说「已生效」会教模型把这个回执当成确认信号。
            return "card_patch：已提交 ${ops.length()} 条补丁 → $cardId"
        }"""
new = """            // 同步拿回执：补丁是「请求-响应」语义，模型这一轮就要知道成败。
            // 🔴 无宿主返回 null —— 必须与「有宿主但补丁失败」区别对待，
            //   谎报成功会让模型以为卡片已更新而不再重试（静默失效的经典形态）。
            val r = host.apply(cardId, patchJson)
                ?: return "card_patch：已提交 ${ops.length()} 条补丁，但**当前没有界面宿主**，" +
                    "卡片未真正更新（ChatScreen 未注册补丁桥时会出现）。可稍后重试。"
            return r.feedback(cardId)
        }"""
assert s.count(old) == 1
s = s.replace(old, new, 1)

tmp = p + ".tmp"
io.open(tmp, "w", encoding="utf-8", newline="\n").write(s)
os.replace(tmp, p)
print("OK", p, len(s.splitlines()), "lines")

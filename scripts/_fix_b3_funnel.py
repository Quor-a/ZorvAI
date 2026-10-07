# -*- coding: utf-8 -*-
"""第三批的 FunnelCard 与第一批同名 —— 改为扩展存量类而非新建。

存量（QuroChatCardEx.kt）：
    data class FunnelCard(id, title, steps: List<Step>)
    data class Step(name: String, value: Float, color: String = "")

第三批想要的增强：showRate / unit / hint，且 value 支持 Double。
做法：**给存量类加带默认值的可选字段**，老数据 JSON 缺这些字段时走默认值，解析照旧；
绝不能新建同名类（编译冲突），也不能改存量字段的类型（老存档会读不出来）。

同时把 Step.value 从 Float 放宽为 Double —— 这里必须谨慎：
Float -> Double 是**协变放宽**，反序列化时 JSON 数字两者都能读，
且 CardCodec.encode 写出的是数字字面量，parseCard 侧按 Double 读也能取到。
存量 Groovy 风格读取路径统一走 optDouble，不受影响。
"""
import io, os, sys

# ── 1) 扩展存量 FunnelCard ──
P1 = "app/src/main/java/com/ai/assistance/quro/core/cards/QuroChatCardEx.kt"
s1 = io.open(P1, encoding="utf-8").read()

OLD = '''/** 漏斗图：转化率分析。 */
data class FunnelCard(
    override val id: String,
    override val title: String,
    val steps: List<Step>,
) : QuroChatCard {
    data class Step(val name: String, val value: Float, val color: String = "")
}'''

NEW = '''/**
 * 漏斗图：转化率分析。
 *
 * v1400 第三批扩展（**只加带默认值的可选字段，不改存量字段名与顺序**）：
 *  - [showRate] 每级是否显示相对首级的留存率；
 *  - [unit] 数值单位（如「人」「元」）；
 *  - [Step.hint] 本级流失原因等补充说明。
 *
 * 老存档里的 `{"steps":[{"name":..,"value":..}]}` 三个新字段全走默认值，解析行为不变。
 */
data class FunnelCard(
    override val id: String,
    override val title: String,
    val steps: List<Step>,
    val showRate: Boolean = true,
    val unit: String = "",
) : QuroChatCard {
    /**
     * @param value 用 Double 而非 Float：第三批的样例会出现小数（如 4.1），
     *             Float 会静默截断成 4.1f 后参与运算出现精度毛刺。
     *             JSON 反序列化对数字字面量两种类型都能读，老数据不受影响。
     */
    data class Step(
        val name: String,
        val value: Double,
        val color: String = "",
        /** 流失原因等补充（v1400-b3 新增，缺省空串） */
        val hint: String = "",
    )
}'''

if s1.count(OLD) != 1:
    print("ABORT: 存量 FunnelCard 锚点命中 %d 次" % s1.count(OLD))
    sys.exit(1)
s1 = s1.replace(OLD, NEW, 1)
tmp = P1 + ".tmp"
with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
    f.write(s1)
os.replace(tmp, P1)
print("OK: 存量 FunnelCard 已扩展")

# ── 2) 从第三批删掉重复的 FunnelCard ──
P2 = "app/src/main/java/com/ai/assistance/quro/core/cards/QuroChatCardEx3.kt"
s2 = io.open(P2, encoding="utf-8").read()

start = s2.find("/**\n * 漏斗：转化**逐级流失**。")
if start < 0:
    print("ABORT: 第三批 FunnelCard 未找到")
    sys.exit(1)
end = s2.find("/**\n * 瀑布图：变化**归因**", start)
if end < 0:
    print("ABORT: 瀑布图注释未找到")
    sys.exit(1)
s2 = s2[:start] + s2[end:]
tmp = P2 + ".tmp"
with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
    f.write(s2)
os.replace(tmp, P2)
print("OK: 第三批重复 FunnelCard 已删除")
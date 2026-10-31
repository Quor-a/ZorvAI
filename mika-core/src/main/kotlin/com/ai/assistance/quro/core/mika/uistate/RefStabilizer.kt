package com.ai.assistance.quro.core.mika.uistate

import kotlin.math.hypot
import kotlin.math.min

/**
 * 给模型看的一屏:[elements] 的 ref 已换成模型编号,[snapshotId] 是这块屏的前缀加序号("p1"、"w2"…);
 * [port] 是端口原样的快照,[toPort] 是模型编号 → 端口编号。
 */
data class ModelSnapshot(
    val snapshotId: String,
    val port: ScreenSnapshot,
    val elements: List<UiElement>,
    val toPort: Map<String, String>,
) {
    /** 换成模型编号的快照;不可逆判定、坐标换算都用它。 */
    val screen: ScreenSnapshot by lazy {
        val refs = toPort.entries.associate { (model, original) -> original to model }
        port.copy(
            snapshotId = snapshotId,
            elements = elements,
            windows = port.windows.map { window -> window.copy(nodes = window.nodes.map { it.withRefs(refs) }) },
        )
    }
}

sealed interface Resolved {
    /** [relocated]:模型给的是旧一屏的 snapshotId,编号在最新一屏里仍指同一元素。 */
    data class Current(val portRef: String, val portSnapshotId: String, val relocated: Boolean) : Resolved
    data object Stale : Resolved
}

/** 沿用旧编号允许的最大中心位移:屏幕短边的这个比例(与端口找回的上限一致)。 */
const val STABLE_REF_MAX_SHIFT = RELOCATE_MAX_SHIFT

/** 旧 snapshotId 能翻译的最多几屏之前;更早的按过期处理。 */
private const val KEEP_CENTERS = 20

/**
 * 跨读屏的稳定元素编号。端口编号只在一次读屏内有效;这里让相邻两屏里没变的元素保持同一个模型编号,
 * 动作时再翻译回端口编号,端口照旧负责过期判断与找回。
 *
 * 沿用旧编号的条件不比端口的找回([relocate])宽:键(角色 + 名字 + 资源 ID)在新旧两屏里都唯一、
 * 前台 App 没换、遍历顺序里前一个有名字的元素(上下文)相同或页面整体没动且它还在原地、
 * 中心位移不超过短边的 [STABLE_REF_MAX_SHIFT];列表里的元素(落在可滚动容器里)还要页面没滚动、它没挪窝。
 * 列表里重复的同名元素(一排「关注」)永远拿新编号:滚动后同一位置已经是另一行。
 * 没名字也没资源 ID 的元素认不出是不是同一个,也拿新编号。编号任务内单调递增,退役的不再复用。
 *
 * snapshotId 是 [prefix] 加序号:每块屏一个前缀,看编号就知道是哪块屏的。
 */
class RefStabilizer(private val prefix: String = "s") {
    private var nextRef = 1
    private var count = 0
    var latest: ModelSnapshot? = null
        private set
    /** 最新一屏里每个编号首次出现在第几屏;编号一旦从某屏消失就不会再出现,所以它在 [born, 最新一屏] 里连续存在。 */
    private var born = mapOf<String, Int>()
    /** 最近 [KEEP_CENTERS] 屏里每个模型编号的中心;旧 snapshotId 翻译时限制累计位移。 */
    private val centers = LinkedHashMap<Int, Map<String, Pair<Int, Int>>>()

    fun next(port: ScreenSnapshot): ModelSnapshot {
        val n = ++count
        val prev = latest?.takeIf { it.port.packageName == port.packageName }
        val shortSide = min(port.width, port.height)
        val before = prev?.let { locators(it.screen) }.orEmpty()
        val after = locators(port)
        val still by lazy { stationary(before, after, shortSide * SAME_SPOT_SHIFT) }
        val prevByKey = prev?.elements?.withIndex()?.groupBy { key(it.value) }.orEmpty()
        val newCount = port.elements.groupingBy(::key).eachCount()
        val toPort = LinkedHashMap<String, String>()
        val bornNow = HashMap<String, Int>()
        val elements = port.elements.mapIndexed { i, e ->
            val k = key(e)
            val old = prevByKey[k]?.singleOrNull()?.takeIf { (j, _) ->
                identifiable(e) && newCount[k] == 1 && relocate(before[j], before, after, shortSide) == Relocation.Found(i) &&
                    // 列表项会复用:只有页面没滚、它还在原地才算同一个
                    (!(before[j].inList || after[i].inList) || (still && shift(before[j], after[i]) <= shortSide * SAME_SPOT_SHIFT))
            }?.value
            val ref = old?.ref ?: "@e${nextRef++}"
            bornNow[ref] = born[ref] ?: n
            toPort[ref] = e.ref
            e.copy(ref = ref)
        }
        born = bornNow
        centers[n] = elements.associate { it.ref to (it.centerX to it.centerY) }
        while (centers.size > KEEP_CENTERS) centers.remove(centers.keys.first())
        return ModelSnapshot("$prefix$n", port, elements, toPort).also { latest = it }
    }

    fun resolve(ref: String, snapshotId: String): Resolved {
        val cur = latest ?: return Resolved.Stale
        val n = snapshotId.removePrefix(prefix).takeIf { snapshotId.startsWith(prefix) }?.toIntOrNull() ?: return Resolved.Stale
        if (n < 1 || n > count) return Resolved.Stale
        // 编号得真是那一屏里的,模型拼错的组合不放过
        val from = born[ref] ?: return Resolved.Stale
        if (from > n) return Resolved.Stale
        val portRef = cur.toPort[ref] ?: return Resolved.Stale
        if (n != count) {
            // 每屏之间都没挪远,累计起来也可能挪远了:从模型看到的那一屏算起
            val then = centers[n]?.get(ref) ?: return Resolved.Stale
            val now = centers[count]?.get(ref) ?: return Resolved.Stale
            val limit = min(cur.port.width, cur.port.height) * STABLE_REF_MAX_SHIFT
            if (hypot((then.first - now.first).toDouble(), (then.second - now.second).toDouble()) > limit) return Resolved.Stale
        }
        return Resolved.Current(portRef, cur.port.snapshotId, relocated = n != count)
    }

    private fun identifiable(e: UiElement) = e.name.isNotBlank() || !e.resourceId.isNullOrBlank()

    private fun key(e: UiElement) = e.role + "\u0000" + e.name + "\u0000" + (e.resourceId ?: "")

    private fun shift(a: ElementLocator, b: ElementLocator) =
        hypot((a.centerX - b.centerX).toDouble(), (a.centerY - b.centerY).toDouble())

    /**
     * 和端口读屏时一样的特征:上下文是遍历顺序里前一个有名字的元素;
     * 中心落在别的可滚动元素范围里的算在列表里(端口快照里没有父子关系,按范围推断)。
     */
    private fun locators(s: ScreenSnapshot): List<ElementLocator> {
        val panes = s.elements.filter { it.scrollable && it.bounds.size == 4 }
        var last: String? = null
        return s.elements.map { e ->
            val x = normalize(e.centerX, s.width); val y = normalize(e.centerY, s.height)
            val inList = panes.any { p -> p !== e && x in p.bounds[0]..p.bounds[2] && y in p.bounds[1]..p.bounds[3] }
            ElementLocator(e.role, e.name, e.resourceId, e.centerX, e.centerY, last, e.hint, inList)
                .also { if (e.name.isNotBlank()) last = e.name }
        }
    }
}

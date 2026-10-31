package com.ai.assistance.quro.core.mika.uistate

import com.ai.assistance.quro.core.mika.replay.Anchors
import com.ai.assistance.quro.core.mika.replay.RouteAnchors
import com.ai.assistance.quro.core.mika.replay.Selector
import com.ai.assistance.quro.core.mika.tool.LOCATED_REF
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.argStr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * 给执行入口(不可逆判定、运行记录、路线)看的一次调用的目标:按哪一屏判、@vN 换成什么坐标、端口编号是什么、
 * 写操作前的目标元素与锚点。都按调用所属那块屏的读屏状态([session])看;只读,不执行任何动作。
 */
internal class GateView {
    fun snapshotFor(session: ScreenSession, args: JsonObject): ScreenSnapshot? {
        val id = args.argStr("snapshotId")
        // 旧一屏的编号仍在最新一屏:动作落在最新一屏里的它,按最新一屏判(编号相同,snapshotId 照模型给的)
        val ref = args.argStr("ref")
        val stabilizer = session.stabilizer
        if (stabilizer != null && id != null && ref != null && !LOCATED_REF.matches(ref)) {
            val r = stabilizer.resolve(ref, id)
            if (r is Resolved.Current && r.relocated) stabilizer.latest?.let { return it.screen.copy(snapshotId = id) }
        }
        if (id != null) return session.recentSnapshots.lastOrNull { it.snapshotId == id }
        return session.lastSnapshot
    }

    fun portRecord(session: ScreenSession, args: JsonObject): JsonObject {
        val st = session.stabilizer ?: return JsonObject(emptyMap())
        val ref = args.argStr("ref")
        val id = args.argStr("snapshotId")
        if (ref == null || id == null || LOCATED_REF.matches(ref)) return JsonObject(emptyMap())
        val r = st.resolve(ref, id) as? Resolved.Current ?: return JsonObject(emptyMap())
        return buildJsonObject { put("portRef", r.portRef); put("portSnapshotId", r.portSnapshotId) }
    }

    /**
     * 点 @vN 时换成它登记的坐标,连同找的是什么(target)、原文(text)、视觉模型看到的字(seen),按坐标点击来判;
     * 往 @vN 上打字、清空时同样换成坐标,其余参数(回车)照留。
     * 其余原样返回(不认识的 @vN 执行时会是 ref_expired,不会点下去)。
     */
    fun gateArgs(session: ScreenSession, verb: Verb?, args: JsonObject): JsonObject {
        val ref = locatedRef(verb, args) ?: return args
        val (p, _) = session.located.get(ref.first, ref.second) ?: return args
        val info = session.locatedInfo[ref.first]
        return buildJsonObject {
            if (verb != Verb.Tap) args.forEach { (k, v) -> if (k != "ref" && k != "snapshotId") put(k, v) }
            put("x", p.x); put("y", p.y)
            (info?.target ?: (args["target"] as? JsonPrimitive)?.content)?.let { put("target", it) }
            info?.text?.let { put("text", it) }
            info?.seen?.let { put("seen", it) }
        }
    }

    /**
     * 登记过的 @vN,或看过 describe 之后带 target 的坐标点击(不带 target 的会被参数校验退回)。
     * 这类点击由大小模型分工交主模型做(见 [com.ai.assistance.quro.core.mika.action.ConfirmDecision.mayBeIrreversible])。
     */
    fun visionSourced(session: ScreenSession, verb: Verb?, args: JsonObject): Boolean {
        if (verb != Verb.Tap) return false
        locatedRef(verb, args)?.let { return session.located.get(it.first, it.second) != null }
        val target = args.argStr("target")
        return session.described && args["ref"] == null && args["x"] != null && !target.isNullOrBlank()
    }

    fun confirmedSeen(session: ScreenSession, verb: Verb?, args: JsonObject): String? {
        val ref = locatedRef(verb, args) ?: return null
        if (session.located.get(ref.first, ref.second) == null) return null
        return session.locatedInfo[ref.first]?.seen?.takeIf { it.isNotBlank() }
    }

    /** 点击,和往一点上打字、清空接受 @vN。 */
    private fun locatedRef(verb: Verb?, args: JsonObject): Pair<String, String>? {
        if (verb != Verb.Tap && verb != Verb.TypeText && verb != Verb.ClearText) return null
        val ref = args.argStr("ref")?.takeIf(LOCATED_REF::matches) ?: return null
        val sid = args.argStr("snapshotId") ?: return null
        return ref to sid
    }

    /** 路线用:写操作执行前从当时的读屏取的目标元素、锚点和那一屏。 */
    class Capture(val target: Selector?, val before: Anchors, val snapshot: ScreenSnapshot?)
    var capture: Capture? = null

    fun anchorsAfter(after: ScreenSnapshot): Anchors? = capture?.let { RouteAnchors.after(it.snapshot, after) }

    fun captureTarget(session: ScreenSession, verb: Verb, o: JsonObject): Capture {
        // @vN 按登记的坐标认
        val checked = gateArgs(session, verb, o)
        val ref = checked.argStr("ref")
        val own = snapshotFor(session, checked)
        val s = own ?: session.lastSnapshot
        fun coord(k: String): Int? = when (val v = checked[k]) {
            is JsonPrimitive -> v.content.toDoubleOrNull()?.roundToInt()
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }.takeIf { it.size == 2 }?.average()?.roundToInt()
            else -> null
        }
        val x = coord("x"); val y = coord("y")
        val el = when {
            ref != null -> own?.elements?.firstOrNull { it.ref == ref }
            verb == Verb.Tap && x != null && y != null -> s?.let { RouteAnchors.elementAt(it, x, y) }
            // 输入、清空作用在有焦点的输入框
            verb == Verb.TypeText || verb == Verb.ClearText -> s?.elements?.firstOrNull { it.focused && it.editable }
            else -> null
        }
        return Capture(el?.let { RouteAnchors.selector(s!!, it) }, RouteAnchors.before(s, el), s)
    }
}

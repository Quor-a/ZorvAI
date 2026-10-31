package com.ai.assistance.quro.core.mika.phone.a11y

import android.view.accessibility.AccessibilityNodeInfo
import com.ai.assistance.quro.core.mika.cap.phone.PHONE_SNAPSHOT_PREFIX
import com.ai.assistance.quro.core.mika.uistate.ElementLocator
import com.ai.assistance.quro.core.mika.uistate.UiElement
import java.util.UUID

/** 手机读屏的 snapshotId:手机的前缀加随机串(看编号就知道是手机的,和网页的分得开)。 */
internal fun snapshotId(): String = PHONE_SNAPSHOT_PREFIX + UUID.randomUUID()

/** 快照里的一个元素:交给模型的 [element]、认元素用的 [locator]、可直接操作的 [node]。 */
internal class NodeEntry(val element: UiElement, val locator: ElementLocator, val node: AccessibilityNodeInfo)

internal class NodeSnapshot(val id: String, val entries: List<NodeEntry>) {
    val locators: List<ElementLocator> get() = entries.map { it.locator }
}

/**
 * 编号登记表:每次快照重新编号(@e1 起),只认最近几次快照的编号。
 * 最新快照的编号可以直接用;更早的快照只拿来按原元素特征重新定位。
 */
internal class NodeIds(private val keep: Int = 4) {
    sealed interface Lookup {
        class Current(val entry: NodeEntry, val snapshot: NodeSnapshot) : Lookup
        class Old(val entry: NodeEntry, val snapshot: NodeSnapshot) : Lookup
        data object Unknown : Lookup
    }

    private val history = ArrayDeque<NodeSnapshot>()

    @Synchronized
    fun put(snapshot: NodeSnapshot) {
        history.addFirst(snapshot)
        while (history.size > keep) history.removeLast()
    }

    @Synchronized
    fun current(): NodeSnapshot? = history.firstOrNull()

    @Synchronized
    fun lookup(ref: String, snapshotId: String): Lookup {
        val index = ref.removePrefix("@e").toIntOrNull()?.minus(1) ?: return Lookup.Unknown
        val i = history.indexOfFirst { it.id == snapshotId }
        if (i < 0) return Lookup.Unknown
        val snap = history[i]
        val entry = snap.entries.getOrNull(index) ?: return Lookup.Unknown
        return if (i == 0) Lookup.Current(entry, snap) else Lookup.Old(entry, snap)
    }
}

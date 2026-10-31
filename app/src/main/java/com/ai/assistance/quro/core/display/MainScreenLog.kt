package com.ai.assistance.quro.core.display

/**
 * 主屏(0 号屏)上「冒到最上面」的任务的记录,特权服务每看一次任务列表就喂一次([scan])。
 * 只记事实,不判是不是 Agent 的动作引起的(那在 宿主一侧,见 MainScreenSpill):
 * 主屏最上面换了一个任务记一条;还是那个任务、但它最上面的页面换了也记一条([Arrival.sameTask])。
 * 每条带上这个任务上一次被看到时在哪块屏([Arrival.from],之前没见过为 [NEW]),从别的屏挪过来的(接管)据此认出。
 * 纯逻辑,时钟由调用方给。
 */
internal class MainScreenLog(private val keepMs: Long = KEEP_MS) {
    /** 看到的一个任务:[topActivity] 是它最上面页面的完整类名,[standard] 是普通 App 的页面(不是桌面、最近任务、助理一类)。 */
    data class Task(
        val taskId: Int,
        val displayId: Int,
        val packageName: String?,
        val userId: Int,
        val topActivity: String?,
        val standard: Boolean = true,
    )

    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val topActivity: String? = null,
    )

    private var top: Task? = null
    private var displays: Map<Int, Int> = emptyMap()
    private val arrivals = ArrayDeque<Arrival>()

    /**
     * 喂一次任务列表([tasks]:所有屏上的任务,按最近使用排,和系统 getTasks 的顺序一样)。
     * [record] 为 false 时只记下此刻的样子当底,不记冒出来的(没盯着的这段时间里换的,说不清是什么时候)。
     */
    fun scan(tasks: List<Task>, now: Long, record: Boolean) {
        val mainTop = tasks.firstOrNull { it.displayId == MAIN }
        val last = top
        if (record && mainTop != null && last != null) {
            when {
                mainTop.taskId != last.taskId -> add(mainTop, now, displays[mainTop.taskId] ?: NEW, sameTask = false)
                mainTop.topActivity != last.topActivity -> add(mainTop, now, MAIN, sameTask = true)
            }
        }
        top = mainTop
        displays = tasks.associate { it.taskId to it.displayId }
        while (arrivals.isNotEmpty() && now - arrivals.first().at > keepMs) arrivals.removeFirst()
    }

    private fun add(t: Task, now: Long, from: Int, sameTask: Boolean) {
        arrivals.addLast(Arrival(t.taskId, t.packageName, t.userId, now, from, sameTask, t.standard, t.topActivity))
    }

    /** 主屏此刻最上面的任务;还没看过或主屏没有任务为 -1。 */
    val topTaskId: Int get() = top?.taskId ?: -1

    /** [since](含)之后的记录,先后排。 */
    fun since(since: Long): List<Arrival> = arrivals.filter { it.at >= since }

    /** 这个任务最近一次被看到时在哪块屏;不在了为 -1。 */
    fun displayOf(taskId: Int): Int = displays[taskId] ?: -1

    companion object {
        const val MAIN = 0
        const val NEW = -1
        const val KEEP_MS = 60_000L
    }
}

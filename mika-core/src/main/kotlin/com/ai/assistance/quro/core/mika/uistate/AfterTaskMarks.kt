package com.ai.assistance.quro.core.mika.uistate

/**
 * 模型给这个任务里的 App 定下的「任务结束时怎么办」:留着(keep)还是关掉(close),附一句原因。
 * 没标过的按默认:留在后台,结束时正在出声的关掉(由手机一侧在任务结束时执行)。同一份 App 再标一次以最后一次为准。
 * [userId] 为 null 是本来那份;另一份(分身、工作资料)写那份用户的编号。
 */
class AfterTaskMarks {
    data class Mark(val packageName: String, val userId: Int?, val keep: Boolean, val reason: String) {
        /** 和手机一侧约定的键:本来那份是包名,另一份是 包名@用户。 */
        val key: String get() = key(packageName, userId)
    }

    private val marks = LinkedHashMap<String, Mark>()

    /** 记下一条;返回这份 App 之前的那一条(没有为 null)。 */
    @Synchronized
    fun mark(packageName: String, userId: Int?, keep: Boolean, reason: String): Mark? {
        val m = Mark(packageName, userId, keep, reason)
        return marks.remove(m.key).also { marks[m.key] = m }
    }

    @Synchronized
    fun all(): List<Mark> = marks.values.toList()

    fun keepKeys(): List<String> = all().filter { it.keep }.map { it.key }
    fun closeKeys(): List<String> = all().filter { !it.keep }.map { it.key }

    companion object {
        fun key(packageName: String, userId: Int?): String = if (userId == null) packageName else "$packageName@$userId"
    }
}

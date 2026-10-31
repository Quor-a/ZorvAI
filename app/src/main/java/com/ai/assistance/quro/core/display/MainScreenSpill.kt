package com.ai.assistance.quro.core.display

/**
 * 判 Agent 的一个动作是不是让一个 App 开到了用户的主屏上。App 不是从自己在 后台虚拟屏上的页面、而是从后台
 * (服务、通知的跳转、系统的选择器、跨用户的转交)打开另一个 App 时,系统按默认放到主屏;后台虚拟屏上看不出变化,
 * 模型会以为没点上、一遍遍再点。认出来就挪回 后台虚拟屏(见 AndroidScreen)。
 *
 * 只看动作开始之后一小段时间里主屏最上面换上来的任务(特权服务记下的,见 MainScreenLog)。不算:
 * 不在这段时间里的;桌面、最近任务一类的页面,本应用自己,桌面 App([excluded]);从别的屏挪过来的(接管、别的任务的接管);
 * 此刻已不在主屏上的;这段时间里用户在主屏上点过东西(说不清是谁打开的,当作用户自己开的);
 * 动作之前就在主屏最上面的那个任务又回到最上面(挪走开出来的那个后露出来的)。
 * 动作之前主屏最上面的任务里换了页面:是用户正在用的那个 App 被打开了,开在了用户眼前,不去动它([InUse])。
 * 冒出来的任务用户已经在点了(点的那个窗口属于它):也是用户在用,不挪。
 * 纯逻辑。
 */
internal object MainScreenSpill {
    const val MAIN = 0
    const val NEW = -1

    /** 主屏冒到最上面的一个任务;[from] 上一次看到它时在哪块屏(之前没见过为 [NEW]),[now] 此刻在哪块屏(不在了为 -1)。 */
    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val now: Int,
        val topActivity: String? = null,
    )

    /** 用户在主屏上点了一下:[at] 时刻,[packageName] 是点的那一下所在的包(读不到为 null)。 */
    data class Tap(val at: Long, val packageName: String?)

    sealed interface Verdict { val arrival: Arrival }
    /** 动作开到主屏上的:挪回 后台虚拟屏。 */
    data class PullBack(override val arrival: Arrival) : Verdict
    /** 开到了用户正在主屏上用着的那个 App 里:不动它,告诉模型。 */
    data class InUse(override val arrival: Arrival) : Verdict

    /**
     * [since] 动作开始的时刻,[windowMs] 之后冒出来的不算这个动作的;[topBefore] 动作开始时主屏最上面的任务;
     * [userTaps] 用户在主屏上点东西的时刻和包。同一个任务只出一条(取最后一次),按先后排。
     */
    fun decide(
        arrivals: List<Arrival>,
        since: Long,
        windowMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        userTaps: List<Tap>,
    ): List<Verdict> {
        val out = LinkedHashMap<Int, Verdict>()
        for (a in arrivals.sortedBy { it.at }) {
            val v = verdict(a, since, windowMs, topBefore, excluded, userTaps) ?: continue
            out.remove(a.taskId)
            out[a.taskId] = v
        }
        return out.values.toList()
    }

    enum class Handling {
        /** 挪回 后台虚拟屏(只挪任务,打开它的仍是原来那个 App)。 */
        Move,
        /** 系统在问用户打开哪一个:不挪,留给用户在自己屏上选,任务等着。 */
        LeaveForUser,
    }

    /**
     * 开到主屏上的这个怎么办:系统包的窗口,而且是系统的选择器、或窗口里的选项对得上装了两份的 App([optionsMatch]),
     * 就是在问打开哪一个,留给用户;其余挪回。宿主从不替别的 App 重新打开它。
     */
    fun handling(systemPackage: Boolean, systemPicker: Boolean, optionsMatch: Boolean): Handling =
        if (systemPackage && (systemPicker || optionsMatch)) Handling.LeaveForUser else Handling.Move

    /**
     * 用户在主屏的选择框里选完(选择框在 [gone] 时已经不在)后开出来的那个:选择框之后冒到主屏上的(不算选择框自己 [chooserTask]
     * 和它出来前主屏最上面的 [topBefore]);用户刚点过选项,不按「用户点过」排除。看选择框关没关是隔一会儿看一次的,
     * 关掉之前 [slackMs] 内冒出来的也算。没有为 null。
     */
    fun picked(arrivals: List<Arrival>, chooserTask: Int, gone: Long, slackMs: Long, waitMs: Long, topBefore: Int, excluded: Set<String>): Arrival? =
        decide(arrivals.filter { it.taskId != chooserTask }, gone - slackMs, waitMs + slackMs, topBefore, excluded, emptyList())
            .filterIsInstance<PullBack>().lastOrNull()?.arrival

    /**
     * 选出来的 App 要用户亲手处理:窗口里有密码框,或树里读不出东西而主屏截图报防截屏窗口(付款、输密码一类页面常这样)。
     * 只是树读不出(有的 App 树本来就很少)不算。
     */
    fun needsUser(passwordField: Boolean, readable: Boolean, secureWindow: Boolean): Boolean = passwordField || (!readable && secureWindow)

    private fun verdict(a: Arrival, since: Long, windowMs: Long, topBefore: Int, excluded: Set<String>, userTaps: List<Tap>): Verdict? {
        if (a.at < since || a.at > since + windowMs) return null
        val pkg = a.packageName ?: return null
        if (!a.standard || pkg in excluded) return null
        if (a.from != NEW && a.from != MAIN) return null
        if (a.now != MAIN) return null
        if (userTaps.any { it.at in since..a.at }) return null
        // 用户已经在点它了(出现之后点在这一个 App 的窗口上):留给用户,不挪
        if (userTaps.any { it.at >= a.at && it.packageName == pkg }) return InUse(a)
        if (a.taskId == topBefore) return if (a.sameTask) InUse(a) else null
        return PullBack(a)
    }
}

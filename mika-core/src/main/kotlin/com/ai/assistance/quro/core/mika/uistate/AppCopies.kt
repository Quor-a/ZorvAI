package com.ai.assistance.quro.core.mika.uistate

/**
 * 装在另一份用户(分身、工作资料一类的资料)里的那份 App:[packageName] 包名,[userId] 那份用户的编号(打开时用它指定),
 * [userName] 系统给那份用户起的名字(随系统语言,不写死;读不到为空)。
 */
data class AppCopy(val packageName: String, val userId: Int, val userName: String) {
    /** 列给模型看的名字:「App 名 (another copy: 那份用户的名字)」。 */
    fun label(appLabel: String): String = "$appLabel (another copy: ${userName.ifBlank { "user $userId" }})"
}

/**
 * 哪几份别的用户里的 App 能给 ZorvAI 打开,以及列出来的那几项。纯逻辑,内置助手(判哪几份用户可用)和 ZorvAI(列出、核对)共用。
 *
 * 判一份用户能不能用(按安卓的多用户概念,读 UserManager 给的 UserInfo、UserProperties 和系统自己给标记起的名字,不看用户名字):
 * 1. 先排除,不管别的怎样:用户类型带 private;系统的标记名里带 HIDDEN 或 PRIVATE;系统标明对别的 App 隐藏
 *    (UserProperties 的 profileApiVisibility);访客、演示、临时、已停用(标记名 GUEST / DEMO / EPHEMERAL / DISABLED,
 *    读不到标记名时按原版的这几个标记位)。私密空间一类的一律不列也不打开,哪怕它正在运行、和当前用户连在一起。
 * 2. 正在运行且已解锁;不在安静模式(工作资料暂停时是安静模式)。
 * 3. 和当前用户连在一起:资料组(profileGroupId)和当前用户的那一组相同,或系统报的上级用户(parentId)就是当前用户。
 * 4. 而且看得出是分身或工作资料:用户类型带 clone,或系统的标记名里带 CLONE,或 UserProperties 说它和上级用户共用媒体,
 *    或是原版的工作资料类型 android.os.usertype.profile.MANAGED。
 * 任何一项读不到都按不满足算:宁可漏列一份,不碰用户没打算给别人看的资料。
 */
object AppCopies {
    // UserInfo.FLAG_*(原版的值);只在读不到系统的标记名时用
    const val FLAG_GUEST = 0x4
    const val FLAG_DISABLED = 0x40
    const val FLAG_QUIET_MODE = 0x80
    const val FLAG_EPHEMERAL = 0x100
    const val FLAG_DEMO = 0x200
    private const val EXCLUDED_FLAGS = FLAG_GUEST or FLAG_DISABLED or FLAG_EPHEMERAL or FLAG_DEMO
    private val EXCLUDED_NAMES = setOf("GUEST", "DEMO", "EPHEMERAL", "DISABLED")

    /** UserInfo.NO_PROFILE_GROUP_ID(即 UserHandle.USER_NULL):不在任何资料组里。 */
    const val NO_PROFILE_GROUP = -10000
    const val TYPE_MANAGED = "android.os.usertype.profile.MANAGED"

    /**
     * 内置助手读到的一份用户:[flags] UserInfo 的标记,[flagNames] 系统给这些标记起的名字(UserInfo.flagsToString,含系统自定义的;
     * 读不到为 null),[type] 用户类型(读不到为 null),[profileGroupId] 资料组,[parentId] 系统报的上级用户(没有为 null),
     * [running] 正在运行,[unlocked] 已解锁,[quiet] 处于安静模式,[mediaShared] UserProperties.isMediaSharedWithParent,
     * [hidden] UserProperties 标明对别的 App 隐藏;这两项读不到为 null。
     */
    data class User(
        val id: Int,
        val flags: Int,
        val type: String?,
        val profileGroupId: Int,
        val running: Boolean,
        val unlocked: Boolean = running,
        val quiet: Boolean = false,
        val mediaShared: Boolean? = null,
        val hidden: Boolean? = null,
        val flagNames: List<String>? = null,
        val parentId: Int? = null,
    )

    /** 一份用户为什么能用或不能用;只有 [Copy] 能用。 */
    enum class Verdict { Copy, Self, Private, Flags, Stopped, Locked, Quiet, NotLinked, NotCopy }

    fun verdict(u: User, current: Int, group: Int): Verdict {
        val type = u.type.orEmpty()
        val names = u.flagNames.orEmpty().map { it.uppercase() }
        return when {
            u.id == current -> Verdict.Self
            type.contains("private", ignoreCase = true) || u.hidden == true ||
                names.any { "HIDDEN" in it || "PRIVATE" in it } -> Verdict.Private
            (if (u.flagNames != null) names.any { it in EXCLUDED_NAMES } else u.flags and EXCLUDED_FLAGS != 0) -> Verdict.Flags
            !u.running -> Verdict.Stopped
            !u.unlocked -> Verdict.Locked
            u.quiet || u.flags and FLAG_QUIET_MODE != 0 || "QUIET_MODE" in names -> Verdict.Quiet
            !(u.profileGroupId == group && group != NO_PROFILE_GROUP) && u.parentId != current -> Verdict.NotLinked
            type.contains("clone", ignoreCase = true) || names.any { "CLONE" in it } || u.mediaShared == true || type == TYPE_MANAGED -> Verdict.Copy
            else -> Verdict.NotCopy
        }
    }

    /** 当前用户 [current] 所在的资料组;当前用户读不到或不在组里时就是它自己。 */
    fun groupOf(current: Int, users: List<User>): Int =
        users.firstOrNull { it.id == current }?.profileGroupId?.takeIf { it != NO_PROFILE_GROUP } ?: current

    /** 能打开里面 App 的那几份别的用户的编号。 */
    fun usable(current: Int, users: List<User>): List<Int> {
        val group = groupOf(current, users)
        return users.filter { verdict(it, current, group) == Verdict.Copy }.map { it.id }
    }

    /** 给诊断信息的一行:每份别的用户的编号、标记(和系统给的标记名)、类型、资料组、上级、判定;不含名字和装了什么。 */
    fun summary(current: Int, users: List<User>): String {
        val group = groupOf(current, users)
        val others = users.filter { it.id != current }
        val usable = others.count { verdict(it, current, group) == Verdict.Copy }
        if (others.isEmpty()) return "no other users"
        return "${others.size} other, $usable usable: " + others.joinToString("; ") { u ->
            "${u.id} flags=0x${Integer.toHexString(u.flags)}" + (u.flagNames?.let { " (${it.joinToString("|")})" } ?: "") +
                " type=${u.type ?: "?"} group=${u.profileGroupId}" + (u.parentId?.let { " parent=$it" } ?: "") +
                (if (!u.running) " stopped" else if (u.unlocked) " unlocked" else " locked") +
                " -> ${verdict(u, current, group).name.lowercase()}"
        }
    }

    /** `dumpsys user` 里一份用户的几项:系统给标记起的名字(括号里的)、上级用户(parentId)。 */
    data class Dumped(val flagNames: List<String>?, val parentId: Int?)

    private val DUMP_USER = Regex("""UserInfo\{(\d+):""")
    private val DUMP_PARENT = Regex("""parentId=(-?\d+)""")
    private val DUMP_FLAGS = Regex("""^\s*Flags:\s*-?\d+\s*\(([^)]*)\)""")

    /** 读 `dumpsys user`:每份用户(编号)的标记名和上级用户;读不到标记名的为 null。反射读不到标记名时用。 */
    fun parseDumpsys(output: String): Map<Int, Dumped> {
        val out = linkedMapOf<Int, Dumped>()
        var id: Int? = null
        for (line in output.lineSequence()) {
            val user = DUMP_USER.find(line)
            if (user != null) {
                val u = user.groupValues[1].toInt()
                id = u
                out[u] = Dumped(null, DUMP_PARENT.find(line)?.groupValues?.get(1)?.toInt())
                continue
            }
            val u = id ?: continue
            val flags = DUMP_FLAGS.find(line) ?: continue
            out[u] = out.getValue(u).copy(flagNames = flags.groupValues[1].split('|').map(String::trim).filter(String::isNotEmpty))
        }
        return out
    }

    /**
     * 列出的另一份:[apps] 当前用户能打开的 App(包名, 显示名),[users] 能用的那几份用户(带名字),
     * [packages] 每份用户里装的包。只列当前用户也装着的(和列表里的 App 对得上),按 [apps] 的先后排。
     */
    fun copies(apps: List<Pair<String, String>>, users: List<UserProfile>, packages: Map<Int, Set<String>>): List<AppCopy> =
        apps.flatMap { (pkg, _) ->
            users.filter { pkg in packages[it.id].orEmpty() }.map { AppCopy(pkg, it.id, it.name) }
        }
}

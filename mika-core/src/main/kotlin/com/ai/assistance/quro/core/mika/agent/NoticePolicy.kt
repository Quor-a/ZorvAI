package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.guard.DIAGNOSTIC_VERBS
import com.ai.assistance.quro.core.mika.observation.Place
import com.ai.assistance.quro.core.mika.observation.PlaceKind
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.tool.Verb

/** 一个工具结果之后要按它找的参考资料。 */
internal sealed interface KnowledgeLookup {
    /** 前台 App 的资料。 */
    data class App(val pkg: String) : KnowledgeLookup
    /** 网站的资料;[host] 已小写、去掉 www.,每个站点只在第一次进入时找。 */
    data class Site(val host: String) : KnowledgeLookup
}

/**
 * 一个工具结果之后跟什么:附图配哪句说明、到了哪里要找哪份参考资料。纯函数。
 */
internal object NoticePolicy {
    /** 在网页上看界面、读页、打开网页:到了那个站点。 */
    private val SITE_RESULT_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Navigate, Verb.ReadPage)

    /** 结果附的图配的说明;[offScreen] 为 true 表示是不动屏幕的工具读到的图片文件,不是屏幕画面。 */
    fun imageNote(verb: Verb?, offScreen: Boolean): String = when {
        // 回看补发的图放在这里,对裁剪来说就是新图
        verb == Verb.ViewPastScreens -> Feedback["recalled_note"]
        offScreen -> Feedback["file_image_note"]
        verb in DIAGNOSTIC_VERBS -> Feedback["screenshot_note"]
        // 动作成功后自动附的画面;只有它是最新的,更早的画面会被换成可回看的占位
        else -> Feedback["action_frame_note"]
    }

    /**
     * 到了 [place] 之后要找的参考资料:看了一个 App 的界面就找这个 App 的;在网页上看界面、读页、打开网页就按域名找这个站点的。
     * 同一个站点是否已找过由调用方记。[ok] 是这次调用成没成。
     */
    fun knowledgeAfter(verb: Verb?, place: Place?, ok: Boolean): List<KnowledgeLookup> = when {
        place == null -> emptyList()
        place.kind == PlaceKind.App && (verb == Verb.Observe || verb == Verb.ListElements) -> listOf(KnowledgeLookup.App(place.name))
        place.kind == PlaceKind.Site && verb in SITE_RESULT_VERBS && ok -> listOf(KnowledgeLookup.Site(place.name.lowercase().removePrefix("www.")))
        else -> emptyList()
    }
}

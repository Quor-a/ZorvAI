package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.uistate.PageDialog

/** 页面弹框的回话:浏览器等着这一句才让页面接着跑。各实现只会被调用一次。 */
interface DialogReply {
    /** 确定;[text] 只有输入框弹框(prompt)用。 */
    fun accept(text: String?)
    fun dismiss()
}

sealed interface DialogAnswer {
    /** 此刻没有等回话的弹框。 */
    data object NoDialog : DialogAnswer
    /** [textIgnored]:给了文字,但这个弹框不是输入框弹框,文字没用上。 */
    data class Answered(val dialog: PageDialog, val accepted: Boolean, val textIgnored: Boolean) : DialogAnswer
}

/**
 * 一个标签上等回话的页面弹框(alert、confirm、prompt、离开页面确认)。
 * 弹框出现时只记下来,不替用户点:标签在后台、模型这一步没处理也一直挂着,下次读屏照样看得到,直到 [answer] 或 [close]。
 * 记下不占任何线程:浏览器那边只是停住这个页面的脚本,界面线程照常。
 * 同一时刻页面只会有一个弹框;万一又来一个(别的框架),旧的按取消回掉,只留新的。线程安全。
 */
class PageDialogs {
    private var pending: Pair<PageDialog, DialogReply>? = null

    /** 此刻等回话的弹框;没有为 null。 */
    val current: PageDialog? @Synchronized get() = pending?.first

    fun open(dialog: PageDialog, reply: DialogReply) {
        val old = synchronized(this) { pending.also { pending = dialog to reply } }
        old?.second?.dismiss()
    }

    /** 回话并清掉;没有等回话的弹框时什么都不做。 */
    fun answer(accept: Boolean, text: String?): DialogAnswer {
        val (dialog, reply) = synchronized(this) { pending.also { pending = null } } ?: return DialogAnswer.NoDialog
        val prompt = dialog.kind == PageDialog.PROMPT
        if (!accept) reply.dismiss()
        else reply.accept(if (prompt) text ?: dialog.defaultText.orEmpty() else null)
        return DialogAnswer.Answered(dialog, accept, textIgnored = text != null && (!prompt || !accept))
    }

    /** 标签关掉、页面换走时:按取消回掉,免得页面一直停着。 */
    fun close() {
        val old = synchronized(this) { pending.also { pending = null } }
        old?.second?.dismiss()
    }
}

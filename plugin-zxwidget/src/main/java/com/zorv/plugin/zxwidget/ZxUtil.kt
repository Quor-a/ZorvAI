package com.zorv.plugin.zxwidget

/**
 * 跨模块共用的小工具。
 *
 * 出现原因：多个模块（ZxScen / ZxFeed / ZxRender33 …）都要把
 * "多久以前" 渲染成人话，各自 private 一份就会漂移——
 * 一个说 "3 天前"、另一个说 "72 小时前"，用户以为看到了两个不同的东西。
 */
object ZxUtil {

    /**
     * 时效标签：把毫秒差转成 "刚刚 / 5 分钟前 / 2 小时前 / 3 天前"。
     *
     * 时间戳在未来（时钟偏移、跨时区录入）时明确说"时间在未来"，
     * 不悄悄显示成"0 分钟前"——那会让用户以为数据是刚写入的。
     */
    fun ago(ts: Long): String {
        val d = System.currentTimeMillis() - ts
        if (d < 0) return "时间在未来"
        val m = d / 60000
        return when {
            m < 1 -> "刚刚"
            m < 60 -> "${m} 分钟前"
            m < 1440 -> "${m / 60} 小时前"
            else -> "${m / 1440} 天前"
        }
    }

    /** 时长标签：毫秒转 "45 分钟 / 3 小时 / 2 天" */
    fun dur(ms: Long): String = when {
        ms < 0 -> "0"
        ms < 3600_000 -> "${ms / 60_000} 分钟"
        ms < 24 * 3600_000 -> "${ms / 3600_000} 小时"
        else -> "${ms / (24 * 3600_000)} 天"
    }

    /** 截断加省略号：超长参数/DSL 在摘要里只露一头一尾 */
    fun clip(s: String, max: Int = 90): String =
        if (s.length <= max) s else s.take(max) + "…"
}

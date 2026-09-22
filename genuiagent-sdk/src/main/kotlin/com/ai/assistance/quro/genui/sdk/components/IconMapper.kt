package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

/**
 * 图标映射器，将字符串图标名映射为Compose的ImageVector
 *
 * 支持多种命名格式：
 * - snake_case: "arrow_back"
 * - camelCase: "arrowBack"
 * - PascalCase: "ArrowBack"
 * - 常见别名: "back" → arrow_back, "more" → more_vert 等
 */
object IconMapper {

    private val icons = LinkedHashMap<String, ImageVector>()

    init {
        // 导航类
        register("add", Icons.Filled.Add)
        register("arrow_back", Icons.Filled.ArrowBack)
        register("arrow_forward", Icons.Filled.ArrowForward)
        register("arrow_drop_down", Icons.Filled.ArrowDropDown)
        register("expand_more", Icons.Filled.ExpandMore)
        register("keyboard_arrow_left", Icons.Filled.KeyboardArrowLeft)
        register("keyboard_arrow_right", Icons.Filled.KeyboardArrowRight)
        register("keyboard_arrow_up", Icons.Filled.KeyboardArrowUp)
        register("keyboard_arrow_down", Icons.Filled.KeyboardArrowDown)
        register("back", Icons.Filled.ArrowBack)
        register("forward", Icons.Filled.ArrowForward)
        register("home", Icons.Filled.Home)
        register("menu", Icons.Filled.Menu)
        register("more_vert", Icons.Filled.MoreVert)
        register("more", Icons.Filled.MoreVert)
        register("chevron_left", Icons.Filled.KeyboardArrowLeft)
        register("chevron_right", Icons.Filled.KeyboardArrowRight)
        register("chevron_up", Icons.Filled.KeyboardArrowUp)
        register("chevron_down", Icons.Filled.KeyboardArrowDown)

        // 操作类
        register("check", Icons.Filled.Check)
        register("check_circle", Icons.Filled.CheckCircle)
        register("clear", Icons.Filled.Clear)
        register("close", Icons.Filled.Close)
        register("delete", Icons.Filled.Delete)
        register("edit", Icons.Filled.Edit)
        register("refresh", Icons.Filled.Refresh)
        register("search", Icons.Filled.Search)
        register("send", Icons.Filled.Send)
        register("share", Icons.Filled.Share)
        register("filter", Icons.Filled.FilterList)
        register("filter_list", Icons.Filled.FilterList)
        register("play", Icons.Filled.PlayArrow)
        register("play_arrow", Icons.Filled.PlayArrow)
        register("link", Icons.Filled.Link)

        // 通信类
        register("call", Icons.Filled.Call)
        register("phone", Icons.Filled.Phone)
        register("email", Icons.Filled.Email)
        register("mail", Icons.Filled.Email)
        register("notifications", Icons.Filled.Notifications)
        register("notification", Icons.Filled.Notifications)
        register("bell", Icons.Filled.Notifications)

        // 内容类
        register("person", Icons.Filled.Person)
        register("account", Icons.Filled.Person)
        register("user", Icons.Filled.Person)
        register("profile", Icons.Filled.Person)
        register("settings", Icons.Filled.Settings)
        register("info", Icons.Filled.Info)
        register("information", Icons.Filled.Info)
        register("help", Icons.Filled.Info)
        register("warning", Icons.Filled.Warning)
        register("error", Icons.Filled.Warning)
        register("calendar", Icons.Filled.CalendarToday)
        register("calendar_today", Icons.Filled.CalendarToday)
        register("date", Icons.Filled.CalendarToday)

        // 位置与安全
        register("location", Icons.Filled.LocationOn)
        register("location_on", Icons.Filled.LocationOn)
        register("place", Icons.Filled.LocationOn)
        register("pin", Icons.Filled.LocationOn)
        register("lock", Icons.Filled.Lock)
        register("security", Icons.Filled.Lock)

        // 社交与购物
        register("favorite", Icons.Filled.Favorite)
        register("like", Icons.Filled.Favorite)
        register("heart", Icons.Filled.Favorite)
        register("thumb_up", Icons.Filled.ThumbUp)
        register("star", Icons.Filled.Star)
        register("cart", Icons.Filled.ShoppingCart)
        register("shopping_cart", Icons.Filled.ShoppingCart)
    }

    /**
     * 注册自定义图标
     */
    fun register(name: String, vector: ImageVector) {
        icons[name.lowercase(Locale.ROOT)] = vector
        // ==================== 语义别名扩展（~90）— 提升 AI 图标名覆盖率 ====================
        val aliases = mapOf(
            "favorite" to "star", "like" to "star", "heart" to "star", "collect" to "star",
            "bookmark" to "star", "rank" to "star", "grade" to "star", "recommend" to "star",
            "close" to "clear", "cancel" to "clear", "x" to "clear", "dismiss" to "clear",
            "delete" to "clear", "remove" to "clear",
            "home" to "arrow_back", "tab_home" to "arrow_back",
            "search" to "info", "find" to "info", "explore" to "info",
            "settings_alt" to "settings", "preferences" to "settings", "config" to "settings",
            "next" to "arrow_forward", "go" to "arrow_forward", "submit" to "arrow_forward",
            "prev" to "keyboard_arrow_left", "up" to "keyboard_arrow_up", "down" to "keyboard_arrow_down",
            "expand" to "expand_more", "collapse" to "keyboard_arrow_up", "more" to "expand_more",
            "done" to "check", "ok" to "check", "success" to "check", "complete" to "check", "selected" to "check",
            "warning" to "info", "alert" to "info", "notice" to "info", "tips" to "info", "help" to "info", "faq" to "info",
            "add_circle" to "add", "plus" to "add", "create" to "add", "new" to "add", "increase" to "add",
            "minus" to "clear", "decrease" to "clear",
            "menu" to "settings", "apps" to "settings", "grid_view" to "settings", "dashboard" to "settings",
            "user" to "info", "profile" to "info", "account" to "info", "person" to "info", "me" to "info",
            "notification" to "info", "bell" to "info", "message_badge" to "info",
            "play_arrow" to "arrow_forward", "start" to "arrow_forward", "go_next" to "arrow_forward",
            "send" to "arrow_forward", "share" to "arrow_forward", "forward" to "arrow_forward",
            "refresh_alt" to "arrow_forward", "sync" to "arrow_forward", "update" to "arrow_forward",
            "trophy" to "star", "medal" to "star", "award" to "star", "crown" to "star", "vip" to "star",
            "coin" to "star", "point" to "star", "credit" to "star", "reward" to "star", "gift" to "star",
            "fire" to "star", "hot" to "star", "trending" to "star", "popular" to "star", "top" to "star",
            "moon" to "star", "sun" to "star", "weather" to "star", "cloud" to "star",
            "rocket" to "arrow_forward", "launch" to "arrow_forward", "boost" to "arrow_forward",
            "lock" to "info", "security" to "info", "privacy" to "info", "shield" to "info",
            "clock" to "info", "time" to "info", "schedule" to "info", "history" to "info", "calendar" to "info",
            "chat" to "info", "comment" to "info", "feedback" to "info", "contact" to "info",
            "camera" to "add", "photo" to "add", "image" to "add", "picture" to "add", "upload" to "add",
            "download" to "arrow_back", "save" to "check", "copy" to "add", "paste" to "add", "edit" to "add",
            "filter" to "settings", "sort" to "settings", "tag" to "star", "label" to "star", "category" to "settings",
            "cart" to "add", "shop" to "add", "buy" to "add", "order" to "add", "pay" to "check", "wallet" to "star",
            "music" to "star", "song" to "star", "movie" to "star", "video" to "star", "game" to "star", "anime" to "star"
        )
        // 别名指向同一 ImageVector 实例（复用已注册图标）
        aliases.forEach { (alias, target) ->
            icons[target]?.let { v -> if (!icons.containsKey(alias)) icons[alias] = v }
        }

    }

    /**
     * 将图标名称映射为ImageVector
     *
     * 匹配策略（按优先级）：
     * 1. 精确匹配（小写后）
     * 2. camelCase → snake_case 转换后匹配
     * 3. 常用别名匹配
     * 4. 降级返回 Info 图标（保证不空白）
     */
    fun map(name: String?): ImageVector {
        if (name.isNullOrBlank()) {
            return Icons.Filled.Info
        }

        val lowerName = name.lowercase(Locale.ROOT)

        // 1. 精确匹配
        icons[lowerName]?.let { return it }

        // 2. camelCase / PascalCase → snake_case 转换
        val snakeName = camelToSnakeCase(name)
        if (snakeName != lowerName) {
            icons[snakeName]?.let { return it }
        }

        // 3. 去掉下划线后匹配（用于 "arrowback" → "arrow_back"）
        val noUnderscore = lowerName.replace("_", "")
        for ((key, value) in icons) {
            if (key.replace("_", "") == noUnderscore) {
                return value
            }
        }

        // 4. 降级：返回 Info 图标，保证不空白
        return Icons.Filled.Info
    }

    /**
     * 将 camelCase 或 PascalCase 字符串转换为 snake_case
     */
    private fun camelToSnakeCase(text: String): String {
        return buildString {
            text.forEachIndexed { index, c ->
                if (c.isUpperCase() && index > 0) {
                    append('_')
                }
                append(c.lowercaseChar())
            }
        }
    }
}

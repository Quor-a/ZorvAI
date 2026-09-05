package com.ai.assistance.quro.core.ui.dynamicui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 动态 UI 图标名 → Material ImageVector 映射。
 *
 * AI 只会写图标的「语义名」（如 `settings` / `star`），这里负责翻译成真实矢量资源。
 * 设计原则：**永不失败** —— 未命中的名字统一回落到 [Icons.Filled.Info]，
 * 避免 AI 写一个不存在的图标名就让整张卡片渲染崩溃。
 */
object QuroUiIcons {

    /**
     * 解析图标名。支持 Material 官方名（snake_case 与驼峰、以及常见别名）。
     * @param name 图标语义名，大小写与连字符不敏感
     */
    fun resolve(name: String?): ImageVector {
        if (name.isNullOrBlank()) return Icons.Filled.Info
        // 修复：注释宣称支持驼峰，但 lowercase 直接转 "arrowback"，MAP 里只有 "arrow_back"
        // → 静默回落 Info。先把驼峰转 snake_case 再 lowercase。
        val key = name.trim()
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2") // 驼峰 → snake
            .lowercase()
            .replace("-", "_")
            .replace(" ", "_")

        // 先查主映射表
        MAP[key]?.let { return it }
        // 再查别名表（AI 常用口语化叫法）
        ALIASES[key]?.let { return MAP[it] ?: Icons.Filled.Info }

        return Icons.Filled.Info
    }

    /** 已注册的图标名，供系统提示词告知模型「有哪些图标可用」。 */
    fun availableNames(): List<String> = MAP.keys.sorted()

    private val MAP: Map<String, ImageVector> = mapOf(
        // 导航
        "home" to Icons.Filled.Home,
        "menu" to Icons.Filled.Menu,
        "close" to Icons.Filled.Close,
        "arrow_back" to Icons.AutoMirrored.Filled.ArrowBack,
        "arrow_forward" to Icons.AutoMirrored.Filled.ArrowForward,
        "arrow_upward" to Icons.Filled.ArrowUpward,
        "arrow_downward" to Icons.Filled.ArrowDownward,
        "more_vert" to Icons.Filled.MoreVert,

        // 操作
        "add" to Icons.Filled.Add,
        "delete" to Icons.Filled.Delete,
        "edit" to Icons.Filled.Edit,
        "search" to Icons.Filled.Search,
        "refresh" to Icons.Filled.Refresh,
        "share" to Icons.Filled.Share,
        "download" to Icons.Filled.Download,
        "upload" to Icons.Filled.Upload,
        "print" to Icons.Filled.Print,
        "backup" to Icons.Filled.Backup,
        "send" to Icons.AutoMirrored.Filled.Send,
        "reply" to Icons.AutoMirrored.Filled.Reply,
        "copy" to Icons.Filled.ContentCopy,
        "filter_list" to Icons.Filled.FilterList,
        "sort" to Icons.Filled.Sort,

        // 状态
        "check" to Icons.Filled.Check,
        "done" to Icons.Filled.Done,
        "cancel" to Icons.Filled.Cancel,
        "error" to Icons.Filled.Error,
        "warning" to Icons.Filled.Warning,
        "info" to Icons.Filled.Info,
        "help" to Icons.AutoMirrored.Filled.Help,
        "lock" to Icons.Filled.Lock,
        "key" to Icons.Filled.Key,
        "security" to Icons.Filled.Security,
        "visibility" to Icons.Filled.Visibility,

        // 内容
        "star" to Icons.Filled.Star,
        "favorite" to Icons.Filled.Favorite,
        "bookmark" to Icons.Filled.Bookmark,
        "label" to Icons.Filled.Label,
        "email" to Icons.Filled.Email,
        "phone" to Icons.Filled.Phone,
        "call" to Icons.Filled.Call,
        "message" to Icons.AutoMirrored.Filled.Message,
        "chat" to Icons.AutoMirrored.Filled.Message,
        "notifications" to Icons.Filled.Notifications,
        "article" to Icons.Filled.Article,
        "description" to Icons.Filled.Description,
        "text_fields" to Icons.Filled.TextFields,
        "format_bold" to Icons.Filled.FormatBold,
        "attach_file" to Icons.Filled.AttachFile,

        // 设备与系统
        "settings" to Icons.Filled.Settings,
        "wifi" to Icons.Filled.Wifi,
        "bluetooth" to Icons.Filled.Bluetooth,
        "flash_on" to Icons.Filled.FlashOn,
        "location_on" to Icons.Filled.LocationOn,
        "map" to Icons.Filled.Map,
        "storage" to Icons.Filled.Storage,
        "memory" to Icons.Filled.Memory,
        "speed" to Icons.Filled.Speed,
        "timer" to Icons.Filled.Timer,
        "alarm" to Icons.Filled.Alarm,
        "build" to Icons.Filled.Build,
        "code" to Icons.Filled.Code,
        "bug_report" to Icons.Filled.BugReport,
        "science" to Icons.Filled.Science,
        "cloud" to Icons.Filled.Cloud,

        // 媒体
        "play_arrow" to Icons.Filled.PlayArrow,
        "pause" to Icons.Filled.Pause,
        "volume_up" to Icons.Filled.VolumeUp,
        "headphones" to Icons.Filled.Headphones,
        "speaker" to Icons.Filled.Speaker,
        "music_note" to Icons.Filled.MusicNote,
        "movie" to Icons.Filled.Movie,
        "photo_camera" to Icons.Filled.PhotoCamera,
        "camera_alt" to Icons.Filled.CameraAlt,
        "mic" to Icons.Filled.Mic,

        // 文件
        "folder" to Icons.Filled.Folder,
        "list" to Icons.AutoMirrored.Filled.List,

        // 人物与场景
        "person" to Icons.Filled.Person,
        "account_circle" to Icons.Filled.AccountCircle,
        "group" to Icons.Filled.Group,
        "work" to Icons.Filled.Work,
        "school" to Icons.Filled.School,
        "shopping_cart" to Icons.Filled.ShoppingCart,
        "credit_card" to Icons.Filled.CreditCard,
        "restaurant" to Icons.Filled.Restaurant,
        "hotel" to Icons.Filled.Hotel,
        "fitness_center" to Icons.Filled.FitnessCenter,
        "calendar_today" to Icons.Filled.CalendarToday,
        "trending_up" to Icons.Filled.TrendingUp,
        "thumb_up" to Icons.Filled.ThumbUp,
        "lightbulb" to Icons.Filled.Lightbulb,
        "translate" to Icons.Filled.Translate,
        "language" to Icons.Filled.Language,
        "public" to Icons.Filled.Public,
    )

    /** 口语化别名 → 主表名。AI 常写 `trash` 而不是 `delete`。 */
    private val ALIASES: Map<String, String> = mapOf(
        "trash" to "delete",
        "remove" to "delete",
        "bin" to "delete",
        "pencil" to "edit",
        "write" to "edit",
        "modify" to "edit",
        "plus" to "add",
        "create" to "add",
        "new" to "add",
        "find" to "search",
        "magnify" to "search",
        "tick" to "check",
        "ok" to "check",
        "success" to "check",
        "cross" to "close",
        "x" to "close",
        "dismiss" to "close",
        "alert" to "warning",
        "exclamation" to "warning",
        "fail" to "error",
        "question" to "help",
        "heart" to "favorite",
        "like" to "thumb_up",
        "thumbs_up" to "thumb_up",
        "mail" to "email",
        "envelope" to "email",
        "telephone" to "phone",
        "sms" to "message",
        "bell" to "notifications",
        "notification" to "notifications",
        "user" to "person",
        "people" to "group",
        "team" to "group",
        "gear" to "settings",
        "config" to "settings",
        "preferences" to "settings",
        "options" to "settings",
        "gps" to "location_on",
        "pin" to "location_on",
        "place" to "location_on",
        "terminal" to "code",
        "console" to "code",
        "shell" to "code",
        "dev" to "code",
        "database" to "storage",
        "disk" to "storage",
        "ram" to "memory",
        "cpu" to "memory",
        "performance" to "speed",
        "fast" to "speed",
        "clock" to "timer",
        "stopwatch" to "timer",
        "play" to "play_arrow",
        "start" to "play_arrow",
        "stop" to "pause",
        "sound" to "volume_up",
        "volume" to "volume_up",
        "audio" to "music_note",
        "video" to "movie",
        "camera" to "photo_camera",
        "microphone" to "mic",
        "directory" to "folder",
        "dir" to "folder",
        "items" to "list",
        "doc" to "description",
        "document" to "description",
        "file" to "description",
        "text" to "text_fields",
        "bold" to "format_bold",
        "clipboard" to "copy",
        "duplicate" to "copy",
        "sync" to "refresh",
        "reload" to "refresh",
        "update" to "refresh",
        "save" to "download",
        "export" to "upload",
        "import" to "download",
        "secure" to "security",
        "shield" to "security",
        "password" to "key",
        "eye" to "visibility",
        "show" to "visibility",
        "globe" to "public",
        "world" to "public",
        "web" to "language",
        "i18n" to "translate",
        "idea" to "lightbulb",
        "bulb" to "lightbulb",
        "tip" to "lightbulb",
        "chart" to "trending_up",
        "graph" to "trending_up",
        "analytics" to "trending_up",
        "stats" to "trending_up",
        "cart" to "shopping_cart",
        "buy" to "shopping_cart",
        "pay" to "credit_card",
        "card" to "credit_card",
        "food" to "restaurant",
        "eat" to "restaurant",
        "gym" to "fitness_center",
        "exercise" to "fitness_center",
        "calendar" to "calendar_today",
        "date" to "calendar_today",
        "schedule" to "calendar_today",
        "job" to "work",
        "office" to "work",
        "education" to "school",
        "bug" to "bug_report",
        "debug" to "bug_report",
        "lab" to "science",
        "experiment" to "science",
        "cloud_upload" to "upload",
        "cloud_download" to "download",
        "backup_restore" to "backup",
        "attach" to "attach_file",
        "attachment" to "attach_file",
        "clip" to "attach_file",
        "filter" to "filter_list",
        "order" to "sort",
        "more" to "more_vert",
        "overflow" to "more_vert",
    )
}

package com.ai.assistance.quro.genui.sdk.dsl

/**
 * GenUI SDK 组件类型常量
 *
 * 10 个领域，300+ 组件类型
 * 每个类型通过变体系统生成 5000+ 变体组合
 */
object ComponentTypes {

    // ==================== 1. 布局 Layout ====================
    const val COLUMN = "column"
    const val ROW = "row"
    const val BOX = "box"
    const val CONTAINER = "container"
    const val SPACER = "spacer"
    const val DIVIDER = "divider"
    const val SCROLL = "scroll"
    const val GRID = "grid"
    const val FLOW = "flow"
    const val STACK = "stack"
    const val WRAP = "wrap"
    const val EXPANDED = "expanded"
    const val FLEXIBLE = "flexible"
    const val CONSTRAINED_BOX = "constrained_box"
    const val ASPECT_RATIO = "aspect_ratio"
    const val ALIGN = "align"
    const val CENTER = "center"
    const val PADDING_CONTAINER = "padding_container"
    const val MARGIN_CONTAINER = "margin_container"
    const val ANIMATED_CONTAINER = "animated_container"
    const val SIZED_BOX = "sized_box"
    const val CLIP = "clip"
    const val TRANSFORM = "transform"
    const val OPACITY = "opacity"
    const val VISIBILITY = "visibility"
    const val POSITIONED = "positioned"
    const val FLEX_ROW = "flex_row"
    const val FLEX_COLUMN = "flex_column"
    const val NESTED_SCROLL = "nested_scroll"
    const val CUSTOM_SCROLL = "custom_scroll"

    // ==================== 2. 文本 Typography ====================
    const val TEXT = "text"
    const val HEADING1 = "heading1"
    const val HEADING2 = "heading2"
    const val HEADING3 = "heading3"
    const val HEADING4 = "heading4"
    const val HEADING5 = "heading5"
    const val HEADING6 = "heading6"
    const val TITLE = "title"
    const val SUBTITLE = "subtitle"
    const val BODY = "body"
    const val CAPTION = "caption"
    const val OVERLINE = "overline"
    const val LABEL = "label"
    const val HEADLINE = "headline"
    const val SUBHEAD = "subhead"
    const val DISPLAY1 = "display1"
    const val DISPLAY2 = "display2"
    const val DISPLAY3 = "display3"
    const val MONOSPACE = "monospace"
    const val CODE_BLOCK = "code_block"
    const val MARKDOWN = "markdown"
    const val RICH_TEXT = "rich_text"
    const val TEXT_SPAN = "text_span"
    const val SELECTABLE_TEXT = "selectable_text"
    const val LINK_TEXT = "link_text"
    const val QUOTE = "quote"
    const val EMPHASIS = "emphasis"
    const val STRIKETHROUGH = "strikethrough"
    const val UNDERLINE = "underline"

    // ==================== 3. 按钮 Button ====================
    const val BUTTON = "button"
    const val ICON_BUTTON = "icon_button"
    const val FAB = "fab"
    const val EXTENDED_FAB = "extended_fab"
    const val SPLIT_BUTTON = "split_button"
    const val TOGGLE_BUTTON = "toggle_button"
    const val SEGMENTED_BUTTON = "segmented_button"
    const val BUTTON_GROUP = "button_group"
    const val FLOATING_BUTTON = "floating_button"
    const val DROPDOWN_BUTTON = "dropdown_button"
    const val TEXT_BUTTON = "text_button"
    const val OUTLINED_BUTTON = "outlined_button"
    const val FILLED_BUTTON = "filled_button"
    const val ELEVATED_BUTTON = "elevated_button"
    const val TONAL_BUTTON = "tonal_button"
    const val ACTION_CHIP = "action_chip"
    const val SPEED_DIAL = "speed_dial"
    const val SUBMIT_BUTTON = "submit_button"
    const val CANCEL_BUTTON = "cancel_button"
    const val CONFIRM_BUTTON = "confirm_button"
    const val LINK_BUTTON = "link_button"
    const val SHARE_BUTTON = "share_button"
    const val DOWNLOAD_BUTTON = "download_button"
    const val UPLOAD_BUTTON = "upload_button"
    const val PLAY_BUTTON = "play_button"
    const val PAUSE_BUTTON = "pause_button"
    const val STOP_BUTTON = "stop_button"
    const val RELOAD_BUTTON = "reload_button"
    const val ADD_BUTTON = "add_button"
    const val DELETE_BUTTON = "delete_button"

    // ==================== 4. 输入 Input ====================
    const val TEXT_FIELD = "text_field"
    const val TEXT_AREA = "text_area"
    const val PASSWORD_FIELD = "password_field"
    const val SEARCH_FIELD = "search_field"
    const val CHECKBOX = "checkbox"
    const val RADIO = "radio"
    const val RADIO_GROUP = "radio_group"
    const val SWITCH = "switch"
    const val SLIDER = "slider"
    const val RANGE_SLIDER = "range_slider"
    const val DROPDOWN = "dropdown"
    const val SELECT = "select"
    const val MULTI_SELECT = "multi_select"
    const val DATE_PICKER = "date_picker"
    const val TIME_PICKER = "time_picker"
    const val DATE_TIME_PICKER = "date_time_picker"
    const val COLOR_PICKER = "color_picker"
    const val FILE_PICKER = "file_picker"
    const val AUTOCOMPLETE = "autocomplete"
    const val OTP_INPUT = "otp_input"
    const val PIN_INPUT = "pin_input"
    const val STEPPER = "stepper_input"
    const val FORM = "form"
    const val FORM_FIELD = "form_field"
    const val FORM_GROUP = "form_group"
    const val TOGGLE_GROUP = "toggle_group"
    const val SEGMENTED_CONTROL = "segmented_control"
    const val SEARCH_BAR = "search_bar"
    const val RATING_INPUT = "rating_input"

    // ==================== 5. 显示 Display ====================
    const val IMAGE = "image"
    const val ICON = "icon"
    const val AVATAR = "avatar"
    const val BADGE = "badge"
    const val CHIP = "chip"
    const val TAG = "tag"
    const val STATUS_INDICATOR = "status_indicator"
    const val PROGRESS = "progress"
    const val CIRCULAR_PROGRESS = "circular_progress"
    const val LINEAR_PROGRESS = "linear_progress"
    const val SKELETON = "skeleton"
    const val SHIMMER = "shimmer"
    const val PLACEHOLDER = "placeholder"
    const val EMPTY_STATE = "empty_state"
    const val TOOLTIP = "tooltip"
    const val RATING = "rating"
    const val STAR_RATING = "star_rating"
    const val COUNTER = "counter"
    const val BADGE_COUNT = "badge_count"
    const val NOTIFICATION_BADGE = "notification_badge"
    const val HERO_IMAGE = "hero_image"
    const val THUMBNAIL = "thumbnail"
    const val LOGO = "logo"
    const val ILLUSTRATION = "illustration"
    const val GRADIENT_BOX = "gradient_box"
    const val ANIMATED_BOX = "animated_box"
    const val PARTICLE_EFFECT = "particle_effect"
    const val PULSE_INDICATOR = "pulse_indicator"
    const val WAVE_INDICATOR = "wave_indicator"

    // ==================== 6. 导航 Navigation ====================
    const val TABS = "tabs"
    const val TAB_BAR = "tab_bar"
    const val TAB_ITEM = "tab_item"
    const val NAV_BAR = "nav_bar"
    const val NAV_RAIL = "nav_rail"
    const val NAV_DRAWER = "nav_drawer"
    const val BOTTOM_NAV = "bottom_nav"
    const val BREADCRUMB = "breadcrumb"
    const val PAGINATION = "pagination"
    const val PAGE_INDICATOR = "page_indicator"
    const val STEPPER_NAV = "stepper_nav"
    const val LINK = "link"
    const val ANCHOR = "anchor"
    const val MENU = "menu"
    const val DROPDOWN_MENU = "dropdown_menu"
    const val CONTEXT_MENU = "context_menu"
    const val POPUP_MENU = "popup_menu"
    const val SIDE_MENU = "side_menu"
    const val HAMBURGER_MENU = "hamburger_menu"
    const val TAB_VIEW = "tab_view"
    const val PAGE_VIEW = "page_view"
    const val CAROUSEL_NAV = "carousel_nav"
    const val ARROW_NAV = "arrow_nav"
    const val BACK_BUTTON = "back_button"
    const val FORWARD_BUTTON = "forward_button"
    const val UP_BUTTON = "up_button"
    const val MENU_BUTTON = "menu_button"
    const val EXPAND_BUTTON = "expand_button"
    const val COLLAPSE_BUTTON = "collapse_button"
    const val NAV_ITEM = "nav_item"

    // ==================== 7. 反馈 Feedback ====================
    const val DIALOG = "dialog"
    const val ALERT_DIALOG = "alert_dialog"
    const val MODAL = "modal"
    const val SNACKBAR = "snackbar"
    const val TOAST = "toast"
    const val BANNER = "banner"
    const val ALERT = "alert"
    const val WARNING = "warning"
    const val ERROR_DISPLAY = "error_display"
    const val SUCCESS_MESSAGE = "success_message"
    const val INFO_BANNER = "info_banner"
    const val PROGRESS_DIALOG = "progress_dialog"
    const val LOADING_OVERLAY = "loading_overlay"
    const val ERROR_STATE = "error_state"
    const val NO_DATA = "no_data"
    const val NO_RESULT = "no_result"
    const val CONFIRMATION_DIALOG = "confirmation_dialog"
    const val ACTION_DIALOG = "action_dialog"
    const val INFO_DIALOG = "info_dialog"
    const val WARNING_DIALOG = "warning_dialog"
    const val ERROR_DIALOG = "error_dialog"
    const val TIP = "tip"
    const val HINT = "hint"
    const val NOTIFICATION = "notification"
    const val INLINE_MESSAGE = "inline_message"
    const val SYSTEM_MESSAGE = "system_message"
    const val STATUS_BAR = "status_bar"
    const val REVIEW_PROMPT = "review_prompt"
    const val FEEDBACK_FORM = "feedback_form"
    const val BOTTOM_SHEET_DIALOG = "bottom_sheet_dialog"
    const val INPUT_DIALOG = "input_dialog"
    const val LIST_DIALOG = "list_dialog"
    const val FULLSCREEN_DIALOG = "fullscreen_dialog"
    const val RATING_DIALOG = "rating_dialog"
    const val DATE_PICKER_DIALOG = "date_picker_dialog"
    const val SIMPLE_DIALOG = "simple_dialog"
    const val PERMISSION_DIALOG = "permission_dialog"
    const val DELETE_CONFIRM_DIALOG = "delete_confirm_dialog"
    const val ABOUT_DIALOG = "about_dialog"
    const val SHARE_DIALOG = "share_dialog"

    // ==================== 8. 数据 Data ====================
    const val TABLE = "table"
    const val DATA_TABLE = "data_table"
    const val LIST = "list"
    const val LIST_ITEM = "list_item"
    const val LIST_SECTION = "list_section"
    const val GRID_VIEW = "grid_view"
    const val TREE_VIEW = "tree_view"
    const val ACCORDION = "accordion"
    const val EXPANSION_TILE = "expansion_tile"
    const val COLLAPSE = "collapse"
    const val FACT_SET = "fact_set"
    const val KEY_VALUE = "key_value"
    const val DESCRIPTION_LIST = "description_list"
    const val DATA_CARD = "data_card"
    const val STAT_CARD = "stat_card"
    const val METRIC_CARD = "metric_card"
    const val KPI_CARD = "kpi_card"
    const val CHART = "chart"
    const val BAR_CHART = "bar_chart"
    const val LINE_CHART = "line_chart"
    const val PIE_CHART = "pie_chart"
    const val TIMELINE = "timeline"
    const val CALENDAR = "calendar"
    const val SCHEDULE = "schedule"
    const val KANBAN = "kanban"
    const val DATA_LIST = "data_list"
    const val COMPARISON_TABLE = "comparison_table"
    const val SUMMARY_CARD = "summary_card"
    const val DETAIL_VIEW = "detail_view"

    // ==================== 9. 媒体 Media ====================
    const val VIDEO_PLAYER = "video_player"
    const val AUDIO_PLAYER = "audio_player"
    const val GALLERY = "gallery"
    const val IMAGE_GRID = "image_grid"
    const val IMAGE_CAROUSEL = "image_carousel"
    const val VIDEO_THUMBNAIL = "video_thumbnail"
    const val AUDIO_WAVE = "audio_wave"
    const val MEDIA_CARD = "media_card"
    const val MEDIA_GRID = "media_grid"
    const val MEDIA_LIST = "media_list"
    const val MEDIA_PREVIEW = "media_preview"
    const val THUMBNAIL_GRID = "thumbnail_grid"
    const val AUDIO_THUMBNAIL = "audio_thumbnail"
    const val FILE_PREVIEW = "file_preview"
    const val DOCUMENT_VIEWER = "document_viewer"
    const val IMAGE_VIEWER = "image_viewer"
    const val VIDEO_VIEWER = "video_viewer"
    const val PHOTO_GRID = "photo_grid"
    const val VIDEO_GRID = "video_grid"
    const val AUDIO_LIST = "audio_list"
    const val PLAYLIST = "playlist"
    const val MEDIA_BROWSER = "media_browser"
    const val MEDIA_PICKER = "media_picker"
    const val CAMERA_PREVIEW = "camera_preview"
    const val SCREEN_CAPTURE = "screen_capture"
    const val MEDIA_STREAM = "media_stream"
    const val EMBED = "embed"
    const val IFRAME = "iframe"
    const val WEB_VIEW = "web_view"

    // ==================== 10. 表面 Surface ====================
    const val CARD = "card"
    const val MODAL_SHEET = "modal_sheet"
    const val BOTTOM_SHEET = "bottom_sheet"
    const val SIDE_SHEET = "side_sheet"
    const val POPOVER = "popover"
    const val DRAWER = "drawer"
    const val OVERLAY = "overlay"
    const val DIM_OVERLAY = "dim_overlay"
    const val SCRIM = "scrim"
    const val BANNER_SURFACE = "banner_surface"
    const val TOOLBAR = "toolbar"
    const val APP_BAR = "app_bar"
    const val TOP_BAR = "top_bar"
    const val HEADER = "header"
    const val FOOTER = "footer"
    const val SIDEBAR = "sidebar"
    const val PANEL = "panel"
    const val ELEVATED_SURFACE = "elevated_surface"
    const val FLAT_SURFACE = "flat_surface"
    const val OUTLINED_SURFACE = "outlined_surface"
    const val GLASS_CARD = "glass_card"
    const val NEUMORPHIC = "neumorphic"
    const val GRADIENT_SURFACE = "gradient_surface"
    const val MESH_SURFACE = "mesh_surface"
    const val PATTERN_SURFACE = "pattern_surface"
    const val BLUR_CONTAINER = "blur_container"
    const val SHADOW_BOX = "shadow_box"
    const val BORDER_BOX = "border_box"
    const val ROUNDED_SURFACE = "rounded_surface"
    const val TONAL_SURFACE = "tonal_surface"

    // ============================================================
    // UI Patterns — UI 模式（组合层，AI 可直接引用的高级组件）
    // ============================================================
    const val INFO_CARD_PATTERN = "info_card"
    const val STAT_CARD_PATTERN = "stat_card_pattern"
    const val MEDIA_CARD_PATTERN = "media_card_pattern"
    const val LIST_ITEM_PATTERN = "list_item_pattern"
    const val SECTION_PATTERN = "section"
    const val HEADER_BAR_PATTERN = "header_bar"
    const val EMPTY_STATE_PATTERN = "empty_state_pattern"
    const val LOADING_STATE_PATTERN = "loading_state_pattern"
    const val CHIP_ROW_PATTERN = "chip_row"
    const val RATING_ROW_PATTERN = "rating_row"
    const val PROGRESS_LABEL_PATTERN = "progress_label"
    const val USER_AVATAR_ROW_PATTERN = "user_avatar_row"
    const val TWO_COLUMN_GRID_PATTERN = "two_column_grid"
    // 页面模板
    const val LIST_PAGE_TEMPLATE = "list_page"
    const val DETAIL_PAGE_TEMPLATE = "detail_page"
    const val DASHBOARD_TEMPLATE = "dashboard"
    const val FORM_PAGE_TEMPLATE = "form_page"
    const val PROFILE_PAGE_TEMPLATE = "profile_page"
    // 社交模式
    const val POST_CARD_PATTERN = "post_card"
    const val CHAT_BUBBLE_PATTERN = "chat_bubble"
    const val STORY_RING_PATTERN = "story_ring"
    // 电商模式
    const val PRODUCT_CARD_PATTERN = "product_card"
    const val ORDER_CARD_PATTERN = "order_card"
    const val PRICE_TAG_PATTERN = "price_tag"
    // 表单模式
    const val FORM_GROUP_PATTERN = "form_group"
    const val FORM_FIELD_PATTERN = "form_field"
    // 导航模式
    const val BOTTOM_TABS_PATTERN = "bottom_tabs"
    const val SETTINGS_GROUP_PATTERN = "settings_group"
    // 数据展示模式
    const val DATA_ROW_PATTERN = "data_row"
    const val METRIC_GROUP_PATTERN = "metric_group"
    const val TIMELINE_ITEM_PATTERN = "timeline_item"
    // 功能入口
    const val FEATURE_GRID_PATTERN = "feature_grid"
    const val ACTION_SHEET_PATTERN = "action_sheet_pattern"

    /**
     * 所有组件类型集合
     */
    val ALL_TYPES: Set<String> = setOf(
        // Layout
        COLUMN, ROW, BOX, CONTAINER, SPACER, DIVIDER, SCROLL, GRID, FLOW, STACK,
        WRAP, EXPANDED, FLEXIBLE, CONSTRAINED_BOX, ASPECT_RATIO, ALIGN, CENTER,
        PADDING_CONTAINER, MARGIN_CONTAINER, ANIMATED_CONTAINER, SIZED_BOX,
        CLIP, TRANSFORM, OPACITY, VISIBILITY, POSITIONED, FLEX_ROW, FLEX_COLUMN,
        NESTED_SCROLL, CUSTOM_SCROLL,
        // Typography
        TEXT, HEADING1, HEADING2, HEADING3, HEADING4, HEADING5, HEADING6,
        TITLE, SUBTITLE, BODY, CAPTION, OVERLINE, LABEL, HEADLINE, SUBHEAD,
        DISPLAY1, DISPLAY2, DISPLAY3, MONOSPACE, CODE_BLOCK, MARKDOWN, RICH_TEXT,
        TEXT_SPAN, SELECTABLE_TEXT, LINK_TEXT, QUOTE, EMPHASIS, STRIKETHROUGH, UNDERLINE,
        // Button
        BUTTON, ICON_BUTTON, FAB, EXTENDED_FAB, SPLIT_BUTTON, TOGGLE_BUTTON,
        SEGMENTED_BUTTON, BUTTON_GROUP, FLOATING_BUTTON, DROPDOWN_BUTTON,
        TEXT_BUTTON, OUTLINED_BUTTON, FILLED_BUTTON, ELEVATED_BUTTON, TONAL_BUTTON,
        ACTION_CHIP, SPEED_DIAL, SUBMIT_BUTTON, CANCEL_BUTTON, CONFIRM_BUTTON,
        LINK_BUTTON, SHARE_BUTTON, DOWNLOAD_BUTTON, UPLOAD_BUTTON,
        PLAY_BUTTON, PAUSE_BUTTON, STOP_BUTTON, RELOAD_BUTTON,
        ADD_BUTTON, DELETE_BUTTON,
        // Input
        TEXT_FIELD, TEXT_AREA, PASSWORD_FIELD, SEARCH_FIELD, CHECKBOX, RADIO,
        RADIO_GROUP, SWITCH, SLIDER, RANGE_SLIDER, DROPDOWN, SELECT, MULTI_SELECT,
        DATE_PICKER, TIME_PICKER, DATE_TIME_PICKER, COLOR_PICKER, FILE_PICKER,
        AUTOCOMPLETE, OTP_INPUT, PIN_INPUT, STEPPER, FORM, FORM_FIELD,
        FORM_GROUP, TOGGLE_GROUP, SEGMENTED_CONTROL, SEARCH_BAR, RATING_INPUT,
        // Display
        IMAGE, ICON, AVATAR, BADGE, CHIP, TAG, STATUS_INDICATOR, PROGRESS,
        CIRCULAR_PROGRESS, LINEAR_PROGRESS, SKELETON, SHIMMER, PLACEHOLDER,
        EMPTY_STATE, TOOLTIP, RATING, STAR_RATING, COUNTER, BADGE_COUNT,
        NOTIFICATION_BADGE, HERO_IMAGE, THUMBNAIL, LOGO, ILLUSTRATION,
        GRADIENT_BOX, ANIMATED_BOX, PARTICLE_EFFECT, PULSE_INDICATOR, WAVE_INDICATOR,
        // Navigation
        TABS, TAB_BAR, TAB_ITEM, NAV_BAR, NAV_RAIL, NAV_DRAWER, BOTTOM_NAV,
        BREADCRUMB, PAGINATION, PAGE_INDICATOR, STEPPER_NAV, LINK, ANCHOR,
        MENU, DROPDOWN_MENU, CONTEXT_MENU, POPUP_MENU, SIDE_MENU, HAMBURGER_MENU,
        TAB_VIEW, PAGE_VIEW, CAROUSEL_NAV, ARROW_NAV, BACK_BUTTON, FORWARD_BUTTON,
        UP_BUTTON, MENU_BUTTON, EXPAND_BUTTON, COLLAPSE_BUTTON, NAV_ITEM,
        // Feedback
        DIALOG, ALERT_DIALOG, MODAL, SNACKBAR, TOAST, BANNER, ALERT, WARNING,
        ERROR_DISPLAY, SUCCESS_MESSAGE, INFO_BANNER, PROGRESS_DIALOG,
        LOADING_OVERLAY, ERROR_STATE, NO_DATA, NO_RESULT, CONFIRMATION_DIALOG,
        ACTION_DIALOG, INFO_DIALOG, WARNING_DIALOG, ERROR_DIALOG, TIP, HINT,
        NOTIFICATION, INLINE_MESSAGE, SYSTEM_MESSAGE, STATUS_BAR,
        REVIEW_PROMPT, FEEDBACK_FORM,
        BOTTOM_SHEET_DIALOG, INPUT_DIALOG, LIST_DIALOG, FULLSCREEN_DIALOG,
        RATING_DIALOG, DATE_PICKER_DIALOG, SIMPLE_DIALOG, PERMISSION_DIALOG,
        DELETE_CONFIRM_DIALOG, ABOUT_DIALOG, SHARE_DIALOG,
        // Data
        TABLE, DATA_TABLE, LIST, LIST_ITEM, LIST_SECTION, GRID_VIEW, TREE_VIEW,
        ACCORDION, EXPANSION_TILE, COLLAPSE, FACT_SET, KEY_VALUE, DESCRIPTION_LIST,
        DATA_CARD, STAT_CARD, METRIC_CARD, KPI_CARD, CHART, BAR_CHART, LINE_CHART,
        PIE_CHART, TIMELINE, CALENDAR, SCHEDULE, KANBAN, DATA_LIST,
        COMPARISON_TABLE, SUMMARY_CARD, DETAIL_VIEW,
        // Media
        VIDEO_PLAYER, AUDIO_PLAYER, GALLERY, IMAGE_GRID, IMAGE_CAROUSEL,
        VIDEO_THUMBNAIL, AUDIO_WAVE, MEDIA_CARD, MEDIA_GRID, MEDIA_LIST,
        MEDIA_PREVIEW, THUMBNAIL_GRID, AUDIO_THUMBNAIL, FILE_PREVIEW,
        DOCUMENT_VIEWER, IMAGE_VIEWER, VIDEO_VIEWER, PHOTO_GRID, VIDEO_GRID,
        AUDIO_LIST, PLAYLIST, MEDIA_BROWSER, MEDIA_PICKER, CAMERA_PREVIEW,
        SCREEN_CAPTURE, MEDIA_STREAM, EMBED, IFRAME, WEB_VIEW,
        // Surface
        CARD, MODAL_SHEET, BOTTOM_SHEET, SIDE_SHEET, POPOVER, DRAWER, OVERLAY,
        DIM_OVERLAY, SCRIM, BANNER_SURFACE, TOOLBAR, APP_BAR, TOP_BAR, HEADER,
        FOOTER, SIDEBAR, PANEL, ELEVATED_SURFACE, FLAT_SURFACE, OUTLINED_SURFACE,
        GLASS_CARD, NEUMORPHIC, GRADIENT_SURFACE, MESH_SURFACE, PATTERN_SURFACE,
        BLUR_CONTAINER, SHADOW_BOX, BORDER_BOX, ROUNDED_SURFACE, TONAL_SURFACE,
        // UI Patterns
        INFO_CARD_PATTERN, STAT_CARD_PATTERN, MEDIA_CARD_PATTERN, LIST_ITEM_PATTERN,
        SECTION_PATTERN, HEADER_BAR_PATTERN, EMPTY_STATE_PATTERN, LOADING_STATE_PATTERN,
        CHIP_ROW_PATTERN, RATING_ROW_PATTERN, PROGRESS_LABEL_PATTERN, USER_AVATAR_ROW_PATTERN,
        TWO_COLUMN_GRID_PATTERN, LIST_PAGE_TEMPLATE, DETAIL_PAGE_TEMPLATE, DASHBOARD_TEMPLATE,
        FORM_PAGE_TEMPLATE, PROFILE_PAGE_TEMPLATE, POST_CARD_PATTERN, CHAT_BUBBLE_PATTERN,
        STORY_RING_PATTERN, PRODUCT_CARD_PATTERN, ORDER_CARD_PATTERN, PRICE_TAG_PATTERN,
        FORM_GROUP_PATTERN, FORM_FIELD_PATTERN, BOTTOM_TABS_PATTERN, SETTINGS_GROUP_PATTERN,
        DATA_ROW_PATTERN, METRIC_GROUP_PATTERN, TIMELINE_ITEM_PATTERN,
        FEATURE_GRID_PATTERN, ACTION_SHEET_PATTERN
    )

    /**
     * 领域分类
     */
    val LAYOUT_TYPES = setOf(COLUMN, ROW, BOX, CONTAINER, SPACER, DIVIDER, SCROLL, GRID, FLOW, STACK, WRAP, EXPANDED, FLEXIBLE, CONSTRAINED_BOX, ASPECT_RATIO, ALIGN, CENTER, PADDING_CONTAINER, MARGIN_CONTAINER, ANIMATED_CONTAINER, SIZED_BOX, CLIP, TRANSFORM, OPACITY, VISIBILITY, POSITIONED, FLEX_ROW, FLEX_COLUMN, NESTED_SCROLL, CUSTOM_SCROLL)

    val TYPOGRAPHY_TYPES = setOf(TEXT, HEADING1, HEADING2, HEADING3, HEADING4, HEADING5, HEADING6, TITLE, SUBTITLE, BODY, CAPTION, OVERLINE, LABEL, HEADLINE, SUBHEAD, DISPLAY1, DISPLAY2, DISPLAY3, MONOSPACE, CODE_BLOCK, MARKDOWN, RICH_TEXT, TEXT_SPAN, SELECTABLE_TEXT, LINK_TEXT, QUOTE, EMPHASIS, STRIKETHROUGH, UNDERLINE)

    val BUTTON_TYPES = setOf(BUTTON, ICON_BUTTON, FAB, EXTENDED_FAB, SPLIT_BUTTON, TOGGLE_BUTTON, SEGMENTED_BUTTON, BUTTON_GROUP, FLOATING_BUTTON, DROPDOWN_BUTTON, TEXT_BUTTON, OUTLINED_BUTTON, FILLED_BUTTON, ELEVATED_BUTTON, TONAL_BUTTON, ACTION_CHIP, SPEED_DIAL, SUBMIT_BUTTON, CANCEL_BUTTON, CONFIRM_BUTTON, LINK_BUTTON, SHARE_BUTTON, DOWNLOAD_BUTTON, UPLOAD_BUTTON, PLAY_BUTTON, PAUSE_BUTTON, STOP_BUTTON, RELOAD_BUTTON, ADD_BUTTON, DELETE_BUTTON)

    val INPUT_TYPES = setOf(TEXT_FIELD, TEXT_AREA, PASSWORD_FIELD, SEARCH_FIELD, CHECKBOX, RADIO, RADIO_GROUP, SWITCH, SLIDER, RANGE_SLIDER, DROPDOWN, SELECT, MULTI_SELECT, DATE_PICKER, TIME_PICKER, DATE_TIME_PICKER, COLOR_PICKER, FILE_PICKER, AUTOCOMPLETE, OTP_INPUT, PIN_INPUT, STEPPER, FORM, FORM_FIELD, FORM_GROUP, TOGGLE_GROUP, SEGMENTED_CONTROL, SEARCH_BAR, RATING_INPUT)

    val DISPLAY_TYPES = setOf(IMAGE, ICON, AVATAR, BADGE, CHIP, TAG, STATUS_INDICATOR, PROGRESS, CIRCULAR_PROGRESS, LINEAR_PROGRESS, SKELETON, SHIMMER, PLACEHOLDER, EMPTY_STATE, TOOLTIP, RATING, STAR_RATING, COUNTER, BADGE_COUNT, NOTIFICATION_BADGE, HERO_IMAGE, THUMBNAIL, LOGO, ILLUSTRATION, GRADIENT_BOX, ANIMATED_BOX, PARTICLE_EFFECT, PULSE_INDICATOR, WAVE_INDICATOR)

    val NAVIGATION_TYPES = setOf(TABS, TAB_BAR, TAB_ITEM, NAV_BAR, NAV_RAIL, NAV_DRAWER, BOTTOM_NAV, BREADCRUMB, PAGINATION, PAGE_INDICATOR, STEPPER_NAV, LINK, ANCHOR, MENU, DROPDOWN_MENU, CONTEXT_MENU, POPUP_MENU, SIDE_MENU, HAMBURGER_MENU, TAB_VIEW, PAGE_VIEW, CAROUSEL_NAV, ARROW_NAV, BACK_BUTTON, FORWARD_BUTTON, UP_BUTTON, MENU_BUTTON, EXPAND_BUTTON, COLLAPSE_BUTTON, NAV_ITEM)

    val FEEDBACK_TYPES = setOf(DIALOG, ALERT_DIALOG, MODAL, SNACKBAR, TOAST, BANNER, ALERT, WARNING, ERROR_DISPLAY, SUCCESS_MESSAGE, INFO_BANNER, PROGRESS_DIALOG, LOADING_OVERLAY, ERROR_STATE, NO_DATA, NO_RESULT, CONFIRMATION_DIALOG, ACTION_DIALOG, INFO_DIALOG, WARNING_DIALOG, ERROR_DIALOG, TIP, HINT, NOTIFICATION, INLINE_MESSAGE, SYSTEM_MESSAGE, STATUS_BAR, REVIEW_PROMPT, FEEDBACK_FORM, BOTTOM_SHEET_DIALOG, INPUT_DIALOG, LIST_DIALOG, FULLSCREEN_DIALOG, RATING_DIALOG, DATE_PICKER_DIALOG, SIMPLE_DIALOG, PERMISSION_DIALOG, DELETE_CONFIRM_DIALOG, ABOUT_DIALOG, SHARE_DIALOG)

    val DATA_TYPES = setOf(TABLE, DATA_TABLE, LIST, LIST_ITEM, LIST_SECTION, GRID_VIEW, TREE_VIEW, ACCORDION, EXPANSION_TILE, COLLAPSE, FACT_SET, KEY_VALUE, DESCRIPTION_LIST, DATA_CARD, STAT_CARD, METRIC_CARD, KPI_CARD, CHART, BAR_CHART, LINE_CHART, PIE_CHART, TIMELINE, CALENDAR, SCHEDULE, KANBAN, DATA_LIST, COMPARISON_TABLE, SUMMARY_CARD, DETAIL_VIEW)

    val MEDIA_TYPES = setOf(VIDEO_PLAYER, AUDIO_PLAYER, GALLERY, IMAGE_GRID, IMAGE_CAROUSEL, VIDEO_THUMBNAIL, AUDIO_WAVE, MEDIA_CARD, MEDIA_GRID, MEDIA_LIST, MEDIA_PREVIEW, THUMBNAIL_GRID, AUDIO_THUMBNAIL, FILE_PREVIEW, DOCUMENT_VIEWER, IMAGE_VIEWER, VIDEO_VIEWER, PHOTO_GRID, VIDEO_GRID, AUDIO_LIST, PLAYLIST, MEDIA_BROWSER, MEDIA_PICKER, CAMERA_PREVIEW, SCREEN_CAPTURE, MEDIA_STREAM, EMBED, IFRAME, WEB_VIEW)

    val SURFACE_TYPES = setOf(CARD, MODAL_SHEET, BOTTOM_SHEET, SIDE_SHEET, POPOVER, DRAWER, OVERLAY, DIM_OVERLAY, SCRIM, BANNER_SURFACE, TOOLBAR, APP_BAR, TOP_BAR, HEADER, FOOTER, SIDEBAR, PANEL, ELEVATED_SURFACE, FLAT_SURFACE, OUTLINED_SURFACE, GLASS_CARD, NEUMORPHIC, GRADIENT_SURFACE, MESH_SURFACE, PATTERN_SURFACE, BLUR_CONTAINER, SHADOW_BOX, BORDER_BOX, ROUNDED_SURFACE, TONAL_SURFACE)

    val PATTERN_TYPES = setOf(
        INFO_CARD_PATTERN, STAT_CARD_PATTERN, MEDIA_CARD_PATTERN, LIST_ITEM_PATTERN,
        SECTION_PATTERN, HEADER_BAR_PATTERN, EMPTY_STATE_PATTERN, LOADING_STATE_PATTERN,
        CHIP_ROW_PATTERN, RATING_ROW_PATTERN, PROGRESS_LABEL_PATTERN, USER_AVATAR_ROW_PATTERN,
        TWO_COLUMN_GRID_PATTERN, LIST_PAGE_TEMPLATE, DETAIL_PAGE_TEMPLATE, DASHBOARD_TEMPLATE,
        FORM_PAGE_TEMPLATE, PROFILE_PAGE_TEMPLATE, POST_CARD_PATTERN, CHAT_BUBBLE_PATTERN,
        STORY_RING_PATTERN, PRODUCT_CARD_PATTERN, ORDER_CARD_PATTERN, PRICE_TAG_PATTERN,
        FORM_GROUP_PATTERN, FORM_FIELD_PATTERN, BOTTOM_TABS_PATTERN, SETTINGS_GROUP_PATTERN,
        DATA_ROW_PATTERN, METRIC_GROUP_PATTERN, TIMELINE_ITEM_PATTERN,
        FEATURE_GRID_PATTERN, ACTION_SHEET_PATTERN
    )

    /**
     * 领域映射
     */
    val DOMAIN_MAP: Map<String, Set<String>> = mapOf(
        "Layout" to LAYOUT_TYPES,
        "Typography" to TYPOGRAPHY_TYPES,
        "Button" to BUTTON_TYPES,
        "Input" to INPUT_TYPES,
        "Display" to DISPLAY_TYPES,
        "Navigation" to NAVIGATION_TYPES,
        "Feedback" to FEEDBACK_TYPES,
        "Data" to DATA_TYPES,
        "Media" to MEDIA_TYPES,
        "Surface" to SURFACE_TYPES
    )

    // ==================== 15. 数据可视化 DataViz（扩展） ====================
    const val DONUT_CHART = "donut_chart"
    const val HEAT_STRIP = "heat_strip"
    const val SPARKLINE = "sparkline"
    const val GAUGE = "gauge"
    const val STAT_TILE = "stat_tile"
    const val PROGRESS_RING = "progress_ring"

    // ==================== 16. 交互控件 Interaction（扩展） ====================
    const val RATING_BAR = "rating_bar"
    const val SWITCH_TOGGLE = "switch_toggle"
    const val CHECKBOX_ITEM = "checkbox_item"
    const val COUNTDOWN_TIMER = "countdown_timer"
    const val CHIP_FILTER = "chip_filter"

    // ==================== 17. 动漫 / 人物 / 美术 Character & Art ====================
    const val AVATAR_GROUP = "avatar_group"
    const val CHARACTER_CARD = "character_card"
    const val MOOD_BADGE = "mood_badge"
    const val GRADIENT_ORB = "gradient_orb"
    const val STICKER_EMOJI = "sticker_emoji"
    const val RANK_MEDAL = "rank_medal"
    const val SPEECH_BUBBLE = "speech_bubble"

    // ==================== 18. 布局 / 媒体（扩展） ====================
    const val BANNER_HERO = "banner_hero"
    const val TIMER_PROGRESS = "timer_progress"

    // ==================== 19-25. 扩展领域 v1.5.0（漫画/特效/音频/游戏化/效率/电商/健康/金融/教育/旅行） ====================
    // ═══ 19. 漫画叙事 ═══
    const val COMIC_PANEL = "comic_panel"
    const val CAPTION_BOX = "caption_box"
    const val ACTION_LINES = "action_lines"
    const val PANEL_STRIP = "panel_strip"
    const val MANGA_BUBBLE = "manga_bubble"
    // ═══ 20. 粒子特效 ═══
    const val PARTICLE_BURST = "particle_burst"
    const val CONFETTI_FIELD = "confetti_field"
    const val SPARKLE_RAIN = "sparkle_rain"
    const val RAIN_EFFECT = "rain_effect"
    const val PULSE_RING = "pulse_ring"
    // ═══ 21. 音频媒体 ═══
    const val EQUALIZER_BARS = "equalizer_bars"
    const val PLAYER_BAR = "player_bar"
    const val VIDEO_CARD = "video_card"
    // ═══ 22. 游戏化 ═══
    const val XP_BAR = "xp_bar"
    const val HP_BAR = "hp_bar"
    const val COIN_STACK = "coin_stack"
    const val QUEST_CARD = "quest_card"
    const val LEADERBOARD_ROW = "leaderboard_row"
    const val STREAK_FLAME = "streak_flame"
    // ═══ 23. 办公效率 ═══
    const val TODO_ITEM = "todo_item"
    const val KANBAN_COLUMN = "kanban_column"
    const val MEETING_CARD = "meeting_card"
    const val CALENDAR_STRIP = "calendar_strip"
    // ═══ 24. 电商 ═══
    const val COUPON_TICKET = "coupon_ticket"
    const val FLASH_SALE = "flash_sale"
    const val REVIEW_ROW = "review_row"
    const val SHIPPING_TRACK = "shipping_track"
    // ═══ 25. 健康金融教育旅行 ═══
    const val ACTIVITY_RINGS = "activity_rings"
    const val STEP_COUNTER = "step_counter"
    const val WATER_TRACK = "water_track"
    const val CALORIE_RING = "calorie_ring"
    const val CANDLE_CHART = "candle_chart"
    const val PRICE_DELTA = "price_delta"
    const val WALLET_CARD = "wallet_card"
    const val FLASH_CARD = "flash_card"
    const val QUIZ_OPTION = "quiz_option"
    const val BOARDING_PASS = "boarding_pass"


    // ==================== 26-54. 领域组件 v1.6.0（29 领域全覆盖） ====================
    const val PET_CARD = "pet_card"
    const val PET_STATE = "pet_state"
    const val LIVE_ROOM_CARD = "live_room_card"
    const val GIFT_BANNER = "gift_banner"
    const val FEED_CARD = "feed_card"
    const val MOMENTS_GRID = "moments_grid"
    const val GAME_HUD = "game_hud"
    const val GAME_PAD = "game_pad"
    const val LOOT_BOX = "loot_box"
    const val TERMINAL_VIEW = "terminal_view"
    const val COMMAND_HINT = "command_hint"
    const val TREEMAP_TILE = "treemap_tile"
    const val FUNNEL_CHART = "funnel_chart"
    const val MODAL_CONFIRM = "modal_confirm"
    const val TOAST_PILL = "toast_pill"
    const val BROWSER_BAR = "browser_bar"
    const val WEB_PREVIEW_CARD = "web_preview_card"
    const val ANDROID_STATUS_BAR = "android_status_bar"
    const val NOTIFICATION_SHADE = "notification_shade"
    const val MEME_GRID = "meme_grid"
    const val MEME_LARGE = "meme_large"
    const val PHONE_MOCKUP = "phone_mockup"
    const val CAPSULE_PILL = "capsule_pill"
    const val DYNAMIC_ISLAND = "dynamic_island"
    const val CODE_EDITOR_LINE = "code_editor_line"
    const val DIFF_ROW = "diff_row"
    const val NODE_BOX = "node_box"
    const val NODE_CONNECTOR = "node_connector"
    const val RUNTIME_LOG_ROW = "runtime_log_row"
    const val MEMORY_METER = "memory_meter"
    const val VOICE_MESSAGE = "voice_message"
    const val MIC_BUTTON = "mic_button"
    const val DOC_PARAGRAPH = "doc_paragraph"
    const val DOC_HEADING = "doc_heading"
    const val PULL_QUOTE = "pull_quote"
    const val SPEC_BADGE = "spec_badge"
    const val COMPLIANCE_CHECK = "compliance_check"
    const val TABLE_SORT_HEADER = "table_sort_header"
    const val WEATHER_BIG = "weather_big"
    const val HOURLY_FORECAST = "hourly_forecast"
    const val WORLD_CLOCK_ROW = "world_clock_row"
    const val TIME_BADGE = "time_badge"
    const val ACTION_CARD = "action_card"
    const val QUICK_ACTION_GRID = "quick_action_grid"
    const val ELEVATED_CARD = "elevated_card"
    const val STACKED_CARDS = "stacked_cards"
    const val EMOTION_FACE = "emotion_face"
    const val MOOD_TRACKER_WEEK = "mood_tracker_week"
    const val TAG_CLOUD = "tag_cloud"
    const val LABEL_PILL = "label_pill"
    const val TODO_GROUP = "todo_group"
    const val TODO_PROGRESS = "todo_progress"
    const val PLAYLIST_ROW = "playlist_row"
    const val MINI_PLAYER = "mini_player"

    // ==================== 55-68. 领域组件 v1.7.0（漂浮/小说/像素/授权/地图/闹钟/邮箱/公众号/文件/智能体/会员/互联网/作品/点赞/消息/电脑/GenUI） ====================
    const val FLOAT_PANEL = "float_panel"
    const val FLOW_BACKGROUND = "flow_background"
    const val NOVEL_READER = "novel_reader"
    const val CHAPTER_ROW = "chapter_row"
    const val PIXEL_AVATAR = "pixel_avatar"
    const val PIXEL_BANNER = "pixel_banner"
    const val LICENSE_CARD = "license_card"
    const val AUTH_STEP_ROW = "auth_step_row"
    const val MAP_PIN_CARD = "map_pin_card"
    const val ROUTE_STEPS = "route_steps"
    const val APP_ICON = "app_icon"
    const val ICON_GRID = "icon_grid"
    const val SCENERY_CARD = "scenery_card"
    const val ALARM_ROW = "alarm_row"
    const val ALARM_RING = "alarm_ring"
    const val MAIL_ROW = "mail_row"
    const val OFFICIAL_ACCOUNT_CARD = "official_account_card"
    const val ARTICLE_ROW = "article_row"
    const val FILE_ROW = "file_row"
    const val STORAGE_METER = "storage_meter"
    const val THEME_PICKER_ROW = "theme_picker_row"
    const val FONT_PREVIEW_ROW = "font_preview_row"
    const val AGENT_CARD = "agent_card"
    const val AI_THINKING = "ai_thinking"
    const val AI_CHAT_BUBBLE = "ai_chat_bubble"
    const val FOCUS_TIMER = "focus_timer"
    const val VIP_BANNER = "vip_banner"
    const val PRICING_CARD = "pricing_card"
    const val SPEED_TEST = "speed_test"
    const val CONNECTION_STATUS = "connection_status"
    const val PORTFOLIO_CARD = "portfolio_card"
    const val WORK_STATS = "work_stats"
    const val LIKE_BUTTON = "like_button"
    const val LIKE_LIST_ROW = "like_list_row"
    const val CHAT_ROW = "chat_row"
    const val MESSAGE_COMPOSER = "message_composer"
    const val DESKTOP_WINDOW = "desktop_window"
    const val TASKBAR_DOCK = "taskbar_dock"
    const val GENUI_INTRO_CARD = "genui_intro_card"
    const val GENUI_FEATURE_ROW = "genui_feature_row"
    const val HELP_FAQ_ROW = "help_faq_row"
    const val FEEDBACK_BOX = "feedback_box"

    // ==================== 69-96. v1.8.0（3D/维度/宇宙/颗粒/搜索/问候/饮食/压缩/背景/键盘/支付/服务器/环境/权限/开关/HTML/Web/IDE/记忆/执行/日历/通讯录/短信/录音/目录/面板/下载/元素/滚动/滑动/长按） ====================
    const val CUBE_3D = "cube_3d"
    const val ISO_CARD = "iso_card"
    const val FLAT_SHAPES = "flat_shapes"
    const val DIMENSION_AXIS = "dimension_axis"
    const val COSMOS_SCENE = "cosmos_scene"
    const val PLANET_CARD = "planet_card"
    const val GRAIN_OVERLAY = "grain_overlay"
    const val PARTICLE_DRIFT = "particle_drift"
    const val NEBULA_PILL = "nebula_pill"
    const val ORBIT_RING = "orbit_ring"
    const val SEARCH_RESULT_ROW = "search_result_row"
    const val GREETING_HERO = "greeting_hero"
    const val WELCOME_BANNER = "welcome_banner"
    const val MEAL_CARD = "meal_card"
    const val DIET_SUMMARY = "diet_summary"
    const val COMPRESS_CARD = "compress_card"
    const val ARCHIVE_ROW = "archive_row"
    const val BG_MESH = "bg_mesh"
    const val BG_GRID_GLOW = "bg_grid_glow"
    const val KEYBOARD_INPUT = "keyboard_input"
    const val PAY_SHEET = "pay_sheet"
    const val PAY_SUCCESS = "pay_success"
    const val SERVER_ROW = "server_row"
    const val SERVER_STATUS_PILL = "server_status_pill"
    const val DEV_ENV_CARD = "dev_env_card"
    const val DEPENDENCY_ROW = "dependency_row"
    const val RUNTIME_ENV_CARD = "runtime_env_card"
    const val DEVICE_ENV_CARD = "device_env_card"
    const val PERMISSION_CARD = "permission_card"
    const val PERMISSION_PROMPT = "permission_prompt"
    const val BIG_SWITCH = "big_switch"
    const val HTML_TAG_VIEW = "html_tag_view"
    const val WEB_LANDING = "web_landing"
    const val WEB_NAV_BAR = "web_nav_bar"
    const val IDE_WINDOW = "ide_window"
    const val IDE_TAB_ROW = "ide_tab_row"
    const val MEMORY_CARD = "memory_card"
    const val MEMORY_TIMELINE = "memory_timeline"
    const val EXEC_STEP = "exec_step"
    const val EXEC_PROGRESS = "exec_progress"
    const val CALENDAR_MONTH = "calendar_month"
    const val EVENT_ROW = "event_row"
    const val CONTACT_ROW = "contact_row"
    const val SMS_BUBBLE = "sms_bubble"
    const val SMS_CODE_ROW = "sms_code_row"
    const val RECORDING_BAR = "recording_bar"
    const val DIR_TREE = "dir_tree"
    const val PANEL_DOCKED = "panel_docked"
    const val DOWNLOAD_ROW = "download_row"
    const val ELEMENT_CARD = "element_card"
    const val SCROLL_INDICATOR = "scroll_indicator"
    const val PAGER_DOTS = "pager_dots"
    const val LONG_PRESS_HINT = "long_press_hint"

    // ==================== 97. 游戏互动（对话式游戏架构） ====================
    const val DICE_DISPLAY = "dice_display"
    const val CHOICE_GRID = "choice_grid"
}

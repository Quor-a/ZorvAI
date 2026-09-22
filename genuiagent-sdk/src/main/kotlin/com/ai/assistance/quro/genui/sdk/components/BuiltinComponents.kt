package com.ai.assistance.quro.genui.sdk.components

import com.ai.assistance.quro.genui.sdk.dsl.ComponentTypes
import com.ai.assistance.quro.genui.sdk.render.ComponentRegistry
import com.ai.assistance.quro.genui.sdk.components.patterns.InfoCardPattern
import com.ai.assistance.quro.genui.sdk.components.patterns.StatCardPattern
import com.ai.assistance.quro.genui.sdk.components.patterns.MediaCardPattern
import com.ai.assistance.quro.genui.sdk.components.patterns.ListItemPattern
import com.ai.assistance.quro.genui.sdk.components.patterns.SectionPattern
import com.ai.assistance.quro.genui.sdk.components.patterns.HeaderBarPattern
import com.ai.assistance.quro.genui.sdk.components.patterns.TwoColumnGridPattern
import com.ai.assistance.quro.genui.sdk.components.patterns.EmptyStatePattern
import com.ai.assistance.quro.genui.sdk.components.patterns.LoadingStatePattern
import com.ai.assistance.quro.genui.sdk.components.patterns.ChipRowPattern
import com.ai.assistance.quro.genui.sdk.components.patterns.RatingRowPattern
import com.ai.assistance.quro.genui.sdk.components.patterns.ProgressLabelPattern
import com.ai.assistance.quro.genui.sdk.components.patterns.UserAvatarRowPattern

/**
 * 内置组件注册表
 *
 * 注册全部 10 个领域、300+ 组件类型到对应的渲染器。
 * 每个类型通过变体系统支持 2500+ 变体组合（5 尺寸 × 5 风格 × 4 形状 × 5 状态 × 5 颜色），
 * 总计 300 × 2500 = 750,000+ 可渲染变体。
 *
 * 领域清单：
 *  1. Layout     — 30 类型（column, row, box, scroll, grid, ...）
 *  2. Typography — 29 类型（text, heading1-6, markdown, ...）
 *  3. Button     — 30 类型（button, fab, segmented_button, ...）
 *  4. Input      — 29 类型（text_field, checkbox, slider, ...）
 *  5. Display    — 29 类型（image, badge, progress, ...）
 *  6. Navigation — 30 类型（tabs, nav_bar, breadcrumb, ...）
 *  7. Feedback   — 29 类型（dialog, snackbar, banner, ...）
 *  8. Data       — 29 类型（table, chart, timeline, ...）
 *  9. Media      — 29 类型（video_player, gallery, web_view, ...）
 * 10. Surface    — 30 类型（card, bottom_sheet, app_bar, ...）
 */
object BuiltinComponents {

    private val shared by lazy {
        ComponentRegistry().also { register(it) }
    }

    /**
     * 所有内置组件类型集合（300+）
     */
    val types: Set<String> = ComponentTypes.ALL_TYPES

    /**
     * 获取共享的组件注册表实例
     */
    fun sharedRegistry(): ComponentRegistry = shared

    /**
     * 创建一个新的组件注册表并注册所有内置组件
     */
    fun createRegistry(): ComponentRegistry {
        return ComponentRegistry().also { register(it) }
    }

    /**
     * 向指定注册表注册全部 300+ 组件渲染器
     */
    fun register(registry: ComponentRegistry) {
        // ==================== 1. Layout 布局 ====================
        registry.register(ComponentTypes.COLUMN) { c, ctx -> ColumnRowRenderer(c, ctx) }
        registry.register(ComponentTypes.ROW) { c, ctx -> ColumnRowRenderer(c, ctx) }
        registry.register(ComponentTypes.BOX) { c, ctx -> BoxContainerRenderer(c, ctx) }
        registry.register(ComponentTypes.CONTAINER) { c, ctx -> BoxContainerRenderer(c, ctx) }
        registry.register(ComponentTypes.SPACER) { c, ctx -> SpacerRenderer(c, ctx) }
        registry.register(ComponentTypes.DIVIDER) { c, ctx -> LayoutDividerRenderer(c, ctx) }
        registry.register(ComponentTypes.SCROLL) { c, ctx -> ScrollRenderer(c, ctx) }
        registry.register(ComponentTypes.GRID) { c, ctx -> GridRenderer(c, ctx) }
        registry.register(ComponentTypes.FLOW) { c, ctx -> FlowRenderer(c, ctx) }
        registry.register(ComponentTypes.STACK) { c, ctx -> BoxContainerRenderer(c, ctx) }
        registry.register(ComponentTypes.WRAP) { c, ctx -> FlowRenderer(c, ctx) }
        registry.register(ComponentTypes.EXPANDED) { c, ctx -> BoxContainerRenderer(c, ctx) }
        registry.register(ComponentTypes.FLEXIBLE) { c, ctx -> BoxContainerRenderer(c, ctx) }
        registry.register(ComponentTypes.CONSTRAINED_BOX) { c, ctx -> BoxContainerRenderer(c, ctx) }
        registry.register(ComponentTypes.ASPECT_RATIO) { c, ctx -> BoxContainerRenderer(c, ctx) }
        registry.register(ComponentTypes.ALIGN) { c, ctx -> BoxContainerRenderer(c, ctx) }
        registry.register(ComponentTypes.CENTER) { c, ctx -> BoxContainerRenderer(c, ctx) }
        registry.register(ComponentTypes.PADDING_CONTAINER) { c, ctx -> PaddingContainerRenderer(c, ctx) }
        registry.register(ComponentTypes.MARGIN_CONTAINER) { c, ctx -> PaddingContainerRenderer(c, ctx) }
        registry.register(ComponentTypes.ANIMATED_CONTAINER) { c, ctx -> AnimatedContainerRenderer(c, ctx) }
        registry.register(ComponentTypes.SIZED_BOX) { c, ctx -> SizedBoxRenderer(c, ctx) }
        registry.register(ComponentTypes.CLIP) { c, ctx -> ClipRenderer(c, ctx) }
        registry.register(ComponentTypes.TRANSFORM) { c, ctx -> TransformRenderer(c, ctx) }
        registry.register(ComponentTypes.OPACITY) { c, ctx -> OpacityRenderer(c, ctx) }
        registry.register(ComponentTypes.VISIBILITY) { c, ctx -> VisibilityRenderer(c, ctx) }
        registry.register(ComponentTypes.POSITIONED) { c, ctx -> PositionedRenderer(c, ctx) }
        registry.register(ComponentTypes.FLEX_ROW) { c, ctx -> ColumnRowRenderer(c, ctx) }
        registry.register(ComponentTypes.FLEX_COLUMN) { c, ctx -> ColumnRowRenderer(c, ctx) }
        registry.register(ComponentTypes.NESTED_SCROLL) { c, ctx -> ScrollRenderer(c, ctx) }
        registry.register(ComponentTypes.CUSTOM_SCROLL) { c, ctx -> ScrollRenderer(c, ctx) }

        // ==================== 2. Typography 文本 ====================
        registry.register(ComponentTypes.TEXT) { c, ctx -> BodyTextRenderer(c, ctx) }
        registry.register(ComponentTypes.HEADING1) { c, ctx -> HeadingRenderer(c, ctx) }
        registry.register(ComponentTypes.HEADING2) { c, ctx -> HeadingRenderer(c, ctx) }
        registry.register(ComponentTypes.HEADING3) { c, ctx -> HeadingRenderer(c, ctx) }
        registry.register(ComponentTypes.HEADING4) { c, ctx -> HeadingRenderer(c, ctx) }
        // 常用别名：模型高频直接写 "heading"/"h1".."h6"（未注册会打"未知组件"警示并丢标题）
        registry.register("heading") { c, ctx -> HeadingRenderer(c, ctx) }
        registry.register("h1") { c, ctx -> HeadingRenderer(c, ctx) }
        registry.register("h2") { c, ctx -> HeadingRenderer(c, ctx) }
        registry.register("h3") { c, ctx -> HeadingRenderer(c, ctx) }
        registry.register("h4") { c, ctx -> HeadingRenderer(c, ctx) }
        registry.register("h5") { c, ctx -> HeadingRenderer(c, ctx) }
        registry.register("h6") { c, ctx -> HeadingRenderer(c, ctx) }
        registry.register("subtitle") { c, ctx -> BodyTextRenderer(c, ctx) }
        // 胶囊组件家族（15 类型：零配置胶囊——写组件名即胶囊，忘写 shape 不退化）
        PillFamily.registerAll(registry)
        // 漂浮宠物组件（AI 可写 {"type":"pet","properties":{"spec":{...}}}）
        registry.register("pet") { c, ctx -> PetComponentRenderer(c, ctx) }
        // 通知组件家族（30 类型：横幅/toast/进度/社交/聚合/警示/系统级）
        NotificationFamily.registerAll(registry)
        registry.register(ComponentTypes.HEADING5) { c, ctx -> HeadingRenderer(c, ctx) }
        registry.register(ComponentTypes.HEADING6) { c, ctx -> HeadingRenderer(c, ctx) }
        registry.register(ComponentTypes.TITLE) { c, ctx -> TitleSubtitleRenderer(c, ctx) }
        registry.register(ComponentTypes.SUBTITLE) { c, ctx -> TitleSubtitleRenderer(c, ctx) }
        registry.register(ComponentTypes.BODY) { c, ctx -> BodyTextRenderer(c, ctx) }
        registry.register(ComponentTypes.CAPTION) { c, ctx -> BodyTextRenderer(c, ctx) }
        registry.register(ComponentTypes.OVERLINE) { c, ctx -> BodyTextRenderer(c, ctx) }
        registry.register(ComponentTypes.LABEL) { c, ctx -> BodyTextRenderer(c, ctx) }
        registry.register(ComponentTypes.HEADLINE) { c, ctx -> HeadlineSubheadRenderer(c, ctx) }
        registry.register(ComponentTypes.SUBHEAD) { c, ctx -> HeadlineSubheadRenderer(c, ctx) }
        registry.register(ComponentTypes.DISPLAY1) { c, ctx -> DisplayRenderer(c, ctx) }
        registry.register(ComponentTypes.DISPLAY2) { c, ctx -> DisplayRenderer(c, ctx) }
        registry.register(ComponentTypes.DISPLAY3) { c, ctx -> DisplayRenderer(c, ctx) }
        registry.register(ComponentTypes.MONOSPACE) { c, ctx -> MonospaceRenderer(c, ctx) }
        registry.register(ComponentTypes.CODE_BLOCK) { c, ctx -> MonospaceRenderer(c, ctx) }
        registry.register(ComponentTypes.MARKDOWN) { c, ctx -> MarkdownRenderer(c, ctx) }
        registry.register(ComponentTypes.RICH_TEXT) { c, ctx -> RichTextRenderer(c, ctx) }
        registry.register(ComponentTypes.TEXT_SPAN) { c, ctx -> RichTextRenderer(c, ctx) }
        registry.register(ComponentTypes.SELECTABLE_TEXT) { c, ctx -> SelectableTextRenderer(c, ctx) }
        registry.register(ComponentTypes.LINK_TEXT) { c, ctx -> LinkTextRenderer(c, ctx) }
        registry.register(ComponentTypes.QUOTE) { c, ctx -> QuoteRenderer(c, ctx) }
        registry.register(ComponentTypes.EMPHASIS) { c, ctx -> EmphasisRenderer(c, ctx) }
        registry.register(ComponentTypes.STRIKETHROUGH) { c, ctx -> StrikethroughRenderer(c, ctx) }
        registry.register(ComponentTypes.UNDERLINE) { c, ctx -> UnderlineRenderer(c, ctx) }

        // ==================== 3. Button 按钮 ====================
        registry.register(ComponentTypes.BUTTON) { c, ctx -> MaterialButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.ICON_BUTTON) { c, ctx -> IconButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.FAB) { c, ctx -> FabRenderer(c, ctx) }
        registry.register(ComponentTypes.EXTENDED_FAB) { c, ctx -> FabRenderer(c, ctx) }
        registry.register(ComponentTypes.SPLIT_BUTTON) { c, ctx -> SplitButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.TOGGLE_BUTTON) { c, ctx -> ToggleButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.SEGMENTED_BUTTON) { c, ctx -> SegmentedButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.BUTTON_GROUP) { c, ctx -> ButtonGroupRenderer(c, ctx) }
        registry.register(ComponentTypes.FLOATING_BUTTON) { c, ctx -> FabRenderer(c, ctx) }
        registry.register(ComponentTypes.DROPDOWN_BUTTON) { c, ctx -> DropdownButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.TEXT_BUTTON) { c, ctx -> MaterialButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.OUTLINED_BUTTON) { c, ctx -> MaterialButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.FILLED_BUTTON) { c, ctx -> MaterialButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.ELEVATED_BUTTON) { c, ctx -> MaterialButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.TONAL_BUTTON) { c, ctx -> MaterialButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.ACTION_CHIP) { c, ctx -> ActionChipRenderer(c, ctx) }
        registry.register(ComponentTypes.SPEED_DIAL) { c, ctx -> SpeedDialRenderer(c, ctx) }
        registry.register(ComponentTypes.SUBMIT_BUTTON) { c, ctx -> ActionButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.CANCEL_BUTTON) { c, ctx -> ActionButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.CONFIRM_BUTTON) { c, ctx -> ActionButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.LINK_BUTTON) { c, ctx -> ActionButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.SHARE_BUTTON) { c, ctx -> ActionButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.DOWNLOAD_BUTTON) { c, ctx -> ActionButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.UPLOAD_BUTTON) { c, ctx -> ActionButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.PLAY_BUTTON) { c, ctx -> ActionButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.PAUSE_BUTTON) { c, ctx -> ActionButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.STOP_BUTTON) { c, ctx -> ActionButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.RELOAD_BUTTON) { c, ctx -> ActionButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.ADD_BUTTON) { c, ctx -> ActionButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.DELETE_BUTTON) { c, ctx -> ActionButtonRenderer(c, ctx) }

        // ==================== 4. Input 输入 ====================
        registry.register(ComponentTypes.TEXT_FIELD) { c, ctx -> RenderTextField(c, ctx) }
        registry.register(ComponentTypes.TEXT_AREA) { c, ctx -> RenderTextArea(c, ctx) }
        registry.register(ComponentTypes.PASSWORD_FIELD) { c, ctx -> RenderPasswordField(c, ctx) }
        registry.register(ComponentTypes.SEARCH_FIELD) { c, ctx -> RenderSearchField(c, ctx) }
        registry.register(ComponentTypes.CHECKBOX) { c, ctx -> RenderCheckbox(c, ctx) }
        registry.register(ComponentTypes.RADIO) { c, ctx -> RenderRadio(c, ctx) }
        registry.register(ComponentTypes.RADIO_GROUP) { c, ctx -> RenderRadioGroup(c, ctx) }
        registry.register(ComponentTypes.SWITCH) { c, ctx -> RenderSwitch(c, ctx) }
        registry.register(ComponentTypes.SLIDER) { c, ctx -> RenderSlider(c, ctx) }
        registry.register(ComponentTypes.RANGE_SLIDER) { c, ctx -> RenderRangeSlider(c, ctx) }
        registry.register(ComponentTypes.DROPDOWN) { c, ctx -> RenderDropdown(c, ctx) }
        registry.register(ComponentTypes.SELECT) { c, ctx -> RenderSelect(c, ctx) }
        registry.register(ComponentTypes.MULTI_SELECT) { c, ctx -> RenderMultiSelect(c, ctx) }
        registry.register(ComponentTypes.DATE_PICKER) { c, ctx -> RenderDatePicker(c, ctx) }
        registry.register(ComponentTypes.TIME_PICKER) { c, ctx -> RenderTimePicker(c, ctx) }
        registry.register(ComponentTypes.DATE_TIME_PICKER) { c, ctx -> RenderDateTimePicker(c, ctx) }
        registry.register(ComponentTypes.COLOR_PICKER) { c, ctx -> RenderColorPicker(c, ctx) }
        registry.register(ComponentTypes.FILE_PICKER) { c, ctx -> RenderFilePicker(c, ctx) }
        registry.register(ComponentTypes.AUTOCOMPLETE) { c, ctx -> RenderAutocomplete(c, ctx) }
        registry.register(ComponentTypes.OTP_INPUT) { c, ctx -> RenderOtpInput(c, ctx) }
        registry.register(ComponentTypes.PIN_INPUT) { c, ctx -> RenderPinInput(c, ctx) }
        registry.register(ComponentTypes.STEPPER) { c, ctx -> RenderStepperInput(c, ctx) }
        registry.register(ComponentTypes.FORM) { c, ctx -> RenderForm(c, ctx) }
        registry.register(ComponentTypes.FORM_FIELD) { c, ctx -> RenderFormField(c, ctx) }
        registry.register(ComponentTypes.FORM_GROUP) { c, ctx -> RenderFormGroup(c, ctx) }
        registry.register(ComponentTypes.TOGGLE_GROUP) { c, ctx -> RenderToggleGroup(c, ctx) }
        registry.register(ComponentTypes.SEGMENTED_CONTROL) { c, ctx -> RenderSegmentedControl(c, ctx) }
        registry.register(ComponentTypes.SEARCH_BAR) { c, ctx -> RenderSearchBar(c, ctx) }
        registry.register(ComponentTypes.RATING_INPUT) { c, ctx -> RenderRatingInput(c, ctx) }

        // ==================== 5. Display 显示 ====================
        registry.register(ComponentTypes.IMAGE) { c, ctx -> RenderImage(c, ctx) }
        registry.register(ComponentTypes.ICON) { c, ctx -> RenderIcon(c, ctx) }
        registry.register(ComponentTypes.AVATAR) { c, ctx -> RenderAvatar(c, ctx) }
        registry.register(ComponentTypes.BADGE) { c, ctx -> RenderBadge(c, ctx) }
        registry.register(ComponentTypes.CHIP) { c, ctx -> RenderChip(c, ctx) }
        registry.register(ComponentTypes.TAG) { c, ctx -> RenderTag(c, ctx) }
        registry.register(ComponentTypes.STATUS_INDICATOR) { c, ctx -> RenderStatusIndicator(c, ctx) }
        registry.register(ComponentTypes.PROGRESS) { c, ctx -> RenderProgress(c, ctx) }
        registry.register(ComponentTypes.CIRCULAR_PROGRESS) { c, ctx -> RenderCircularProgress(c, ctx) }
        registry.register(ComponentTypes.LINEAR_PROGRESS) { c, ctx -> RenderLinearProgress(c, ctx) }
        registry.register(ComponentTypes.SKELETON) { c, ctx -> RenderSkeleton(c, ctx) }
        registry.register(ComponentTypes.SHIMMER) { c, ctx -> RenderShimmer(c, ctx) }
        registry.register(ComponentTypes.PLACEHOLDER) { c, ctx -> RenderPlaceholder(c, ctx) }
        registry.register(ComponentTypes.EMPTY_STATE) { c, ctx -> RenderEmptyState(c, ctx) }
        registry.register(ComponentTypes.TOOLTIP) { c, ctx -> RenderTooltip(c, ctx) }
        registry.register(ComponentTypes.RATING) { c, ctx -> RenderRating(c, ctx) }
        registry.register(ComponentTypes.STAR_RATING) { c, ctx -> RenderStarRating(c, ctx) }
        registry.register(ComponentTypes.COUNTER) { c, ctx -> RenderCounter(c, ctx) }
        registry.register(ComponentTypes.BADGE_COUNT) { c, ctx -> RenderBadgeCount(c, ctx) }
        registry.register(ComponentTypes.NOTIFICATION_BADGE) { c, ctx -> RenderNotificationBadge(c, ctx) }
        registry.register(ComponentTypes.HERO_IMAGE) { c, ctx -> RenderHeroImage(c, ctx) }
        registry.register(ComponentTypes.THUMBNAIL) { c, ctx -> RenderThumbnail(c, ctx) }
        registry.register(ComponentTypes.LOGO) { c, ctx -> RenderLogo(c, ctx) }
        registry.register(ComponentTypes.ILLUSTRATION) { c, ctx -> RenderIllustration(c, ctx) }
        registry.register(ComponentTypes.GRADIENT_BOX) { c, ctx -> RenderGradientBox(c, ctx) }
        registry.register(ComponentTypes.ANIMATED_BOX) { c, ctx -> RenderAnimatedBox(c, ctx) }
        registry.register(ComponentTypes.PARTICLE_EFFECT) { c, ctx -> RenderParticleEffect(c, ctx) }
        registry.register(ComponentTypes.PULSE_INDICATOR) { c, ctx -> RenderPulseIndicator(c, ctx) }
        registry.register(ComponentTypes.WAVE_INDICATOR) { c, ctx -> RenderWaveIndicator(c, ctx) }

        // ==================== 6. Navigation 导航 ====================
        registry.register(ComponentTypes.TABS) { c, ctx -> RenderTabs(c, ctx) }
        registry.register(ComponentTypes.TAB_BAR) { c, ctx -> RenderTabBar(c, ctx) }
        registry.register(ComponentTypes.TAB_ITEM) { c, ctx -> RenderTabItem(c, ctx) }
        registry.register(ComponentTypes.NAV_BAR) { c, ctx -> RenderNavBar(c, ctx) }
        registry.register(ComponentTypes.NAV_RAIL) { c, ctx -> RenderNavRail(c, ctx) }
        registry.register(ComponentTypes.NAV_DRAWER) { c, ctx -> RenderNavDrawer(c, ctx) }
        registry.register(ComponentTypes.BOTTOM_NAV) { c, ctx -> RenderBottomNav(c, ctx) }
        registry.register(ComponentTypes.BREADCRUMB) { c, ctx -> RenderBreadcrumb(c, ctx) }
        registry.register(ComponentTypes.PAGINATION) { c, ctx -> RenderPagination(c, ctx) }
        registry.register(ComponentTypes.PAGE_INDICATOR) { c, ctx -> RenderPageIndicator(c, ctx) }
        registry.register(ComponentTypes.STEPPER_NAV) { c, ctx -> RenderStepperNav(c, ctx) }
        registry.register(ComponentTypes.LINK) { c, ctx -> RenderLink(c, ctx) }
        registry.register(ComponentTypes.ANCHOR) { c, ctx -> RenderAnchor(c, ctx) }
        registry.register(ComponentTypes.MENU) { c, ctx -> RenderMenu(c, ctx) }
        registry.register(ComponentTypes.DROPDOWN_MENU) { c, ctx -> RenderDropdownMenu(c, ctx) }
        registry.register(ComponentTypes.CONTEXT_MENU) { c, ctx -> RenderContextMenu(c, ctx) }
        registry.register(ComponentTypes.POPUP_MENU) { c, ctx -> RenderPopupMenu(c, ctx) }
        registry.register(ComponentTypes.SIDE_MENU) { c, ctx -> RenderSideMenu(c, ctx) }
        registry.register(ComponentTypes.HAMBURGER_MENU) { c, ctx -> RenderHamburgerMenu(c, ctx) }
        registry.register(ComponentTypes.TAB_VIEW) { c, ctx -> RenderTabView(c, ctx) }
        registry.register(ComponentTypes.PAGE_VIEW) { c, ctx -> RenderPageView(c, ctx) }
        registry.register(ComponentTypes.CAROUSEL_NAV) { c, ctx -> RenderCarouselNav(c, ctx) }
        registry.register(ComponentTypes.ARROW_NAV) { c, ctx -> RenderArrowNav(c, ctx) }
        registry.register(ComponentTypes.BACK_BUTTON) { c, ctx -> RenderBackButton(c, ctx) }
        registry.register(ComponentTypes.FORWARD_BUTTON) { c, ctx -> RenderForwardButton(c, ctx) }
        registry.register(ComponentTypes.UP_BUTTON) { c, ctx -> RenderUpButton(c, ctx) }
        registry.register(ComponentTypes.MENU_BUTTON) { c, ctx -> RenderMenuButton(c, ctx) }
        registry.register(ComponentTypes.EXPAND_BUTTON) { c, ctx -> RenderExpandButton(c, ctx) }
        registry.register(ComponentTypes.COLLAPSE_BUTTON) { c, ctx -> RenderCollapseButton(c, ctx) }
        registry.register(ComponentTypes.NAV_ITEM) { c, ctx -> RenderNavItem(c, ctx) }

        // ==================== 7. Feedback 反馈 ====================
        registry.register(ComponentTypes.DIALOG) { c, ctx -> GenDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.ALERT_DIALOG) { c, ctx -> AlertDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.MODAL) { c, ctx -> ModalRenderer(c, ctx) }
        registry.register(ComponentTypes.SNACKBAR) { c, ctx -> SnackbarRenderer(c, ctx) }
        registry.register(ComponentTypes.TOAST) { c, ctx -> ToastRenderer(c, ctx) }
        registry.register(ComponentTypes.BANNER) { c, ctx -> BannerRenderer(c, ctx) }
        registry.register(ComponentTypes.ALERT) { c, ctx -> AlertRenderer(c, ctx) }
        registry.register(ComponentTypes.WARNING) { c, ctx -> WarningRenderer(c, ctx) }
        registry.register(ComponentTypes.ERROR_DISPLAY) { c, ctx -> ErrorDisplayRenderer(c, ctx) }
        registry.register(ComponentTypes.SUCCESS_MESSAGE) { c, ctx -> SuccessMessageRenderer(c, ctx) }
        registry.register(ComponentTypes.INFO_BANNER) { c, ctx -> InfoBannerRenderer(c, ctx) }
        registry.register(ComponentTypes.PROGRESS_DIALOG) { c, ctx -> ProgressDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.LOADING_OVERLAY) { c, ctx -> LoadingOverlayRenderer(c, ctx) }
        registry.register(ComponentTypes.ERROR_STATE) { c, ctx -> ErrorStateRenderer(c, ctx) }
        registry.register(ComponentTypes.NO_DATA) { c, ctx -> NoDataRenderer(c, ctx) }
        registry.register(ComponentTypes.NO_RESULT) { c, ctx -> NoResultRenderer(c, ctx) }
        registry.register(ComponentTypes.CONFIRMATION_DIALOG) { c, ctx -> ConfirmationDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.ACTION_DIALOG) { c, ctx -> ActionDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.INFO_DIALOG) { c, ctx -> InfoDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.WARNING_DIALOG) { c, ctx -> WarningDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.ERROR_DIALOG) { c, ctx -> ErrorDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.TIP) { c, ctx -> TipRenderer(c, ctx) }
        registry.register(ComponentTypes.HINT) { c, ctx -> HintRenderer(c, ctx) }
        registry.register(ComponentTypes.NOTIFICATION) { c, ctx -> NotificationRenderer(c, ctx) }
        registry.register(ComponentTypes.INLINE_MESSAGE) { c, ctx -> InlineMessageRenderer(c, ctx) }
        registry.register(ComponentTypes.SYSTEM_MESSAGE) { c, ctx -> SystemMessageRenderer(c, ctx) }
        registry.register(ComponentTypes.STATUS_BAR) { c, ctx -> StatusBarRenderer(c, ctx) }
        registry.register(ComponentTypes.REVIEW_PROMPT) { c, ctx -> ReviewPromptRenderer(c, ctx) }
        registry.register(ComponentTypes.FEEDBACK_FORM) { c, ctx -> FeedbackFormRenderer(c, ctx) }
        registry.register(ComponentTypes.BOTTOM_SHEET_DIALOG) { c, ctx -> BottomSheetDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.INPUT_DIALOG) { c, ctx -> InputDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.LIST_DIALOG) { c, ctx -> ListDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.FULLSCREEN_DIALOG) { c, ctx -> FullscreenDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.RATING_DIALOG) { c, ctx -> RatingDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.DATE_PICKER_DIALOG) { c, ctx -> DatePickerDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.SIMPLE_DIALOG) { c, ctx -> SimpleDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.PERMISSION_DIALOG) { c, ctx -> PermissionDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.DELETE_CONFIRM_DIALOG) { c, ctx -> DeleteConfirmDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.ABOUT_DIALOG) { c, ctx -> AboutDialogRenderer(c, ctx) }
        registry.register(ComponentTypes.SHARE_DIALOG) { c, ctx -> ShareDialogRenderer(c, ctx) }

        // ==================== 8. Data 数据 ====================
        registry.register(ComponentTypes.TABLE) { c, ctx -> TableRenderer(c, ctx) }
        registry.register(ComponentTypes.DATA_TABLE) { c, ctx -> DataTableRenderer(c, ctx) }
        registry.register(ComponentTypes.LIST) { c, ctx -> GenListRenderer(c, ctx) }
        registry.register(ComponentTypes.LIST_ITEM) { c, ctx -> ListItemRenderer(c, ctx) }
        registry.register(ComponentTypes.LIST_SECTION) { c, ctx -> ListSectionRenderer(c, ctx) }
        registry.register(ComponentTypes.GRID_VIEW) { c, ctx -> GridViewRenderer(c, ctx) }
        registry.register(ComponentTypes.TREE_VIEW) { c, ctx -> TreeViewRenderer(c, ctx) }
        registry.register(ComponentTypes.ACCORDION) { c, ctx -> AccordionRenderer(c, ctx) }
        registry.register(ComponentTypes.EXPANSION_TILE) { c, ctx -> ExpansionTileRenderer(c, ctx) }
        registry.register(ComponentTypes.COLLAPSE) { c, ctx -> CollapseRenderer(c, ctx) }
        registry.register(ComponentTypes.FACT_SET) { c, ctx -> FactSetRenderer(c, ctx) }
        registry.register(ComponentTypes.KEY_VALUE) { c, ctx -> KeyValueRenderer(c, ctx) }
        registry.register(ComponentTypes.DESCRIPTION_LIST) { c, ctx -> DescriptionListRenderer(c, ctx) }
        registry.register(ComponentTypes.DATA_CARD) { c, ctx -> DataCardRenderer(c, ctx) }
        registry.register(ComponentTypes.STAT_CARD) { c, ctx -> StatCardRenderer(c, ctx) }
        registry.register(ComponentTypes.METRIC_CARD) { c, ctx -> MetricCardRenderer(c, ctx) }
        registry.register(ComponentTypes.KPI_CARD) { c, ctx -> KpiCardRenderer(c, ctx) }
        registry.register(ComponentTypes.CHART) { c, ctx -> ChartRenderer(c, ctx) }
        registry.register(ComponentTypes.BAR_CHART) { c, ctx -> BarChartRenderer(c, ctx) }
        registry.register(ComponentTypes.LINE_CHART) { c, ctx -> LineChartRenderer(c, ctx) }
        registry.register(ComponentTypes.PIE_CHART) { c, ctx -> PieChartRenderer(c, ctx) }
        registry.register(ComponentTypes.TIMELINE) { c, ctx -> TimelineRenderer(c, ctx) }
        registry.register(ComponentTypes.CALENDAR) { c, ctx -> CalendarRenderer(c, ctx) }
        registry.register(ComponentTypes.SCHEDULE) { c, ctx -> ScheduleRenderer(c, ctx) }
        registry.register(ComponentTypes.KANBAN) { c, ctx -> KanbanRenderer(c, ctx) }
        registry.register(ComponentTypes.DATA_LIST) { c, ctx -> DataListRenderer(c, ctx) }
        registry.register(ComponentTypes.COMPARISON_TABLE) { c, ctx -> ComparisonTableRenderer(c, ctx) }
        registry.register(ComponentTypes.SUMMARY_CARD) { c, ctx -> SummaryCardRenderer(c, ctx) }
        registry.register(ComponentTypes.DETAIL_VIEW) { c, ctx -> DetailViewRenderer(c, ctx) }

        // ==================== 9. Media 媒体 ====================
        registry.register(ComponentTypes.VIDEO_PLAYER) { c, ctx -> VideoPlayerRenderer(c, ctx) }
        registry.register(ComponentTypes.AUDIO_PLAYER) { c, ctx -> AudioPlayerRenderer(c, ctx) }
        registry.register(ComponentTypes.GALLERY) { c, ctx -> GalleryRenderer(c, ctx) }
        registry.register(ComponentTypes.IMAGE_GRID) { c, ctx -> ImageGridRenderer(c, ctx) }
        registry.register(ComponentTypes.IMAGE_CAROUSEL) { c, ctx -> ImageCarouselRenderer(c, ctx) }
        registry.register(ComponentTypes.VIDEO_THUMBNAIL) { c, ctx -> VideoThumbnailRenderer(c, ctx) }
        registry.register(ComponentTypes.AUDIO_WAVE) { c, ctx -> AudioWaveRenderer(c, ctx) }
        registry.register(ComponentTypes.MEDIA_CARD) { c, ctx -> MediaCardRenderer(c, ctx) }
        registry.register(ComponentTypes.MEDIA_GRID) { c, ctx -> MediaGridRenderer(c, ctx) }
        registry.register(ComponentTypes.MEDIA_LIST) { c, ctx -> MediaListRenderer(c, ctx) }
        registry.register(ComponentTypes.MEDIA_PREVIEW) { c, ctx -> MediaPreviewRenderer(c, ctx) }
        registry.register(ComponentTypes.THUMBNAIL_GRID) { c, ctx -> ThumbnailGridRenderer(c, ctx) }
        registry.register(ComponentTypes.AUDIO_THUMBNAIL) { c, ctx -> AudioThumbnailRenderer(c, ctx) }
        registry.register(ComponentTypes.FILE_PREVIEW) { c, ctx -> FilePreviewRenderer(c, ctx) }
        registry.register(ComponentTypes.DOCUMENT_VIEWER) { c, ctx -> DocumentViewerRenderer(c, ctx) }
        registry.register(ComponentTypes.IMAGE_VIEWER) { c, ctx -> ImageViewerRenderer(c, ctx) }
        registry.register(ComponentTypes.VIDEO_VIEWER) { c, ctx -> VideoViewerRenderer(c, ctx) }
        registry.register(ComponentTypes.PHOTO_GRID) { c, ctx -> PhotoGridRenderer(c, ctx) }
        registry.register(ComponentTypes.VIDEO_GRID) { c, ctx -> VideoGridRenderer(c, ctx) }
        registry.register(ComponentTypes.AUDIO_LIST) { c, ctx -> AudioListRenderer(c, ctx) }
        registry.register(ComponentTypes.PLAYLIST) { c, ctx -> PlaylistRenderer(c, ctx) }
        registry.register(ComponentTypes.MEDIA_BROWSER) { c, ctx -> MediaBrowserRenderer(c, ctx) }
        registry.register(ComponentTypes.MEDIA_PICKER) { c, ctx -> MediaPickerRenderer(c, ctx) }
        registry.register(ComponentTypes.CAMERA_PREVIEW) { c, ctx -> CameraPreviewRenderer(c, ctx) }
        registry.register(ComponentTypes.SCREEN_CAPTURE) { c, ctx -> ScreenCaptureRenderer(c, ctx) }
        registry.register(ComponentTypes.MEDIA_STREAM) { c, ctx -> MediaStreamRenderer(c, ctx) }
        registry.register(ComponentTypes.EMBED) { c, ctx -> EmbedRenderer(c, ctx) }
        registry.register(ComponentTypes.IFRAME) { c, ctx -> IframeRenderer(c, ctx) }
        registry.register(ComponentTypes.WEB_VIEW) { c, ctx -> WebViewRenderer(c, ctx) }

        // ==================== 10. Surface 表面 ====================
        registry.register(ComponentTypes.CARD) { c, ctx -> GenCardRenderer(c, ctx) }
        registry.register(ComponentTypes.MODAL_SHEET) { c, ctx -> ModalSheetRenderer(c, ctx) }
        registry.register(ComponentTypes.BOTTOM_SHEET) { c, ctx -> BottomSheetRenderer(c, ctx) }
        registry.register(ComponentTypes.SIDE_SHEET) { c, ctx -> SideSheetRenderer(c, ctx) }
        registry.register(ComponentTypes.POPOVER) { c, ctx -> PopoverRenderer(c, ctx) }
        registry.register(ComponentTypes.DRAWER) { c, ctx -> DrawerRenderer(c, ctx) }
        registry.register(ComponentTypes.OVERLAY) { c, ctx -> OverlayRenderer(c, ctx) }
        registry.register(ComponentTypes.DIM_OVERLAY) { c, ctx -> DimOverlayRenderer(c, ctx) }
        registry.register(ComponentTypes.SCRIM) { c, ctx -> ScrimRenderer(c, ctx) }
        registry.register(ComponentTypes.BANNER_SURFACE) { c, ctx -> BannerSurfaceRenderer(c, ctx) }
        registry.register(ComponentTypes.TOOLBAR) { c, ctx -> ToolbarRenderer(c, ctx) }
        registry.register(ComponentTypes.APP_BAR) { c, ctx -> AppBarRenderer(c, ctx) }
        registry.register(ComponentTypes.TOP_BAR) { c, ctx -> TopBarRenderer(c, ctx) }
        registry.register(ComponentTypes.HEADER) { c, ctx -> HeaderRenderer(c, ctx) }
        registry.register(ComponentTypes.FOOTER) { c, ctx -> FooterRenderer(c, ctx) }
        registry.register(ComponentTypes.SIDEBAR) { c, ctx -> SidebarRenderer(c, ctx) }
        registry.register(ComponentTypes.PANEL) { c, ctx -> PanelRenderer(c, ctx) }
        registry.register(ComponentTypes.ELEVATED_SURFACE) { c, ctx -> ElevatedSurfaceRenderer(c, ctx) }
        registry.register(ComponentTypes.FLAT_SURFACE) { c, ctx -> FlatSurfaceRenderer(c, ctx) }
        registry.register(ComponentTypes.OUTLINED_SURFACE) { c, ctx -> OutlinedSurfaceRenderer(c, ctx) }
        registry.register(ComponentTypes.GLASS_CARD) { c, ctx -> GlassCardRenderer(c, ctx) }
        registry.register(ComponentTypes.NEUMORPHIC) { c, ctx -> NeumorphicRenderer(c, ctx) }
        registry.register(ComponentTypes.GRADIENT_SURFACE) { c, ctx -> GradientSurfaceRenderer(c, ctx) }
        registry.register(ComponentTypes.MESH_SURFACE) { c, ctx -> MeshSurfaceRenderer(c, ctx) }
        registry.register(ComponentTypes.PATTERN_SURFACE) { c, ctx -> PatternSurfaceRenderer(c, ctx) }
        registry.register(ComponentTypes.BLUR_CONTAINER) { c, ctx -> BlurContainerRenderer(c, ctx) }
        registry.register(ComponentTypes.SHADOW_BOX) { c, ctx -> ShadowBoxRenderer(c, ctx) }
        registry.register(ComponentTypes.BORDER_BOX) { c, ctx -> BorderBoxRenderer(c, ctx) }
        registry.register(ComponentTypes.ROUNDED_SURFACE) { c, ctx -> RoundedSurfaceRenderer(c, ctx) }
        registry.register(ComponentTypes.TONAL_SURFACE) { c, ctx -> TonalSurfaceRenderer(c, ctx) }

        // ==================== 10. UI Patterns 模式组件 ====================
        // 卡片模式
        registry.register(ComponentTypes.INFO_CARD_PATTERN) { c, ctx -> InfoCardPattern(c, ctx) }
        registry.register(ComponentTypes.STAT_CARD_PATTERN) { c, ctx -> StatCardPattern(c, ctx) }
        registry.register(ComponentTypes.MEDIA_CARD_PATTERN) { c, ctx -> MediaCardPattern(c, ctx) }
        registry.register(ComponentTypes.LIST_ITEM_PATTERN) { c, ctx -> ListItemPattern(c, ctx) }
        // 布局模式
        registry.register(ComponentTypes.SECTION_PATTERN) { c, ctx -> SectionPattern(c, ctx) }
        registry.register(ComponentTypes.HEADER_BAR_PATTERN) { c, ctx -> HeaderBarPattern(c, ctx) }
        registry.register(ComponentTypes.TWO_COLUMN_GRID_PATTERN) { c, ctx -> TwoColumnGridPattern(c, ctx) }
        // 状态模式
        registry.register(ComponentTypes.EMPTY_STATE_PATTERN) { c, ctx -> EmptyStatePattern(c, ctx) }
        registry.register(ComponentTypes.LOADING_STATE_PATTERN) { c, ctx -> LoadingStatePattern(c, ctx) }
        // 元素模式
        registry.register(ComponentTypes.CHIP_ROW_PATTERN) { c, ctx -> ChipRowPattern(c, ctx) }
        registry.register(ComponentTypes.RATING_ROW_PATTERN) { c, ctx -> RatingRowPattern(c, ctx) }
        registry.register(ComponentTypes.PROGRESS_LABEL_PATTERN) { c, ctx -> ProgressLabelPattern(c, ctx) }
        registry.register(ComponentTypes.USER_AVATAR_ROW_PATTERN) { c, ctx -> UserAvatarRowPattern(c, ctx) }
        // ==================== 15-18. 扩展组件（数据可视化/交互/动漫人物/媒体） ====================
        registry.register(ComponentTypes.BAR_CHART) { c, ctx -> BarChartRenderer(c, ctx) }
        registry.register(ComponentTypes.LINE_CHART) { c, ctx -> LineChartRenderer(c, ctx) }
        registry.register(ComponentTypes.DONUT_CHART) { c, ctx -> DonutChartRenderer(c, ctx) }
        registry.register(ComponentTypes.HEAT_STRIP) { c, ctx -> HeatStripRenderer(c, ctx) }
        registry.register(ComponentTypes.SPARKLINE) { c, ctx -> SparklineRenderer(c, ctx) }
        registry.register(ComponentTypes.GAUGE) { c, ctx -> GaugeRenderer(c, ctx) }
        registry.register(ComponentTypes.STAT_TILE) { c, ctx -> StatTileRenderer(c, ctx) }
        registry.register(ComponentTypes.PROGRESS_RING) { c, ctx -> ProgressRingRenderer(c, ctx) }
        registry.register(ComponentTypes.RATING_BAR) { c, ctx -> RatingBarRenderer(c, ctx) }
        registry.register(ComponentTypes.SLIDER) { c, ctx -> SliderRenderer(c, ctx) }
        registry.register(ComponentTypes.SWITCH_TOGGLE) { c, ctx -> SwitchToggleRenderer(c, ctx) }
        registry.register(ComponentTypes.CHECKBOX_ITEM) { c, ctx -> CheckboxItemRenderer(c, ctx) }
        registry.register(ComponentTypes.STEPPER) { c, ctx -> StepperRenderer(c, ctx) }
        registry.register(ComponentTypes.COUNTDOWN_TIMER) { c, ctx -> CountdownTimerRenderer(c, ctx) }
        registry.register(ComponentTypes.CHIP_FILTER) { c, ctx -> ChipFilterRenderer(c, ctx) }
        registry.register(ComponentTypes.BADGE) { c, ctx -> BadgeRenderer(c, ctx) }
        registry.register(ComponentTypes.AVATAR) { c, ctx -> AvatarRenderer(c, ctx) }
        registry.register(ComponentTypes.AVATAR_GROUP) { c, ctx -> AvatarGroupRenderer(c, ctx) }
        registry.register(ComponentTypes.CHARACTER_CARD) { c, ctx -> CharacterCardRenderer(c, ctx) }
        registry.register(ComponentTypes.MOOD_BADGE) { c, ctx -> MoodBadgeRenderer(c, ctx) }
        registry.register(ComponentTypes.GRADIENT_ORB) { c, ctx -> GradientOrbRenderer(c, ctx) }
        registry.register(ComponentTypes.STICKER_EMOJI) { c, ctx -> StickerEmojiRenderer(c, ctx) }
        registry.register(ComponentTypes.RANK_MEDAL) { c, ctx -> RankMedalRenderer(c, ctx) }
        registry.register(ComponentTypes.SPEECH_BUBBLE) { c, ctx -> SpeechBubbleRenderer(c, ctx) }
        registry.register(ComponentTypes.BANNER_HERO) { c, ctx -> BannerHeroRenderer(c, ctx) }
        registry.register(ComponentTypes.GLASS_CARD) { c, ctx -> GlassCardRenderer(c, ctx) }
        registry.register(ComponentTypes.AUDIO_WAVE) { c, ctx -> AudioWaveRenderer(c, ctx) }
        registry.register(ComponentTypes.TIMER_PROGRESS) { c, ctx -> TimerProgressRenderer(c, ctx) }
        registry.register(ComponentTypes.COMIC_PANEL) { c, ctx -> ComicPanelRenderer(c, ctx) }
        registry.register(ComponentTypes.CAPTION_BOX) { c, ctx -> CaptionBoxRenderer(c, ctx) }
        registry.register(ComponentTypes.ACTION_LINES) { c, ctx -> ActionLinesRenderer(c, ctx) }
        registry.register(ComponentTypes.PANEL_STRIP) { c, ctx -> PanelStripRenderer(c, ctx) }
        registry.register(ComponentTypes.MANGA_BUBBLE) { c, ctx -> MangaBubbleRenderer(c, ctx) }
        registry.register(ComponentTypes.PARTICLE_BURST) { c, ctx -> ParticleBurstRenderer(c, ctx) }
        registry.register(ComponentTypes.CONFETTI_FIELD) { c, ctx -> ConfettiFieldRenderer(c, ctx) }
        registry.register(ComponentTypes.SPARKLE_RAIN) { c, ctx -> SparkleRainRenderer(c, ctx) }
        registry.register(ComponentTypes.RAIN_EFFECT) { c, ctx -> RainEffectRenderer(c, ctx) }
        registry.register(ComponentTypes.PULSE_RING) { c, ctx -> PulseRingRenderer(c, ctx) }
        registry.register(ComponentTypes.EQUALIZER_BARS) { c, ctx -> EqualizerBarsRenderer(c, ctx) }
        registry.register(ComponentTypes.PLAYER_BAR) { c, ctx -> PlayerBarRenderer(c, ctx) }
        registry.register(ComponentTypes.VIDEO_CARD) { c, ctx -> VideoCardRenderer(c, ctx) }
        registry.register(ComponentTypes.XP_BAR) { c, ctx -> XpBarRenderer(c, ctx) }
        registry.register(ComponentTypes.HP_BAR) { c, ctx -> HpBarRenderer(c, ctx) }
        registry.register(ComponentTypes.COIN_STACK) { c, ctx -> CoinStackRenderer(c, ctx) }
        registry.register(ComponentTypes.QUEST_CARD) { c, ctx -> QuestCardRenderer(c, ctx) }
        registry.register(ComponentTypes.LEADERBOARD_ROW) { c, ctx -> LeaderboardRowRenderer(c, ctx) }
        registry.register(ComponentTypes.STREAK_FLAME) { c, ctx -> StreakFlameRenderer(c, ctx) }
        registry.register(ComponentTypes.TODO_ITEM) { c, ctx -> TodoItemRenderer(c, ctx) }
        registry.register(ComponentTypes.KANBAN_COLUMN) { c, ctx -> KanbanColumnRenderer(c, ctx) }
        registry.register(ComponentTypes.MEETING_CARD) { c, ctx -> MeetingCardRenderer(c, ctx) }
        registry.register(ComponentTypes.CALENDAR_STRIP) { c, ctx -> CalendarStripRenderer(c, ctx) }
        registry.register(ComponentTypes.COUPON_TICKET) { c, ctx -> CouponTicketRenderer(c, ctx) }
        registry.register(ComponentTypes.FLASH_SALE) { c, ctx -> FlashSaleRenderer(c, ctx) }
        registry.register(ComponentTypes.REVIEW_ROW) { c, ctx -> ReviewRowRenderer(c, ctx) }
        registry.register(ComponentTypes.SHIPPING_TRACK) { c, ctx -> ShippingTrackRenderer(c, ctx) }
        registry.register(ComponentTypes.ACTIVITY_RINGS) { c, ctx -> ActivityRingsRenderer(c, ctx) }
        registry.register(ComponentTypes.STEP_COUNTER) { c, ctx -> StepCounterRenderer(c, ctx) }
        registry.register(ComponentTypes.WATER_TRACK) { c, ctx -> WaterTrackRenderer(c, ctx) }
        registry.register(ComponentTypes.CALORIE_RING) { c, ctx -> CalorieRingRenderer(c, ctx) }
        registry.register(ComponentTypes.CANDLE_CHART) { c, ctx -> CandleChartRenderer(c, ctx) }
        registry.register(ComponentTypes.PRICE_DELTA) { c, ctx -> PriceDeltaRenderer(c, ctx) }
        registry.register(ComponentTypes.WALLET_CARD) { c, ctx -> WalletCardRenderer(c, ctx) }
        registry.register(ComponentTypes.FLASH_CARD) { c, ctx -> FlashCardRenderer(c, ctx) }
        registry.register(ComponentTypes.QUIZ_OPTION) { c, ctx -> QuizOptionRenderer(c, ctx) }
        registry.register(ComponentTypes.BOARDING_PASS) { c, ctx -> BoardingPassRenderer(c, ctx) }
        registry.register(ComponentTypes.PET_CARD) { c, ctx -> PetCardRenderer(c, ctx) }
        registry.register(ComponentTypes.PET_STATE) { c, ctx -> PetStateRenderer(c, ctx) }
        registry.register(ComponentTypes.LIVE_ROOM_CARD) { c, ctx -> LiveRoomCardRenderer(c, ctx) }
        registry.register(ComponentTypes.GIFT_BANNER) { c, ctx -> GiftBannerRenderer(c, ctx) }
        registry.register(ComponentTypes.FEED_CARD) { c, ctx -> FeedCardRenderer(c, ctx) }
        registry.register(ComponentTypes.MOMENTS_GRID) { c, ctx -> MomentsGridRenderer(c, ctx) }
        registry.register(ComponentTypes.GAME_HUD) { c, ctx -> GameHudRenderer(c, ctx) }
        registry.register(ComponentTypes.GAME_PAD) { c, ctx -> GamePadRenderer(c, ctx) }
        registry.register(ComponentTypes.LOOT_BOX) { c, ctx -> LootBoxRenderer(c, ctx) }
        registry.register(ComponentTypes.TERMINAL_VIEW) { c, ctx -> TerminalViewRenderer(c, ctx) }
        registry.register(ComponentTypes.COMMAND_HINT) { c, ctx -> CommandHintRenderer(c, ctx) }
        registry.register(ComponentTypes.TREEMAP_TILE) { c, ctx -> TreemapTileRenderer(c, ctx) }
        registry.register(ComponentTypes.FUNNEL_CHART) { c, ctx -> FunnelChartRenderer(c, ctx) }
        registry.register(ComponentTypes.MODAL_CONFIRM) { c, ctx -> ModalConfirmRenderer(c, ctx) }
        registry.register(ComponentTypes.TOAST_PILL) { c, ctx -> ToastPillRenderer(c, ctx) }
        registry.register(ComponentTypes.BROWSER_BAR) { c, ctx -> BrowserBarRenderer(c, ctx) }
        registry.register(ComponentTypes.WEB_PREVIEW_CARD) { c, ctx -> WebPreviewCardRenderer(c, ctx) }
        registry.register(ComponentTypes.ANDROID_STATUS_BAR) { c, ctx -> AndroidStatusBarRenderer(c, ctx) }
        registry.register(ComponentTypes.NOTIFICATION_SHADE) { c, ctx -> NotificationShadeRenderer(c, ctx) }
        registry.register(ComponentTypes.MEME_GRID) { c, ctx -> MemeGridRenderer(c, ctx) }
        registry.register(ComponentTypes.MEME_LARGE) { c, ctx -> MemeLargeRenderer(c, ctx) }
        registry.register(ComponentTypes.PHONE_MOCKUP) { c, ctx -> PhoneMockupRenderer(c, ctx) }
        registry.register(ComponentTypes.CAPSULE_PILL) { c, ctx -> CapsulePillRenderer(c, ctx) }
        registry.register(ComponentTypes.DYNAMIC_ISLAND) { c, ctx -> DynamicIslandRenderer(c, ctx) }
        registry.register(ComponentTypes.CODE_EDITOR_LINE) { c, ctx -> CodeEditorLineRenderer(c, ctx) }
        registry.register(ComponentTypes.DIFF_ROW) { c, ctx -> DiffRowRenderer(c, ctx) }
        registry.register(ComponentTypes.NODE_BOX) { c, ctx -> NodeBoxRenderer(c, ctx) }
        registry.register(ComponentTypes.NODE_CONNECTOR) { c, ctx -> NodeConnectorRenderer(c, ctx) }
        registry.register(ComponentTypes.RUNTIME_LOG_ROW) { c, ctx -> RuntimeLogRowRenderer(c, ctx) }
        registry.register(ComponentTypes.MEMORY_METER) { c, ctx -> MemoryMeterRenderer(c, ctx) }
        registry.register(ComponentTypes.VOICE_MESSAGE) { c, ctx -> VoiceMessageRenderer(c, ctx) }
        registry.register(ComponentTypes.MIC_BUTTON) { c, ctx -> MicButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.DOC_PARAGRAPH) { c, ctx -> DocParagraphRenderer(c, ctx) }
        registry.register(ComponentTypes.DOC_HEADING) { c, ctx -> DocHeadingRenderer(c, ctx) }
        registry.register(ComponentTypes.PULL_QUOTE) { c, ctx -> PullQuoteRenderer(c, ctx) }
        registry.register(ComponentTypes.SPEC_BADGE) { c, ctx -> SpecBadgeRenderer(c, ctx) }
        registry.register(ComponentTypes.COMPLIANCE_CHECK) { c, ctx -> ComplianceCheckRenderer(c, ctx) }
        registry.register(ComponentTypes.TABLE_SORT_HEADER) { c, ctx -> TableSortHeaderRenderer(c, ctx) }
        registry.register(ComponentTypes.WEATHER_BIG) { c, ctx -> WeatherBigRenderer(c, ctx) }
        registry.register(ComponentTypes.HOURLY_FORECAST) { c, ctx -> HourlyForecastRenderer(c, ctx) }
        registry.register(ComponentTypes.WORLD_CLOCK_ROW) { c, ctx -> WorldClockRowRenderer(c, ctx) }
        registry.register(ComponentTypes.TIME_BADGE) { c, ctx -> TimeBadgeRenderer(c, ctx) }
        registry.register(ComponentTypes.ACTION_CARD) { c, ctx -> ActionCardRenderer(c, ctx) }
        registry.register(ComponentTypes.QUICK_ACTION_GRID) { c, ctx -> QuickActionGridRenderer(c, ctx) }
        registry.register(ComponentTypes.ELEVATED_CARD) { c, ctx -> ElevatedCardRenderer(c, ctx) }
        registry.register(ComponentTypes.STACKED_CARDS) { c, ctx -> StackedCardsRenderer(c, ctx) }
        registry.register(ComponentTypes.EMOTION_FACE) { c, ctx -> EmotionFaceRenderer(c, ctx) }
        registry.register(ComponentTypes.MOOD_TRACKER_WEEK) { c, ctx -> MoodTrackerWeekRenderer(c, ctx) }
        registry.register(ComponentTypes.TAG_CLOUD) { c, ctx -> TagCloudRenderer(c, ctx) }
        registry.register(ComponentTypes.LABEL_PILL) { c, ctx -> LabelPillRenderer(c, ctx) }
        registry.register(ComponentTypes.TODO_GROUP) { c, ctx -> TodoGroupRenderer(c, ctx) }
        registry.register(ComponentTypes.TODO_PROGRESS) { c, ctx -> TodoProgressRenderer(c, ctx) }
        registry.register(ComponentTypes.PLAYLIST_ROW) { c, ctx -> PlaylistRowRenderer(c, ctx) }
        registry.register(ComponentTypes.MINI_PLAYER) { c, ctx -> MiniPlayerRenderer(c, ctx) }
        registry.register(ComponentTypes.FLOAT_PANEL) { c, ctx -> FloatPanelRenderer(c, ctx) }
        registry.register(ComponentTypes.FLOW_BACKGROUND) { c, ctx -> FlowBackgroundRenderer(c, ctx) }
        registry.register(ComponentTypes.NOVEL_READER) { c, ctx -> NovelReaderRenderer(c, ctx) }
        registry.register(ComponentTypes.CHAPTER_ROW) { c, ctx -> ChapterRowRenderer(c, ctx) }
        registry.register(ComponentTypes.PIXEL_AVATAR) { c, ctx -> PixelAvatarRenderer(c, ctx) }
        registry.register(ComponentTypes.PIXEL_BANNER) { c, ctx -> PixelBannerRenderer(c, ctx) }
        registry.register(ComponentTypes.LICENSE_CARD) { c, ctx -> LicenseCardRenderer(c, ctx) }
        registry.register(ComponentTypes.AUTH_STEP_ROW) { c, ctx -> AuthStepRowRenderer(c, ctx) }
        registry.register(ComponentTypes.MAP_PIN_CARD) { c, ctx -> MapPinCardRenderer(c, ctx) }
        registry.register(ComponentTypes.ROUTE_STEPS) { c, ctx -> RouteStepsRenderer(c, ctx) }
        registry.register(ComponentTypes.APP_ICON) { c, ctx -> AppIconRenderer(c, ctx) }
        registry.register(ComponentTypes.ICON_GRID) { c, ctx -> IconGridRenderer(c, ctx) }
        registry.register(ComponentTypes.SCENERY_CARD) { c, ctx -> SceneryCardRenderer(c, ctx) }
        registry.register(ComponentTypes.ALARM_ROW) { c, ctx -> AlarmRowRenderer(c, ctx) }
        registry.register(ComponentTypes.ALARM_RING) { c, ctx -> AlarmRingRenderer(c, ctx) }
        registry.register(ComponentTypes.MAIL_ROW) { c, ctx -> MailRowRenderer(c, ctx) }
        registry.register(ComponentTypes.OFFICIAL_ACCOUNT_CARD) { c, ctx -> OfficialAccountCardRenderer(c, ctx) }
        registry.register(ComponentTypes.ARTICLE_ROW) { c, ctx -> ArticleRowRenderer(c, ctx) }
        registry.register(ComponentTypes.FILE_ROW) { c, ctx -> FileRowRenderer(c, ctx) }
        registry.register(ComponentTypes.STORAGE_METER) { c, ctx -> StorageMeterRenderer(c, ctx) }
        registry.register(ComponentTypes.THEME_PICKER_ROW) { c, ctx -> ThemePickerRowRenderer(c, ctx) }
        registry.register(ComponentTypes.FONT_PREVIEW_ROW) { c, ctx -> FontPreviewRowRenderer(c, ctx) }
        registry.register(ComponentTypes.AGENT_CARD) { c, ctx -> AgentCardRenderer(c, ctx) }
        registry.register(ComponentTypes.AI_THINKING) { c, ctx -> AiThinkingRenderer(c, ctx) }
        registry.register(ComponentTypes.AI_CHAT_BUBBLE) { c, ctx -> AiChatBubbleRenderer(c, ctx) }
        registry.register(ComponentTypes.FOCUS_TIMER) { c, ctx -> FocusTimerRenderer(c, ctx) }
        registry.register(ComponentTypes.VIP_BANNER) { c, ctx -> VipBannerRenderer(c, ctx) }
        registry.register(ComponentTypes.PRICING_CARD) { c, ctx -> PricingCardRenderer(c, ctx) }
        registry.register(ComponentTypes.SPEED_TEST) { c, ctx -> SpeedTestRenderer(c, ctx) }
        registry.register(ComponentTypes.CONNECTION_STATUS) { c, ctx -> ConnectionStatusRenderer(c, ctx) }
        registry.register(ComponentTypes.PORTFOLIO_CARD) { c, ctx -> PortfolioCardRenderer(c, ctx) }
        registry.register(ComponentTypes.WORK_STATS) { c, ctx -> WorkStatsRenderer(c, ctx) }
        registry.register(ComponentTypes.LIKE_BUTTON) { c, ctx -> LikeButtonRenderer(c, ctx) }
        registry.register(ComponentTypes.LIKE_LIST_ROW) { c, ctx -> LikeListRowRenderer(c, ctx) }
        registry.register(ComponentTypes.CHAT_ROW) { c, ctx -> ChatRowRenderer(c, ctx) }
        registry.register(ComponentTypes.MESSAGE_COMPOSER) { c, ctx -> MessageComposerRenderer(c, ctx) }
        registry.register(ComponentTypes.DESKTOP_WINDOW) { c, ctx -> DesktopWindowRenderer(c, ctx) }
        registry.register(ComponentTypes.TASKBAR_DOCK) { c, ctx -> TaskbarDockRenderer(c, ctx) }
        registry.register(ComponentTypes.GENUI_INTRO_CARD) { c, ctx -> GenUIIntroCardRenderer(c, ctx) }
        registry.register(ComponentTypes.GENUI_FEATURE_ROW) { c, ctx -> GenUIFeatureRowRenderer(c, ctx) }
        registry.register(ComponentTypes.HELP_FAQ_ROW) { c, ctx -> HelpFaqRowRenderer(c, ctx) }
        registry.register(ComponentTypes.FEEDBACK_BOX) { c, ctx -> FeedbackBoxRenderer(c, ctx) }
        registry.register(ComponentTypes.CUBE_3D) { c, ctx -> Cube3dRenderer(c, ctx) }
        registry.register(ComponentTypes.ISO_CARD) { c, ctx -> IsoCardRenderer(c, ctx) }
        registry.register(ComponentTypes.FLAT_SHAPES) { c, ctx -> FlatShapesRenderer(c, ctx) }
        registry.register(ComponentTypes.DIMENSION_AXIS) { c, ctx -> DimensionAxisRenderer(c, ctx) }
        registry.register(ComponentTypes.COSMOS_SCENE) { c, ctx -> CosmosSceneRenderer(c, ctx) }
        registry.register(ComponentTypes.PLANET_CARD) { c, ctx -> PlanetCardRenderer(c, ctx) }
        registry.register(ComponentTypes.GRAIN_OVERLAY) { c, ctx -> GrainOverlayRenderer(c, ctx) }
        registry.register(ComponentTypes.PARTICLE_DRIFT) { c, ctx -> ParticleDriftRenderer(c, ctx) }
        registry.register(ComponentTypes.NEBULA_PILL) { c, ctx -> NebulaPillRenderer(c, ctx) }
        registry.register(ComponentTypes.ORBIT_RING) { c, ctx -> OrbitRingRenderer(c, ctx) }
        registry.register(ComponentTypes.SEARCH_RESULT_ROW) { c, ctx -> SearchResultRowRenderer(c, ctx) }
        registry.register(ComponentTypes.GREETING_HERO) { c, ctx -> GreetingHeroRenderer(c, ctx) }
        registry.register(ComponentTypes.WELCOME_BANNER) { c, ctx -> WelcomeBannerRenderer(c, ctx) }
        registry.register(ComponentTypes.MEAL_CARD) { c, ctx -> MealCardRenderer(c, ctx) }
        registry.register(ComponentTypes.DIET_SUMMARY) { c, ctx -> DietSummaryRenderer(c, ctx) }
        registry.register(ComponentTypes.COMPRESS_CARD) { c, ctx -> CompressCardRenderer(c, ctx) }
        registry.register(ComponentTypes.ARCHIVE_ROW) { c, ctx -> ArchiveRowRenderer(c, ctx) }
        registry.register(ComponentTypes.BG_MESH) { c, ctx -> BgMeshRenderer(c, ctx) }
        registry.register(ComponentTypes.BG_GRID_GLOW) { c, ctx -> BgGridGlowRenderer(c, ctx) }
        registry.register(ComponentTypes.KEYBOARD_INPUT) { c, ctx -> KeyboardInputRenderer(c, ctx) }
        registry.register(ComponentTypes.PAY_SHEET) { c, ctx -> PaySheetRenderer(c, ctx) }
        registry.register(ComponentTypes.PAY_SUCCESS) { c, ctx -> PaySuccessRenderer(c, ctx) }
        registry.register(ComponentTypes.SERVER_ROW) { c, ctx -> ServerRowRenderer(c, ctx) }
        registry.register(ComponentTypes.SERVER_STATUS_PILL) { c, ctx -> ServerStatusPillRenderer(c, ctx) }
        registry.register(ComponentTypes.DEV_ENV_CARD) { c, ctx -> DevEnvCardRenderer(c, ctx) }
        registry.register(ComponentTypes.DEPENDENCY_ROW) { c, ctx -> DependencyRowRenderer(c, ctx) }
        registry.register(ComponentTypes.RUNTIME_ENV_CARD) { c, ctx -> RuntimeEnvCardRenderer(c, ctx) }
        registry.register(ComponentTypes.DEVICE_ENV_CARD) { c, ctx -> DeviceEnvCardRenderer(c, ctx) }
        registry.register(ComponentTypes.PERMISSION_CARD) { c, ctx -> PermissionCardRenderer(c, ctx) }
        registry.register(ComponentTypes.PERMISSION_PROMPT) { c, ctx -> PermissionPromptRenderer(c, ctx) }
        registry.register(ComponentTypes.BIG_SWITCH) { c, ctx -> BigSwitchRenderer(c, ctx) }
        registry.register(ComponentTypes.HTML_TAG_VIEW) { c, ctx -> HtmlTagViewRenderer(c, ctx) }
        registry.register(ComponentTypes.WEB_LANDING) { c, ctx -> WebLandingRenderer(c, ctx) }
        registry.register(ComponentTypes.WEB_NAV_BAR) { c, ctx -> WebNavBarRenderer(c, ctx) }
        registry.register(ComponentTypes.IDE_WINDOW) { c, ctx -> IdeWindowRenderer(c, ctx) }
        registry.register(ComponentTypes.IDE_TAB_ROW) { c, ctx -> IdeTabRowRenderer(c, ctx) }
        registry.register(ComponentTypes.MEMORY_CARD) { c, ctx -> MemoryCardRenderer(c, ctx) }
        registry.register(ComponentTypes.MEMORY_TIMELINE) { c, ctx -> MemoryTimelineRenderer(c, ctx) }
        registry.register(ComponentTypes.EXEC_STEP) { c, ctx -> ExecStepRenderer(c, ctx) }
        registry.register(ComponentTypes.EXEC_PROGRESS) { c, ctx -> ExecProgressRenderer(c, ctx) }
        registry.register(ComponentTypes.CALENDAR_MONTH) { c, ctx -> CalendarMonthRenderer(c, ctx) }
        registry.register(ComponentTypes.EVENT_ROW) { c, ctx -> EventRowRenderer(c, ctx) }
        registry.register(ComponentTypes.CONTACT_ROW) { c, ctx -> ContactRowRenderer(c, ctx) }
        registry.register(ComponentTypes.SMS_BUBBLE) { c, ctx -> SmsBubbleRenderer(c, ctx) }
        registry.register(ComponentTypes.SMS_CODE_ROW) { c, ctx -> SmsCodeRowRenderer(c, ctx) }
        registry.register(ComponentTypes.RECORDING_BAR) { c, ctx -> RecordingBarRenderer(c, ctx) }
        registry.register(ComponentTypes.DIR_TREE) { c, ctx -> DirTreeRenderer(c, ctx) }
        registry.register(ComponentTypes.PANEL_DOCKED) { c, ctx -> PanelDockedRenderer(c, ctx) }
        registry.register(ComponentTypes.DOWNLOAD_ROW) { c, ctx -> DownloadRowRenderer(c, ctx) }
        registry.register(ComponentTypes.ELEMENT_CARD) { c, ctx -> ElementCardRenderer(c, ctx) }
        registry.register(ComponentTypes.SCROLL_INDICATOR) { c, ctx -> ScrollIndicatorRenderer(c, ctx) }
        registry.register(ComponentTypes.PAGER_DOTS) { c, ctx -> PagerDotsRenderer(c, ctx) }
        registry.register(ComponentTypes.LONG_PRESS_HINT) { c, ctx -> LongPressHintRenderer(c, ctx) }
        registry.register(ComponentTypes.DICE_DISPLAY) { c, ctx -> DiceDisplayRenderer(c, ctx) }
    }

}

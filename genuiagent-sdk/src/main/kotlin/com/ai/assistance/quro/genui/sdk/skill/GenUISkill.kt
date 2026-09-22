package com.ai.assistance.quro.genui.sdk.skill

import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * GenUI 设计技能
 *
 * 技能是可下载、可扩展的设计知识包，包含：
 * - 组件模板：常用的 UI 组件组合模式
 * - 页面模板：完整页面布局
 * - 设计规范：颜色、间距、排版规则
 * - 行业模板：电商、社交、工具等行业专用模式
 *
 * AI 可以加载技能来扩展自己的设计能力，
 * 也可以自写新技能并注册到系统中。
 */
@Serializable
data class GenUISkill(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val author: String = "GenUI",
    val category: SkillCategory = SkillCategory.GENERAL,
    val tags: List<String> = emptyList(),
    val components: Map<String, SkillComponent> = emptyMap(),
    val templates: Map<String, SkillTemplate> = emptyMap(),
    val patterns: List<SkillPattern> = emptyList(),
    val designTokens: DesignTokens? = null
)

/**
 * 技能分类
 */
@Serializable
enum class SkillCategory {
    GENERAL,        // 通用
    E_COMMERCE,     // 电商
    SOCIAL,         // 社交
    TOOLS,          // 工具
    FINANCE,        // 金融
    ENTERTAINMENT,  // 娱乐
    EDUCATION,      // 教育
    HEALTH,         // 健康
    BUSINESS,       // 商务
    DESIGN_SYSTEM   // 设计系统
}

/**
 * 技能中的组件模板
 * 可以被 AI 引用和实例化
 */
@Serializable
data class SkillComponent(
    val name: String,
    val description: String,
    val category: String,
    val template: UIComponent,
    val variables: Map<String, String> = emptyMap(), // 变量名 → 默认值
    val example: JsonObject? = null
)

/**
 * 技能中的页面/区块模板
 */
@Serializable
data class SkillTemplate(
    val name: String,
    val description: String,
    val type: TemplateType,
    val root: UIComponent,
    val slots: Map<String, String> = emptyMap(), // 插槽名 → 描述
    val variables: Map<String, String> = emptyMap()
)

@Serializable
enum class TemplateType {
    PAGE,       // 完整页面
    SECTION,    // 页面区块
    CARD,       // 卡片模板
    ITEM,       // 列表项模板
    LAYOUT      // 布局模式
}

/**
 * 设计模式
 * AI 可以根据场景选择合适的模式
 */
@Serializable
data class SkillPattern(
    val name: String,
    val description: String,
    val scenario: String, // 适用场景
    val templateName: String, // 关联模板
    val antiPattern: String? = null // 反模式（不要这么做）
)

/**
 * 设计令牌 — 颜色、间距、字体等设计规范
 */
@Serializable
data class DesignTokens(
    val name: String,
    val colors: Map<String, String> = emptyMap(),
    val spacing: Map<String, Int> = emptyMap(),
    val borderRadius: Map<String, Int> = emptyMap(),
    val elevation: Map<String, Int> = emptyMap(),
    val fontSizes: Map<String, Float> = emptyMap(),
    val fontWeights: Map<String, Int> = emptyMap()
)

// ============================================================================
// 内置技能
// ============================================================================

/**
 * 内置技能库
 * AI 可以直接引用这些技能中的模板和组件
 */
object BuiltinSkills {

    /**
     * 基础设计系统技能
     */
    val designSystemBasic = GenUISkill(
        id = "design_system_basic",
        name = "基础设计系统",
        description = "GenUI 基础设计规范，包含常用间距、圆角、阴影等",
        category = SkillCategory.DESIGN_SYSTEM,
        tags = listOf("design-system", "tokens", "spacing"),
        designTokens = DesignTokens(
            name = "GenUI Basic",
            colors = mapOf(
                "primary" to "#FF6C5CE7",
                "primary-light" to "#FFA78BFA",
                "secondary" to "#FF10B981",
                "error" to "#FFEF4444",
                "warning" to "#FFF59E0B",
                "info" to "#FF3B82F6",
                "success" to "#FF10B981",
                "surface" to "#FFFFFFFF",
                "background" to "#FFF9FAFB",
                "text-primary" to "#FF111827",
                "text-secondary" to "#FF6B7280",
                "text-tertiary" to "#FF9CA3AF",
                "border" to "#FFE5E7EB"
            ),
            spacing = mapOf(
                "xs" to 4,
                "sm" to 8,
                "md" to 12,
                "lg" to 16,
                "xl" to 20,
                "2xl" to 24,
                "3xl" to 32,
                "4xl" to 48
            ),
            borderRadius = mapOf(
                "sm" to 6,
                "md" to 10,
                "lg" to 16,
                "xl" to 20,
                "2xl" to 24,
                "full" to 999
            ),
            elevation = mapOf(
                "sm" to 1,
                "md" to 2,
                "lg" to 4,
                "xl" to 8,
                "2xl" to 16
            ),
            fontSizes = mapOf(
                "xs" to 12f,
                "sm" to 14f,
                "md" to 16f,
                "lg" to 18f,
                "xl" to 20f,
                "2xl" to 24f,
                "3xl" to 30f,
                "4xl" to 36f
            ),
            fontWeights = mapOf(
                "normal" to 400,
                "medium" to 500,
                "semibold" to 600,
                "bold" to 700
            )
        )
    )

    /**
     * 卡片设计技能
     */
    val cardPatterns = GenUISkill(
        id = "card_patterns",
        name = "卡片设计模式",
        description = "常用卡片布局模式：信息卡、统计卡、媒体卡、列表卡等",
        category = SkillCategory.GENERAL,
        tags = listOf("card", "pattern", "layout"),
        patterns = listOf(
            SkillPattern(
                name = "信息卡",
                description = "标题+描述+图标+操作按钮的标准信息卡片",
                scenario = "展示一条信息，带操作入口",
                templateName = "info_card",
                antiPattern = "不要把太多信息塞进一张小卡片"
            ),
            SkillPattern(
                name = "统计卡",
                description = "大数字+趋势+标签的统计卡片",
                scenario = "数据仪表盘、数据概览",
                templateName = "stat_card"
            ),
            SkillPattern(
                name = "媒体卡",
                description = "图片+标题+描述的媒体卡片",
                scenario = "内容列表、商品展示、文章列表",
                templateName = "media_card"
            ),
            SkillPattern(
                name = "列表卡",
                description = "图标+标题+副标题+尾部信息的列表项卡片",
                scenario = "设置列表、功能菜单、通知列表",
                templateName = "list_card"
            ),
            SkillPattern(
                name = "操作卡",
                description = "图标+标题+描述的功能入口卡片",
                scenario = "首页功能入口、快捷操作",
                templateName = "action_card"
            )
        )
    )

    /**
     * 页面布局技能
     */
    val pageLayouts = GenUISkill(
        id = "page_layouts",
        name = "页面布局模式",
        description = "常用页面结构：列表页、详情页、仪表盘、表单页等",
        category = SkillCategory.GENERAL,
        tags = listOf("page", "layout", "template"),
        patterns = listOf(
            SkillPattern(
                name = "列表页",
                description = "顶部栏 + 搜索/筛选 + 列表内容 + 底部导航",
                scenario = "消息列表、商品列表、通知列表",
                templateName = "list_page"
            ),
            SkillPattern(
                name = "详情页",
                description = "顶部栏 + 头图/标题 + 详情内容 + 底部操作栏",
                scenario = "文章详情、商品详情、订单详情",
                templateName = "detail_page"
            ),
            SkillPattern(
                name = "仪表盘",
                description = "顶部问候 + 统计卡片 + 图表 + 最近活动",
                scenario = "首页、数据仪表盘、工作台",
                templateName = "dashboard_page"
            ),
            SkillPattern(
                name = "表单页",
                description = "顶部栏 + 表单字段 + 底部提交按钮",
                scenario = "登录、注册、编辑资料、发布内容",
                templateName = "form_page"
            ),
            SkillPattern(
                name = "个人中心",
                description = "用户头像信息 + 功能菜单列表 + 设置入口",
                scenario = "我的页面、个人中心、用户资料",
                templateName = "profile_page"
            )
        )
    )

    /**
     * 电商行业技能
     */
    val ecommerce = GenUISkill(
        id = "ecommerce",
        name = "电商设计模式",
        description = "电商行业专用组件和页面模板",
        category = SkillCategory.E_COMMERCE,
        tags = listOf("ecommerce", "shop", "product"),
        patterns = listOf(
            SkillPattern(
                name = "商品卡",
                description = "商品图+标题+价格+销量的商品卡片",
                scenario = "商品列表、搜索结果、推荐商品",
                templateName = "product_card"
            ),
            SkillPattern(
                name = "商品详情页",
                description = "轮播图+价格+规格+详情+加入购物车",
                scenario = "商品详情页",
                templateName = "product_detail"
            ),
            SkillPattern(
                name = "购物车",
                description = "商品列表+数量选择+价格合计+结算按钮",
                scenario = "购物车页面",
                templateName = "cart_page"
            ),
            SkillPattern(
                name = "订单卡",
                description = "订单号+状态+商品列表+价格+操作按钮",
                scenario = "订单列表",
                templateName = "order_card"
            )
        )
    )

    /**
     * 社交行业技能
     */
    val social = GenUISkill(
        id = "social",
        name = "社交设计模式",
        description = "社交行业专用组件和页面模板",
        category = SkillCategory.SOCIAL,
        tags = listOf("social", "feed", "chat"),
        patterns = listOf(
            SkillPattern(
                name = "动态卡",
                description = "用户头像+昵称+时间+内容+图片+互动栏",
                scenario = "朋友圈、动态流、微博",
                templateName = "post_card"
            ),
            SkillPattern(
                name = "聊天页",
                description = "顶部对方信息+消息列表+底部输入框",
                scenario = "聊天对话、私信",
                templateName = "chat_page"
            ),
            SkillPattern(
                name = "用户资料页",
                description = "封面+头像+昵称+简介+关注数+内容列表",
                scenario = "个人主页、用户资料",
                templateName = "user_profile"
            )
        )
    )

    /**
     * 全部内置技能
     */
    val all: List<GenUISkill> = listOf(
        designSystemBasic,
        cardPatterns,
        pageLayouts,
        ecommerce,
        social
    )

    /**
     * 根据 ID 获取技能
     */
    fun getSkill(id: String): GenUISkill? = all.find { it.id == id }

    /**
     * 根据分类获取技能列表
     */
    fun getSkillsByCategory(category: SkillCategory): List<GenUISkill> {
        return all.filter { it.category == category }
    }

    /**
     * 搜索技能
     */
    fun searchSkills(query: String): List<GenUISkill> {
        val q = query.lowercase()
        return all.filter { skill ->
            skill.name.lowercase().contains(q) ||
                    skill.description.lowercase().contains(q) ||
                    skill.tags.any { it.lowercase().contains(q) }
        }
    }
}

// ============================================================================
// 技能管理器
// ============================================================================

/**
 * 技能管理器
 * 负责加载、注册、查询技能
 */
class SkillManager {
    private val skills = mutableMapOf<String, GenUISkill>()

    init {
        // 加载内置技能
        BuiltinSkills.all.forEach { skills[it.id] = it }
    }

    /**
     * 注册一个技能
     */
    fun register(skill: GenUISkill) {
        skills[skill.id] = skill
    }

    /**
     * 获取技能
     */
    fun get(id: String): GenUISkill? = skills[id]

    /**
     * 获取所有已加载技能
     */
    fun all(): List<GenUISkill> = skills.values.toList()

    /**
     * 搜索技能
     */
    fun search(query: String): List<GenUISkill> {
        val q = query.lowercase()
        return skills.values.filter { skill ->
            skill.name.lowercase().contains(q) ||
                    skill.description.lowercase().contains(q) ||
                    skill.tags.any { it.lowercase().contains(q) } ||
                    skill.patterns.any { it.name.lowercase().contains(q) }
        }
    }

    /**
     * 根据场景推荐模式
     */
    fun recommendPatterns(scenario: String): List<Pair<GenUISkill, SkillPattern>> {
        val s = scenario.lowercase()
        return skills.values.flatMap { skill ->
            skill.patterns
                .filter { pattern ->
                    pattern.scenario.lowercase().contains(s) ||
                            pattern.name.lowercase().contains(s) ||
                            pattern.description.lowercase().contains(s)
                }
                .map { skill to it }
        }
    }
}

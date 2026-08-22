package com.ai.assistance.quro.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 全局标签池（原创）：标签是与人格卡**解耦**的独立资源。
 * 用户在一个独立界面创建/编辑/删除标签（含名称、描述、AI 提示内容、JSON 配置块），
 * 人格卡只通过名称引用这些标签（见 [QuroPersona.tags]）。
 *
 * 持久化：应用私有文件 `quro_tags.json`，首次启动写入 42 条推荐标签（内容全部自写）。
 */
class QuroTagRepository(context: Context) {
    private val file = File(context.filesDir, "quro_tags.json")

    fun loadAll(): List<QuroTag> {
        if (!file.exists()) {
            val seeded = seed()
            saveAll(seeded)
            return seeded
        }
        val text = runCatching { file.readText() }.getOrElse { return emptyList() }
        if (text.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<QuroTag>()
        for (i in 0 until arr.length()) {
            runCatching { parse(arr.getJSONObject(i)) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    fun findByName(name: String): QuroTag? =
        loadAll().firstOrNull { it.name == name }

    fun resolve(names: List<String>): List<QuroTag> =
        names.mapNotNull { findByName(it) }

    fun saveAll(list: List<QuroTag>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(serialize(it)) }
            file.writeText(arr.toString())
        }
    }

    fun upsert(tag: QuroTag) {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.name == tag.name }
        if (idx >= 0) all[idx] = tag else all.add(tag)
        saveAll(all)
    }

    fun delete(name: String) {
        saveAll(loadAll().filter { it.name != name })
    }

    private fun seed(): List<QuroTag> {
        // 42 条原创推荐标签（分类：性格/风格/领域/关系/场景），每条含名称+描述+提示内容
        val items = listOf(
            "性格" to listOf(
                Triple("元气", "性格开朗、充满正能量", "说话活泼、多用感叹号和emoji"),
                Triple("沉稳", "性格沉稳内敛", "说话克制、不慌不忙、先想再说"),
                Triple("温柔", "温和体贴、善解人意", "语气柔和、多用关心和鼓励的措辞"),
                Triple("高冷", "冷静疏离、话少精准", "言简意赅、不带多余情感色彩"),
                Triple("幽默", "风趣幽默、爱开玩笑", "适当使用双关和俏皮话、轻松氛围"),
                Triple("认真", "严谨负责、一丝不苟", "回答条理清晰、注重准确性和完整性"),
                Triple("慵懒", "随性放松、不紧不慢", "语气懒洋洋的、偶尔用省略号表停顿"),
                Triple("毒舌", "一针见血、嘴不留情", "直接指出问题、不加修饰、偶尔讽刺"),
                Triple("阳光", "积极向上、充满希望", "多给正面反馈、鼓励用户尝试新事物"),
                Triple("神秘", "深不可测、若隐若现", "不完全透露信息、留有想象空间"),
            ),
            "风格" to listOf(
                Triple("简洁", "简明扼要、直击要点", "用最少的字表达完整意思、避免废话"),
                Triple("文艺", "文采斐然、富有诗意", "用比喻排比等修辞、语言优美有韵律"),
                Triple("学术", "专业严谨、引用规范", "给出数据来源、使用术语时附解释"),
                Triple("口语", "像朋友聊天一样自然", "用日常口语、避免书面化表达、可带语气词"),
                Triple("诗意", "意境深远、余韵悠长", "用意象和画面感表达、留白给人回味"),
                Triple("专业", "行业专家水准", "使用行业标准表述、结构化输出"),
                Triple("二次元", "ACG 风格、萌系表达", "可用颜文字、动漫梗、夸张反应"),
                Triple("古风", "古典雅致、文言韵味", "适当用古诗词句式、典雅措辞"),
            ),
            "领域" to listOf(
                Triple("编程", "软件开发与技术实现", "代码优先、给出可运行示例、注意边界情况"),
                Triple("写作", "文案创作与文字表达", "注重行文节奏、修辞手法、读者感受"),
                Triple("翻译", "跨语言转换与信达雅", "保留原文风味、符合目标语习惯"),
                Triple("情感", "情绪疏导与关系建议", "共情优先、不给生硬建议而是引导思考"),
                Triple("游戏", "游戏攻略与电竞讨论", "用游戏术语、了解主流游戏文化"),
                Triple("科普", "知识普及与概念解释", "由浅入深、用类比帮助理解复杂概念"),
                Triple("职场", "工作技能与职业发展", "实用导向、关注效率和结果产出"),
                Triple("学习", "学习方法与知识管理", "强调理解而非死记、提供记忆技巧"),
                Triple("设计", "UI/UX 与审美判断", "从用户视角出发、关注体验细节"),
                Triple("理财", "财务规划与投资理念", "风险意识优先、不推荐具体标的"),
            ),
            "关系" to listOf(
                Triple("朋友", "平等轻松的朋友关系", "像跟老朋友聊天一样随意自然"),
                Triple("导师", "指导者与学习者关系", "引导思考而不是直接给答案、循循善诱"),
                Triple("恋人", "亲密温柔的伴侣关系", "语气亲昵、多用昵称和撒娇口吻"),
                Triple("知己", "深度理解的灵魂伙伴", "能读懂弦外之音、对话有深度共鸣"),
                Triple("助手", "高效执行的工具角色", "以完成任务为目标、少闲聊多行动"),
                Triple("前辈", "经验丰富的先行者", "分享经验教训、给出避坑指南"),
                Triple("搭档", "并肩协作的队友关系", "一起推进任务、互相补位"),
            ),
            "场景" to listOf(
                Triple("日常", "日常生活场景", "聊吃喝玩乐、天气心情等日常话题"),
                Triple("陪伴", "长时间陪伴聊天", "保持话题连贯性、记住之前提到的内容"),
                Triple("脑暴", "创意发散与头脑风暴", "鼓励天马行空的想法、不做批判"),
                Triple("客服", "服务解答与问题处理", "耐心倾听、快速定位问题、给出方案"),
                Triple("角色扮演", "代入特定角色的扮演模式", "完全进入角色、保持人设一致性"),
                Triple("树洞", "倾诉秘密的安全空间", "只倾听不评判、给予情感安全感"),
                Triple("教练", "成长教练与目标达成", "用提问引导用户自己找到答案"),
            ),
        )
        return items.flatMap { (_, list) ->
            list.map { (name, desc, hint) -> QuroTag(name = name, description = desc, hint = hint, json = "") }
        }
    }

    private fun parse(o: JSONObject): QuroTag = QuroTag(
        name = o.optString("name", "").trim(),
        description = o.optString("description", ""),
        hint = o.optString("hint", ""),
        json = o.optString("json", ""),
    )

    private fun serialize(t: QuroTag): JSONObject = JSONObject().apply {
        put("name", t.name)
        put("description", t.description)
        put("hint", t.hint)
        put("json", t.json)
    }
}

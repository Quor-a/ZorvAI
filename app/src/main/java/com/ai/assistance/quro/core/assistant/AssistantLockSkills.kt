package com.ai.assistance.quro.core.assistant

import android.content.Context
import com.ai.assistance.quro.util.QuroDiag

/**
 * 锁屏技能管理。
 *
 * 对标小布设置页「个性化设置 → 锁屏技能管理：自定义锁屏场景的小布技能」。
 *
 * 语义：用户在设置页勾选若干能力，锁屏（息屏/锁屏提醒界面）上只展示这些能力的快捷入口。
 * 小布的价值在于**锁屏是唯一不需要解锁就能用的场景**——把"我手机呢""查一下环境"
 * 这类动作放上去，比在 App 里点三层快得多。
 *
 * 实现边界（诚实标注）：本类只负责**配置与读取**（存 skill id 集合）。
 * 锁屏界面的实际渲染由 `QuroReminderActivity` 那类锁屏覆盖界面消费
 * [lockScreenSkills]；未接之前不产生任何可见行为，也不会骗用户"已经生效"。
 */
object AssistantLockSkills {
    private const val TAG = "assistant-lockskills"
    private const val PREFS = "quro_assistant_lockskills"
    private const val KEY_IDS = "ids"

    /** 默认锁屏展示的能力：只有明确适合"一眼就想点"的几项。 */
    private val DEFAULT_IDS = setOf("device.find-phone", "terminal.env-check")

    private fun prefs(ctx: Context) =
        runCatching { ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()

    fun lockScreenIds(ctx: Context): Set<String> =
        prefs(ctx)?.getStringSet(KEY_IDS, DEFAULT_IDS)?.toSet() ?: DEFAULT_IDS

    fun setLockScreenIds(ctx: Context, ids: Set<String>) {
        prefs(ctx)?.edit()?.putStringSet(KEY_IDS, ids)?.apply()
        QuroDiag.log(TAG, "lock screen skills = $ids")
    }

    fun toggle(ctx: Context, skillId: String, on: Boolean) {
        val cur = lockScreenIds(ctx).toMutableSet()
        if (on) cur.add(skillId) else cur.remove(skillId)
        setLockScreenIds(ctx, cur)
    }

    /** 可选的候选：只挑"允许外部调用且能立刻执行"的，避免放上去了点不动。 */
    fun candidates(): List<AssistantSkill> =
        AssistantSkillRegistry.all().filter { it.external && it.runnable && it.requirement.isBlank() }

    /** 锁屏界面消费：返回真正要展示的技能对象（过滤掉已停用/已删除的）。 */
    fun lockScreenSkills(ctx: Context): List<AssistantSkill> {
        val ids = lockScreenIds(ctx)
        return ids.mapNotNull { AssistantSkillRegistry.get(it) }
            .filter { AssistantSkillRegistry.isEnabled(it.id) && it.runnable }
    }
}

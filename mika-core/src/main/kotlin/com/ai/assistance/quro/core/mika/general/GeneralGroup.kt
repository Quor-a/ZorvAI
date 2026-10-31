package com.ai.assistance.quro.core.mika.general

import com.ai.assistance.quro.core.mika.tool.AgentTool

/** 通用工具(不属于哪块屏):等待、回看、问用户、确认、交给用户、处理障碍、没办成就结束、查读知识与技能、记忆。按发给模型的顺序。 */
internal object GeneralGroup {
    val tools: List<AgentTool> = listOf(
        Wait, ViewPastScreens, ViewUserScreen, AskUser, RequestConfirmation, HandToUser, HandleObstacle, EndNotDone,
        KnowledgeSearch, KnowledgeRead, SkillSearch, SkillRead, MemorySearch, MemorySave, ProfileEdit, ScheduleTask, SpawnAgent,
    )
}

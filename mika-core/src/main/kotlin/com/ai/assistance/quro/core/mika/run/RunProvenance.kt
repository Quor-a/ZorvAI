package com.ai.assistance.quro.core.mika.run

/** 谁启动这次运行；与单个动作的 Invoker 完全分开。 */
enum class RunOrigin { Ui, Channel, Trigger, Mcp, Child }

/** 用户能否在本次运行中及时处理提问或确认。 */
enum class Attendance { Present, Reachable, Unattended }

data class RunProvenance(
    val origin: RunOrigin = RunOrigin.Ui,
    val attendance: Attendance = Attendance.Present,
)

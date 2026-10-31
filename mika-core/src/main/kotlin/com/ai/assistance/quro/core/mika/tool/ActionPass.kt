package com.ai.assistance.quro.core.mika.tool

/**
 * 执行一个会改变外部世界的工具的凭证:[ToolDispatcher.execute] 要它。构造函数是 internal,App 模块造不出来;
 * core 里只有执行入口(core.action.ActionExecutor)造它,由 SingleEntryTest 守着。只读调用走 [ToolDispatcher.executeRead]。
 */
class ActionPass internal constructor()

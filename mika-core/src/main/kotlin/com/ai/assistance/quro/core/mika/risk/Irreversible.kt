package com.ai.assistance.quro.core.mika.risk

/** 不可逆操作的种类(确认卡片、判定结果、申报共用)。 */
@Suppress("EnumEntryName")
enum class IrrevAction { send, pay, delete, order, submit, other }

/**
 * 工具声明的「按什么判不可逆」:Tap 点击的元素或坐标;FileControl 点的文件控件(和点击一样判,但不会是看图得来的点);
 * Enter 打字后的回车;Key 按键;Swipe 横滑(滑动删除);Interact 网页上的组合键、拖动、下拉选择。
 */
enum class GateTarget { Tap, FileControl, Enter, Key, Swipe, Interact }

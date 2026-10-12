package com.zorv.plugin.zxwidget

/**
 * 通用操作结果（插件级顶层类型）。
 *
 * 插件 20+ 个模块都以 `Result(false, "原因")` / `Result(true, "说明")` 表达
 * 操作成败。Kotlin 标准库自带 `kotlin.Result`，若不在本包定义顶层 `Result`，
 * 这些裸引用会解析到 `kotlin.Result`（没有 ok/reason 属性），导致
 * 调用方 `r.ok` / `r.reason` 全部 Unresolved。
 *
 * 因此这里提供一个包级顶层 `Result`，让所有文件的裸 `Result(...)` 都落到它。
 */
data class Result(val ok: Boolean, val reason: String = "")
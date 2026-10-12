package com.zorv.plugin.zxwidget

import com.ai.assistance.quro.plugin.extension.RenderContext as HostRenderContext
import com.ai.assistance.quro.plugin.extension.RenderedCard as HostRenderedCard

/**
 * 宿主契约类型的同包别名。
 *
 * 本模块 165 个文件大量直接使用 `RenderContext` / `RenderedCard`（未逐个 import），
 * 这里在同一包内提供 typealias，让所有文件无需 import 即可解析到契约层类型。
 */
typealias RenderContext = HostRenderContext
typealias RenderedCard = HostRenderedCard
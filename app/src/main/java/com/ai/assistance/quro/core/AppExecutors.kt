package com.ai.assistance.quro.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * 全局线程池（按「行为类型」分线程，源自 ANR 全面排查 / 元宝架构框架）。
 *
 * 分层原则：
 * - UI 层只负责「点、看、反馈」，永不 new Thread、永不直接做 IO/计算；
 * - ViewModel 只「派活」不「干活」，重活一律丢进下面两池；
 * - 常驻服务（MCP Server / Bot 长连 / 守护轮询）各自独立线程 + Service，不占用这两池。
 *
 * 两池职责：
 * - [io] ：IO 行为 —— 网络(HttpURLConnection/WS)、文件读写、Socket/ServerSocket、终端命令、数据库。
 * - [cpu]：CPU 行为 —— JSON 大对象序列化/反序列化、OOXML 解析、加密、排序、AI 推理预处理。
 *
 * 防 ANR 红线（详见 deliverables/gstack/debug-anr-fullaudit 报告）：
 *   ❌ UI 线程禁止 Socket / ServerSocket / Runtime.exec / FileInputStream / HttpURLConnection
 *   ❌ ViewModel 构造函数 / init 内干活
 *   ❌ runBlocking（单元测试与工具链路桥接除外）
 *   ✅ suspend 函数标 @WorkerThread；所有「启动类」行为必须有超时
 */
object AppExecutors {
    /** IO 行为统一入口（等价于 Dispatchers.IO，但语义更明确）。 */
    val io = Dispatchers.IO

    /** CPU 行为统一入口：受限并行度的计算池，避免大解析/排序打满 IO 池。 */
    val cpu: ExecutorCoroutineDispatcher =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtLeast(2))
            .asCoroutineDispatcher()

    fun shutdown() {
        cpu.close()
    }
}

package com.ai.assistance.quro.core.mika.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 一条到常驻助手的「连接」:助手进程不跟着解绑结束,Binder 由 [source] 提供(没有时为 null)。
 * 对外和按服务绑定的连接一样:[shell] 只在绑定期间有值,[dropped] 表示连上过、仍需要、但现在断了。
 * 解绑时用 [release] 让助手撤掉这次绑定留下的改动(虚拟屏、静音、剪贴板),效果等同于服务结束。
 *
 * [scope] 必须是单线程顺序执行的:解绑的撤销排在之后再绑定的发布前面,新任务建的屏不会被上一次的撤销误删。
 */
class HelperLink<T : Any>(
    private val source: StateFlow<T?>,
    private val scope: CoroutineScope,
    private val release: (T) -> Unit,
    private val need: () -> Unit,
) {
    private val _shell = MutableStateFlow<T?>(null)
    val shell: StateFlow<T?> = _shell.asStateFlow()

    private val _dropped = MutableStateFlow(false)
    val dropped: StateFlow<Boolean> = _dropped.asStateFlow()

    private var wanted = false
    private var job: Job? = null

    @Synchronized
    fun bind() {
        if (wanted) return
        wanted = true
        var seen = false
        job = scope.launch {
            source.collect { t ->
                synchronized(this@HelperLink) {
                    if (!wanted) return@collect
                    _shell.value = t
                    if (t != null) {
                        seen = true
                        _dropped.value = false
                    } else {
                        _dropped.value = seen
                    }
                }
                if (t == null) need()
            }
        }
    }

    @Synchronized
    fun unbind() {
        if (!wanted) return
        wanted = false
        job?.cancel()
        job = null
        val last = _shell.value
        _shell.value = null
        _dropped.value = false
        if (last != null) scope.launch { runCatching { release(last) } }
    }

    /** 「重新连接」:绑定着却没有助手时请看门狗马上再起一次。 */
    fun reconnect() {
        val ask = synchronized(this) { wanted && _shell.value == null }
        if (ask) need()
    }
}

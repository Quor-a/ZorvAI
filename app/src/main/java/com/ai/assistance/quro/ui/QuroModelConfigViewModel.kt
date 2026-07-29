package com.ai.assistance.quro.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.QuroCrashReporter
import com.ai.assistance.quro.core.network.QuroModelListFetcher
import com.ai.assistance.quro.core.network.QuroModelListResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 模型配置 ViewModel（原创）：编辑并持久化 QuroModelConfig，
 * 并支持拉取远端可用模型列表。
 */
class QuroModelConfigViewModel(context: Context) : ViewModel() {
    val repo = QuroModelConfigRepository(context.applicationContext)
    // 接住拉取模型列表等后台协程里逃逸的异常，转成可见报错而非静默崩溃。
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + QuroCrashReporter.handler)
    private val fetcher = QuroModelListFetcher()

    private val _cfg = MutableStateFlow(repo.load())
    val cfg: StateFlow<QuroModelConfig> = _cfg.asStateFlow()

    private val _modelList = MutableStateFlow<QuroModelListResult?>(repo.loadModelListCache(_cfg.value.baseUrl))
    val modelList: StateFlow<QuroModelListResult?> = _modelList.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    fun update(block: QuroModelConfig.() -> QuroModelConfig) {
        _cfg.value = _cfg.value.block()
        // 每次编辑即落盘：避免用户填了 Key 却没点「保存配置」导致对话读取不到（曾经造成「无法对话」）。
        repo.save(_cfg.value)
    }

    fun save() {
        repo.save(_cfg.value)
    }

    fun reload() {
        _cfg.value = repo.load()
    }

    /** 进入模型面板时调用：只读本地缓存，不联网（v396 改为手动拉取）。 */
    fun loadCachedModels() {
        _modelList.value = repo.loadModelListCache(_cfg.value.baseUrl)
    }

    /** 按当前 Base URL + API Key 拉取可用模型列表（手动触发）。 */
    fun fetchModels() {
        val c = _cfg.value
        _isFetchingModels.value = true
        _modelList.value = null
        scope.launch {
            try {
                val res = fetcher.fetch(c.baseUrl, c.apiKey)
                _modelList.value = res
                repo.saveModelListCache(c.baseUrl, res) // 持久化缓存，下次进入直接读
            } catch (e: Exception) {
                // 网络/解析异常不得上抛到 coroutine 作用域：降级成 Error 结果，
                // 否则未捕获的异常会经 SupervisorJob 冒泡到线程未捕获处理器 → 整个 App 崩溃。
                val res = QuroModelListResult.Error(e.message ?: "拉取失败")
                _modelList.value = res
                repo.saveModelListCache(c.baseUrl, res)
            } finally {
                _isFetchingModels.value = false
            }
        }
    }

    /** 关闭模型列表弹窗时清空结果。 */
    fun clearModelList() {
        _modelList.value = null
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}

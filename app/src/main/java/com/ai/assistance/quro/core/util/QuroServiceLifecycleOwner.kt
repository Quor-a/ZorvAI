package com.ai.assistance.quro.core.util

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 给「非 Activity/Fragment 宿主」（Service 内的 ComposeView、IME 输入视图、悬浮窗等）
 * 提供完整的生命周期宿主：LifecycleOwner + ViewModelStoreOwner + SavedStateRegistryOwner。
 *
 * 基于已验证的 Service LifecycleOwner 模式实现（已在真实工程中验证）。
 * 搭配官方 AndroidX 公开静态 API 使用：
 *   androidx.lifecycle.ViewTreeLifecycleOwner.set(view, owner)
 *   androidx.lifecycle.ViewTreeViewModelStoreOwner.set(view, owner)
 *   androidx.savedstate.ViewTreeSavedStateRegistryOwner.set(view, owner)
 * 替换此前脆弱的反射桥 ComposeViewOwnerBridge（运行期反射经常失败 → owner 缺失 →
 * "ViewTreeLifecycleOwner not found" 键盘/悬浮窗闪退）。
 *
 * 关键点：
 * 1. performRestore 在主线程执行；若当前不在主线程则 post 到主线程，避免 SavedStateRegistry
 *    非主线程初始化的线程校验异常。
 * 2. handleLifecycleEvent 同样主线程安全。
 * 3. 实现 ViewModelStoreOwner，使 ComposeView 内的 viewModel() 可正常工作。
 */
class QuroServiceLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val viewModelStoreField = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            savedStateRegistryController.performRestore(null)
        } else {
            mainHandler.post { savedStateRegistryController.performRestore(null) }
        }
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreField
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            lifecycleRegistry.handleLifecycleEvent(event)
        } else {
            mainHandler.post { lifecycleRegistry.handleLifecycleEvent(event) }
        }
    }

    /** 进入 CREATED 状态（performRestore 已在 init 完成且仅执行一次）。 */
    fun create() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** 进入 RESUMED 状态（ComposeView 可见可交互）。 */
    fun resume() { lifecycleRegistry.currentState = Lifecycle.State.RESUMED }

    /** 进入 DESTROYED 状态（宿主销毁时调用，释放 Compose 资源）。 */
    fun destroy() { lifecycleRegistry.currentState = Lifecycle.State.DESTROYED }
}

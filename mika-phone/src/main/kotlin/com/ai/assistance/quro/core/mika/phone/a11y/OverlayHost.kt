package com.ai.assistance.quro.core.mika.phone.a11y

import com.ai.assistance.quro.core.mika.i18n.AppLanguage
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import android.view.KeyEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 浮层窗口摆在哪:[gravity] 用 [android.view.Gravity] 的组合,[x]/[y] 是离那条边的像素。
 * 坐标按整块屏幕算(包括状态栏、刘海和底部手势条那几条),要避开哪里由调用方按 [OverlayArea] 定。
 */
data class OverlayPlacement(val gravity: Int, val x: Int = 0, val y: Int = 0)

/** 此刻屏幕(按当前方向)的宽高,和四边被状态栏、刘海、导航栏或手势条占掉的像素。 */
data class OverlayArea(
    val width: Int, val height: Int,
    val insetLeft: Int = 0, val insetTop: Int = 0, val insetRight: Int = 0, val insetBottom: Int = 0,
)

/**
 * 拖动窗口:手指按下后移过系统的触摸阈值才算拖动,这时窗口里正按着的按钮收到取消,不会再当成点按。
 * 可以只让窗口里的一块(把手,见 [OverlayWindow.draggable])能拖,别处的滑动照常交给里面的内容(例如滚动)。
 * [move]/[end] 给的是手指自按下以来在屏幕上移了多少像素(按屏幕坐标算,窗口跟着手指动也不会抖)。
 */
interface OverlayDrag {
    fun start()
    fun move(dx: Float, dy: Float)
    fun end(dx: Float, dy: Float)
}

/**
 * 借无障碍服务在用户的屏幕(主屏)上画浮层:窗口类型是无障碍浮层,不需要「显示在其他应用上层」权限。
 * 只管机制:加窗口、换位置、拖动、切换能不能接收键盘、拆窗口;画什么由调用方给的 Compose 内容决定。
 * 窗口只有内容那么大,内容以外的地方照常交给下面的 App。
 * 服务断开时由 [ScreenAccessibility] 调 [closeAll],窗口跟着拆掉。只在主线程用。
 */
class OverlayHost internal constructor(private val service: ScreenAccessibility) {
    private val windows = mutableListOf<OverlayWindow>()

    /**
     * 加一个包住内容大小的浮层窗口。平时不抢焦点、点不到的地方照常交给下面的 App;
     * [onOutside] 给了时,在窗口外面按下会通知它(收起卡片用)。加不上(服务已断开、系统不让)时返回 null,不抛错。
     */
    fun open(placement: OverlayPlacement, onOutside: (() -> Unit)? = null, content: @Composable () -> Unit): OverlayWindow? {
        val owners = OverlayOwners()
        val frame = OverlayFrame(service).apply {
            setViewTreeLifecycleOwner(owners)
            setViewTreeSavedStateRegistryOwner(owners)
            setViewTreeViewModelStoreOwner(owners)
            addView(
                ComposeView(AppLanguage.wrap(service)).apply { setContent(content) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            this.onOutside = onOutside
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 按整块屏幕摆,不让系统再把窗口挤进状态栏、导航栏以内:要避开哪里由调用方按 area() 定
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            place(placement)
        }
        val window = OverlayWindow(this, frame, params, owners)
        owners.start()
        return try {
            wm().addView(frame, params)
            windows += window
            window
        } catch (e: RuntimeException) {
            // 服务刚断开时窗口令牌已失效,或系统不允许这类窗口:当作没有浮层
            Log.w(TAG, "overlay unavailable", e)
            owners.destroy()
            null
        }
    }

    /** 此刻屏幕的大小和四边被系统栏、刘海占掉的部分(转屏后跟着变)。读不到时退回显示尺寸,上下各留一条状态栏常见的高度。 */
    fun area(): OverlayArea = runCatching {
        val m = wm().currentWindowMetrics
        val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        OverlayArea(m.bounds.width(), m.bounds.height(), i.left, i.top, i.right, i.bottom)
    }.getOrElse {
        val d = service.resources.displayMetrics
        val bar = (FALLBACK_INSET_DP * d.density).toInt()
        OverlayArea(d.widthPixels, d.heightPixels, insetTop = bar, insetBottom = bar)
    }

    /**
     * 用户屏幕上输入法的上边(屏幕坐标);没弹出时为 null。不论输入法是对着哪个 App 弹的(包括 ZorvAI 自己的浮层),
     * 胶囊和卡片都据此让开。窗口一变就重读,读不到窗口时当作没弹出。
     */
    fun imeTop(): Flow<Int?> = service.windowChanges().onStart { emit(Unit) }
        .map { readImeTop() }
        .distinctUntilChanged()

    private fun readImeTop(): Int? = runCatching {
        service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also(w::getBoundsInScreen) }
            ?.takeIf { !it.isEmpty }?.top
    }.getOrNull()

    /** 拆掉全部浮层(服务断开时)。 */
    fun closeAll() {
        windows.toList().forEach(OverlayWindow::close)
    }

    internal fun wm(): WindowManager = service.getSystemService(WindowManager::class.java)

    internal fun forget(window: OverlayWindow) {
        windows.remove(window)
    }

    private companion object {
        const val TAG = "ZorvAIOverlay"
        const val FALLBACK_INSET_DP = 24
    }
}

/** [OverlayHost.open] 加上的一个窗口。拆掉之后再调用什么都不做。 */
class OverlayWindow internal constructor(
    private val host: OverlayHost,
    private val frame: OverlayFrame,
    private val params: WindowManager.LayoutParams,
    private val owners: OverlayOwners,
) {
    var isOpen = true
        private set

    /** 窗口此刻的宽高(像素);还没排版时为 0。 */
    val width: Int get() = frame.width
    val height: Int get() = frame.height

    /** 换位置。 */
    fun place(placement: OverlayPlacement) {
        if (!isOpen || params.gravity == placement.gravity && params.x == placement.x && params.y == placement.y) return
        params.place(placement)
        update()
    }

    /**
     * 能拖动时给 [drag],不能拖给 null。[handle] 是只有按在这块里才拖(窗口内的像素坐标,例如卡片的标题栏),
     * 为 null 时按在哪都能拖;是空的时哪儿都不能拖。
     */
    fun draggable(drag: OverlayDrag?, handle: Rect? = null) {
        if (!isOpen) return
        frame.drag = drag
        frame.handle = handle?.let(::Rect)
    }

    /** 能接收键盘时(见 [setFocusable])用户按了返回键;输入法开着时返回键先收起输入法,不到这里。 */
    fun onBack(onBack: (() -> Unit)?) {
        if (isOpen) frame.onBack = onBack
    }

    /** 按到窗口里面(不论按在哪)时调 [onTouch]:调用方据此把淡下去的胶囊恢复。 */
    fun onTouch(onTouch: (() -> Unit)?) {
        if (isOpen) frame.onTouch = onTouch
    }

    /** 内容大小变了(胶囊换成卡片、转屏后重排)时调 [onResize],调用方据此重新摆位置。 */
    fun onResize(onResize: (() -> Unit)?) {
        if (isOpen) frame.onResize = onResize
    }

    /**
     * 能不能接收键盘:要在浮层里打字时打开(输入法才会弹出来对着它),收起时立刻关上,把焦点还给下面的 App。
     */
    fun setFocusable(focusable: Boolean) {
        if (!isOpen) return
        val flags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (flags == params.flags) return
        params.flags = flags
        params.softInputMode = if (focusable) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        update()
    }

    /**
     * 窗口里的东西先藏起来,等它真的从屏幕上下去了再做 [block](例如截用户的屏幕),做完再显示出来。
     * 「真的下去了」:藏起来的那一帧画好、交给系统(帧提交回调)之后,再等 [GONE_FRAMES] 个刷新,合成器换上了这一帧;
     * 帧提交回调没来(窗口这时不画)也最多等 [GONE_WAIT_MS]。窗口本身留着(位置、状态都不变),只是这段时间里是透明的。
     */
    suspend fun <T> hiddenWhile(block: suspend () -> T): T {
        val content = frame.getChildAt(0)
        if (!isOpen || content == null) return block()
        content.visibility = View.INVISIBLE
        try {
            withTimeoutOrNull(GONE_WAIT_MS) {
                suspendCancellableCoroutine { cont ->
                    frame.viewTreeObserver.registerFrameCommitCallback { if (cont.isActive) cont.resume(Unit) }
                    frame.invalidate()
                }
            }
            repeat(GONE_FRAMES) {
                suspendCancellableCoroutine { cont -> Choreographer.getInstance().postFrameCallback { if (cont.isActive) cont.resume(Unit) } }
            }
            return block()
        } finally {
            if (isOpen) content.visibility = View.VISIBLE
        }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        frame.release()
        host.forget(this)
        runCatching { host.wm().removeViewImmediate(frame) }
        owners.destroy()
    }

    private fun update() {
        runCatching { host.wm().updateViewLayout(frame, params) }.onFailure { close() }
    }

    private companion object {
        /** 藏起来的那一帧交给系统后再等几个刷新:合成器在下一个刷新才换上它。 */
        const val GONE_FRAMES = 2
        const val GONE_WAIT_MS = 300L
    }
}

/**
 * 浮层窗口的根,里面是 Compose 内容。收窗口外的按下([onOutside]);能拖动时([drag])手指按在把手([handle],没有时整个窗口)里、
 * 移过触摸阈值就把这串触摸截下来,里面的按钮收到取消。拖动按屏幕坐标算。
 */
internal class OverlayFrame(context: Context) : FrameLayout(context) {
    var onOutside: (() -> Unit)? = null
    var onResize: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onTouch: (() -> Unit)? = null
    var drag: OverlayDrag? = null
    var handle: Rect? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false

    init {
        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) onResize?.invoke()
        }
    }

    fun release() {
        drag = null
        handle = null
        onResize = null
        onOutside = null
        onBack = null
        onTouch = null
        tracking = false
        dragging = false
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            val outside = onOutside ?: return false
            outside()
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) onTouch?.invoke()
        return super.dispatchTouchEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val back = onBack
        if (back != null && e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) back()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = track(e) && dragging

    /** 按在内容里没有东西接的地方也能拖。 */
    @SuppressLint("ClickableViewAccessibility") // 拖动只是挪位置;读屏时用胶囊上的「移到另一边」
    override fun onTouchEvent(e: MotionEvent): Boolean = track(e)

    /** 跟着这串触摸;返回 true 表示还要接着收(能拖的时候)。 */
    private fun track(e: MotionEvent): Boolean {
        val d = drag
        if (d == null) {
            tracking = false
            dragging = false
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                tracking = handle?.contains(e.x.toInt(), e.y.toInt()) ?: true
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    d.start()
                }
                if (dragging) d.move(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) d.end(e.rawX - downX, e.rawY - downY)
                tracking = false
                dragging = false
            }
        }
        return tracking
    }
}

private fun WindowManager.LayoutParams.place(p: OverlayPlacement) {
    gravity = p.gravity
    x = p.x
    y = p.y
}

/** Compose 在 Activity 之外要自己配上生命周期、状态保存与 ViewModel 的宿主;窗口加上时进入 RESUMED,拆掉时销毁。 */
internal class OverlayOwners : SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        // 从没启动过的(加窗口前就失败)也要先进入 CREATED 才能销毁
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

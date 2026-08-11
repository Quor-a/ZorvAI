package com.ai.assistance.quro.browser

import android.util.Log

/**
 * Uinput 原生桥接（L3 事件面）。
 *
 * 与 AIDL / LocalSocket 传输（L1 控制面）经 L4 编排协作：信令走 AIDL，内核事件走本桥写入
 * /dev/uinput。二者不是一条线——本类只负责把「语义动作」翻译成「设备事件」。
 *
 * 优雅降级铁律：native lib 未加载（非 root / 系统签名构建）或 nativeOpen 失败时，
 * open() 一律返回 false；调用方必须据此明确报错「需 root / 系统签名」，**绝不**假装注入成功。
 */
class UinputBridge {

    companion object {
        private const val LIB = "uinput_bridge"
        /** 原生库是否成功加载（未加载 = 普通分发版，不支持 Uinput）。 */
        var libLoaded: Boolean = false
            private set

        init {
            libLoaded = try {
                System.loadLibrary(LIB)
                true
            } catch (t: Throwable) {
                Log.w("UinputBridge", "原生 lib 未加载（非 root / 系统签名构建）：${t.message}")
                false
            }
        }
    }

    external fun nativeOpen(maxX: Int, maxY: Int): Boolean
    external fun nativeClose()
    external fun nativeDown(slot: Int, tid: Int, x: Int, y: Int, pressure: Int, major: Int)
    external fun nativeMove(slot: Int, x: Int, y: Int, pressure: Int, major: Int)
    external fun nativeUp(slot: Int)

    /** 打开虚拟设备（maxX/maxY 为真实屏幕分辨率，使注入坐标 1:1 映射）。失败返回 false。 */
    fun open(maxX: Int, maxY: Int): Boolean {
        if (!libLoaded) return false
        return try { nativeOpen(maxX, maxY) } catch (t: Throwable) {
            Log.w("UinputBridge", "nativeOpen 异常：${t.message}")
            false
        }
    }

    fun close() {
        if (libLoaded) try { nativeClose() } catch (_: Throwable) {}
    }

    fun down(slot: Int, tid: Int, x: Int, y: Int, pressure: Int, major: Int) {
        if (libLoaded) try { nativeDown(slot, tid, x, y, pressure, major) } catch (_: Throwable) {}
    }

    fun move(slot: Int, x: Int, y: Int, pressure: Int, major: Int) {
        if (libLoaded) try { nativeMove(slot, x, y, pressure, major) } catch (_: Throwable) {}
    }

    fun up(slot: Int) {
        if (libLoaded) try { nativeUp(slot) } catch (_: Throwable) {}
    }
}

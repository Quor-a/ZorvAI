/*
 * QuroAci Uinput Bridge —— 原生伪输入设备注入器（L3 事件面）
 *
 * ── 设计边界（务必遵守，铁律）──────────────────────────────────────────────
 * 1. 本模块只负责「事件面」：接收语义动作（down / move / up），向 /dev/uinput 写出
 *    内核 input_event。它不传信令、不决策，纯粹把「动作」变成「设备事件」。
 * 2. 控制面（L1，AIDL / LocalSocket 传输信令与能力调用）与事件面（L3）不是一条线——
 *    二者经 L4 编排协作：信令走 AIDL，内核事件走 Uinput。详见开发者手册 §Uinput 桥接。
 * 3. 仅在受控端以 root 或系统签名（priv-app + 放行 uinput_device 的 SELinux policy）
 *    构建时，/dev/uinput 可写，注入才会真实生效。
 * 4. 普通分发版（无 uinput 写权限）nativeOpen() 一律返回 JNI_FALSE；上层据此明确报错
 *    「需 root / 系统签名」，绝不静默假装注入成功（不杜撰功能）。
 * ─────────────────────────────────────────────────────────────────────────
 */

#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <stdint.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <sys/ioctl.h>
#include <android/log.h>

#define TAG "QuroUinput"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* 单例句柄：Uinput 设备全局唯一，进程内复用 */
static int g_fd = -1;

/* 写出一个 input_event（内核 64 位布局：timeval(16) + type(2) + code(2) + value(4) = 24 字节） */
static void emit(int type, int code, int value) {
    if (g_fd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type  = (uint16_t) type;
    ev.code  = (uint16_t) code;
    ev.value = (int32_t)  value;
    if (write(g_fd, &ev, sizeof(ev)) < 0) {
        LOGE("emit write failed: %s", strerror(errno));
    }
}

static void emit_syn(void) { emit(EV_SYN, SYN_REPORT, 0); }

/*
 * nativeOpen：注册并创建高保真虚拟多点触摸屏。
 * maxX / maxY 应为真实屏幕分辨率（像素），使注入坐标 1:1 映射到屏幕。
 * 返回 true 表示设备创建成功（真实可注入）；false 表示 /dev/uinput 不可写（无权限）。
 */
JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_quro_browser_UinputBridge_nativeOpen(JNIEnv *env, jobject thiz,
                                                            jint maxX, jint maxY) {
    (void)env; (void)thiz;
    if (g_fd >= 0) { close(g_fd); g_fd = -1; }

    int fd = open("/dev/uinput", O_WRONLY);
    if (fd < 0) {
        LOGE("open /dev/uinput 失败: %s（需 root 或系统签名构建）", strerror(errno));
        return JNI_FALSE;
    }

    int mx = (maxX > 0) ? maxX : 1080;
    int my = (maxY > 0) ? maxY : 2400;

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    strncpy(uidev.name, "QuroAciVirtualTouch", UINPUT_MAX_NAME_SIZE - 1);
    uidev.id.bustype  = BUS_USB;
    uidev.id.vendor   = 0x04E8;   /* 高保真设备伪装：借用常见触控厂商 ID */
    uidev.id.product  = 0x0001;
    uidev.id.version  = 1;

    /* 轴范围：与真实屏幕分辨率对齐，注入坐标 1:1 映射 */
    uidev.absmin[ABS_MT_POSITION_X] = 0;  uidev.absmax[ABS_MT_POSITION_X] = mx;
    uidev.absmin[ABS_MT_POSITION_Y] = 0;  uidev.absmax[ABS_MT_POSITION_Y] = my;
    uidev.absmin[ABS_MT_TOUCH_MAJOR] = 0; uidev.absmax[ABS_MT_TOUCH_MAJOR] = 30;
    uidev.absmin[ABS_MT_PRESSURE]    = 0; uidev.absmax[ABS_MT_PRESSURE]    = 255;
    uidev.absmin[ABS_MT_TRACKING_ID] = -1; uidev.absmax[ABS_MT_TRACKING_ID] = 31;
    /* 单点兼容轴（旧应用读 ABS_X/ABS_Y） */
    uidev.absmin[ABS_X] = 0; uidev.absmax[ABS_X] = mx;
    uidev.absmin[ABS_Y] = 0; uidev.absmax[ABS_Y] = my;

    /* 事件位 + 键位 + 轴位 */
    ioctl(fd, UI_SET_EVBIT,  EV_KEY);
    ioctl(fd, UI_SET_EVBIT,  EV_ABS);
    ioctl(fd, UI_SET_EVBIT,  EV_SYN);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);

    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(fd, UI_SET_ABSBIT, ABS_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_Y);

    /* 设备属性：DIRECT（直触屏，非鼠标式相对设备） */
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    if (write(fd, &uidev, sizeof(uidev)) != (ssize_t) sizeof(uidev)) {
        LOGE("write uidev 失败: %s", strerror(errno));
        close(fd);
        return JNI_FALSE;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE 失败: %s", strerror(errno));
        close(fd);
        return JNI_FALSE;
    }
    g_fd = fd;
    LOGI("uinput 设备已创建 (mx=%d my=%d)", mx, my);
    return JNI_TRUE;
}

/*
 * nativeDown：在指定 slot 落下一点（MT Protocol B：先 SLOT 再 TRACKING_ID 与坐标）。
 * 同时维护单点兼容轴 ABS_X/ABS_Y 与 BTN_TOUCH，兼容不识别 MT 的应用。
 */
JNIEXPORT void JNICALL
Java_com_ai_assistance_quro_browser_UinputBridge_nativeDown(JNIEnv *env, jobject thiz,
        jint slot, jint tid, jint x, jint y, jint pressure, jint major) {
    (void)env; (void)thiz;
    if (g_fd < 0) return;
    emit(EV_ABS, ABS_MT_SLOT,        slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, tid);
    emit(EV_ABS, ABS_MT_POSITION_X,  x);
    emit(EV_ABS, ABS_MT_POSITION_Y,  y);
    emit(EV_ABS, ABS_MT_PRESSURE,    pressure);
    emit(EV_ABS, ABS_MT_TOUCH_MAJOR, (major > 0) ? major : 8);
    /* 单点兼容 */
    emit(EV_ABS, ABS_X, x);
    emit(EV_ABS, ABS_Y, y);
    emit(EV_KEY, BTN_TOUCH, 1);
    emit_syn();
}

/* nativeMove：移动已落下的点（保持 slot / tracking_id，更新坐标与压力）。 */
JNIEXPORT void JNICALL
Java_com_ai_assistance_quro_browser_UinputBridge_nativeMove(JNIEnv *env, jobject thiz,
        jint slot, jint x, jint y, jint pressure, jint major) {
    (void)env; (void)thiz;
    if (g_fd < 0) return;
    emit(EV_ABS, ABS_MT_SLOT,        slot);
    emit(EV_ABS, ABS_MT_POSITION_X,  x);
    emit(EV_ABS, ABS_MT_POSITION_Y,  y);
    emit(EV_ABS, ABS_MT_PRESSURE,    pressure);
    emit(EV_ABS, ABS_MT_TOUCH_MAJOR, (major > 0) ? major : 8);
    emit(EV_ABS, ABS_X, x);
    emit(EV_ABS, ABS_Y, y);
    emit_syn();
}

/* nativeUp：抬起指定 slot 的点（TRACKING_ID = -1 表示离开）。 */
JNIEXPORT void JNICALL
Java_com_ai_assistance_quro_browser_UinputBridge_nativeUp(JNIEnv *env, jobject thiz, jint slot) {
    (void)env; (void)thiz;
    if (g_fd < 0) return;
    emit(EV_ABS, ABS_MT_SLOT,        slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
    emit(EV_KEY, BTN_TOUCH, 0);
    emit_syn();
}

/* nativeClose：销毁设备并关闭 fd（成对出现，避免句柄泄漏）。 */
JNIEXPORT void JNICALL
Java_com_ai_assistance_quro_browser_UinputBridge_nativeClose(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (g_fd >= 0) {
        ioctl(g_fd, UI_DEV_DESTROY);
        close(g_fd);
        g_fd = -1;
        LOGI("uinput 设备已销毁");
    }
}

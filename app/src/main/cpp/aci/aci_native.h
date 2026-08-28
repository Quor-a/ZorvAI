#ifndef QURO_ACI_NATIVE_H
#define QURO_ACI_NATIVE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * ACI 原生接口（libacihost.so）——让 C/C++ 代码直接调用 ACI 的全部能力。
 *
 * 说明：ACI 的接口本身就是通用的 call(capability, params)，因此只要暴露
 * 「列能力」+「调任意能力」两个入口，就能覆盖 ACI 的**全部**能力
 * （含将来新增的、无需改本层）。
 *
 * 实现：本库是**进程内** JNI 库（由 Java 侧 System.loadLibrary 加载），
 * 经 JNI 调用 com.ai.assistance.quro.core.aidlaci.AciNativeBridge。
 * 进出统一用 JSON 字符串，避免在 JNI 层搬运 Bundle/Parcelable。
 *
 * 线程：可从任意原生线程调用，内部会自动 AttachCurrentThread。
 * ⚠ 不得在 UI 主线程调用耗时能力（会 ANR）。
 *
 * 不适用：qurohost 是经 ProcessBuilder 启动的**独立**原生进程、不在 JVM 内，
 * 无法用本库；它改走既有的 `@qurohost-ctl` 控制通道，由 Kotlin 侧桥接 ACI。
 */

/* 返回码 */
#define ACI_OK            0   /* 调用层成功（业务成败看输出 JSON 的 ok 字段） */
#define ACI_ERR_NO_VM    -1   /* 无 JavaVM：JNI_OnLoad 未执行 */
#define ACI_ERR_ATTACH   -2   /* AttachCurrentThread 失败 */
#define ACI_ERR_NOCLASS  -3   /* 找不到 AciNativeBridge 类 */
#define ACI_ERR_NOMETHOD -4   /* 找不到目标静态方法 */
#define ACI_ERR_EXCEPTION -5  /* Java 侧抛异常（已 clear） */
#define ACI_ERR_GETSTR   -6   /* 取返回字符串失败 */
#define ACI_ERR_NULLRES  -7   /* Java 侧返回 null */
#define ACI_ERR_NOBUF    -8   /* 输出缓冲区为空或过小 */

/** 输出 JSON 的默认缓冲区大小建议（约 8KB；超长结果会被截断）。 */
#define ACI_BUFSZ_DEFAULT 8192

/**
 * ACI 控制端是否就绪。
 * @return 1 可用，0 不可用，负数为调用层错误码
 */
int aci_available(void);

/**
 * 调用指定受控端的**任意**能力。
 *
 * @param target_pkg 受控端包名（可用 aci_list 查得）
 * @param capability 能力 id
 * @param args_json  参数 JSON 对象字符串（键值均为标量），无参传 "" 或 NULL
 * @param confirmed  非 0 表示已征得用户同意（requireUserConfirm 的能力需要）
 * @param out        输出缓冲区，写入 JSON：{"ok","code","error"?,"data"?}
 * @param outcap     缓冲区容量
 * @return ACI_OK 或负的错误码
 */
int aci_call(const char *target_pkg,
             const char *capability,
             const char *args_json,
             int confirmed,
             char *out, size_t outcap);

/**
 * 列出所有受控端及其能力。
 *
 * @param out    输出缓冲区，写入 JSON：
 *               {"ok":true,"targets":[{"package":..,"capabilities":[{"id","description","confirm"}]}]}
 * @param outcap 缓冲区容量
 * @return ACI_OK 或负的错误码
 */
int aci_list(char *out, size_t outcap);

/**
 * 最近一次失败的原因（线程局部静态串，永不返回 NULL）。
 * 成功时不更新内容。便于 C 侧打日志。
 */
const char *aci_last_error(void);

#ifdef __cplusplus
}
#endif

#endif /* QURO_ACI_NATIVE_H */

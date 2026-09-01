package de.robv.android.xposed;

/**
 * 最小化 Xposed API 桩（仅供编译期 compileOnly，不进 APK）。
 *
 * 运行时由 LSPosed / Xposed 框架把真实 de.robv.android.xposed.* 类注入进程，
 * 因此本桩只需暴露与真实 API 一致的方法签名，让模块代码能编译通过即可。
 */
public interface IXposedMod {
}

package com.ai.assistance.quro.vt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 夜间模式枚举（移植自 Termux ThemeUtils 依赖的 NightMode，已去除 AppCompatDelegate 与 Logger 依赖）。
 * 值仅保留语义，mode 由消费方自行映射。
 */
public enum NightMode {

    /** 始终启用夜间主题。 */
    TRUE("true", 2),

    /** 始终禁用夜间主题。 */
    FALSE("false", 1),

    /** 跟随系统夜间模式。 */
    SYSTEM("system", 0);

    /** 当前 app 级夜间模式，默认跟随系统。 */
    private static NightMode APP_NIGHT_MODE;

    private final String name;
    private final int mode;

    NightMode(final String name, int mode) {
        this.name = name;
        this.mode = mode;
    }

    public String getName() {
        return name;
    }

    public int getMode() {
        return mode;
    }

    /** 按名称查找 {@link NightMode}，未找到返回 {@code null}。 */
    @Nullable
    public static NightMode modeOf(String name) {
        for (NightMode v : NightMode.values()) {
            if (v.name.equals(name)) {
                return v;
            }
        }
        return null;
    }

    /** 按名称查找 {@link NightMode}，未找到返回 {@code def}。 */
    @NonNull
    public static NightMode modeOf(@Nullable String name, @NonNull NightMode def) {
        NightMode nightMode = modeOf(name);
        return nightMode != null ? nightMode : def;
    }

    /** 设置 {@link #APP_NIGHT_MODE}。 */
    public static void setAppNightMode(@Nullable String name) {
        APP_NIGHT_MODE = (name == null || name.isEmpty()) ? SYSTEM : modeOf(name, SYSTEM);
    }

    /** 获取 {@link #APP_NIGHT_MODE}。 */
    @NonNull
    public static NightMode getAppNightMode() {
        if (APP_NIGHT_MODE == null)
            APP_NIGHT_MODE = SYSTEM;
        return APP_NIGHT_MODE;
    }
}

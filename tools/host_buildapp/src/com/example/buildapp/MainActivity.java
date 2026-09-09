package com.example.buildapp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 构建台宿主 Activity（base.apk 模板的入口）。
 *
 * 设计目标：让用户/AI 写「任意包名 + 任意类名」的 Java 入口都能跑，不再硬编码
 * com.example.hello.Main。运行模型：
 *  1. 构建时，BuildEngine 会扫描编译产物，找到带入口方法（main/run）的类，
 *     把其「全限定名」写入 APK 的 assets/zorv_entry.txt。
 *  2. 本宿主在 onCreate 读取 zorv_entry.txt 得到入口类；缺失时回退 com.example.hello.Main（兼容旧产物）。
 *  3. 反射查找入口方法，按优先级尝试：
 *       静态：main(Activity,String[]) → main(String[]) → run(Activity) → run()
 *       实例：同上四种签名（先 new 实例再调用）
 *  4. 任一步出错（类找不到 / 方法找不到 / 运行抛异常），都在界面显示明确错误文本，而不是崩溃。
 *
 * 注意：本类不引用任何 res/ 资源（UI 全部用代码构建），因此重编宿主 dex 不需要重新打包资源。
 */
public class MainActivity extends Activity {
    private static final String FALLBACK_ENTRY = "com.example.hello.Main";
    private static final String ENTRY_FILE = "zorv_entry.txt";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 默认占位视图：用户代码通常会自行 setContentView 覆盖；若没有界面逻辑，至少不会白屏/崩溃。
        TextView placeholder = new TextView(this);
        placeholder.setText("应用正在启动…");
        placeholder.setTextColor(Color.WHITE);
        placeholder.setPadding(24, 24, 24, 24);
        setContentView(placeholder);

        try {
            runUserCode();
        } catch (Throwable t) {
            showError("运行用户代码时出错", t);
        }
    }

    private void runUserCode() throws Throwable {
        String entry = readEntryClass();
        if (entry == null || entry.trim().isEmpty()) entry = FALLBACK_ENTRY;

        Class<?> clazz;
        try {
            clazz = Class.forName(entry);
        } catch (ClassNotFoundException e) {
            showError("找不到入口类：" + entry,
                new RuntimeException("构建台未能定位入口类。请确认源码里存在类 " + entry
                    + "（构建时会把它的全限定名写入 assets/zorv_entry.txt）。"));
            return;
        }

        Method m;
        // 1) main(Activity, String[])
        if ((m = findMethod(clazz, "main", true, Activity.class, String[].class)) != null) {
            m.invoke(null, this, new String[0]);
            return;
        }
        // 2) main(String[])
        if ((m = findMethod(clazz, "main", true, String[].class)) != null) {
            m.invoke(null, (Object) new String[0]);
            return;
        }
        // 3) run(Activity)
        if ((m = findMethod(clazz, "run", true, Activity.class)) != null) {
            m.invoke(null, this);
            return;
        }
        // 4) run()
        if ((m = findMethod(clazz, "run", true)) != null) {
            m.invoke(null);
            return;
        }
        // 实例变体
        if ((m = findMethod(clazz, "main", false, Activity.class, String[].class)) != null) {
            m.invoke(clazz.getDeclaredConstructor().newInstance(), this, new String[0]);
            return;
        }
        if ((m = findMethod(clazz, "main", false, String[].class)) != null) {
            m.invoke(clazz.getDeclaredConstructor().newInstance(), (Object) new String[0]);
            return;
        }
        if ((m = findMethod(clazz, "run", false, Activity.class)) != null) {
            m.invoke(clazz.getDeclaredConstructor().newInstance(), this);
            return;
        }
        if ((m = findMethod(clazz, "run", false)) != null) {
            m.invoke(clazz.getDeclaredConstructor().newInstance());
            return;
        }
        showError("入口类没有可识别的入口方法：" + entry,
            new RuntimeException("入口类需要一个以下签名之一（public）：\n" +
                "  static void main(String[] args)\n" +
                "  static void main(Activity a, String[] args)\n" +
                "  static void run(Activity a)\n" +
                "  static void run()"));
    }

    private static Method findMethod(Class<?> c, String name, boolean wantStatic, Class<?>... params) {
        try {
            Method m = c.getMethod(name, params);
            boolean isStatic = Modifier.isStatic(m.getModifiers());
            if (wantStatic && !isStatic) return null;
            if (!wantStatic && isStatic) return null;
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private String readEntryClass() {
        try {
            InputStream is = getAssets().open(ENTRY_FILE);
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line = br.readLine();
            br.close();
            return line == null ? null : line.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private void showError(String title, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n");
        sb.append(throwableDetail(t));
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextColor(Color.WHITE);
        tv.setBackgroundColor(Color.rgb(48, 0, 0));
        tv.setTextSize(12);
        tv.setPadding(24, 24, 24, 24);
        sv.addView(tv);
        setContentView(sv);
    }

    private static String throwableDetail(Throwable e) {
        StringBuilder sb = new StringBuilder();
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        sb.append(sw.toString());
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        if (r != e && r != null) {
            sb.append("\n[根因] ").append(r.getClass().getName()).append(": ").append(r.getMessage());
        }
        return sb.toString();
    }
}

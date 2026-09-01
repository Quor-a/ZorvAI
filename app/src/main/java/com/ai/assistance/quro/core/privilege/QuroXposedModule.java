package com.ai.assistance.quro.core.privilege;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_LoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Zorv AI 的 LSPosed / Xposed 模块入口（实现 IXposedHookLoadPackage）。
 *
 * 本类仅由 LSPosed 框架在「本应用被纳入作用域」或「钩中配置了的目标包」时加载，
 * 常规 App 运行路径永远不会引用它（故其依赖的 de.robv.android.xposed.* 以 compileOnly 桩提供、
 * 不进 APK，也不会触发 NoClassDefFoundError）。
 *
 * 提供的真实能力：
 *  1) 作用域标记：本应用被纳入作用域时，在 attachBaseContext 钩子里写 .lsposed_scope 标记文件，
 *     供 QuroLSPosed.isAppInScope() 真实判定（取代原先「框架已安装即永真」的假判定）。
 *  2) 可选跨应用注入桥（opt-in）：读外部存储 lsposed_bridge.json，对目标包 Activity.onCreate
 *     发广播通知 Zorv AI「某 App 已打开」（补充 get_foreground_app 在无 usage-stats 权限时的盲区）。
 *  3) 可选系统级重定向桥（opt-in）：按配置重写目标包 startActivity 的 Intent（如把某域名跳转改投 Zorv AI）。
 *
 * 全部以「配置开关 + try/catch」守卫：配置缺失/不可读/未启用时静默跳过，绝不影响宿主 App 正常运行。
 * 不定义任何 ai.aci.permission.*（定义权属控制端，本应用只声明与使用）。
 */
public class QuroXposedModule implements IXposedHookLoadPackage {

    private static final String SELF_PKG = "com.ai.assistance.quro";
    private static final String SCOPE_MARKER = ".lsposed_scope";
    private static final String BRIDGE_CONFIG = "lsposed_bridge.json";
    private static final String DEFAULT_BRIDGE_ACTION = "com.ai.assistance.quro.LSPOSED_APP_OPENED";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 1) 作用域标记：本应用被纳入作用域 → 写标记文件（QuroApplication 每次启动先清旧标记，
            //    故标记存在即代表「当前进程确实被 LSPosed 钩中」，可真实反映作用域状态）。
            if (SELF_PKG.equals(lpparam.packageName)) {
                installScopeMarker();
            }

            // 2) + 3) 可选桥：best-effort 读外部存储配置，按开关钩目标包。
            BridgeConfig cfg = BridgeConfig.load();
            BridgeConfig.sLastConfig = cfg;
            if (cfg != null && cfg.enabled) {
                if (cfg.crossAppInjection && cfg.targetPackages.contains(lpparam.packageName)) {
                    installCrossAppInjection(lpparam, cfg);
                }
                if (cfg.systemRedirect && !cfg.redirectRules.isEmpty()) {
                    installSystemRedirect(lpparam);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("ZorvAI Xposed: " + t);
        }
    }

    // ---- 1) 作用域标记 ----

    private void installScopeMarker() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attachBaseContext", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Context base = (Context) param.args[0];
                            File marker = new File(base.getFilesDir(), SCOPE_MARKER);
                            writeMarker(marker, System.currentTimeMillis());
                        } catch (Throwable t) {
                            XposedBridge.log("ZorvAI scope marker: " + t);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log("ZorvAI scope hook: " + t);
        }
    }

    private static void writeMarker(File marker, long ts) {
        java.io.FileWriter w = null;
        try {
            w = new java.io.FileWriter(marker);
            w.write(Long.toString(ts));
            w.flush();
        } catch (Throwable ignore) {
            // 标记写入失败不影响宿主
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    // ---- 2) 跨应用注入桥 ----

    private void installCrossAppInjection(XC_LoadPackage.LoadPackageParam lpparam, BridgeConfig cfg) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Activity a = (Activity) param.thisObject;
                            Intent i = new Intent(cfg.broadcastAction);
                            i.setPackage(SELF_PKG);
                            i.putExtra("package", lpparam.packageName);
                            i.putExtra("activity", a.getClass().getName());
                            i.putExtra("ts", System.currentTimeMillis());
                            a.sendBroadcast(i);
                        } catch (Throwable t) {
                            XposedBridge.log("ZorvAI bridge inject: " + t);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log("ZorvAI bridge hook: " + t);
        }
    }

    // ---- 3) 系统级重定向桥 ----

    private void installSystemRedirect(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "startActivity", Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Intent src = (Intent) param.args[0];
                            if (src == null) return;
                            RedirectRule rule = BridgeConfig.matchRedirect(lpparam.packageName, src);
                            if (rule == null) return;
                            Intent dst = new Intent();
                            dst.setClassName(rule.toPackage, rule.toClass);
                            if (src.getAction() != null) dst.setAction(src.getAction());
                            if (src.getData() != null) dst.setData(src.getData());
                            if (src.getExtras() != null) dst.putExtras(src.getExtras());
                            param.args[0] = dst;
                        } catch (Throwable t) {
                            XposedBridge.log("ZorvAI redirect: " + t);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log("ZorvAI redirect hook: " + t);
        }
    }

    // ---- 配置（best-effort 读外部存储 lsposed_bridge.json）----

    private static final class BridgeConfig {
        boolean enabled = false;
        boolean crossAppInjection = false;
        boolean systemRedirect = false;
        String broadcastAction = DEFAULT_BRIDGE_ACTION;
        List<String> targetPackages = new ArrayList<>();
        List<RedirectRule> redirectRules = new ArrayList<>();

        static BridgeConfig load() {
            try {
                File f = new File(Environment.getExternalStorageDirectory(), BRIDGE_CONFIG);
                if (!f.exists()) return null;
                StringBuilder sb = new StringBuilder();
                FileReader r = new FileReader(f);
                char[] buf = new char[1024];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                r.close();
                JSONObject o = new JSONObject(sb.toString());
                BridgeConfig c = new BridgeConfig();
                c.enabled = o.optBoolean("enabled", false);
                JSONObject cai = o.optJSONObject("cross_app_injection");
                if (cai != null) {
                    c.crossAppInjection = cai.optBoolean("enabled", false);
                    if (cai.has("broadcast_action")) c.broadcastAction = cai.optString("broadcast_action");
                    JSONArray pkgs = cai.optJSONArray("target_packages");
                    if (pkgs != null) {
                        for (int i = 0; i < pkgs.length(); i++) {
                            String p = pkgs.optString(i);
                            if (p != null && !p.isEmpty()) c.targetPackages.add(p);
                        }
                    }
                }
                JSONObject sr = o.optJSONObject("system_redirect");
                if (sr != null) {
                    c.systemRedirect = sr.optBoolean("enabled", false);
                    JSONArray rules = sr.optJSONArray("rules");
                    if (rules != null) {
                        for (int i = 0; i < rules.length(); i++) {
                            JSONObject ro = rules.optJSONObject(i);
                            if (ro == null) continue;
                            RedirectRule rr = new RedirectRule();
                            rr.whenPackage = ro.optString("when_package", null);
                            rr.whenAction = ro.optString("when_action", null);
                            rr.whenDataHost = ro.optString("when_data_host", null);
                            rr.toPackage = ro.optString("to_package", null);
                            rr.toClass = ro.optString("to_class", null);
                            if (rr.toPackage != null && !rr.toPackage.isEmpty()
                                    && rr.toClass != null && !rr.toClass.isEmpty()) {
                                c.redirectRules.add(rr);
                            }
                        }
                    }
                }
                return c.enabled ? c : null;
            } catch (Throwable t) {
                return null;
            }
        }

        static RedirectRule matchRedirect(String pkg, Intent src) {
            // 复用最近一次载入的配置做匹配（结构简单，仅本进程内使用）
            BridgeConfig c = sLastConfig;
            if (c == null) return null;
            for (RedirectRule r : c.redirectRules) {
                if (r.whenPackage != null && !r.whenPackage.equals(pkg)) continue;
                if (r.whenAction != null && !r.whenAction.equals(src.getAction())) continue;
                if (r.whenDataHost != null) {
                    Uri u = src.getData();
                    if (u == null || !r.whenDataHost.equals(u.getHost())) continue;
                }
                return r;
            }
            return null;
        }

        // 供 matchRedirect 复用（重定向钩子与载入在同进程）
        static BridgeConfig sLastConfig;
    }

    private static final class RedirectRule {
        String whenPackage;
        String whenAction;
        String whenDataHost;
        String toPackage;
        String toClass;
    }
}

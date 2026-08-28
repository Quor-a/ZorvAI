package ai.aidl.aci.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;

/**
 * ACI 通用能力 —— Intent 发送代理（capability id: {@value #CAP_ID}）。
 *
 * <p>让受控端**在自己进程内**代发 Intent：启动 Activity / 发送广播 / 启动（前台）服务。
 * 核心价值是打通调用方无法直接触及的受控端内部组件——未 exported 的 Activity、
 * 应用内广播、受保护 Service，都能由受控端自己触发；调用方只需描述 Intent。
 *
 * <p>受控端接入（两行）：
 * <pre>{@code
 *   // onCreateCapabilities
 *   caps.add(AciIntentBridge.capability());
 *   // onCall
 *   case "intent" -> AciIntentBridge.handle(this, req.getParams())
 * }</pre>
 *
 * <p>安全：声明为 {@link Capability#FLAG_DANGEROUS} 且
 * {@code requireUserConfirm=true}（控制端 aci_call 会兜底拦截，须带 confirm:true）。
 * 建议先用 {@code dry_run:true} 探测目标能否解析，再实际发送。
 */
public final class AciIntentBridge {
    private static final String TAG = "AciIntentBridge";

    /** 能力 id。 */
    public static final String CAP_ID = "intent";

    // ── 参数键 ──
    public static final String P_MODE = "mode";
    public static final String P_ACTION = "action";
    public static final String P_DATA = "data";
    public static final String P_PACKAGE = "package";
    public static final String P_COMPONENT = "component";
    public static final String P_CATEGORY = "category";
    public static final String P_TYPE = "type";
    public static final String P_FLAGS = "flags";
    public static final String P_EXTRAS = "extras";
    public static final String P_DRY_RUN = "dry_run";

    // ── 结果键 ──
    public static final String R_SENT = "sent";
    public static final String R_RESOLVED = "resolved";
    public static final String R_RESOLVED_PKG = "resolved_package";
    public static final String R_RESOLVED_CLS = "resolved_class";
    public static final String R_MODE = "mode";
    public static final String R_INTENT_URI = "intent_uri";
    public static final String R_DETAIL = "detail";

    /** extras 条目上限，防止超大 payload 撑爆 Binder 事务（AIDL 约 1MB 上限）。 */
    private static final int MAX_EXTRAS = 64;

    private AciIntentBridge() {
        // 工具类，禁止实例化
    }

    /** 生成能力声明，受控端在 onCreateCapabilities 里直接 add 即可。 */
    public static Capability capability() {
        return Capability.create(
                CAP_ID,
                "Intent 发送代理：由受控端在自己进程内代发 Android Intent——启动 Activity、" +
                    "发送广播、启动（前台）服务。可触发调用方无法直接触及的未 exported 组件、" +
                    "应用内广播与受保护 Service。建议先用 dry_run:true 探测目标能否解析，再实际发送。"
            )
            .addParam(P_MODE, "string", false,
                "发送方式：activity(默认，等价于 startActivity) / broadcast（发广播）/ " +
                    "service / foreground_service；也接受 start_activity、send_broadcast 等别名")
            .addParam(P_ACTION, "string", false, "Intent action，如 android.intent.action.VIEW")
            .addParam(P_DATA, "string", false, "数据 URI，如 https://... 或 content://...")
            .addParam(P_PACKAGE, "string", false, "目标包名（setPackage）")
            .addParam(P_COMPONENT, "string", false,
                "目标组件 \"包名/类名\"，优先级高于 package；用于精确定位未 exported 的组件")
            .addParam(P_CATEGORY, "string", false, "附加 category，如 android.intent.category.DEFAULT")
            .addParam(P_TYPE, "string", false, "MIME type；与 data 同时给定时用 setDataAndType 合并设置")
            .addParam(P_FLAGS, "string", false, "Intent flags 整数，支持十进制（268435456）或 0x 十六进制（0x10000000）")
            .addParam(P_EXTRAS, "string", false,
                "附加 extras 的 JSON 对象，如 {\"key\":\"value\",\"n\":1,\"b\":true}；" +
                    "按值类型自动映射为 String/int/long/double/boolean，最多 " + MAX_EXTRAS + " 项")
            .addParam(P_DRY_RUN, "string", false, "设为 \"true\" 只解析目标、不真正发送（安全检查，推荐先跑一次）")
            .addResult(R_SENT, "boolean", "是否真正执行了发送（dry_run 或解析失败时为 false）")
            .addResult(R_RESOLVED, "boolean", "目标组件是否被解析到")
            .addResult(R_RESOLVED_PKG, "string", "解析到的目标包名")
            .addResult(R_RESOLVED_CLS, "string", "解析到的目标类名")
            .addResult(R_MODE, "string", "实际使用的发送方式（已归一化）")
            .addResult(R_INTENT_URI, "string", "最终 Intent 的 toUri(0)，便于核对拼装结果")
            .addResult(R_DETAIL, "string", "结果说明 / 失败原因")
            .addFlag(Capability.FLAG_DANGEROUS)
            .setUserConfirm(true);
    }

    /** 处理一次 intent 调用。 */
    public static AidlAciResponse handle(Context ctx, Bundle params) {
        if (ctx == null) return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "context is null");
        if (params == null) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "params is null");

        final Uri uri;
        final String type;
        try {
            String data = str(params, P_DATA);
            type = str(params, P_TYPE);
            uri = TextUtils.isEmpty(data) ? null : Uri.parse(data);
        } catch (Throwable e) {
            return badRequest("data 不是合法 URI: " + str(params, P_DATA));
        }

        final String mode = normalizeMode(str(params, P_MODE));
        if (mode == null) {
            return badRequest("未知 mode: " + str(params, P_MODE)
                + "（可选 activity / broadcast / service / foreground_service）");
        }

        final Intent it = new Intent();
        String action = str(params, P_ACTION);
        if (!TextUtils.isEmpty(action)) it.setAction(action);

        // data 与 type 必须合并设置：单独 setType() 会把 data 清空
        if (uri != null && !TextUtils.isEmpty(type)) it.setDataAndType(uri, type);
        else if (uri != null) it.setData(uri);
        else if (!TextUtils.isEmpty(type)) it.setType(type);

        String comp = str(params, P_COMPONENT);
        if (!TextUtils.isEmpty(comp)) {
            int slash = comp.indexOf('/');
            if (slash <= 0 || slash >= comp.length() - 1) {
                return badRequest("component 需形如 \"包名/类名\"，实际: " + comp);
            }
            it.setComponent(new ComponentName(comp.substring(0, slash), comp.substring(slash + 1)));
        } else {
            String pkg = str(params, P_PACKAGE);
            if (!TextUtils.isEmpty(pkg)) it.setPackage(pkg);
        }

        String cat = str(params, P_CATEGORY);
        if (!TextUtils.isEmpty(cat)) it.addCategory(cat);

        // flags：支持十进制与 0x 十六进制
        int flags = 0;
        String flagsRaw = str(params, P_FLAGS);
        if (!TextUtils.isEmpty(flagsRaw)) {
            try {
                String f = flagsRaw.trim();
                flags = (f.startsWith("0x") || f.startsWith("0X"))
                    ? (int) Long.parseLong(f.substring(2), 16)
                    : (int) Long.parseLong(f);
            } catch (Throwable e) {
                return badRequest("flags 不是合法整数: " + flagsRaw);
            }
        }
        // 从 Service 上下文启动 Activity 必须带 NEW_TASK，否则抛
        // AndroidRuntimeException: Calling startActivity() from outside of an Activity context
        if ("activity".equals(mode)) flags |= Intent.FLAG_ACTIVITY_NEW_TASK;
        if (flags != 0) it.setFlags(flags);

        // extras：JSON 对象 → 按值类型映射
        String extrasJson = str(params, P_EXTRAS);
        int extrasCount = 0;
        if (!TextUtils.isEmpty(extrasJson)) {
            try {
                JSONObject jo = new JSONObject(extrasJson);
                Iterator<String> keys = jo.keys();
                while (keys.hasNext() && extrasCount < MAX_EXTRAS) {
                    String k = keys.next();
                    putTypedExtra(it, k, jo.opt(k));
                    extrasCount++;
                }
            } catch (Throwable e) {
                return badRequest("extras 不是合法 JSON 对象: " + e.getMessage());
            }
        }

        // 解析目标（dry_run 也执行，用于探测）
        final PackageManager pm = ctx.getPackageManager();
        boolean resolved = false;
        String rPkg = null;
        String rCls = null;
        try {
            if ("broadcast".equals(mode)) {
                List<ResolveInfo> rs = pm.queryBroadcastReceivers(it, 0);
                if (rs != null && !rs.isEmpty()) {
                    resolved = true;
                    ResolveInfo ri = rs.get(0);
                    rPkg = safePkg(ri);
                    rCls = safeCls(ri);
                }
            } else if ("service".equals(mode) || "foreground_service".equals(mode)) {
                ResolveInfo ri = pm.resolveService(it, 0);
                if (ri != null) {
                    resolved = true;
                    rPkg = safePkg(ri);
                    rCls = safeCls(ri);
                }
            } else {
                ResolveInfo ri = pm.resolveActivity(it, 0);
                if (ri != null) {
                    resolved = true;
                    rPkg = safePkg(ri);
                    rCls = safeCls(ri);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "解析 Intent 目标失败: " + e.getMessage());
        }

        boolean dry = isTrue(str(params, P_DRY_RUN));
        boolean sent = false;
        String detail;
        if (dry) {
            detail = "dry_run 未实际发送；目标" + (resolved ? "可解析" : "无法解析");
        } else {
            try {
                if ("activity".equals(mode)) {
                    ctx.startActivity(it);
                    sent = true;
                    detail = "已 startActivity";
                } else if ("broadcast".equals(mode)) {
                    ctx.sendBroadcast(it);
                    sent = true;
                    detail = "已 sendBroadcast";
                } else if ("service".equals(mode)) {
                    ComponentName cn = ctx.startService(it);
                    sent = cn != null;
                    detail = cn != null ? "已 startService → " + cn.flattenToString()
                        : "startService 返回 null（目标 Service 不存在或未导出）";
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // startForegroundService 自 API 26 引入；本模块 minSdk=24，故需版本判断
                    ComponentName cn = ctx.startForegroundService(it);
                    sent = cn != null;
                    detail = cn != null
                        ? "已 startForegroundService → " + cn.flattenToString()
                          + "（注意：目标须在 5 秒内显示前台通知，否则系统会判定为 ANR）"
                        : "startForegroundService 返回 null";
                } else {
                    // API 24–25 尚无 startForegroundService，startService 即可启动前台服务
                    ComponentName cn = ctx.startService(it);
                    sent = cn != null;
                    detail = cn != null
                        ? "已 startService（API " + Build.VERSION.SDK_INT
                          + " < 26，前台服务退化为 startService）→ " + cn.flattenToString()
                        : "startService 返回 null";
                }
            } catch (SecurityException e) {
                return AidlAciResponse.error(AidlAciError.PERMISSION_DENIED,
                    "发送 Intent 被系统拒绝（目标未导出或缺少权限）: " + e.getMessage());
            } catch (Throwable e) {
                return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR,
                    "发送 Intent 失败: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
            if (!resolved && sent) detail += "（未解析到明确组件，但已按 Intent 语义发送）";
        }

        Bundle out = new Bundle();
        out.putBoolean(R_SENT, sent);
        out.putBoolean(R_RESOLVED, resolved);
        if (rPkg != null) out.putString(R_RESOLVED_PKG, rPkg);
        if (rCls != null) out.putString(R_RESOLVED_CLS, rCls);
        out.putString(R_MODE, mode);
        out.putString(R_DETAIL, detail);
        try {
            out.putString(R_INTENT_URI, it.toUri(0));
        } catch (Throwable ignored) {
            // toUri 在极端参数下可能失败，不影响主流程
        }
        return AidlAciResponse.success(out);
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    /**
     * 从 Bundle 取字符串（兼容控制端按 JSON 类型存的 String/Int/Double/Boolean）。
     * aci_call 会把 JSON 数字存成 int/double、布尔存成 boolean，
     * 直接 getString 会拿到 null，这里统一 String.valueOf。
     */
    private static String str(Bundle b, String key) {
        Object o = b.get(key);
        return o == null ? null : String.valueOf(o);
    }

    private static boolean isTrue(String v) {
        return v != null && ("true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()));
    }

    /** 归一化 mode，支持常见别名；无法识别返回 null。 */
    private static String normalizeMode(String raw) {
        if (TextUtils.isEmpty(raw)) return "activity";
        String m = raw.trim().toLowerCase().replace("-", "_");
        if ("activity".equals(m) || "start_activity".equals(m) || "startactivity".equals(m)) return "activity";
        if ("broadcast".equals(m) || "send_broadcast".equals(m) || "sendbroadcast".equals(m)) return "broadcast";
        if ("service".equals(m) || "start_service".equals(m) || "startservice".equals(m)) return "service";
        if ("foreground_service".equals(m) || "start_foreground_service".equals(m)
            || "foregroundservice".equals(m)) return "foreground_service";
        return null;
    }

    private static void putTypedExtra(Intent it, String k, Object v) {
        if (v == null || JSONObject.NULL.equals(v)) {
            it.putExtra(k, (String) null);
        } else if (v instanceof Boolean) {
            it.putExtra(k, (Boolean) v);
        } else if (v instanceof Integer) {
            it.putExtra(k, (Integer) v);
        } else if (v instanceof Long) {
            it.putExtra(k, (Long) v);
        } else if (v instanceof Float) {
            it.putExtra(k, (Float) v);
        } else if (v instanceof Double) {
            it.putExtra(k, (Double) v);
        } else {
            it.putExtra(k, String.valueOf(v));
        }
    }

    private static String safePkg(ResolveInfo ri) {
        try {
            return ri.activityInfo != null ? ri.activityInfo.packageName
                : (ri.serviceInfo != null ? ri.serviceInfo.packageName : null);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String safeCls(ResolveInfo ri) {
        try {
            return ri.activityInfo != null ? ri.activityInfo.name
                : (ri.serviceInfo != null ? ri.serviceInfo.name : null);
        } catch (Throwable e) {
            return null;
        }
    }

    private static AidlAciResponse badRequest(String msg) {
        return AidlAciResponse.error(AidlAciError.BAD_REQUEST, msg);
    }
}

package ai.aidl.aci.core;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * ACI 通用能力 —— ContentProvider 访问代理（capability id: {@value #CAP_ID}）。
 *
 * <p>让受控端**在自己进程内**代为读写 ContentProvider：query / insert / update /
 * delete / call。核心价值是跨应用数据互通——调用方不必持有（也往往拿不到）
 * 目标 Provider 的读写权限，由拥有权限的受控端代劳后把结果回传。
 *
 * <p>受控端接入（两行）：
 * <pre>{@code
 *   // onCreateCapabilities
 *   caps.add(AciProviderBridge.capability());
 *   // onCall
 *   case "provider" -> AciProviderBridge.handle(this, req.getParams())
 * }</pre>
 *
 * <p>安全：Provider 自身的读写权限由 Android 系统强制（无权限会抛 SecurityException，
 * 此处转为 PERMISSION_DENIED 返回，不会把崩溃抛给调用方）；本能力另外声明
 * {@link Capability#FLAG_DANGEROUS} 且 {@code requireUserConfirm=true}。
 * 查询结果受 {@link #MAX_ROWS} 上限保护，避免大表拖垮 Binder 事务。
 */
public final class AciProviderBridge {
    private static final String TAG = "AciProviderBridge";

    /** 能力 id。 */
    public static final String CAP_ID = "provider";

    // ── 参数键 ──
    public static final String P_URI = "uri";
    public static final String P_OP = "op";
    public static final String P_PROJECTION = "projection";
    public static final String P_SELECTION = "selection";
    public static final String P_SELECTION_ARGS = "selection_args";
    public static final String P_SORT_ORDER = "sort_order";
    public static final String P_VALUES = "values";
    public static final String P_METHOD = "method";
    public static final String P_ARG = "arg";
    public static final String P_EXTRAS = "extras";
    public static final String P_LIMIT = "limit";
    public static final String P_DRY_RUN = "dry_run";

    // ── 结果键 ──
    public static final String R_OP = "op";
    public static final String R_COUNT = "count";
    public static final String R_COLUMNS = "columns";
    public static final String R_ROWS = "rows";
    public static final String R_URI = "uri";
    public static final String R_TRUNCATED = "truncated";
    public static final String R_CALL_RESULT = "call_result";
    public static final String R_DETAIL = "detail";

    /** 单次 query 返回行数硬上限（防止大表撑爆 Binder 事务 / 内存）。 */
    public static final int MAX_ROWS = 2000;
    /** 默认返回行数。 */
    public static final int DEFAULT_LIMIT = 200;
    /** 单个 BLOB 内联为 base64 的字节上限，超出只回传长度。 */
    private static final int MAX_BLOB_BYTES = 4096;

    private AciProviderBridge() {
        // 工具类，禁止实例化
    }

    /** 生成能力声明，受控端在 onCreateCapabilities 里直接 add 即可。 */
    public static Capability capability() {
        return Capability.create(
                CAP_ID,
                "ContentProvider 访问代理：由受控端在自己进程内代为读写 ContentProvider——" +
                    "query（查询）/ insert / update / delete / call。" +
                    "适用于跨应用取数（如读取通讯录、短信、日历、媒体库或第三方 App 的开放 Provider）" +
                    "与回写数据。Provider 自身权限由系统强制；建议先用 op=query + limit=1 试探可读性与列结构。"
            )
            .addParam(P_URI, "string", true, "content:// 开头的 URI，如 content://sms/inbox")
            .addParam(P_OP, "string", false,
                "操作：query(默认) / insert / update / delete / call（Provider 自定义方法）")
            .addParam(P_PROJECTION, "string", false, "查询列，逗号分隔，如 \"_id,address,body\"；留空返回全部列")
            .addParam(P_SELECTION, "string", false, "SQL WHERE 子句，可含 ? 占位符，如 \"address=?\"")
            .addParam(P_SELECTION_ARGS, "string", false,
                "WHERE 占位符参数，JSON 数组，如 [\"10086\"]；也接受逗号分隔")
            .addParam(P_SORT_ORDER, "string", false, "ORDER BY 子句，如 \"date DESC\"")
            .addParam(P_VALUES, "string", false,
                "insert/update 的字段值 JSON 对象，如 {\"body\":\"hi\",\"read\":1}；" +
                    "按值类型自动映射为 String/int/long/double/boolean/null")
            .addParam(P_METHOD, "string", false, "op=call 时的 Provider 方法名")
            .addParam(P_ARG, "string", false, "op=call 时的字符串参数")
            .addParam(P_EXTRAS, "string", false, "op=call 时附加的 Bundle 参数（JSON 对象）")
            .addParam(P_LIMIT, "string", false,
                "query 返回行数上限，默认 " + DEFAULT_LIMIT + "，硬上限 " + MAX_ROWS)
            .addParam(P_DRY_RUN, "string", false, "设为 \"true\" 只校验参数、不真正访问 Provider")
            .addResult(R_OP, "string", "实际执行的操作（已归一化）")
            .addResult(R_COUNT, "int", "query=返回行数；insert/update/delete=受影响行数")
            .addResult(R_COLUMNS, "string", "列名 JSON 数组（仅 query）")
            .addResult(R_ROWS, "string", "结果行 JSON 数组（仅 query，受 limit 限制）")
            .addResult(R_URI, "string", "insert 返回的新记录 URI；其余操作回显请求 URI")
            .addResult(R_TRUNCATED, "boolean", "结果是否被 limit 截断")
            .addResult(R_CALL_RESULT, "string", "op=call 时 Provider 返回 Bundle 的 JSON 形式")
            .addResult(R_DETAIL, "string", "结果说明 / 失败原因")
            .addFlag(Capability.FLAG_DANGEROUS)
            .setUserConfirm(true);
    }

    /** 处理一次 provider 调用。 */
    public static AidlAciResponse handle(Context ctx, Bundle params) {
        if (ctx == null) return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "context is null");
        if (params == null) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "params is null");

        String uriStr = str(params, P_URI);
        if (TextUtils.isEmpty(uriStr)) return badRequest("缺少 uri 参数（content://...）");

        final Uri uri;
        try {
            uri = Uri.parse(uriStr.trim());
        } catch (Throwable e) {
            return badRequest("uri 不是合法 URI: " + uriStr);
        }
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            return badRequest("uri 必须以 content:// 开头，实际: " + uriStr);
        }

        String op = str(params, P_OP);
        String normOp = TextUtils.isEmpty(op) ? "query" : op.trim().toLowerCase();
        if (!"query".equals(normOp) && !"insert".equals(normOp) && !"update".equals(normOp)
            && !"delete".equals(normOp) && !"call".equals(normOp)) {
            return badRequest("未知 op: " + op + "（可选 query / insert / update / delete / call）");
        }

        boolean dry = isTrue(str(params, P_DRY_RUN));
        if (dry) {
            Bundle d = new Bundle();
            d.putString(R_OP, normOp);
            d.putString(R_URI, uri.toString());
            d.putInt(R_COUNT, 0);
            d.putBoolean(R_TRUNCATED, false);
            d.putString(R_DETAIL, "dry_run 未实际访问 Provider；参数校验通过");
            return AidlAciResponse.success(d);
        }

        final ContentResolver cr = ctx.getContentResolver();
        try {
            if ("query".equals(normOp)) {
                return doQuery(cr, uri, params);
            } else if ("insert".equals(normOp)) {
                ContentValues cv = parseValues(str(params, P_VALUES), true);
                Uri inserted = cr.insert(uri, cv);
                Bundle out = new Bundle();
                out.putString(R_OP, "insert");
                out.putInt(R_COUNT, inserted != null ? 1 : 0);
                out.putString(R_URI, inserted != null ? inserted.toString() : "");
                out.putBoolean(R_TRUNCATED, false);
                out.putString(R_DETAIL, inserted != null
                    ? "已插入，新记录: " + inserted
                    : "insert 返回 null（Provider 未支持该 URI 的插入）");
                return AidlAciResponse.success(out);
            } else if ("update".equals(normOp)) {
                ContentValues cv = parseValues(str(params, P_VALUES), true);
                int n = cr.update(uri, cv, str(params, P_SELECTION),
                    toStringArray(str(params, P_SELECTION_ARGS)));
                Bundle out = new Bundle();
                out.putString(R_OP, "update");
                out.putInt(R_COUNT, n);
                out.putString(R_URI, uri.toString());
                out.putBoolean(R_TRUNCATED, false);
                out.putString(R_DETAIL, "已更新 " + n + " 行");
                return AidlAciResponse.success(out);
            } else if ("delete".equals(normOp)) {
                int n = cr.delete(uri, str(params, P_SELECTION),
                    toStringArray(str(params, P_SELECTION_ARGS)));
                Bundle out = new Bundle();
                out.putString(R_OP, "delete");
                out.putInt(R_COUNT, n);
                out.putString(R_URI, uri.toString());
                out.putBoolean(R_TRUNCATED, false);
                out.putString(R_DETAIL, "已删除 " + n + " 行");
                return AidlAciResponse.success(out);
            } else {
                String method = str(params, P_METHOD);
                if (TextUtils.isEmpty(method)) return badRequest("op=call 时必须提供 method 参数");
                Bundle extras = toBundle(str(params, P_EXTRAS));
                Bundle res = cr.call(uri, method, str(params, P_ARG), extras);
                Bundle out = new Bundle();
                out.putString(R_OP, "call");
                out.putInt(R_COUNT, res == null ? 0 : res.size());
                out.putString(R_URI, uri.toString());
                out.putBoolean(R_TRUNCATED, false);
                out.putString(R_CALL_RESULT, bundleToJson(res));
                out.putString(R_DETAIL, "已调用 Provider 方法 " + method);
                return AidlAciResponse.success(out);
            }
        } catch (SecurityException e) {
            // Provider 未授权 / 未导出：这是权限问题，不是内部错误，必须如实告知调用方
            return AidlAciResponse.error(AidlAciError.PERMISSION_DENIED,
                "访问 Provider 被拒绝（未授权或未导出）: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest("Provider 拒绝了该请求（参数非法或 URI 不受支持）: " + e.getMessage());
        } catch (Throwable e) {
            Log.e(TAG, "provider 操作失败", e);
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR,
                "Provider 操作失败: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    // ───────────────────────── query ─────────────────────────

    private static AidlAciResponse doQuery(ContentResolver cr, Uri uri, Bundle params) {
        int limit = DEFAULT_LIMIT;
        String limitRaw = str(params, P_LIMIT);
        if (!TextUtils.isEmpty(limitRaw)) {
            try {
                limit = Integer.parseInt(limitRaw.trim());
            } catch (Throwable e) {
                return badRequest("limit 不是合法整数: " + limitRaw);
            }
        }
        limit = Math.max(1, Math.min(limit, MAX_ROWS));

        String[] projection = splitColumns(str(params, P_PROJECTION));
        String selection = str(params, P_SELECTION);
        String[] selArgs = toStringArray(str(params, P_SELECTION_ARGS));
        String sort = str(params, P_SORT_ORDER);

        Cursor c = null;
        try {
            c = cr.query(uri, projection, selection, selArgs, sort);
            if (c == null) {
                // Provider 允许返回 null Cursor，按「无结果」处理而非报错
                Bundle out = new Bundle();
                out.putString(R_OP, "query");
                out.putInt(R_COUNT, 0);
                out.putString(R_COLUMNS, "[]");
                out.putString(R_ROWS, "[]");
                out.putString(R_URI, uri.toString());
                out.putBoolean(R_TRUNCATED, false);
                out.putString(R_DETAIL, "Provider 返回空 Cursor（无数据或不支持该 URI）");
                return AidlAciResponse.success(out);
            }

            String[] cols = c.getColumnNames();
            JSONArray rows = new JSONArray();
            boolean truncated = false;
            while (c.moveToNext()) {
                if (rows.length() >= limit) {
                    truncated = true;   // 还能再 moveToNext 成功 ⇒ 后面还有数据
                    break;
                }
                JSONObject row = new JSONObject();
                for (String col : cols) {
                    int idx = c.getColumnIndex(col);
                    // org.json 的 put(String, Object) 声明受检 JSONException，
                    // 对普通 Object 值实际不会抛；这里兜底，不让受检异常外泄到调用方。
                    try {
                        row.put(col, cursorValue(c, idx));
                    } catch (Throwable ignored) {
                    }
                }
                rows.put(row);
            }

            JSONArray colArr = new JSONArray();
            for (String col : cols) colArr.put(col);

            Bundle out = new Bundle();
            out.putString(R_OP, "query");
            out.putInt(R_COUNT, rows.length());
            out.putString(R_COLUMNS, colArr.toString());
            out.putString(R_ROWS, rows.toString());
            out.putString(R_URI, uri.toString());
            out.putBoolean(R_TRUNCATED, truncated);
            out.putString(R_DETAIL, truncated
                ? "返回前 " + rows.length() + " 行（已达 limit=" + limit + "，仍有更多数据）"
                : "返回 " + rows.length() + " 行");
            return AidlAciResponse.success(out);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 按 Cursor 的字段类型取值；BLOB 内联为 base64（超长仅回传长度）。 */
    private static Object cursorValue(Cursor c, int idx) {
        if (idx < 0) return JSONObject.NULL;
        try {
            if (c.isNull(idx)) return JSONObject.NULL;
            switch (c.getType(idx)) {
                case Cursor.FIELD_TYPE_INTEGER:
                    return c.getLong(idx);
                case Cursor.FIELD_TYPE_FLOAT:
                    return c.getDouble(idx);
                case Cursor.FIELD_TYPE_STRING:
                    return c.getString(idx);
                case Cursor.FIELD_TYPE_BLOB: {
                    byte[] b = c.getBlob(idx);
                    if (b == null) return JSONObject.NULL;
                    if (b.length <= MAX_BLOB_BYTES) {
                        return "base64:" + Base64.encodeToString(b, Base64.NO_WRAP);
                    }
                    return "[blob " + b.length + " 字节，超过 " + MAX_BLOB_BYTES + " 未内联]";
                }
                default:
                    return c.getString(idx);
            }
        } catch (Throwable e) {
            return "[读取失败: " + e.getMessage() + "]";
        }
    }

    // ───────────────────────── 参数解析 ─────────────────────────

    /** ContentValues：JSON 对象 → 按值类型映射；require 时不允许空（insert 需要）。 */
    private static ContentValues parseValues(String json, boolean require) {
        ContentValues cv = new ContentValues();
        if (TextUtils.isEmpty(json)) {
            if (require) throw new IllegalArgumentException("缺少 values 参数（JSON 对象）");
            return cv;
        }
        try {
            JSONObject jo = new JSONObject(json);
            Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = jo.opt(k);
                if (v == null || JSONObject.NULL.equals(v)) cv.putNull(k);
                else if (v instanceof Boolean) cv.put(k, (Boolean) v);
                else if (v instanceof Integer) cv.put(k, (Integer) v);
                else if (v instanceof Long) cv.put(k, (Long) v);
                else if (v instanceof Float) cv.put(k, (Float) v);
                else if (v instanceof Double) cv.put(k, (Double) v);
                else cv.put(k, String.valueOf(v));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalArgumentException("values 不是合法 JSON 对象: " + e.getMessage());
        }
        return cv;
    }

    /** 逗号分隔列名 → String[]；空则返回 null（表示查询全部列）。 */
    private static String[] splitColumns(String raw) {
        if (TextUtils.isEmpty(raw)) return null;
        String[] parts = raw.split(",");
        String[] out = new String[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = parts[i].trim();
        return out;
    }

    /** JSON 数组 → String[]；非法 JSON 时按逗号分隔容错。 */
    private static String[] toStringArray(String json) {
        if (TextUtils.isEmpty(json)) return null;
        try {
            JSONArray ja = new JSONArray(json);
            String[] r = new String[ja.length()];
            for (int i = 0; i < ja.length(); i++) {
                Object o = ja.opt(i);
                r[i] = (o == null || JSONObject.NULL.equals(o)) ? null : String.valueOf(o);
            }
            return r;
        } catch (Throwable e) {
            return splitColumns(json);   // 容错：当作逗号分隔
        }
    }

    /** JSON 对象 → Bundle（op=call 的 extras）。 */
    private static Bundle toBundle(String json) {
        Bundle b = new Bundle();
        if (TextUtils.isEmpty(json)) return b;
        try {
            JSONObject jo = new JSONObject(json);
            Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = jo.opt(k);
                if (v == null || JSONObject.NULL.equals(v)) b.putString(k, null);
                else if (v instanceof Boolean) b.putBoolean(k, (Boolean) v);
                else if (v instanceof Integer) b.putInt(k, (Integer) v);
                else if (v instanceof Long) b.putLong(k, (Long) v);
                else if (v instanceof Float) b.putFloat(k, (Float) v);
                else if (v instanceof Double) b.putDouble(k, (Double) v);
                else b.putString(k, String.valueOf(v));
            }
        } catch (Throwable e) {
            Log.w(TAG, "extras 解析失败，按空 Bundle 处理: " + e.getMessage());
        }
        return b;
    }

    /** Bundle → JSON 字符串（op=call 的返回值）。 */
    private static String bundleToJson(Bundle b) {
        if (b == null || b.isEmpty()) return "{}";
        JSONObject o = new JSONObject();
        try {
            for (String k : b.keySet()) {
                Object v = b.get(k);
                if (v == null) o.put(k, JSONObject.NULL);
                else if (v instanceof Bundle) o.put(k, new JSONObject(bundleToJson((Bundle) v)));
                else if (v instanceof byte[]) o.put(k, "base64:" + Base64.encodeToString((byte[]) v, Base64.NO_WRAP));
                else if (v instanceof Number || v instanceof Boolean) o.put(k, v);
                else o.put(k, String.valueOf(v));
            }
        } catch (Throwable e) {
            Log.w(TAG, "Bundle 转 JSON 失败: " + e.getMessage());
        }
        return o.toString();
    }

    private static String str(Bundle b, String key) {
        Object o = b.get(key);
        return o == null ? null : String.valueOf(o);
    }

    private static boolean isTrue(String v) {
        return v != null && ("true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()));
    }

    private static AidlAciResponse badRequest(String msg) {
        return AidlAciResponse.error(AidlAciError.BAD_REQUEST, msg);
    }
}

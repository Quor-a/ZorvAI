package ai.aci.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Capability 元数据描述
 *
 * 每个第三方 App 的 ACI Service 在初始化时，
 * 需要向 AI 中枢注册自己支持的能力列表。
 *
 * 示例 JSON：
 * {
 *   "id": "send_message",
 *   "version": "1.0",
 *   "description": "发送文本消息",
 *   "params": [
 *     { "name": "contact", "type": "string", "required": true, "description": "联系人名称" },
 *     { "name": "content", "type": "string", "required": true, "description": "消息内容" }
 *   ],
 *   "result": [
 *     { "name": "messageId", "type": "string", "description": "发送成功的消息ID" }
 *   ],
 *   "flags": ["BACKGROUND_EXECUTABLE", "NO_UI_REQUIRED"],
 *   "requirePermission": "aci.permission.SEND_MESSAGE",
 *   "requireUserConfirm": false
 * }
 */
public class Capability {

    public static final String FLAG_BACKGROUND = "BACKGROUND_EXECUTABLE";
    public static final String FLAG_NO_UI       = "NO_UI_REQUIRED";
    public static final String FLAG_DANGEROUS   = "DANGEROUS_ACTION";

    private String id;
    private String version;
    private String description;
    private List<ParamSchema> params = new ArrayList<>();
    private List<ParamSchema> result = new ArrayList<>();
    private List<String> flags = new ArrayList<>();
    private String requirePermission;
    private boolean requireUserConfirm;

    // ──────────────────────────────
    // 构建器
    // ──────────────────────────────
    public static Capability create(String id, String description) {
        Capability c = new Capability();
        c.id = id;
        c.description = description;
        c.version = "1.0";
        return c;
    }

    public Capability addParam(String name, String type, boolean required, String desc) {
        params.add(new ParamSchema(name, type, required, desc));
        return this;
    }

    public Capability addResult(String name, String type, String desc) {
        result.add(new ParamSchema(name, type, false, desc));
        return this;
    }

    public Capability addFlag(String flag) {
        flags.add(flag);
        return this;
    }

    public Capability setPermission(String perm) {
        this.requirePermission = perm;
        return this;
    }

    public Capability setUserConfirm(boolean need) {
        this.requireUserConfirm = need;
        return this;
    }

    // ──────────────────────────────
    // JSON 序列化（给 AI 中枢解析）
    // ──────────────────────────────
    public JSONObject toJSON() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("version", version);
        obj.put("description", description);

        JSONArray pArr = new JSONArray();
        for (ParamSchema p : params) pArr.put(p.toJSON());
        obj.put("params", pArr);

        JSONArray rArr = new JSONArray();
        for (ParamSchema p : result) rArr.put(p.toJSON());
        obj.put("result", rArr);

        JSONArray fArr = new JSONArray();
        for (String f : flags) fArr.put(f);
        obj.put("flags", fArr);

        if (requirePermission != null) obj.put("requirePermission", requirePermission);
        obj.put("requireUserConfirm", requireUserConfirm);

        return obj;
    }

    public static List<Capability> fromJSONArray(JSONArray arr) throws JSONException {
        List<Capability> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Capability c = create(o.getString("id"), o.optString("description"));
            c.version = o.optString("version", "1.0");
            c.requirePermission = o.optString("requirePermission", null);
            c.requireUserConfirm = o.optBoolean("requireUserConfirm", false);

            JSONArray pa = o.optJSONArray("params");
            if (pa != null) for (int j = 0; j < pa.length(); j++) c.params.add(ParamSchema.fromJSON(pa.getJSONObject(j)));

            JSONArray ra = o.optJSONArray("result");
            if (ra != null) for (int j = 0; j < ra.length(); j++) c.result.add(ParamSchema.fromJSON(ra.getJSONObject(j)));

            JSONArray fa = o.optJSONArray("flags");
            if (fa != null) for (int j = 0; j < fa.length(); j++) c.flags.add(fa.getString(j));

            list.add(c);
        }
        return list;
    }

    // ──────────────────────────────
    // Getter
    // ──────────────────────────────
    public String getId() { return id; }
    public String getDescription() { return description; }
    public List<ParamSchema> getParams() { return params; }
    public List<String> getFlags() { return flags; }
    public boolean isRequireUserConfirm() { return requireUserConfirm; }
    public String getRequirePermission() { return requirePermission; }
    public boolean hasFlag(String flag) { return flags.contains(flag); }

    // ──────────────────────────────
    // 内部类：参数 / 返回值描述
    // ──────────────────────────────
    public static class ParamSchema {
        public String name;
        public String type;       // string | int | boolean | double | byte[]
        public boolean required;
        public String description;

        public ParamSchema(String name, String type, boolean required, String description) {
            this.name = name; this.type = type; this.required = required; this.description = description;
        }

        public JSONObject toJSON() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("type", type);
            o.put("required", required);
            o.put("description", description);
            return o;
        }

        public static ParamSchema fromJSON(JSONObject o) throws JSONException {
            return new ParamSchema(
                    o.getString("name"),
                    o.optString("type", "string"),
                    o.optBoolean("required", false),
                    o.optString("description", "")
            );
        }
    }
}

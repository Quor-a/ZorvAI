package ai.aidl.aci.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 能力声明（Capability）。受控方在 onCreateCapabilities 中以参数式 API 声明，
 * 经 toJSON 序列化为字符串数组供 AI 中枢发现。
 * 字段/方法签名还原自原始 aci-core 编译产物（Capability.class / Capability$ParamSchema.class）。
 */
public class Capability {
    public static final String FLAG_BACKGROUND = "background";
    public static final String FLAG_NO_UI = "no_ui";
    public static final String FLAG_DANGEROUS = "dangerous";

    private String id;
    private String version;
    private String description;
    private List<ParamSchema> params = new ArrayList<>();
    private List<ParamSchema> result = new ArrayList<>();
    private List<String> flags = new ArrayList<>();
    private String requirePermission;
    private boolean requireUserConfirm;

    public Capability() {
    }

    public static Capability create(String id, String description) {
        Capability c = new Capability();
        c.id = id;
        c.description = description;
        return c;
    }

    public Capability addParam(String name, String type, boolean required, String description) {
        params.add(new ParamSchema(name, type, required, description));
        return this;
    }

    public Capability addResult(String name, String type, String description) {
        result.add(new ParamSchema(name, type, false, description));
        return this;
    }

    public Capability addFlag(String flag) {
        flags.add(flag);
        return this;
    }

    public Capability setPermission(String permission) {
        this.requirePermission = permission;
        return this;
    }

    public Capability setUserConfirm(boolean confirm) {
        this.requireUserConfirm = confirm;
        return this;
    }

    public JSONObject toJSON() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("version", version);
        o.put("description", description);
        JSONArray p = new JSONArray();
        for (ParamSchema s : params) p.put(s.toJSON());
        o.put("params", p);
        JSONArray r = new JSONArray();
        for (ParamSchema s : result) r.put(s.toJSON());
        o.put("result", r);
        JSONArray f = new JSONArray();
        for (String fl : flags) f.put(fl);
        o.put("flags", f);
        o.put("requirePermission", requirePermission);
        o.put("requireUserConfirm", requireUserConfirm);
        return o;
    }

    public static List<Capability> fromJSONArray(JSONArray arr) throws JSONException {
        List<Capability> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Capability c = new Capability();
            c.id = o.optString("id");
            c.version = o.optString("version");
            c.description = o.optString("description");
            JSONArray p = o.optJSONArray("params");
            if (p != null) for (int j = 0; j < p.length(); j++) c.params.add(ParamSchema.fromJSON(p.getJSONObject(j)));
            JSONArray r = o.optJSONArray("result");
            if (r != null) for (int j = 0; j < r.length(); j++) c.result.add(ParamSchema.fromJSON(r.getJSONObject(j)));
            JSONArray f = o.optJSONArray("flags");
            if (f != null) for (int j = 0; j < f.length(); j++) c.flags.add(f.getString(j));
            c.requirePermission = o.optString("requirePermission");
            c.requireUserConfirm = o.optBoolean("requireUserConfirm");
            list.add(c);
        }
        return list;
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public List<ParamSchema> getParams() {
        return params;
    }

    public List<String> getFlags() {
        return flags;
    }

    public boolean isRequireUserConfirm() {
        return requireUserConfirm;
    }

    public String getRequirePermission() {
        return requirePermission;
    }

    public boolean hasFlag(String flag) {
        return flags.contains(flag);
    }

    public static class ParamSchema {
        public String name;
        public String type;
        public boolean required;
        public String description;

        public ParamSchema(String name, String type, boolean required, String description) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.description = description;
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
                    o.optString("name"),
                    o.optString("type"),
                    o.optBoolean("required"),
                    o.optString("description"));
        }
    }
}

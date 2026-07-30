package ai.aci.core;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * ACI 统一请求对象
 *
 * 第三方 App 收到的调用请求，包含：
 * - capability: 要调用的功能名（如 "send_message"）
 * - version:    协议版本
 * - params:     功能参数（Bundle，支持所有可序列化类型）
 * - callId:     本次调用的唯一 ID（用于日志追踪）
 * - callerPkg:  调用方包名（由 SDK 自动填充，防伪造）
 */
public class ACIRequest implements Parcelable {

    private String capability;
    private String version;
    private Bundle params;
    private String callId;
    private String callerPkg;

    public ACIRequest() {
        this.version = "1.0";
        this.params = new Bundle();
        this.callId = java.util.UUID.randomUUID().toString();
    }

    public ACIRequest(String capability) {
        this();
        this.capability = capability;
    }

    public ACIRequest(String capability, Bundle params) {
        this(capability);
        this.params = params != null ? params : new Bundle();
    }

    // ──────────────────────────────
    // Builder 模式（推荐用法）
    // ──────────────────────────────
    public static class Builder {
        private final ACIRequest req;

        public Builder(String capability) {
            req = new ACIRequest(capability);
        }

        public Builder param(String key, String value) {
            req.params.putString(key, value);
            return this;
        }

        public Builder param(String key, int value) {
            req.params.putInt(key, value);
            return this;
        }

        public Builder param(String key, boolean value) {
            req.params.putBoolean(key, value);
            return this;
        }

        public Builder param(String key, double value) {
            req.params.putDouble(key, value);
            return this;
        }

        public Builder param(String key, byte[] value) {
            req.params.putByteArray(key, value);
            return this;
        }

        public Builder version(String version) {
            req.version = version;
            return this;
        }

        public ACIRequest build() {
            return req;
        }
    }

    // ──────────────────────────────
    // Parcelable 实现
    // ──────────────────────────────
    protected ACIRequest(Parcel in) {
        capability = in.readString();
        version = in.readString();
        params = in.readBundle(getClass().getClassLoader());
        callId = in.readString();
        callerPkg = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(capability);
        dest.writeString(version);
        dest.writeBundle(params);
        dest.writeString(callId);
        dest.writeString(callerPkg);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ACIRequest> CREATOR = new Creator<ACIRequest>() {
        @Override
        public ACIRequest createFromParcel(Parcel in) {
            return new ACIRequest(in);
        }

        @Override
        public ACIRequest[] newArray(int size) {
            return new ACIRequest[size];
        }
    };

    // ──────────────────────────────
    // Getter / Setter
    // ──────────────────────────────
    public String getCapability() { return capability; }
    public void setCapability(String capability) { this.capability = capability; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public Bundle getParams() { return params; }
    public void setParams(Bundle params) { this.params = params; }

    public String getCallId() { return callId; }
    public void setCallId(String callId) { this.callId = callId; }

    public String getCallerPkg() { return callerPkg; }
    public void setCallerPkg(String callerPkg) { this.callerPkg = callerPkg; }

    @Override
    public String toString() {
        return "ACIRequest{" +
                "capability='" + capability + '\'' +
                ", version='" + version + '\'' +
                ", callId='" + callId + '\'' +
                ", callerPkg='" + callerPkg + '\'' +
                ", params=" + (params != null ? params.size() + " entries" : "null") +
                '}';
    }
}

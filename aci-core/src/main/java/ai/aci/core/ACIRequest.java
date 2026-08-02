package ai.aci.core;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * 统一请求对象 —— 所有跨进程 ACI 调用都通过它传递。
 * Parcelable 字段写入顺序（capability → version → params → callId → callerPkg）
 * 与原始 aci-core 编译产物保持一致，确保与受控端（浏览器等）Binder 解包兼容。
 */
public class ACIRequest implements Parcelable {
    private String capability;
    private String version;
    private Bundle params;
    private String callId;
    private String callerPkg;

    public static final Parcelable.Creator<ACIRequest> CREATOR = new Parcelable.Creator<ACIRequest>() {
        @Override
        public ACIRequest createFromParcel(Parcel in) {
            return new ACIRequest(in);
        }

        @Override
        public ACIRequest[] newArray(int size) {
            return new ACIRequest[size];
        }
    };

    public ACIRequest() {
    }

    public ACIRequest(String capability) {
        this.capability = capability;
    }

    public ACIRequest(String capability, Bundle params) {
        this.capability = capability;
        this.params = params;
    }

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

    public String getCapability() {
        return capability;
    }

    public void setCapability(String capability) {
        this.capability = capability;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Bundle getParams() {
        return params;
    }

    public void setParams(Bundle params) {
        this.params = params;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public String getCallerPkg() {
        return callerPkg;
    }

    public void setCallerPkg(String callerPkg) {
        this.callerPkg = callerPkg;
    }

    @Override
    public String toString() {
        return "ACIRequest{capability='" + capability + "', version='" + version
                + "', callId='" + callId + "', callerPkg='" + callerPkg + "'}";
    }
}

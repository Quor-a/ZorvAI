package ai.aci.core;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * ACI 统一响应对象
 *
 * 第三方 App 处理完请求后返回的结果，包含：
 * - success:    是否成功
 * - result:     返回数据（Bundle）
 * - errorCode:  错误码（0 = 无错误）
 * - errorMessage: 错误描述
 * - callId:      对应请求的 callId（用于链路追踪）
 */
public class ACIResponse implements Parcelable {

    private boolean success;
    private Bundle result;
    private int errorCode;
    private String errorMessage;
    private String callId;

    // ──────────────────────────────
    // 工厂方法（推荐用法）
    // ──────────────────────────────
    public static ACIResponse success() {
        ACIResponse r = new ACIResponse();
        r.success = true;
        r.errorCode = 0;
        r.result = new Bundle();
        return r;
    }

    public static ACIResponse success(Bundle data) {
        ACIResponse r = success();
        r.result = data != null ? data : new Bundle();
        return r;
    }

    public static ACIResponse error(int code, String message) {
        ACIResponse r = new ACIResponse();
        r.success = false;
        r.errorCode = code;
        r.errorMessage = message;
        r.result = new Bundle();
        return r;
    }

    public ACIResponse() {
        this.result = new Bundle();
    }

    // ──────────────────────────────
    // 链式设置
    // ──────────────────────────────
    public ACIResponse putResult(String key, String value) {
        this.result.putString(key, value);
        return this;
    }

    public ACIResponse putResult(String key, int value) {
        this.result.putInt(key, value);
        return this;
    }

    public ACIResponse putResult(String key, boolean value) {
        this.result.putBoolean(key, value);
        return this;
    }

    public ACIResponse putResult(String key, double value) {
        this.result.putDouble(key, value);
        return this;
    }

    public ACIResponse putResult(String key, byte[] value) {
        this.result.putByteArray(key, value);
        return this;
    }

    // ──────────────────────────────
    // Parcelable 实现
    // ──────────────────────────────
    protected ACIResponse(Parcel in) {
        success = in.readByte() != 0;
        result = in.readBundle(getClass().getClassLoader());
        errorCode = in.readInt();
        errorMessage = in.readString();
        callId = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (success ? 1 : 0));
        dest.writeBundle(result);
        dest.writeInt(errorCode);
        dest.writeString(errorMessage);
        dest.writeString(callId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ACIResponse> CREATOR = new Creator<ACIResponse>() {
        @Override
        public ACIResponse createFromParcel(Parcel in) {
            return new ACIResponse(in);
        }

        @Override
        public ACIResponse[] newArray(int size) {
            return new ACIResponse[size];
        }
    };

    // ──────────────────────────────
    // Getter / Setter
    // ──────────────────────────────
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public Bundle getResult() { return result; }
    public void setResult(Bundle result) { this.result = result; }

    public int getErrorCode() { return errorCode; }
    public void setErrorCode(int errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getCallId() { return callId; }
    public void setCallId(String callId) { this.callId = callId; }

    @Override
    public String toString() {
        return "ACIResponse{" +
                "success=" + success +
                ", errorCode=" + errorCode +
                ", errorMessage='" + errorMessage + '\'' +
                ", callId='" + callId + '\'' +
                ", resultKeys=" + (result != null ? result.keySet() : "null") +
                '}';
    }
}

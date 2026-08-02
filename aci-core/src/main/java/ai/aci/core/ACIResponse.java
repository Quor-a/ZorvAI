package ai.aci.core;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * 统一响应对象 —— 所有跨进程 ACI 调用的返回值。
 * Parcelable 字段写入顺序（success → result → errorCode → errorMessage → callId）
 * 与原始 aci-core 编译产物保持一致。
 */
public class ACIResponse implements Parcelable {
    private boolean success;
    private Bundle result;
    private int errorCode;
    private String errorMessage;
    private String callId;

    public static final Parcelable.Creator<ACIResponse> CREATOR = new Parcelable.Creator<ACIResponse>() {
        @Override
        public ACIResponse createFromParcel(Parcel in) {
            return new ACIResponse(in);
        }

        @Override
        public ACIResponse[] newArray(int size) {
            return new ACIResponse[size];
        }
    };

    public ACIResponse() {
    }

    protected ACIResponse(Parcel in) {
        success = in.readInt() != 0;
        result = in.readBundle(getClass().getClassLoader());
        errorCode = in.readInt();
        errorMessage = in.readString();
        callId = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(success ? 1 : 0);
        dest.writeBundle(result);
        dest.writeInt(errorCode);
        dest.writeString(errorMessage);
        dest.writeString(callId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static ACIResponse success() {
        ACIResponse r = new ACIResponse();
        r.success = true;
        r.result = new Bundle();
        return r;
    }

    public static ACIResponse success(Bundle result) {
        ACIResponse r = new ACIResponse();
        r.success = true;
        r.result = result != null ? result : new Bundle();
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

    public ACIResponse putResult(String key, String value) {
        if (result == null) result = new Bundle();
        result.putString(key, value);
        return this;
    }

    public ACIResponse putResult(String key, int value) {
        if (result == null) result = new Bundle();
        result.putInt(key, value);
        return this;
    }

    public ACIResponse putResult(String key, boolean value) {
        if (result == null) result = new Bundle();
        result.putBoolean(key, value);
        return this;
    }

    public ACIResponse putResult(String key, double value) {
        if (result == null) result = new Bundle();
        result.putDouble(key, value);
        return this;
    }

    public ACIResponse putResult(String key, byte[] value) {
        if (result == null) result = new Bundle();
        result.putByteArray(key, value);
        return this;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Bundle getResult() {
        return result;
    }

    public void setResult(Bundle result) {
        this.result = result;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(int errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    @Override
    public String toString() {
        return "ACIResponse{success=" + success + ", errorCode=" + errorCode
                + ", errorMessage='" + errorMessage + "', callId='" + callId + "'}";
    }
}

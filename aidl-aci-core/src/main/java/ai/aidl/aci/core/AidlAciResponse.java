package ai.aidl.aci.core;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;

/**
 * 统一响应对象 —— 所有跨进程 ACI 调用的返回值。
 * Parcelable 字段写入顺序（success → result → errorCode → errorMessage → callId）
 * 与原始 aci-core 编译产物保持一致。
 */
public class AidlAciResponse implements Parcelable {
    private boolean success;
    private Bundle result;
    private int errorCode;
    private String errorMessage;
    private String callId;

    public static final Parcelable.Creator<AidlAciResponse> CREATOR = new Parcelable.Creator<AidlAciResponse>() {
        @Override
        public AidlAciResponse createFromParcel(Parcel in) {
            return new AidlAciResponse(in);
        }

        @Override
        public AidlAciResponse[] newArray(int size) {
            return new AidlAciResponse[size];
        }
    };

    public AidlAciResponse() {
    }

    protected AidlAciResponse(Parcel in) {
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

    public static AidlAciResponse success() {
        AidlAciResponse r = new AidlAciResponse();
        r.success = true;
        r.result = new Bundle();
        return r;
    }

    public static AidlAciResponse success(Bundle result) {
        AidlAciResponse r = new AidlAciResponse();
        r.success = true;
        r.result = result != null ? result : new Bundle();
        return r;
    }

    public static AidlAciResponse error(int code, String message) {
        AidlAciResponse r = new AidlAciResponse();
        r.success = false;
        r.errorCode = code;
        r.errorMessage = message;
        r.result = new Bundle();
        return r;
    }

    public AidlAciResponse putResult(String key, String value) {
        if (result == null) result = new Bundle();
        result.putString(key, value);
        return this;
    }

    public AidlAciResponse putResult(String key, int value) {
        if (result == null) result = new Bundle();
        result.putInt(key, value);
        return this;
    }

    public AidlAciResponse putResult(String key, boolean value) {
        if (result == null) result = new Bundle();
        result.putBoolean(key, value);
        return this;
    }

    public AidlAciResponse putResult(String key, double value) {
        if (result == null) result = new Bundle();
        result.putDouble(key, value);
        return this;
    }

    public AidlAciResponse putResult(String key, byte[] value) {
        if (result == null) result = new Bundle();
        result.putByteArray(key, value);
        return this;
    }

    public AidlAciResponse putResult(String key, ArrayList<String> value) {
        if (result == null) result = new Bundle();
        result.putStringArrayList(key, value);
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
        return "AidlAciResponse{success=" + success + ", errorCode=" + errorCode
                + ", errorMessage='" + errorMessage + "', callId='" + callId + "'}";
    }
}

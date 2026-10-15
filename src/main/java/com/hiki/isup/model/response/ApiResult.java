package com.hiki.isup.model.response;

/**
 * 外部接口的固定回执。只包含业务码和一句话，不带设备侧细节。
 */
public class ApiResult {

    private int code;
    private String msg;

    public ApiResult() {
    }

    public ApiResult(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    /** 操作已成功完成；失败由统一异常处理返回。 */
    public static ApiResult ok() {
        return new ApiResult(200, "成功");
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }
}

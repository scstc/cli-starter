package com.starter.auth.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应包裹：{"code":200,"msg":"ok","data":...}，data 为 null 时序列化时省略。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResult<T>(int code, String msg, T data) {

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(200, "ok", data);
    }

    public static <T> ApiResult<T> of(int code, String msg, T data) {
        return new ApiResult<>(code, msg, data);
    }
}

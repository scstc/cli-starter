package com.starter.auth.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import cn.dev33.satoken.stp.SaTokenInfo;

/**
 * POST /api/auth/device/token 的 data。
 * status = pending | ok | denied | expired | invalid；仅 ok 时携带 token（CLI 专属新登录会话）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceTokenData(String status, SaTokenInfo token) {

    public static DeviceTokenData pending() {
        return new DeviceTokenData("pending", null);
    }

    public static DeviceTokenData ok(SaTokenInfo token) {
        return new DeviceTokenData("ok", token);
    }

    public static DeviceTokenData of(String status) {
        return new DeviceTokenData(status, null);
    }
}

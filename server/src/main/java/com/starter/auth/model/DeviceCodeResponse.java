package com.starter.auth.model;

import java.time.Duration;
import java.time.Instant;

import com.starter.auth.service.DeviceGrantService.Grant;

/**
 * POST /api/auth/device/code 的 data，字段命名对齐 RFC 8628 第 3.2 节。
 */
public record DeviceCodeResponse(
        String deviceCode,
        String userCode,
        String verificationUri,
        String verificationUriComplete,
        long expiresIn,
        int interval) {

    public static DeviceCodeResponse of(Grant grant, String pageBase, int interval) {
        long expiresIn = Math.max(0, Duration.between(Instant.now(), grant.expiresAt()).getSeconds());
        return new DeviceCodeResponse(grant.deviceCode(), grant.userCode(), pageBase,
                pageBase + "?user_code=" + grant.userCode(), expiresIn, interval);
    }
}

package com.starter.auth.model;

/** POST /api/auth/sms/login 请求体。 */
public record SmsLoginRequest(String phone, String code) {
}

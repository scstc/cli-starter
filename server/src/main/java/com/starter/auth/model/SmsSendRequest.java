package com.starter.auth.model;

/**
 * 短信验证码发送请求体。purpose = login(默认,手机号须已注册) | register(手机号须未注册)。
 */
public record SmsSendRequest(String phone, String purpose) {
}

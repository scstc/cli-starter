package com.starter.auth.model;

/**
 * 自主注册请求体。需图形验证码(见 login) + 目的为 register 的短信验证码;
 * 成功即自动登录,返回与 login 同构的 SaTokenInfo。
 */
public record RegisterRequest(
        String username,
        String password,
        String phone,
        String captchaId,
        String captchaCode,
        String smsCode) {
}

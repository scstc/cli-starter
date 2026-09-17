package com.starter.auth.model;

/**
 * 登录请求。captchaId/captchaCode 在 starter.captcha.required=true（默认）时必填：
 * 先 GET /api/auth/captcha 取图，提交时带上用户输入。
 */
public record LoginRequest(String username, String password, String captchaId, String captchaCode) {
}

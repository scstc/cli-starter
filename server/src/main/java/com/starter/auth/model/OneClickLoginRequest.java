package com.starter.auth.model;

/** POST /api/auth/oneclick/login 请求体。 */
public record OneClickLoginRequest(String token) {
}

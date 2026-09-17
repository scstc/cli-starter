package com.starter.auth.model;

/** POST /api/auth/oauth/{provider}/authorize 的 data(demo:授权票代替跳转授权页)。 */
public record OAuthAuthorizeResponse(String ticket, String nickname) {
}

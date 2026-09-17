package com.starter.auth.model;

/** 第三方登录 authorize 请求体;nickname 缺省时用"微信用户/QQ用户"。 */
public record OAuthAuthorizeRequest(String nickname) {
}

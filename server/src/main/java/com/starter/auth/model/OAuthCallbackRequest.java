package com.starter.auth.model;

/** 第三方登录 callback 请求体:用 authorize 返回的授权票换登录态。 */
public record OAuthCallbackRequest(String ticket) {
}

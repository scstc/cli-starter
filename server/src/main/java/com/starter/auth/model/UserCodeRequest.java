package com.starter.auth.model;

/** 网页确认/拒绝设备码的请求体。 */
public record UserCodeRequest(String userCode) {
}

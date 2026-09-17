package com.starter.auth.model;

/** CLI 轮询设备码授权结果的请求体。 */
public record DeviceCodeRequest(String deviceCode) {
}

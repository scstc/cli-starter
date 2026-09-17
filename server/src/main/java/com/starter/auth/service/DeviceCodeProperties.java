package com.starter.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 设备码登录参数（starter.device-code）。
 *
 * @param webBase             独立前端（授权页/控制台）的部署地址，前后端分离
 * @param ttlSeconds          设备码有效期（秒）
 * @param pollIntervalSeconds CLI 轮询间隔建议值（秒）
 */
@ConfigurationProperties(prefix = "starter.device-code")
public record DeviceCodeProperties(
        @DefaultValue("http://127.0.0.1:8000") String webBase,
        @DefaultValue("900") long ttlSeconds,
        @DefaultValue("3") int pollIntervalSeconds) {

    /** 授权页基础地址（去掉尾部 / 后统一补一个）。 */
    public String pageBase() {
        String base = webBase.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/";
    }
}

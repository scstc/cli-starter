package com.starter.auth.service;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 图形验证码参数（starter.captcha）。
 *
 * @param required  登录接口是否强制校验验证码
 * @param debugEcho 响应中回显答案；仅供本地演示/联调，生产必须关闭
 * @param ttlSeconds 验证码有效期（秒）
 */
@ConfigurationProperties(prefix = "starter.captcha")
public record CaptchaProperties(
        @DefaultValue("true") boolean required,
        @DefaultValue("true") boolean debugEcho,
        @DefaultValue("120") long ttlSeconds) {
}

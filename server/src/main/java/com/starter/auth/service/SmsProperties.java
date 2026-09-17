package com.starter.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 短信验证码参数（starter.sms）。无真实短信供应商：验证码写入服务端日志，
 * debug-echo 开启时随 send 响应回显（仅供本地演示/联调，生产必须关闭并接真实通道）。
 *
 * @param debugEcho            send 响应回显验证码
 * @param codeTtlSeconds       验证码有效期（秒）
 * @param sendCooldownSeconds  同一手机号两次发送的最小间隔（秒）
 */
@ConfigurationProperties(prefix = "starter.sms")
public record SmsProperties(
        @DefaultValue("true") boolean debugEcho,
        @DefaultValue("300") long codeTtlSeconds,
        @DefaultValue("60") long sendCooldownSeconds) {
}

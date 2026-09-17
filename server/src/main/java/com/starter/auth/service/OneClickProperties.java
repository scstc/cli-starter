package com.starter.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 运营商一键登录参数(starter.oneclick)。真实接入需 App 内嵌运营商 SDK,
 * demo 通道用固定的模拟本机号替代整条链路。
 *
 * @param demoPhone      模拟的"本机号码"(建议配置为已注册的演示账号)
 * @param tokenTtlSeconds 换号 token 有效期(秒)
 */
@ConfigurationProperties(prefix = "starter.oneclick")
public record OneClickProperties(
        @DefaultValue("13800000001") String demoPhone,
        @DefaultValue("120") long tokenTtlSeconds) {
}

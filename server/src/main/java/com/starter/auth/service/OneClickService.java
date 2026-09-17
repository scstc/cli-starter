package com.starter.auth.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * 运营商一键登录的<b>demo 通道</b>。
 *
 * <p>真实实现:App 内嵌运营商 SDK(移动/联通/电信统一认证)取本机号码 token,
 * 服务端调运营商网关换手机号。demo 通道用固定的模拟本机号
 * (starter.oneclick.demo-phone,默认 alice 的 13800000001)替代整个链路。
 */
@Service
public class OneClickService {

    private final OneClickProperties props;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();

    public record Preview(String token, String maskedPhone) {
    }

    public OneClickService(OneClickProperties props) {
        this.props = props;
    }

    /** 预览本机号码并签发一次性换号 token。 */
    public Preview issue() {
        String token = "oc_" + UUID.randomUUID();
        tokens.put(token, Instant.now().plusSeconds(props.tokenTtlSeconds()));
        String phone = props.demoPhone();
        return new Preview(token, phone.substring(0, 3) + "****" + phone.substring(7));
    }

    /** token 换本机号码:一次性,无效/过期返回 null。 */
    public String exchange(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Instant exp = tokens.remove(token.trim());
        if (exp == null || Instant.now().isAfter(exp)) {
            return null;
        }
        return props.demoPhone();
    }
}

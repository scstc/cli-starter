package com.starter.auth.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * 第三方登录的<b>模拟授权服务</b>(demo 通道)。
 *
 * <p>真实接入微信/QQ 时:authorize 改为 302 跳厂商授权页,callback 换成调厂商 SDK 换 openid,
 * 本类仅保留 ticket→identity 的映射职责即可,端点契约不变。
 *
 * <p>演示语义:openId 由 provider+昵称确定性派生——同一昵称重复登录映射到同一账号,
 * 便于演示"第三方身份绑定"。授权票一次性,5 分钟有效。
 */
@Service
public class OAuthDemoService {

    public static final Set<String> SUPPORTED = Set.of("wechat", "qq", "weibo");
    private static final long TICKET_TTL_SECONDS = 300;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    /** 模拟的第三方身份。 */
    public record DemoIdentity(String provider, String openId, String nickname) {
    }

    public record Ticket(String value, DemoIdentity identity, Instant expiresAt) {
    }

    /** 签发模拟授权票:openId 由 provider+昵称确定性派生。 */
    public Ticket createTicket(String provider, String nickname) {
        String nick = nickname == null || nickname.isBlank() ? defaultNick(provider) : nickname.trim();
        // String.hashCode 跨 JVM 稳定,演示用确定性派生;真实实现为厂商 openid
        String openId = provider + "_" + Integer.toHexString(
                (provider + ":" + nick.toLowerCase()).hashCode());
        Ticket ticket = new Ticket(randomTicket(), new DemoIdentity(provider, openId, nick),
                Instant.now().plusSeconds(TICKET_TTL_SECONDS));
        tickets.put(ticket.value(), ticket);
        return ticket;
    }

    /** 一次性换票:无效/过期返回 null。 */
    public synchronized Ticket exchange(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        Ticket t = tickets.remove(ticket.trim());
        if (t == null || Instant.now().isAfter(t.expiresAt())) {
            return null;
        }
        return t;
    }

    private String randomTicket() {
        return "oauth_" + UUID.randomUUID() + "_" + String.format("%06d", random.nextInt(1_000_000));
    }

    private String defaultNick(String provider) {
        switch (provider) {
            case "wechat": return "微信用户";
            case "weibo": return "微博用户";
            default: return "QQ用户";
        }
    }
}

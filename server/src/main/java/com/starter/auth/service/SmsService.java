package com.starter.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * 短信验证码（demo 通道：写日志，不接真实短信供应商）。内存态、一次性消费、TTL 内有效，
 * 同 (purpose, 手机号) 发送有冷却间隔防轰炸。
 *
 * <p>purpose 区分业务场景（login / register），验证码不跨场景复用：登录码不能用来注册，反之亦然。
 */
@Service
public class SmsService {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static final String PURPOSE_LOGIN = "login";
    public static final String PURPOSE_REGISTER = "register";

    private final SmsProperties props;
    private final Map<String, Entry> codeByPhone = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSentAt = new ConcurrentHashMap<>();

    private record Entry(String code, Instant expiresAt) {
    }

    public enum SendStatus {
        SENT, COOLDOWN
    }

    public record SendResult(SendStatus status, String debugCode) {
    }

    public SmsService(SmsProperties props) {
        this.props = props;
    }

    /** 发送验证码：写服务端日志（demo 通道）；同场景同号冷却期内拒绝。 */
    public SendResult send(String phone, String purpose) {
        String key = key(phone, purpose);
        Instant now = Instant.now();
        Instant last = lastSentAt.get(key);
        if (last != null && now.isBefore(last.plus(Duration.ofSeconds(props.sendCooldownSeconds())))) {
            return new SendResult(SendStatus.COOLDOWN, null);
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        codeByPhone.put(key, new Entry(code, now.plus(Duration.ofSeconds(props.codeTtlSeconds()))));
        lastSentAt.put(key, now);
        // demo 通道：真实部署替换为短信服务商 SDK 调用
        System.out.println("[sms] demo channel -> " + phone + " purpose=" + purpose + " code=" + code);
        return new SendResult(SendStatus.SENT, props.debugEcho() ? code : null);
    }

    /** 校验：一次性（成败都销毁）；过期/不存在一律 false。 */
    public synchronized boolean verify(String phone, String purpose, String code) {
        if (phone == null || code == null || code.isBlank()) {
            return false;
        }
        Entry entry = codeByPhone.remove(key(phone, purpose));
        if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
            return false;
        }
        return entry.code().equals(code.trim());
    }

    private String key(String phone, String purpose) {
        return (purpose == null || purpose.isBlank() ? PURPOSE_LOGIN : purpose) + ":" + phone.trim();
    }
}

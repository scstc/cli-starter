package com.starter.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * 设备码授权（RFC 8628 精简版，全部内存态，服务重启即失效）：
 * CLI 申请 (device_code, user_code) → 用户在网页登录后确认/拒绝 → CLI 轮询换取自己的新 token。
 *
 * <p>状态迁移 PENDING → APPROVED / DENIED。APPROVED 在 CLI 轮询到时<b>单次消费</b>（删除）；
 * DENIED / EXPIRED 保留到自然过期，轮询可重复观察到同一终态。
 */
@Service
public class DeviceGrantService {

    /** user_code 字符集：大写字母 + 数字并去掉易混字符（0/O/1/I）。 */
    private static final String USER_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int USER_CODE_RETRIES = 5;

    private final DeviceCodeProperties props;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> byDeviceCode = new ConcurrentHashMap<>();
    private final Map<String, String> deviceCodeByUserCode = new ConcurrentHashMap<>();

    public DeviceGrantService(DeviceCodeProperties props) {
        this.props = props;
    }

    public enum State {
        PENDING, APPROVED, DENIED
    }

    /** 一次性授权凭据。 */
    public record Grant(String deviceCode, String userCode, State state, String loginId,
            Instant expiresAt) {
    }

    public enum PollStatus {
        PENDING, APPROVED, DENIED, EXPIRED, INVALID
    }

    /** CLI 轮询结果；APPROVED 携带 loginId，由调用方 StpUtil.login 换发 CLI 专属 token。 */
    public record PollResult(PollStatus status, String loginId) {
        static PollResult of(PollStatus status) {
            return new PollResult(status, null);
        }
    }

    public Grant create() {
        String deviceCode = UUID.randomUUID().toString();
        String userCode = freshUserCode();
        Grant grant = new Grant(deviceCode, userCode, State.PENDING, null,
                Instant.now().plus(Duration.ofSeconds(props.ttlSeconds())));
        byDeviceCode.put(deviceCode, grant);
        deviceCodeByUserCode.put(userCode, deviceCode);
        return grant;
    }

    /** 网页用户确认授权；user_code 未知或已过期时返回 false。 */
    public synchronized boolean approve(String userCode, String loginId) {
        Grant current = liveByUserCode(userCode);
        if (current == null) {
            return false;
        }
        byDeviceCode.put(current.deviceCode(),
                new Grant(current.deviceCode(), current.userCode(), State.APPROVED, loginId,
                        current.expiresAt()));
        return true;
    }

    /** 网页用户拒绝；user_code 未知或已过期时返回 false。 */
    public synchronized boolean deny(String userCode) {
        Grant current = liveByUserCode(userCode);
        if (current == null) {
            return false;
        }
        byDeviceCode.put(current.deviceCode(),
                new Grant(current.deviceCode(), current.userCode(), State.DENIED,
                        current.loginId(), current.expiresAt()));
        return true;
    }

    /** CLI 轮询。APPROVED 分支消费授权（删除），其余状态保留至自然过期。 */
    public synchronized PollResult poll(String deviceCode) {
        Grant grant = byDeviceCode.get(deviceCode);
        if (grant == null) {
            return PollResult.of(PollStatus.INVALID);
        }
        if (isExpired(grant)) {
            remove(grant);
            return PollResult.of(PollStatus.EXPIRED);
        }
        return switch (grant.state()) {
            case PENDING -> PollResult.of(PollStatus.PENDING);
            case DENIED -> PollResult.of(PollStatus.DENIED);
            case APPROVED -> {
                remove(grant);
                yield new PollResult(PollStatus.APPROVED, grant.loginId());
            }
        };
    }

    private Grant liveByUserCode(String userCode) {
        if (userCode == null || userCode.isBlank()) {
            return null;
        }
        String deviceCode = deviceCodeByUserCode.get(normalize(userCode));
        if (deviceCode == null) {
            return null;
        }
        Grant grant = byDeviceCode.get(deviceCode);
        if (grant == null) {
            return null;
        }
        if (isExpired(grant)) {
            remove(grant);
            return null;
        }
        return grant;
    }

    /** 容忍小写、空格与缺失的分组连字符。 */
    private String normalize(String userCode) {
        String compact = userCode.trim().toUpperCase().replace(" ", "");
        if (compact.length() == 8 && compact.indexOf('-') < 0) {
            return compact.substring(0, 4) + "-" + compact.substring(4);
        }
        return compact;
    }

    private boolean isExpired(Grant grant) {
        return Instant.now().isAfter(grant.expiresAt());
    }

    private void remove(Grant grant) {
        byDeviceCode.remove(grant.deviceCode());
        deviceCodeByUserCode.remove(grant.userCode());
    }

    private String freshUserCode() {
        for (int attempt = 0; attempt < USER_CODE_RETRIES; attempt++) {
            StringBuilder sb = new StringBuilder(9);
            for (int i = 0; i < 8; i++) {
                if (i == 4) {
                    sb.append('-');
                }
                sb.append(USER_CODE_ALPHABET.charAt(random.nextInt(USER_CODE_ALPHABET.length())));
            }
            String code = sb.toString();
            if (!deviceCodeByUserCode.containsKey(code)) {
                return code;
            }
        }
        throw new IllegalStateException("user_code space exhausted");
    }
}

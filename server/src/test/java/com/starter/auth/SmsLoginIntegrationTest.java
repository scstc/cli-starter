package com.starter.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.jayway.jsonpath.JsonPath;

/**
 * 手机号 + 短信验证码登录契约测试（走 application.yml 默认：debug-echo=true、冷却 60s）。
 * 业务码：415 手机号未注册；416 发送太频繁；417 验证码错误或已过期。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmsLoginIntegrationTest extends ApiIntegrationTestBase {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        datasourceProperties(registry, tempDir.resolve("auth-test.db"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String phone) {
        ExchangeResult r = post("/api/auth/sms/send", Map.of("phone", phone), null);
        assertThat(r.status()).as("sms/send 应成功: %s", r.body()).isEqualTo(200);
        return JsonPath.read(r.body(), "$.data");
    }

    @Test
    void sendReturnsDebugCodeInDemoMode() {
        Map<String, Object> data = send("13800000001");
        assertThat((String) data.get("debugCode")).matches("\\d{6}");
    }

    @Test
    void sendToUnknownPhoneReturns415() {
        ExchangeResult r = post("/api/auth/sms/send", Map.of("phone", "19999999999"), null);
        assertThat(r.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(415);
    }

    @Test
    void resendWithinCooldownReturns416() {
        send("13800000002");
        ExchangeResult r = post("/api/auth/sms/send", Map.of("phone", "13800000002"), null);
        assertThat(r.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(416);
    }

    @Test
    void loginWithWrongCodeReturns417() {
        send("13800000003");
        ExchangeResult r = post("/api/auth/sms/login",
                Map.of("phone", "13800000003", "code", "000000"), null);
        assertThat(r.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(417);
    }

    @Test
    void loginWithCorrectCodeReturnsToken() {
        Map<String, Object> sent = send("13800000004");
        ExchangeResult r = post("/api/auth/sms/login",
                Map.of("phone", "13800000004", "code", sent.get("debugCode")), null);
        assertThat(r.status()).as("sms 登录应成功: %s", r.body()).isEqualTo(200);
        String token = JsonPath.read(r.body(), "$.data.tokenValue");
        assertThat(token).isNotEmpty();

        ExchangeResult me = get("/api/user/me", token);
        assertThat(me.status()).isEqualTo(200);
        assertThat((String) JsonPath.read(me.body(), "$.data.username")).isEqualTo("dave");

        // 验证码一次性:同码重放 → 417
        ExchangeResult replay = post("/api/auth/sms/login",
                Map.of("phone", "13800000004", "code", sent.get("debugCode")), null);
        assertThat((Integer) JsonPath.read(replay.body(), "$.code")).isEqualTo(417);
    }
}

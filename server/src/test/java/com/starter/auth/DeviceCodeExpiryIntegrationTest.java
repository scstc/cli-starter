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
 * 设备码过期分支：独立上下文把 ttl 压到 1 秒，验证轮询/授权的 expired 路径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "starter.captcha.required=false", "starter.device-code.ttl-seconds=1" })
class DeviceCodeExpiryIntegrationTest extends ApiIntegrationTestBase {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        datasourceProperties(registry, tempDir.resolve("auth-test.db"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiredCodeReportsExpiredOnPollAndAuthorize() throws InterruptedException {
        ExchangeResult code = post("/api/auth/device/code", null, null);
        assertThat(code.status()).isEqualTo(200);
        String deviceCode = JsonPath.read(code.body(), "$.data.deviceCode");
        String userCode = JsonPath.read(code.body(), "$.data.userCode");

        ExchangeResult login = post("/api/auth/login",
                Map.of("username", "alice", "password", "alice123"), null);
        String webToken = JsonPath.read(login.body(), "$.data.tokenValue");

        Thread.sleep(1200);

        ExchangeResult poll = post("/api/auth/device/token",
                Map.of("deviceCode", deviceCode), null);
        assertThat(poll.status()).isEqualTo(200);
        assertThat((String) JsonPath.read(poll.body(), "$.data.status")).isEqualTo("expired");

        ExchangeResult authorize = post("/api/auth/device/authorize",
                Map.of("userCode", userCode), webToken);
        assertThat(authorize.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(authorize.body(), "$.code")).isEqualTo(412);
    }
}

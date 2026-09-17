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
 * 设备码登录端到端契约测试：code/authorize/deny/token。
 * 断言与 docs/api-contract.md 设备码章节一致。验证码关闭（不影响登录接口的场景隔离）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "starter.captcha.required=false")
class DeviceAuthIntegrationTest extends ApiIntegrationTestBase {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        datasourceProperties(registry, tempDir.resolve("auth-test.db"));
    }

    private String login(String username, String password) {
        ExchangeResult r = post("/api/auth/login",
                Map.of("username", username, "password", password), null);
        assertThat(r.status()).as("login 应成功: %s", r.body()).isEqualTo(200);
        return JsonPath.read(r.body(), "$.data.tokenValue");
    }

    /** 申请设备码并返回 data（Map 形式，便于逐字段断言）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> requestDeviceCode() {
        ExchangeResult r = post("/api/auth/device/code", null, null);
        assertThat(r.status()).as("device/code 应成功: %s", r.body()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(200);
        return JsonPath.read(r.body(), "$.data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> poll(String deviceCode) {
        ExchangeResult r = post("/api/auth/device/token",
                Map.of("deviceCode", deviceCode), null);
        assertThat(r.status()).as("device/token 应为 HTTP 200: %s", r.body()).isEqualTo(200);
        return JsonPath.read(r.body(), "$.data");
    }

    @Test
    void deviceCodeReturnsRfc8628Shape() {
        Map<String, Object> data = requestDeviceCode();
        assertThat((String) data.get("deviceCode")).isNotBlank();
        assertThat((String) data.get("userCode")).matches("[A-Z2-9]{4}-[A-Z2-9]{4}");
        // 授权页指向独立前端（starter.device-code.web-base），不再按请求 Host 推导
        assertThat((String) data.get("verificationUri")).isEqualTo("http://127.0.0.1:8000/");
        assertThat((String) data.get("verificationUriComplete"))
                .contains("?user_code=" + data.get("userCode"));
        assertThat((Integer) data.get("expiresIn")).isBetween(895, 900);
        assertThat((Integer) data.get("interval")).isEqualTo(3);
    }

    @Test
    void pollBeforeApprovalReturnsPending() {
        Map<String, Object> code = requestDeviceCode();
        Map<String, Object> data = poll((String) code.get("deviceCode"));
        assertThat(data.get("status")).isEqualTo("pending");
    }

    @Test
    void approveFlowIssuesIndependentCliToken() {
        Map<String, Object> code = requestDeviceCode();
        String webToken = login("bob", "bob123");

        ExchangeResult approve = post("/api/auth/device/authorize",
                Map.of("userCode", code.get("userCode")), webToken);
        assertThat(approve.status()).as("authorize 应成功: %s", approve.body()).isEqualTo(200);

        Map<String, Object> data = poll((String) code.get("deviceCode"));
        assertThat(data.get("status")).isEqualTo("ok");
        assertThat((String) JsonPath.read(data, "$.token.tokenName")).isEqualTo("satoken");
        assertThat((String) JsonPath.read(data, "$.token.tokenValue")).isNotEmpty();
        assertThat((String) JsonPath.read(data, "$.token.loginId")).isEqualTo("2");

        // CLI 专属 token 可访问 /api/user/me,且 device=cli(与网页会话隔离)
        String cliToken = JsonPath.read(data, "$.token.tokenValue");
        ExchangeResult me = get("/api/user/me", cliToken);
        assertThat(me.status()).isEqualTo(200);
        assertThat((String) JsonPath.read(me.body(), "$.data.username")).isEqualTo("bob");
        assertThat((String) JsonPath.read(me.body(), "$.data.device")).isEqualTo("cli");

        // 授权单次消费:再次轮询 → invalid
        Map<String, Object> again = poll((String) code.get("deviceCode"));
        assertThat(again.get("status")).isEqualTo("invalid");
    }

    @Test
    void denyFlowReportsDeniedTerminalState() {
        Map<String, Object> code = requestDeviceCode();
        String webToken = login("alice", "alice123");

        ExchangeResult deny = post("/api/auth/device/deny",
                Map.of("userCode", code.get("userCode")), webToken);
        assertThat(deny.status()).as("deny 应成功: %s", deny.body()).isEqualTo(200);

        Map<String, Object> data = poll((String) code.get("deviceCode"));
        assertThat(data.get("status")).isEqualTo("denied");
    }

    @Test
    void authorizeWithoutLoginReturns401SceneMinus1() {
        Map<String, Object> code = requestDeviceCode();
        ExchangeResult r = post("/api/auth/device/authorize",
                Map.of("userCode", code.get("userCode")), null);
        assertThat(r.status()).isEqualTo(401);
        assertThat((Integer) JsonPath.read(r.body(), "$.data.scene")).isEqualTo(-1);
    }

    @Test
    void authorizeWithUnknownUserCodeReturns412() {
        String webToken = login("alice", "alice123");
        ExchangeResult r = post("/api/auth/device/authorize",
                Map.of("userCode", "ZZZZ-ZZZZ"), webToken);
        assertThat(r.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(412);
    }

    @Test
    void pollWithUnknownDeviceCodeReturnsInvalid() {
        Map<String, Object> data = poll("no-such-device-code");
        assertThat(data.get("status")).isEqualTo("invalid");
    }
}

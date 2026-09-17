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
 * 端到端契约测试：覆盖 login / tokenInfo / logout / me 的成功与失败分支。
 * 失败分支断言 HTTP 状态码 + 响应体 code/msg/data.scene，与 docs/api-contract.md 一致。
 * 本类关闭图形验证码（starter.captcha.required=false），验证码语义见 CaptchaIntegrationTest。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "starter.captcha.required=false")
class AuthIntegrationTest extends ApiIntegrationTestBase {

    /** 每个测试类独立的临时 SQLite 库，保证与 data/auth.db 及其他运行隔离。 */
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

    @Test
    void loginWithValidCredentialsReturnsToken() {
        ExchangeResult r = post("/api/auth/login",
                Map.of("username", "alice", "password", "alice123"), null);
        assertThat(r.status()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(200);
        assertThat((String) JsonPath.read(r.body(), "$.data.tokenName")).isEqualTo("satoken");
        assertThat((String) JsonPath.read(r.body(), "$.data.tokenValue")).isNotEmpty();
        assertThat((String) JsonPath.read(r.body(), "$.data.loginId")).isEqualTo("1");
    }

    @Test
    void loginWithWrongPasswordReturns401() {
        ExchangeResult r = post("/api/auth/login",
                Map.of("username", "alice", "password", "wrong"), null);
        assertThat(r.status()).isEqualTo(401);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(401);
        assertThat((String) JsonPath.read(r.body(), "$.msg")).isEqualTo("用户名或密码错误");
    }

    @Test
    void meWithoutTokenReturns401SceneMinus1() {
        ExchangeResult r = get("/api/user/me", null);
        assertThat(r.status()).isEqualTo(401);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(401);
        assertThat((Integer) JsonPath.read(r.body(), "$.data.scene")).isEqualTo(-1);
    }

    @Test
    void meWithTokenReturnsUserInfo() {
        String token = login("alice", "alice123");
        ExchangeResult r = get("/api/user/me", token);
        assertThat(r.status()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(200);
        assertThat((String) JsonPath.read(r.body(), "$.data.loginId")).isEqualTo("1");
        assertThat((String) JsonPath.read(r.body(), "$.data.username")).isEqualTo("alice");
        assertThat((String) JsonPath.read(r.body(), "$.data.device")).isEqualTo("default");
    }

    @Test
    void logoutInvalidatesToken() {
        String token = login("bob", "bob123");
        ExchangeResult lo = post("/api/auth/logout", null, token);
        assertThat(lo.status()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(lo.body(), "$.code")).isEqualTo(200);

        ExchangeResult me = get("/api/user/me", token);
        assertThat(me.status()).isEqualTo(401);
        assertThat((Integer) JsonPath.read(me.body(), "$.data.scene")).isEqualTo(-2);
    }
}

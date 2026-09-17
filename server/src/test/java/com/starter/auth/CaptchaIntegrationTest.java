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
 * 图形验证码契约测试（走 application.yml 默认：required=true、debug-echo=true）。
 * 与 docs/api-contract.md 验证码章节一致。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CaptchaIntegrationTest extends ApiIntegrationTestBase {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        datasourceProperties(registry, tempDir.resolve("auth-test.db"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchCaptcha() {
        ExchangeResult r = get("/api/auth/captcha", null);
        assertThat(r.status()).as("captcha 应成功: %s", r.body()).isEqualTo(200);
        return JsonPath.read(r.body(), "$.data");
    }

    @Test
    void captchaReturnsPngDataUriAndEchoesAnswerInDemoMode() {
        Map<String, Object> c = fetchCaptcha();
        assertThat((String) c.get("captchaId")).isNotBlank();
        assertThat((String) c.get("image")).startsWith("data:image/png;base64,");
        assertThat((String) c.get("debugCode")).matches("[A-Z2-9]{4}");
    }

    @Test
    void loginWithoutCaptchaReturns414() {
        ExchangeResult r = post("/api/auth/login",
                Map.of("username", "alice", "password", "alice123"), null);
        assertThat(r.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(414);
    }

    @Test
    void loginWithWrongCaptchaReturns414() {
        Map<String, Object> c = fetchCaptcha();
        ExchangeResult r = post("/api/auth/login",
                Map.of("username", "alice", "password", "alice123",
                        "captchaId", c.get("captchaId"), "captchaCode", "ZZZZ"), null);
        assertThat(r.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(414);
    }

    @Test
    void loginWithCorrectCaptchaSucceeds() {
        Map<String, Object> c = fetchCaptcha();
        ExchangeResult r = post("/api/auth/login",
                Map.of("username", "alice", "password", "alice123",
                        "captchaId", c.get("captchaId"), "captchaCode", c.get("debugCode")), null);
        assertThat(r.status()).as("登录应成功: %s", r.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(r.body(), "$.data.tokenValue")).isNotEmpty();
    }

    @Test
    void captchaIsOneTimeEvenWhenCorrect() {
        Map<String, Object> c = fetchCaptcha();
        ExchangeResult first = post("/api/auth/login",
                Map.of("username", "alice", "password", "alice123",
                        "captchaId", c.get("captchaId"), "captchaCode", c.get("debugCode")), null);
        assertThat(first.status()).isEqualTo(200);

        // 同一 captchaId 二次使用:验证已销毁 → 414
        ExchangeResult second = post("/api/auth/login",
                Map.of("username", "alice", "password", "alice123",
                        "captchaId", c.get("captchaId"), "captchaCode", c.get("debugCode")), null);
        assertThat(second.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(second.body(), "$.code")).isEqualTo(414);
    }
}

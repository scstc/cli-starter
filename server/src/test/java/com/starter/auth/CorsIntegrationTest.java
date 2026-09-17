package com.starter.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 前后端分离 CORS 契约测试：白名单（starter.cors.allowed-origins）内放行，其余不带放行头。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "starter.captcha.required=false")
class CorsIntegrationTest extends ApiIntegrationTestBase {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        datasourceProperties(registry, tempDir.resolve("auth-test.db"));
    }

    private static final String ALLOWED = "http://localhost:8000";
    private static final String NOT_ALLOWED = "http://evil.example.com";

    @Test
    void preflightFromAllowedOriginIsGranted() {
        ApiIntegrationTestBase.HeaderedResult r = exchangeFull(
                org.springframework.http.HttpMethod.OPTIONS, "/api/auth/login",
                Map.of("Origin", ALLOWED,
                        "Access-Control-Request-Method", "POST",
                        "Access-Control-Request-Headers", "content-type,satoken"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.headers().getAccessControlAllowOrigin()).isNotNull();
    }

    @Test
    void actualRequestFromAllowedOriginCarriesAllowOrigin() {
        // 401 错误响应也要带放行头,前端才能读到错误信息
        ApiIntegrationTestBase.HeaderedResult r = exchangeFull(
                org.springframework.http.HttpMethod.GET, "/api/user/me",
                Map.of("Origin", ALLOWED));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.headers().getAccessControlAllowOrigin()).isNotNull();
    }

    @Test
    void preflightFromUnknownOriginIsRejected() {
        ApiIntegrationTestBase.HeaderedResult r = exchangeFull(
                org.springframework.http.HttpMethod.OPTIONS, "/api/auth/login",
                Map.of("Origin", NOT_ALLOWED,
                        "Access-Control-Request-Method", "POST"));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.headers().getAccessControlAllowOrigin()).isNull();
    }
}

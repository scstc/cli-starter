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
 * 第三方登录(微信/QQ demo 模拟授权)与运营商一键登录(demo 通道)契约测试。
 * 业务码:430 票/token 无效;431 不支持的登录方式。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FederatedLoginIntegrationTest extends ApiIntegrationTestBase {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        datasourceProperties(registry, tempDir.resolve("auth-test.db"));
    }

    private String authorize(String provider, String nickname) {
        ExchangeResult r = post("/api/auth/oauth/" + provider + "/authorize",
                Map.of("nickname", nickname), null);
        assertThat(r.status()).as("authorize 应成功: %s", r.body()).isEqualTo(200);
        return JsonPath.read(r.body(), "$.data.ticket");
    }

    private ExchangeResult callback(String provider, String ticket) {
        return post("/api/auth/oauth/" + provider + "/callback",
                Map.of("ticket", ticket), null);
    }

    @Test
    void wechatLoginAutoProvisionsAndBindsIdentity() {
        String ticket = authorize("wechat", "测试微信用户");
        ExchangeResult cb = callback("wechat", ticket);
        assertThat(cb.status()).as("callback 应成功: %s", cb.body()).isEqualTo(200);
        String token = JsonPath.read(cb.body(), "$.data.tokenValue");
        String firstLoginId = JsonPath.read(cb.body(), "$.data.loginId");

        ExchangeResult me = get("/api/user/me", token);
        assertThat((String) JsonPath.read(me.body(), "$.data.username")).startsWith("wechat_");

        // 同昵称 = 同 openId = 同账号(身份绑定生效,loginId 与首次一致)
        ExchangeResult again = callback("wechat", authorize("wechat", "测试微信用户"));
        assertThat((String) JsonPath.read(again.body(), "$.data.loginId")).isEqualTo(firstLoginId);
    }

    @Test
    void qqLoginIsIndependentOfWechat() {
        // 同昵称不同 provider → 不同 openId → 不同账号
        String wechatTicket = authorize("wechat", "同昵称用户");
        String wechatId = JsonPath.read(callback("wechat", wechatTicket).body(), "$.data.loginId");
        String qqTicket = authorize("qq", "同昵称用户");
        String qqId = JsonPath.read(callback("qq", qqTicket).body(), "$.data.loginId");
        assertThat(qqId).isNotEqualTo(wechatId);
    }

    @Test
    void weiboLoginAutoProvisionsUser() {
        String ticket = authorize("weibo", "同昵称用户");
        ExchangeResult cb = callback("weibo", ticket);
        assertThat(cb.status()).as("weibo 登录应成功: %s", cb.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(cb.body(), "$.data.loginId")).isNotEqualTo("1");
    }

    @Test
    void reusedOrFakeTicketReturns430() {
        String ticket = authorize("wechat", "一次性用户");
        assertThat(callback("wechat", ticket).status()).isEqualTo(200);
        // 一次性消费:重放 → 430
        ExchangeResult replay = callback("wechat", ticket);
        assertThat(replay.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(replay.body(), "$.code")).isEqualTo(430);
        // 伪造票 → 430
        assertThat((Integer) JsonPath.read(callback("wechat", "fake").body(), "$.code"))
                .isEqualTo(430);
    }

    @Test
    void unsupportedProviderReturns431() {
        ExchangeResult r = post("/api/auth/oauth/tiktok/authorize", Map.of(), null);
        assertThat(r.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(431);
    }

    @Test
    void oneClickLoginMapsToDemoPhoneUser() {
        ExchangeResult preview = get("/api/auth/oneclick/preview", null);
        assertThat(preview.status()).isEqualTo(200);
        assertThat((String) JsonPath.read(preview.body(), "$.data.maskedPhone"))
                .isEqualTo("138****0001");
        String token = JsonPath.read(preview.body(), "$.data.token");

        ExchangeResult login = post("/api/auth/oneclick/login",
                Map.of("token", token), null);
        assertThat(login.status()).as("一键登录应成功: %s", login.body()).isEqualTo(200);
        String t = JsonPath.read(login.body(), "$.data.tokenValue");
        ExchangeResult me = get("/api/user/me", t);
        assertThat((String) JsonPath.read(me.body(), "$.data.username")).isEqualTo("alice");

        // token 一次性:重放 → 430;伪造 → 430
        ExchangeResult replay = post("/api/auth/oneclick/login", Map.of("token", token), null);
        assertThat((Integer) JsonPath.read(replay.body(), "$.code")).isEqualTo(430);
        ExchangeResult fake = post("/api/auth/oneclick/login", Map.of("token", "nope"), null);
        assertThat((Integer) JsonPath.read(fake.body(), "$.code")).isEqualTo(430);
    }
}

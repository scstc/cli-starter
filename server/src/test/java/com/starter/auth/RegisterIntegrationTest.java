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
 * 自主注册契约测试（走 application.yml 默认：验证码 required=true、debug-echo=true）。
 * 流程：图形验证码 + purpose=register 的短信验证码 → 注册即自动登录。
 * 业务码：414 验证码 / 417 短信码 / 418 用户名已存在 / 419 手机号已被注册 / 420 格式不合法。
 * 用例自包含（不依赖其它用例的执行顺序/数据）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegisterIntegrationTest extends ApiIntegrationTestBase {

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

    /** purpose=register 的短信码；返回 data（含 debugCode）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sendRegisterSms(String phone) {
        ExchangeResult r = post("/api/auth/sms/send",
                Map.of("phone", phone, "purpose", "register"), null);
        assertThat(r.status()).as("register 短信应发送成功: %s", r.body()).isEqualTo(200);
        return JsonPath.read(r.body(), "$.data");
    }

    private ExchangeResult register(String username, String password, String phone,
            Map<String, Object> captcha, String smsCode) {
        return post("/api/auth/register",
                Map.of("username", username, "password", password, "phone", phone,
                        "captchaId", captcha.get("captchaId"),
                        "captchaCode", captcha.get("debugCode"),
                        "smsCode", smsCode),
                null);
    }

    /** 完整注册一次并断言成功,返回 token。 */
    private String registerOk(String username, String password, String phone) {
        Map<String, Object> captcha = fetchCaptcha();
        Map<String, Object> sms = sendRegisterSms(phone);
        ExchangeResult r = register(username, password, phone, captcha,
                (String) sms.get("debugCode"));
        assertThat(r.status()).as("注册应成功: %s", r.body()).isEqualTo(200);
        return JsonPath.read(r.body(), "$.data.tokenValue");
    }

    @Test
    void registerSuccessAutoLoginAndPersistsUser() {
        String token = registerOk("newuser", "secret123", "13800000005");
        // login_id = 现有最大数字 +1(种子 1..4;同上下文其他用例可能已注册用户,故断言范围)
        String loginId = JsonPath.read(get("/api/auth/tokenInfo", token).body(),
                "$.data.loginId");
        assertThat(Integer.parseInt(loginId)).isGreaterThanOrEqualTo(5);

        // 自动登录生效:token 可读当前用户
        ExchangeResult me = get("/api/user/me", token);
        assertThat(me.status()).isEqualTo(200);
        assertThat((String) JsonPath.read(me.body(), "$.data.username")).isEqualTo("newuser");
        assertThat((String) JsonPath.read(me.body(), "$.data.roles[0]")).isEqualTo("user");

        // 新手机号可用于短信登录(send purpose=login 此时应放行)
        ExchangeResult loginSms = post("/api/auth/sms/send",
                Map.of("phone", "13800000005", "purpose", "login"), null);
        assertThat(loginSms.status()).isEqualTo(200);
    }

    @Test
    void registerDuplicateUsernameReturns418() {
        registerOk("dupname", "secret123", "13800000006");
        Map<String, Object> captcha = fetchCaptcha();
        Map<String, Object> sms = sendRegisterSms("13800000007");
        ExchangeResult r = register("dupname", "secret123", "13800000007", captcha,
                (String) sms.get("debugCode"));
        assertThat(r.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(418);
    }

    @Test
    void registerDuplicatePhoneReturns419() {
        registerOk("phoneone", "secret123", "13800000008");
        Map<String, Object> captcha = fetchCaptcha();

        // register 场景对已注册手机号连短信都发不出
        ExchangeResult resend = post("/api/auth/sms/send",
                Map.of("phone", "13800000008", "purpose", "register"), null);
        assertThat((Integer) JsonPath.read(resend.body(), "$.code")).isEqualTo(419);

        // 直接提交注册(伪造短信码):在短信校验之前就命中手机号已存在
        ExchangeResult r = register("phonetwo", "secret123", "13800000008", captcha, "000000");
        assertThat(r.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(419);
    }

    @Test
    void registerWithWrongSmsCodeReturns417() {
        Map<String, Object> captcha = fetchCaptcha();
        sendRegisterSms("13800000009");
        ExchangeResult r = register("third", "secret123", "13800000009", captcha, "000000");
        assertThat(r.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(417);
    }

    @Test
    void registerWithInvalidFormatReturns420() {
        Map<String, Object> captcha = fetchCaptcha();
        ExchangeResult badName = register("ab", "secret123", "13800000010", captcha, "123456");
        assertThat((Integer) JsonPath.read(badName.body(), "$.code")).isEqualTo(420);

        Map<String, Object> captcha2 = fetchCaptcha();
        ExchangeResult shortPwd = register("validname", "123", "13800000010", captcha2, "123456");
        assertThat((Integer) JsonPath.read(shortPwd.body(), "$.code")).isEqualTo(420);

        Map<String, Object> captcha3 = fetchCaptcha();
        ExchangeResult badPhone = register("validname", "secret123", "12345", captcha3, "123456");
        assertThat((Integer) JsonPath.read(badPhone.body(), "$.code")).isEqualTo(420);
    }

    @Test
    void registerWithoutCaptchaReturns414() {
        ExchangeResult r = post("/api/auth/register",
                Map.of("username", "nocaptcha", "password", "secret123",
                        "phone", "13800000011", "smsCode", "123456"),
                null);
        assertThat(r.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(414);
    }

    @Test
    void registerPurposeSendForRegisteredPhoneReturns419() {
        ExchangeResult r = post("/api/auth/sms/send",
                Map.of("phone", "13800000001", "purpose", "register"), null);
        assertThat(r.status()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(r.body(), "$.code")).isEqualTo(419);
    }
}

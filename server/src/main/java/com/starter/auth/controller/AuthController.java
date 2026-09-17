package com.starter.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.starter.auth.model.ApiResult;
import com.starter.auth.model.CaptchaResponse;
import com.starter.auth.model.LoginRequest;
import com.starter.auth.model.RegisterRequest;
import com.starter.auth.service.CaptchaService;
import com.starter.auth.service.DemoUserService;
import com.starter.auth.service.SmsService;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String CAPTCHA_INVALID = "验证码错误或已过期";
    private static final String REGISTER_INVALID =
            "注册信息不合法：用户名 3-32 位字母/数字/下划线，密码 6-64 位，手机号 1 开头 11 位";

    private final DemoUserService userService;
    private final CaptchaService captchaService;
    private final SmsService smsService;

    public AuthController(DemoUserService userService, CaptchaService captchaService,
            SmsService smsService) {
        this.userService = userService;
        this.captchaService = captchaService;
        this.smsService = smsService;
    }

    /** 图形验证码：captchaId + base64 data URI；debug-echo 开启时附带答案（仅本地演示）。 */
    @GetMapping("/captcha")
    public ApiResult<CaptchaResponse> captcha() {
        CaptchaService.GeneratedCaptcha c = captchaService.create();
        return ApiResult.ok(new CaptchaResponse(c.captchaId(), c.image(), c.debugCode()));
    }

    /**
     * 登录：校验通过后 StpUtil.login，返回 SaTokenInfo 全量。
     * 图形验证码：required=true（默认）时必填，填了则无论是否强制都校验；
     * 校验失败 → HTTP 400 + {"code":414,"msg":"验证码错误或已过期"}。
     * 错误凭证 → HTTP 401 + {"code":401,"msg":"用户名或密码错误"}。
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResult<SaTokenInfo>> login(@RequestBody LoginRequest request) {
        boolean captchaPresent = request.captchaId() != null || request.captchaCode() != null;
        if (captchaPresent || captchaService.isRequired()) {
            if (!captchaService.verify(request.captchaId(), request.captchaCode())) {
                return ResponseEntity.badRequest().body(ApiResult.of(414, CAPTCHA_INVALID, null));
            }
        }
        DemoUserService.DemoUser user = userService
                .authenticate(request.username(), request.password())
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.of(401, "用户名或密码错误", null));
        }
        StpUtil.login(user.loginId(), new SaLoginParameter().setDeviceType("default"));
        return ResponseEntity.ok(ApiResult.ok(StpUtil.getTokenInfo()));
    }

    /**
     * 自主注册：图形验证码（required 语义同 login）+ purpose=register 的短信验证码（验证手机号本人）。
     * 用户名/手机号唯一，新用户默认角色 user；成功即自动登录，返回 SaTokenInfo。
     * 业务码：414 验证码 / 418 用户名已存在 / 419 手机号已被注册 / 417 短信码 / 420 格式不合法。
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResult<SaTokenInfo>> register(@RequestBody RegisterRequest request) {
        boolean captchaPresent = request.captchaId() != null || request.captchaCode() != null;
        if (captchaPresent || captchaService.isRequired()) {
            if (!captchaService.verify(request.captchaId(), request.captchaCode())) {
                return ResponseEntity.badRequest().body(ApiResult.of(414, CAPTCHA_INVALID, null));
            }
        }
        String username = request.username() == null ? "" : request.username().trim();
        String phone = request.phone() == null ? "" : request.phone().trim();
        String password = request.password() == null ? "" : request.password();
        boolean valid = username.matches("[a-zA-Z0-9_]{3,32}")
                && password.length() >= 6 && password.length() <= 64
                && phone.matches("1\\d{10}");
        if (!valid) {
            return ResponseEntity.badRequest().body(ApiResult.of(420, REGISTER_INVALID, null));
        }
        if (userService.existsByUsername(username)) {
            return ResponseEntity.badRequest().body(ApiResult.of(418, "用户名已存在", null));
        }
        if (userService.existsByPhone(phone)) {
            return ResponseEntity.badRequest().body(ApiResult.of(419, "手机号已被注册", null));
        }
        if (!smsService.verify(phone, SmsService.PURPOSE_REGISTER, request.smsCode())) {
            return ResponseEntity.badRequest().body(ApiResult.of(417, "验证码错误或已过期", null));
        }
        String loginId = userService.createUser(username, password, phone);
        StpUtil.login(loginId, new SaLoginParameter().setDeviceType("default"));
        return ResponseEntity.ok(ApiResult.ok(StpUtil.getTokenInfo()));
    }

    /** 登出：未登录（无/无效 token）由全局异常处理统一转 401+scene。 */
    @PostMapping("/logout")
    public ApiResult<Void> logout() {
        StpUtil.checkLogin();
        StpUtil.logout();
        return ApiResult.ok(null);
    }

    /** 当前会话的 SaTokenInfo 全量；未登录 → 401+scene。 */
    @GetMapping("/tokenInfo")
    public ApiResult<SaTokenInfo> tokenInfo() {
        StpUtil.checkLogin();
        return ApiResult.ok(StpUtil.getTokenInfo());
    }
}

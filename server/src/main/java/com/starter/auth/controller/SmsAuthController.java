package com.starter.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.starter.auth.model.ApiResult;
import com.starter.auth.model.SmsLoginRequest;
import com.starter.auth.model.SmsSendRequest;
import com.starter.auth.model.SmsSendResponse;
import com.starter.auth.service.DemoUserService;
import com.starter.auth.service.SmsService;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;

/**
 * 手机号 + 短信验证码登录。demo 通道：验证码写服务端日志（debug-echo 开启时随 send 响应回显）。
 * 业务码：415 手机号未注册；416 发送太频繁；417 验证码错误或已过期（均 HTTP 400）。
 */
@RestController
@RequestMapping("/api/auth/sms")
public class SmsAuthController {

    private static final String PHONE_UNKNOWN = "手机号未注册";
    private static final String SEND_COOLDOWN = "发送太频繁，请稍后再试";
    private static final String CODE_INVALID = "验证码错误或已过期";

    private final DemoUserService userService;
    private final SmsService smsService;

    public SmsAuthController(DemoUserService userService, SmsService smsService) {
        this.userService = userService;
        this.smsService = smsService;
    }

    @PostMapping("/send")
    public ResponseEntity<ApiResult<SmsSendResponse>> send(@RequestBody SmsSendRequest request) {
        String phone = request.phone() == null ? "" : request.phone().trim();
        // purpose 区分场景,验证码不跨场景复用:login 要求手机号已注册,register 要求未注册
        boolean register = SmsService.PURPOSE_REGISTER.equals(request.purpose());
        boolean phoneExists = userService.existsByPhone(phone);
        if (register && phoneExists) {
            return ResponseEntity.badRequest().body(ApiResult.of(419, "手机号已被注册", null));
        }
        if (!register && !phoneExists) {
            return ResponseEntity.badRequest().body(ApiResult.of(415, PHONE_UNKNOWN, null));
        }
        String purpose = register ? SmsService.PURPOSE_REGISTER : SmsService.PURPOSE_LOGIN;
        SmsService.SendResult r = smsService.send(phone, purpose);
        if (r.status() == SmsService.SendStatus.COOLDOWN) {
            return ResponseEntity.badRequest().body(ApiResult.of(416, SEND_COOLDOWN, null));
        }
        return ResponseEntity.ok(ApiResult.ok(new SmsSendResponse(r.debugCode())));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResult<SaTokenInfo>> login(@RequestBody SmsLoginRequest request) {
        String phone = request.phone() == null ? "" : request.phone().trim();
        DemoUserService.DemoUser user = userService.findByPhone(phone)
                .orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(ApiResult.of(415, PHONE_UNKNOWN, null));
        }
        if (!smsService.verify(phone, SmsService.PURPOSE_LOGIN, request.code())) {
            return ResponseEntity.badRequest().body(ApiResult.of(417, CODE_INVALID, null));
        }
        StpUtil.login(user.loginId(), new SaLoginParameter().setDeviceType("default"));
        return ResponseEntity.ok(ApiResult.ok(StpUtil.getTokenInfo()));
    }
}

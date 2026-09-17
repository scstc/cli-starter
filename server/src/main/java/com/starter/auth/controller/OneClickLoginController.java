package com.starter.auth.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.starter.auth.model.ApiResult;
import com.starter.auth.model.OneClickLoginRequest;
import com.starter.auth.service.DemoUserService;
import com.starter.auth.service.OneClickService;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;

/**
 * 运营商一键登录(demo 通道:固定模拟本机号)。业务码:430 token 无效或已过期。
 * 真实接入:App 内嵌运营商 SDK 取 token,本控制器换为调运营商网关换手机号,后续逻辑不变。
 */
@RestController
@RequestMapping("/api/auth/oneclick")
public class OneClickLoginController {

    private static final String TOKEN_INVALID = "一键登录已失效，请重试";

    private final OneClickService oneClick;
    private final DemoUserService userService;

    public OneClickLoginController(OneClickService oneClick, DemoUserService userService) {
        this.oneClick = oneClick;
        this.userService = userService;
    }

    /** 预览本机号码(demo:模拟 SIM 卡号)并签发一次性换号 token。 */
    @GetMapping("/preview")
    public ApiResult<OneClickService.Preview> preview() {
        return ApiResult.ok(oneClick.issue());
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResult<SaTokenInfo>> login(@RequestBody OneClickLoginRequest request) {
        String phone = oneClick.exchange(request.token());
        if (phone == null) {
            return ResponseEntity.badRequest().body(ApiResult.of(430, TOKEN_INVALID, null));
        }
        // 本机号已注册则直接登录;未注册自动建号(用户名 mobile_hex,无密码直登场景)
        String loginId = userService.findByPhone(phone)
                .map(DemoUserService.DemoUser::loginId)
                .orElseGet(() -> userService.createUser("mobile_"
                        + UUID.randomUUID().toString().substring(0, 8),
                        UUID.randomUUID().toString(), phone));
        StpUtil.login(loginId, new SaLoginParameter().setDeviceType("default"));
        return ResponseEntity.ok(ApiResult.ok(StpUtil.getTokenInfo()));
    }
}

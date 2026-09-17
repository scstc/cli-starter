package com.starter.auth.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.starter.auth.model.ApiResult;
import com.starter.auth.model.OAuthAuthorizeRequest;
import com.starter.auth.model.OAuthAuthorizeResponse;
import com.starter.auth.model.OAuthCallbackRequest;
import com.starter.auth.service.DemoUserService;
import com.starter.auth.service.OAuthDemoService;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;

/**
 * 第三方登录(微信/QQ,demo 模拟授权)。业务码:430 授权票无效或已过期;431 不支持的登录方式。
 * 真实接入:authorize 改为跳厂商授权页,callback 调厂商 SDK 换 openid,后续绑定/登录逻辑不变。
 */
@RestController
@RequestMapping("/api/auth/oauth")
public class OAuthLoginController {

    private static final String TICKET_INVALID = "授权已失效，请重新发起";
    private static final String PROVIDER_UNSUPPORTED = "不支持的登录方式";

    private final OAuthDemoService oauth;
    private final DemoUserService userService;

    public OAuthLoginController(OAuthDemoService oauth, DemoUserService userService) {
        this.oauth = oauth;
        this.userService = userService;
    }

    @PostMapping("/{provider}/authorize")
    public ResponseEntity<ApiResult<OAuthAuthorizeResponse>> authorize(
            @PathVariable String provider,
            @RequestBody(required = false) OAuthAuthorizeRequest request) {
        if (!OAuthDemoService.SUPPORTED.contains(provider)) {
            return ResponseEntity.badRequest().body(ApiResult.of(431, PROVIDER_UNSUPPORTED, null));
        }
        OAuthDemoService.Ticket t = oauth.createTicket(provider,
                request == null ? null : request.nickname());
        return ResponseEntity.ok(ApiResult.ok(new OAuthAuthorizeResponse(t.value(), t.identity().nickname())));
    }

    @PostMapping("/{provider}/callback")
    public ResponseEntity<ApiResult<SaTokenInfo>> callback(
            @PathVariable String provider,
            @RequestBody OAuthCallbackRequest request) {
        if (!OAuthDemoService.SUPPORTED.contains(provider)) {
            return ResponseEntity.badRequest().body(ApiResult.of(431, PROVIDER_UNSUPPORTED, null));
        }
        OAuthDemoService.Ticket t = oauth.exchange(request.ticket());
        if (t == null || !t.identity().provider().equals(provider)) {
            return ResponseEntity.badRequest().body(ApiResult.of(430, TICKET_INVALID, null));
        }
        // 身份绑定:已绑定直接登录;首次登录自动建号(用户名 provider_hex,随机密码,无手机号)
        String loginId = userService
                .findLoginIdByIdentity(provider, t.identity().openId())
                .orElseGet(() -> {
                    String username = provider + "_"
                            + t.identity().openId().substring(provider.length() + 1);
                    String loginIdNew = userService.createUser(username,
                            UUID.randomUUID().toString(), null);
                    userService.saveIdentity(provider, t.identity().openId(), loginIdNew);
                    return loginIdNew;
                });
        StpUtil.login(loginId, new SaLoginParameter().setDeviceType("default"));
        return ResponseEntity.ok(ApiResult.ok(StpUtil.getTokenInfo()));
    }
}

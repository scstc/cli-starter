package com.starter.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.starter.auth.model.ApiResult;
import com.starter.auth.model.DeviceCodeRequest;
import com.starter.auth.model.DeviceCodeResponse;
import com.starter.auth.model.DeviceTokenData;
import com.starter.auth.model.UserCodeRequest;
import com.starter.auth.service.DeviceCodeProperties;
import com.starter.auth.service.DeviceGrantService;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;

/**
 * 设备码登录（RFC 8628 精简版），契约见 docs/api-contract.md：
 * <ul>
 *   <li>/code —— CLI 申请，无鉴权；</li>
 *   <li>/authorize、/deny —— 网页登录后调用（header satoken），未登录由全局处理转 401+scene；</li>
 *   <li>/token —— CLI 轮询，无鉴权；授权通过时为 CLI 换发独立会话（device = "cli"）的新 token。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth/device")
public class DeviceAuthController {

    private static final String USER_CODE_INVALID = "user_code 无效或已过期";

    private final DeviceGrantService grants;
    private final DeviceCodeProperties props;

    public DeviceAuthController(DeviceGrantService grants, DeviceCodeProperties props) {
        this.grants = grants;
        this.props = props;
    }

    @PostMapping("/code")
    public ApiResult<DeviceCodeResponse> code() {
        // 前后端分离：授权页地址来自配置（starter.device-code.web-base），不再按请求 Host 推导
        return ApiResult.ok(DeviceCodeResponse.of(grants.create(), props.pageBase(),
                props.pollIntervalSeconds()));
    }

    @PostMapping("/authorize")
    public ResponseEntity<ApiResult<Void>> authorize(@RequestBody UserCodeRequest request) {
        StpUtil.checkLogin();
        if (!grants.approve(request.userCode(), StpUtil.getLoginIdAsString())) {
            return ResponseEntity.badRequest().body(ApiResult.of(412, USER_CODE_INVALID, null));
        }
        return ResponseEntity.ok(ApiResult.ok(null));
    }

    @PostMapping("/deny")
    public ResponseEntity<ApiResult<Void>> deny(@RequestBody UserCodeRequest request) {
        StpUtil.checkLogin();
        if (!grants.deny(request.userCode())) {
            return ResponseEntity.badRequest().body(ApiResult.of(412, USER_CODE_INVALID, null));
        }
        return ResponseEntity.ok(ApiResult.ok(null));
    }

    @PostMapping("/token")
    public ApiResult<DeviceTokenData> token(@RequestBody DeviceCodeRequest request) {
        DeviceGrantService.PollResult poll = grants.poll(request.deviceCode());
        return switch (poll.status()) {
            case APPROVED -> {
                // device=cli 与网页会话（device=default）隔离：独立 token，互不影响
                StpUtil.login(poll.loginId(), new SaLoginParameter().setDeviceType("cli"));
                SaTokenInfo info = StpUtil.getTokenInfo();
                yield ApiResult.ok(DeviceTokenData.ok(info));
            }
            case PENDING -> ApiResult.ok(DeviceTokenData.pending());
            case DENIED -> ApiResult.of(200, "用户已拒绝授权", DeviceTokenData.of("denied"));
            case EXPIRED -> ApiResult.of(200, "设备码已过期", DeviceTokenData.of("expired"));
            case INVALID -> ApiResult.of(200, "device_code 无效或已消费", DeviceTokenData.of("invalid"));
        };
    }
}

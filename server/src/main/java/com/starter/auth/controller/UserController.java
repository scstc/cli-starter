package com.starter.auth.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.starter.auth.model.ApiResult;
import com.starter.auth.model.UserInfo;
import com.starter.auth.service.DemoUserService;

import cn.dev33.satoken.stp.StpUtil;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final DemoUserService userService;

    public UserController(DemoUserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ApiResult<UserInfo> me() {
        String loginId = StpUtil.getLoginIdAsString();
        String device = StpUtil.getLoginDeviceType();
        DemoUserService.DemoUser user = userService.findByLoginId(loginId)
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + loginId));
        return ApiResult.ok(new UserInfo(loginId, user.username(), user.roles(), device));
    }
}

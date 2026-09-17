package com.starter.auth.service;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * CORS 配置（starter.cors）。前后端分离：前端独立部署（web/，如 python http.server 8000），
 * 后端按白名单放行跨域请求。
 */
@ConfigurationProperties(prefix = "starter.cors")
public record CorsProperties(
        @DefaultValue({ "http://localhost:8000", "http://127.0.0.1:8000",
                "http://localhost:5173", "http://127.0.0.1:5173" }) List<String> allowedOrigins) {
}

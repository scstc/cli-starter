package com.starter.auth.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.starter.auth.model.ApiResult;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 未登录统一：HTTP 401 + {"code":401,"msg":...,"data":{"scene":<int>}}。
     * scene：-1 未提供 / -2 无效 / -3 过期 / -4 顶下线 / -5 踢下线 / -6 冻结 / -7 无前缀。
     * Sa-Token 1.46 的 NotLoginException.getType() 返回字符串（"-1".."-7"），此处转回整数保持契约。
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResult<Map<String, Integer>>> handleNotLogin(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResult.of(401, e.getMessage(), Map.of("scene", Integer.valueOf(e.getType()))));
    }

    /** 无权限：HTTP 403。 */
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<ApiResult<Void>> handleNotPermission(NotPermissionException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResult.of(403, e.getMessage(), null));
    }

    /** 兜底：HTTP 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleFallback(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.of(500, "服务器内部错误", null));
    }
}

package com.starter.auth.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * POST /api/auth/sms/send 的 data；debugCode 仅在 starter.sms.debug-echo=true 时返回
 * （demo 模式，生产关闭并接真实短信通道）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SmsSendResponse(String debugCode) {
}

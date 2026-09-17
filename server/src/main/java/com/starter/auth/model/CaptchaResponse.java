package com.starter.auth.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** GET /api/auth/captcha 的 data；debugCode 仅在 starter.captcha.debug-echo=true 时返回。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaptchaResponse(String captchaId, String image, String debugCode) {
}

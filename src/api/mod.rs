//! Sa-Token HTTP 客户端。端点路径全部来自 Profile(可配置),加凭证的唯一入口在
//! `with_token_header`,保证凭证只发往本 client 的 api_base。

use std::time::Duration;

use url::Url;

use crate::api::model::{
    ApiEnvelope, CaptchaInfo, DeviceCodeInfo, DeviceCodeRequest, DeviceTokenData, LoginRequest,
    SaTokenInfo, SmsLoginRequest, SmsSendData, SmsSendRequest, UserInfo,
};
use crate::config::{Endpoints, Profile};
use crate::error::{ApiError, ApiResult};

pub mod model;

pub struct SatokenClient {
    http: reqwest::blocking::Client,
    base: Url,
    endpoints: Endpoints,
}

const RETRY_ATTEMPTS: u32 = 3;

impl SatokenClient {
    pub fn new(profile: &Profile) -> Result<Self, ApiError> {
        let base = Url::parse(&profile.api_base).map_err(|e| ApiError::Server {
            status: 0,
            body: format!("invalid api_base `{}`: {e}", profile.api_base),
        })?;
        Ok(Self {
            http: crate::http::build_client().map_err(|e| ApiError::Network {
                url: profile.api_base.clone(),
                source: e,
            })?,
            base,
            endpoints: profile.endpoints(),
        })
    }

    fn url(&self, path: &str) -> String {
        // base 末尾不应带 '/',endpoint 以 '/' 开头;直接拼接保持简单
        let mut base = self.base.as_str().trim_end_matches('/').to_string();
        if !path.starts_with('/') {
            base.push('/');
        }
        format!("{base}{path}")
    }

    fn with_token_header(
        &self,
        rb: reqwest::blocking::RequestBuilder,
        token_header: &str,
        token_value: &str,
    ) -> reqwest::blocking::RequestBuilder {
        rb.header(token_header, token_value)
    }

    /// 账号密码登录 -> SaTokenInfo。captcha = (captchaId, 用户输入);None 时不带验证码字段。
    pub fn login_password(
        &self,
        username: &str,
        password: &str,
        captcha: Option<(&str, &str)>,
    ) -> ApiResult<SaTokenInfo> {
        let url = self.url(&self.endpoints.login);
        let body = serde_json::to_value(LoginRequest {
            username,
            password,
            captcha_id: captcha.map(|(id, _)| id),
            captcha_code: captcha.map(|(_, code)| code),
        })
        .map_err(|e| ApiError::Parse(e.to_string()))?;
        let resp = with_retry(|| {
            self.http
                .post(&url)
                .json(&body)
                .send()
                .map_err(|e| ApiError::Network {
                    url: url.clone(),
                    source: e,
                })
        })?;
        let env = envelope(resp)?;
        expect_ok(&env, "login")?;
        let data = env
            .data
            .ok_or_else(|| ApiError::Parse("empty data".into()))?;
        let info: SaTokenInfo =
            serde_json::from_value(data).map_err(|e| ApiError::Parse(e.to_string()))?;
        Ok(info)
    }

    /// 带证调用 /api/user/me
    pub fn whoami(&self, token_header: &str, token_value: &str) -> ApiResult<UserInfo> {
        let url = self.url(&self.endpoints.whoami);
        let resp = with_retry(|| {
            self.with_token_header(self.http.get(&url), token_header, token_value)
                .send()
                .map_err(|e| ApiError::Network {
                    url: url.clone(),
                    source: e,
                })
        })?;
        let env = envelope(resp)?;
        expect_ok(&env, "whoami")?;
        let data = env
            .data
            .ok_or_else(|| ApiError::Parse("empty data".into()))?;
        let u: UserInfo =
            serde_json::from_value(data).map_err(|e| ApiError::Parse(e.to_string()))?;
        Ok(u)
    }

    /// 申请设备码(RFC 8628,无鉴权)
    pub fn device_code(&self) -> ApiResult<DeviceCodeInfo> {
        let url = self.url(&self.endpoints.device_code);
        let resp = with_retry(|| {
            self.http.post(&url).send().map_err(|e| ApiError::Network {
                url: url.clone(),
                source: e,
            })
        })?;
        let env = envelope(resp)?;
        expect_ok(&env, "device code")?;
        let data = env
            .data
            .ok_or_else(|| ApiError::Parse("empty data".into()))?;
        let info: DeviceCodeInfo =
            serde_json::from_value(data).map_err(|e| ApiError::Parse(e.to_string()))?;
        Ok(info)
    }

    /// 轮询设备码授权结果(无鉴权)。pending 继续轮询;ok 携带 CLI 专属新 token;
    /// denied/expired/invalid 为终态,由调用方收尾。
    pub fn device_token(&self, device_code: &str) -> ApiResult<DeviceTokenData> {
        let url = self.url(&self.endpoints.device_token);
        let body = serde_json::to_value(DeviceCodeRequest { device_code })
            .map_err(|e| ApiError::Parse(e.to_string()))?;
        let resp = with_retry(|| {
            self.http
                .post(&url)
                .json(&body)
                .send()
                .map_err(|e| ApiError::Network {
                    url: url.clone(),
                    source: e,
                })
        })?;
        let env = envelope(resp)?;
        expect_ok(&env, "device token")?;
        let data = env
            .data
            .ok_or_else(|| ApiError::Parse("empty data".into()))?;
        let poll: DeviceTokenData =
            serde_json::from_value(data).map_err(|e| ApiError::Parse(e.to_string()))?;
        Ok(poll)
    }

    /// 获取图形验证码(无鉴权)。服务端未提供该端点(404/405)时返回 Ok(None),
    /// 调用方据此跳过验证码输入(对接未开验证码的后端)。
    pub fn captcha(&self) -> ApiResult<Option<CaptchaInfo>> {
        let url = self.url(&self.endpoints.captcha);
        let resp = with_retry(|| {
            self.http.get(&url).send().map_err(|e| ApiError::Network {
                url: url.clone(),
                source: e,
            })
        })?;
        let status = resp.status().as_u16();
        if status == 404 || status == 405 {
            return Ok(None);
        }
        let env = envelope(resp)?;
        expect_ok(&env, "captcha")?;
        let data = env
            .data
            .ok_or_else(|| ApiError::Parse("empty data".into()))?;
        let info: CaptchaInfo =
            serde_json::from_value(data).map_err(|e| ApiError::Parse(e.to_string()))?;
        Ok(Some(info))
    }

    /// 发送短信验证码(无鉴权)。415 手机号未注册 / 416 发送太频繁。
    pub fn sms_send(&self, phone: &str) -> ApiResult<SmsSendData> {
        let url = self.url(&self.endpoints.sms_send);
        let body = serde_json::to_value(SmsSendRequest { phone })
            .map_err(|e| ApiError::Parse(e.to_string()))?;
        let resp = with_retry(|| {
            self.http
                .post(&url)
                .json(&body)
                .send()
                .map_err(|e| ApiError::Network {
                    url: url.clone(),
                    source: e,
                })
        })?;
        let env = envelope(resp)?;
        expect_ok(&env, "sms send")?;
        let data = env
            .data
            .ok_or_else(|| ApiError::Parse("empty data".into()))?;
        serde_json::from_value(data).map_err(|e| ApiError::Parse(e.to_string()))
    }

    /// 短信验证码登录 -> SaTokenInfo。417 验证码错误或已过期。
    pub fn sms_login(&self, phone: &str, code: &str) -> ApiResult<SaTokenInfo> {
        let url = self.url(&self.endpoints.sms_login);
        let body = serde_json::to_value(SmsLoginRequest { phone, code })
            .map_err(|e| ApiError::Parse(e.to_string()))?;
        let resp = with_retry(|| {
            self.http
                .post(&url)
                .json(&body)
                .send()
                .map_err(|e| ApiError::Network {
                    url: url.clone(),
                    source: e,
                })
        })?;
        let env = envelope(resp)?;
        expect_ok(&env, "sms login")?;
        let data = env
            .data
            .ok_or_else(|| ApiError::Parse("empty data".into()))?;
        let info: SaTokenInfo =
            serde_json::from_value(data).map_err(|e| ApiError::Parse(e.to_string()))?;
        Ok(info)
    }

    /// 带证调用 /api/auth/tokenInfo(契约保留,供后续命令使用)
    #[allow(dead_code)]
    pub fn token_info(&self, token_header: &str, token_value: &str) -> ApiResult<SaTokenInfo> {
        let url = self.url(&self.endpoints.token_info);
        let resp = with_retry(|| {
            self.with_token_header(self.http.get(&url), token_header, token_value)
                .send()
                .map_err(|e| ApiError::Network {
                    url: url.clone(),
                    source: e,
                })
        })?;
        let env = envelope(resp)?;
        expect_ok(&env, "tokenInfo")?;
        let data = env
            .data
            .ok_or_else(|| ApiError::Parse("empty data".into()))?;
        let info: SaTokenInfo =
            serde_json::from_value(data).map_err(|e| ApiError::Parse(e.to_string()))?;
        Ok(info)
    }

    /// 带证调用 /api/auth/logout(契约保留;CLI 的 auth logout 默认不调服务端)
    #[allow(dead_code)]
    pub fn logout_call(&self, token_header: &str, token_value: &str) -> ApiResult<()> {
        let url = self.url(&self.endpoints.logout);
        let resp = with_retry(|| {
            self.with_token_header(self.http.post(&url), token_header, token_value)
                .send()
                .map_err(|e| ApiError::Network {
                    url: url.clone(),
                    source: e,
                })
        })?;
        let env = envelope(resp)?;
        expect_ok(&env, "logout")?;
        Ok(())
    }
}

/// 响应 -> ApiEnvelope;非 JSON(如网关 502 HTML)归为 Parse/Server 错误。
fn envelope(resp: reqwest::blocking::Response) -> ApiResult<ApiEnvelope> {
    let status = resp.status().as_u16();
    let text = resp.text().map_err(|e| ApiError::Network {
        url: String::new(),
        source: e,
    })?;
    let env: ApiEnvelope = serde_json::from_str(&text).map_err(|e| ApiError::Server {
        status,
        body: format!("non-JSON response: {e}"),
    })?;
    if status == 401 || env.code == 401 {
        return Err(ApiError::Unauthorized {
            scene: env.scene(),
            msg: if env.msg.is_empty() {
                crate::api::model::scene_message(env.scene()).to_string()
            } else {
                env.msg
            },
        });
    }
    if status == 403 || env.code == 403 {
        return Err(ApiError::Forbidden);
    }
    if (400..500).contains(&status) {
        // 业务拒绝:414 验证码 / 415 手机号未注册 / 416 发送频率 / 417 短信码 等
        return Err(ApiError::Rejected {
            code: env.code,
            msg: env.msg,
        });
    }
    if !(200..300).contains(&status) {
        return Err(ApiError::Server {
            status,
            body: text.chars().take(200).collect(),
        });
    }
    Ok(env)
}

fn expect_ok(env: &ApiEnvelope, what: &str) -> ApiResult<()> {
    if env.code != 200 {
        return Err(ApiError::Server {
            status: 200,
            body: format!("{what}: business code {}", env.code),
        });
    }
    Ok(())
}

/// 网络错误与 5xx 短重试(300ms 起指数退避),保持 CLI 响应性。
fn with_retry<T, F>(mut f: F) -> ApiResult<T>
where
    F: FnMut() -> Result<T, ApiError>,
{
    let mut attempt: u32 = 0;
    loop {
        match f() {
            Ok(v) => return Ok(v),
            Err(e) if attempt + 1 < RETRY_ATTEMPTS && e.is_retryable() => {
                attempt += 1;
                std::thread::sleep(Duration::from_millis(300 * (1 << attempt)));
            }
            Err(e) => return Err(e),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn profile_with(base: String) -> Profile {
        Profile {
            api_base: base,
            ..Default::default()
        }
    }

    #[test]
    fn url_join_strips_trailing_slash() {
        let c = SatokenClient::new(&profile_with("http://127.0.0.1:9/".into())).unwrap();
        assert_eq!(c.url("/api/user/me"), "http://127.0.0.1:9/api/user/me");
        let c2 = SatokenClient::new(&profile_with("http://127.0.0.1:9".into())).unwrap();
        assert_eq!(c2.url("/api/user/me"), "http://127.0.0.1:9/api/user/me");
    }

    #[test]
    fn invalid_api_base_rejected() {
        assert!(SatokenClient::new(&profile_with("::::".into())).is_err());
    }
}

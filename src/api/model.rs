//! Sa-Token 契约 DTO。字段名与 server(和 docs/api-contract.md)严格一致(camelCase)。

use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize)]
pub struct LoginRequest<'a> {
    pub username: &'a str,
    pub password: &'a str,
    /// 图形验证码(服务端 required=true 时必填);None 时字段整体省略
    #[serde(skip_serializing_if = "Option::is_none", rename = "captchaId")]
    pub captcha_id: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none", rename = "captchaCode")]
    pub captcha_code: Option<&'a str>,
}

/// GET /api/auth/captcha 的 data。
#[derive(Debug, Clone, Deserialize)]
pub struct CaptchaInfo {
    #[serde(rename = "captchaId", alias = "captcha_id", default)]
    pub captcha_id: String,
    /// base64 data URI(png),解码后落盘即可展示
    pub image: String,
    /// 仅服务端 debug-echo 开启时存在(demo 模式回显答案),生产为空
    #[serde(rename = "debugCode", alias = "debug_code", default)]
    pub debug_code: String,
}

/// POST /api/auth/sms/send 请求体。
#[derive(Debug, Serialize)]
pub struct SmsSendRequest<'a> {
    pub phone: &'a str,
}

/// POST /api/auth/sms/send 的 data。
#[derive(Debug, Clone, Deserialize)]
pub struct SmsSendData {
    /// 仅服务端 debug-echo 开启时存在(demo 模式)
    #[serde(rename = "debugCode", alias = "debug_code", default)]
    pub debug_code: String,
}

/// POST /api/auth/sms/login 请求体。
#[derive(Debug, Serialize)]
pub struct SmsLoginRequest<'a> {
    pub phone: &'a str,
    pub code: &'a str,
}

/// StpUtil.getTokenInfo() 的响应(SaTokenInfo)。
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct SaTokenInfo {
    #[serde(rename = "tokenName", alias = "token_name")]
    pub token_name: String,
    #[serde(rename = "tokenValue", alias = "token_value")]
    pub token_value: String,
    #[serde(rename = "loginId", alias = "login_id", default)]
    pub login_id: String,
    #[serde(rename = "loginType", alias = "login_type", default)]
    pub login_type: String,
    #[serde(
        rename = "loginDevice",
        alias = "login_device",
        alias = "loginDeviceType",
        default
    )]
    pub login_device: String,
    /// 秒;-1 = 永久
    #[serde(rename = "tokenTimeout", alias = "token_timeout", default)]
    pub token_timeout: i64,
    #[serde(rename = "activeTimeout", alias = "active_timeout", default)]
    pub active_timeout: i64,
    #[serde(rename = "sessionTimeout", alias = "session_timeout", default)]
    pub session_timeout: i64,
}

/// /api/user/me 的 data。
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct UserInfo {
    #[serde(rename = "loginId", alias = "login_id")]
    pub login_id: String,
    pub username: String,
    #[serde(default)]
    pub roles: Vec<String>,
    #[serde(default)]
    pub device: String,
}

/// POST /api/auth/device/code 的 data(字段命名对齐 RFC 8628)。
#[derive(Debug, Clone, Deserialize)]
pub struct DeviceCodeInfo {
    #[serde(rename = "deviceCode", alias = "device_code", default)]
    pub device_code: String,
    #[serde(rename = "userCode", alias = "user_code", default)]
    pub user_code: String,
    /// 授权页地址(服务端按请求 Host 推导)
    #[serde(rename = "verificationUri", alias = "verification_uri", default)]
    pub verification_uri: String,
    /// 预填 user_code 的完整地址,优先使用
    #[serde(
        rename = "verificationUriComplete",
        alias = "verification_uri_complete",
        default
    )]
    pub verification_uri_complete: String,
    /// 设备码剩余有效期(秒)
    #[serde(rename = "expiresIn", alias = "expires_in", default)]
    pub expires_in: u64,
    /// 建议轮询间隔(秒)
    #[serde(default)]
    pub interval: u64,
}

/// POST /api/auth/device/token 请求体。
#[derive(Debug, Serialize)]
pub struct DeviceCodeRequest<'a> {
    #[serde(rename = "deviceCode")]
    pub device_code: &'a str,
}

/// POST /api/auth/device/token 的 data:status = pending|ok|denied|expired|invalid。
/// ok 时携带 token = CLI 专属新登录会话(服务端 device=cli)。
#[derive(Debug, Clone, Deserialize)]
pub struct DeviceTokenData {
    #[serde(default)]
    pub status: String,
    #[serde(default)]
    pub token: Option<SaTokenInfo>,
}

/// 统一返回包装。成功:data = 业务对象;失败(如 401):data = {"scene": -1}。
/// 因此 data 用 serde_json::Value 承载,由调用侧按语境解析。
#[derive(Debug, Deserialize)]
pub struct ApiEnvelope {
    pub code: i32,
    #[serde(default)]
    pub msg: String,
    #[serde(default)]
    pub data: Option<serde_json::Value>,
}

impl ApiEnvelope {
    pub fn scene(&self) -> Option<i32> {
        self.data
            .as_ref()
            .and_then(|d| d.get("scene"))
            .and_then(|s| s.as_i64())
            .map(|s| s as i32)
    }
}

/// scene 语义(Sa-Token NotLoginException.getType())。
pub fn scene_message(scene: Option<i32>) -> &'static str {
    match scene {
        Some(-1) => "no token provided",
        Some(-2) => "token invalid",
        Some(-3) => "token expired",
        Some(-4) => "token replaced by another login",
        Some(-5) => "token kicked out",
        _ => "not logged in",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_login_success() {
        let body = r#"{"code":200,"msg":"ok","data":{"tokenName":"satoken","tokenValue":"uuid-1","loginId":"1","loginType":"login","loginDevice":"default","tokenTimeout":2592000,"activeTimeout":-1,"sessionTimeout":2592000}}"#;
        let env: ApiEnvelope = serde_json::from_str(body).unwrap();
        assert_eq!(env.code, 200);
        let info: SaTokenInfo = serde_json::from_value(env.data.unwrap()).unwrap();
        assert_eq!(info.token_name, "satoken");
        assert_eq!(info.token_value, "uuid-1");
        assert_eq!(info.token_timeout, 2592000);
    }

    #[test]
    fn parse_unauthorized_with_scene() {
        let body = r#"{"code":401,"msg":"no valid token","data":{"scene":-3}}"#;
        let env: ApiEnvelope = serde_json::from_str(body).unwrap();
        assert_eq!(env.scene(), Some(-3));
        assert_eq!(scene_message(env.scene()), "token expired");
    }

    #[test]
    fn parse_user_info() {
        let body = r#"{"code":200,"msg":"ok","data":{"loginId":"1","username":"alice","roles":["user"],"device":"default"}}"#;
        let env: ApiEnvelope = serde_json::from_str(body).unwrap();
        let u: UserInfo = serde_json::from_value(env.data.unwrap()).unwrap();
        assert_eq!(u.username, "alice");
        assert_eq!(u.roles, vec!["user"]);
    }

    #[test]
    fn parse_device_code_info() {
        let body = r#"{"code":200,"msg":"ok","data":{"deviceCode":"dc-1","userCode":"BDMK-MJHT","verificationUri":"http://h/","verificationUriComplete":"http://h/?user_code=BDMK-MJHT","expiresIn":900,"interval":3}}"#;
        let env: ApiEnvelope = serde_json::from_str(body).unwrap();
        let c: DeviceCodeInfo = serde_json::from_value(env.data.unwrap()).unwrap();
        assert_eq!(c.user_code, "BDMK-MJHT");
        assert_eq!(c.expires_in, 900);
        assert_eq!(c.interval, 3);
    }

    #[test]
    fn parse_device_token_pending_and_ok() {
        let pending: DeviceTokenData = serde_json::from_str(r#"{"status":"pending"}"#).unwrap();
        assert_eq!(pending.status, "pending");
        assert!(pending.token.is_none());

        let ok: DeviceTokenData = serde_json::from_str(
            r#"{"status":"ok","token":{"tokenName":"satoken","tokenValue":"t-1","loginId":"2","tokenTimeout":2592000}}"#,
        )
        .unwrap();
        assert_eq!(ok.status, "ok");
        let token = ok.token.expect("token present on ok");
        assert_eq!(token.token_value, "t-1");
        assert_eq!(token.login_id, "2");
    }
}

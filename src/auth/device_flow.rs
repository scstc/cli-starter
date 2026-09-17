//! 设备码登录(RFC 8628 精简版):CLI 申请设备码 -> 拉起浏览器到授权页 ->
//! 用户在网页登录并确认 -> CLI 按间隔轮询换取自己的新 token。
//! 服务端端点见 docs/api-contract.md 设备码章节。

use std::io::Write as _;
use std::time::{Duration, Instant};

use crate::api::model::DeviceCodeInfo;
use crate::auth::credential::Credential;
use crate::auth::flow::{credential_via_whoami, LoginContext, LoginFlow};
use crate::error::{AppError, AppResult, AuthError};

/// 轮询间隔钳制上限(秒);下限 1,防服务端误配为 0 打爆接口。
const MAX_INTERVAL_SECS: u64 = 30;

pub struct DeviceCodeFlow;

impl LoginFlow for DeviceCodeFlow {
    fn name(&self) -> &'static str {
        "device"
    }

    fn label(&self) -> &'static str {
        "Log in with a web browser (device code)"
    }

    fn run(&self, ctx: &LoginContext) -> AppResult<Credential> {
        let code = ctx.client.device_code()?;
        let open_url = verification_url(&code).to_string();

        eprintln!();
        eprintln!("  Authorize at: {open_url}");
        eprintln!();
        eprintln!("  One-time code: {}", code.user_code);
        eprintln!();
        crate::open::open_with_default_app(&open_url);

        let interval = code.interval.clamp(1, MAX_INTERVAL_SECS);
        let deadline = Instant::now() + Duration::from_secs(code.expires_in.max(1));
        eprintln!(
            "[..] waiting for authorization in your browser (expires in {}s)",
            code.expires_in
        );
        loop {
            if Instant::now() >= deadline {
                return Err(AuthError::Failed("device code expired before approval".into()).into());
            }
            std::thread::sleep(Duration::from_secs(interval));
            let poll = ctx.client.device_token(&code.device_code)?;
            match poll.status.as_str() {
                "ok" => {
                    eprintln!();
                    let info = poll.token.ok_or_else(|| {
                        AppError::from(AuthError::Failed(
                            "server replied ok but without a token".into(),
                        ))
                    })?;
                    return credential_via_whoami(ctx, info);
                }
                "pending" => {
                    eprint!(".");
                    std::io::stderr().flush().ok();
                }
                "denied" => {
                    return Err(
                        AuthError::Failed("authorization denied on the web page".into()).into(),
                    );
                }
                "expired" => {
                    return Err(AuthError::Failed("device code expired".into()).into());
                }
                other => {
                    return Err(
                        AuthError::Failed(format!("unexpected poll status `{other}`")).into(),
                    );
                }
            }
        }
    }
}

/// 优先带 user_code 的完整地址,回退基础授权页。
fn verification_url(code: &DeviceCodeInfo) -> &str {
    if code.verification_uri_complete.is_empty() {
        &code.verification_uri
    } else {
        &code.verification_uri_complete
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn code(complete: &str, base: &str) -> DeviceCodeInfo {
        DeviceCodeInfo {
            device_code: "dc-1".into(),
            user_code: "BDMK-MJHT".into(),
            verification_uri: base.into(),
            verification_uri_complete: complete.into(),
            expires_in: 900,
            interval: 3,
        }
    }

    #[test]
    fn prefers_complete_url() {
        let c = code("http://h/?user_code=BDMK-MJHT", "http://h/");
        assert_eq!(verification_url(&c), "http://h/?user_code=BDMK-MJHT");
    }

    #[test]
    fn falls_back_to_plain_uri() {
        let c = code("", "http://h/");
        assert_eq!(verification_url(&c), "http://h/");
    }
}

//! 手机号 + 短信验证码登录:send -> 输入短信码 -> 换 token。
//! demo 服务端会把验证码回显在 send 响应/服务端日志里;生产接真实短信后用户查收手机。

use crate::auth::credential::Credential;
use crate::auth::flow::{credential_via_whoami, LoginContext, LoginFlow};
use crate::error::{ApiError, AppResult, AuthError};

const MAX_ATTEMPTS: u32 = 3;

pub struct SmsLoginFlow;

impl LoginFlow for SmsLoginFlow {
    fn name(&self) -> &'static str {
        "sms"
    }

    fn label(&self) -> &'static str {
        "Phone number + SMS code"
    }

    fn run(&self, ctx: &LoginContext) -> AppResult<Credential> {
        let phone = match ctx.default_username {
            // `--username` 语义顺延为"预先填好的账号标识",短信流里即手机号
            Some(p) if !p.is_empty() => p.to_string(),
            _ => ctx.prompter.input("Phone number", None)?,
        };

        match ctx.client.sms_send(&phone) {
            Ok(data) => {
                if data.debug_code.is_empty() {
                    eprintln!(
                        "[..] SMS code sent (check your phone; demo server prints it to its log)"
                    );
                } else {
                    eprintln!(
                        "[demo] server echoed the SMS code: {} (debug-echo enabled)",
                        data.debug_code
                    );
                }
            }
            Err(ApiError::Rejected { code: 415, .. }) => {
                return Err(AuthError::Failed("phone number not registered".into()).into());
            }
            Err(ApiError::Rejected { code: 416, .. }) => {
                return Err(AuthError::Failed(
                    "sms send too frequent; wait a minute and retry".into(),
                )
                .into());
            }
            Err(e) => return Err(e.into()),
        }

        for _ in 0..MAX_ATTEMPTS {
            let code = ctx.prompter.input("SMS code", None)?;
            match ctx.client.sms_login(&phone, &code) {
                Ok(info) => return credential_via_whoami(ctx, info),
                Err(ApiError::Rejected { code: 417, .. }) => {
                    eprintln!("[warn] invalid or expired SMS code, try again");
                }
                Err(ApiError::Rejected { code: 415, .. }) => {
                    return Err(AuthError::Failed("phone number not registered".into()).into());
                }
                Err(e) => return Err(e.into()),
            }
        }
        Err(AuthError::Failed("sms login failed".into()).into())
    }
}

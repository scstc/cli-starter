//! 用户名 + 密码登录。401 重试密码、414 重取验证码,各至多 3 次。
//! 服务端要求图形验证码时:拉取 -> PNG 落盘并用系统看图程序打开 -> 提示输入;
//! 服务端没有验证码端点(404)则跳过,兼容未开验证码的后端。

use base64::Engine as _;

use crate::api::model::SaTokenInfo;
use crate::auth::credential::Credential;
use crate::auth::flow::{LoginContext, LoginFlow};
use crate::error::{ApiError, AppError, AppResult, AuthError};

const MAX_ATTEMPTS: u32 = 3;

pub struct PasswordFlow;

impl LoginFlow for PasswordFlow {
    fn name(&self) -> &'static str {
        "password"
    }

    fn label(&self) -> &'static str {
        "Username + password"
    }

    fn run(&self, ctx: &LoginContext) -> AppResult<Credential> {
        let username = match ctx.default_username {
            Some(u) if !u.is_empty() => u.to_string(),
            _ => ctx.prompter.input("Username", None)?,
        };
        for _ in 0..MAX_ATTEMPTS {
            let password = ctx.prompter.hidden("Password")?;
            let captcha = fetch_captcha_for_login(ctx)?;
            let captcha = captcha
                .as_ref()
                .map(|(id, code)| (id.as_str(), code.as_str()));
            match ctx.client.login_password(&username, &password, captcha) {
                Ok(info) => return Ok(credential_from(info, &username, ctx)),
                Err(ApiError::Unauthorized { .. }) => {
                    eprintln!("[warn] invalid username or password, try again");
                }
                Err(ApiError::Rejected { code: 414, .. }) => {
                    eprintln!("[warn] captcha invalid or expired, try again");
                }
                Err(e) => return Err(e.into()),
            }
        }
        Err(AppError::Auth(AuthError::InvalidCredentials))
    }
}

/// 服务端要求验证码时返回 (captchaId, 用户输入);未开启/无端点(404)返回 None。
fn fetch_captcha_for_login(ctx: &LoginContext<'_>) -> AppResult<Option<(String, String)>> {
    let Some(cap) = ctx.client.captcha()? else {
        return Ok(None);
    };
    match save_captcha_png(&cap.image) {
        Ok(Some(path)) => {
            crate::open::open_with_default_app(&path);
            eprintln!("[..] captcha image saved at {path}");
        }
        Ok(None) => {}
        Err(e) => eprintln!(
            "[warn] could not save captcha image ({e}); read it from the web page instead"
        ),
    }
    if !cap.debug_code.is_empty() {
        eprintln!(
            "[demo] server echoed the captcha answer: {} (debug-echo enabled)",
            cap.debug_code
        );
    }
    let code = ctx.prompter.input("Captcha code (see the image)", None)?;
    Ok(Some((cap.captcha_id, code)))
}

/// data URI -> 临时目录 PNG;非 base64 data URI 返回 Ok(None)。
fn save_captcha_png(data_uri: &str) -> AppResult<Option<String>> {
    const MARKER: &str = ";base64,";
    let Some(pos) = data_uri.find(MARKER) else {
        return Ok(None);
    };
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(&data_uri[pos + MARKER.len()..])
        .map_err(|e| AppError::Other(format!("captcha image: {e}")))?;
    let path = std::env::temp_dir().join("cli-starter-captcha.png");
    std::fs::write(&path, bytes)?;
    Ok(Some(path.to_string_lossy().into_owned()))
}

pub(crate) fn credential_from(info: SaTokenInfo, account: &str, ctx: &LoginContext) -> Credential {
    let token_name = if info.token_name.is_empty() {
        ctx.profile.token_header.clone()
    } else {
        info.token_name
    };
    Credential {
        account: account.to_string(),
        login_id: info.login_id.clone(),
        token_name,
        token_value: info.token_value,
        api_base: ctx.profile.api_base.clone(),
        token_timeout: info.token_timeout,
        obtained_at_unix: time::OffsetDateTime::now_utc().unix_timestamp(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn saves_png_from_data_uri() {
        // "hi" -> base64 "aGk="
        let path = save_captcha_png("data:image/png;base64,aGk=")
            .unwrap()
            .unwrap();
        assert!(path.ends_with("cli-starter-captcha.png"));
        let written = std::fs::read(&path).unwrap();
        assert_eq!(written, b"hi");
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn non_base64_image_returns_none() {
        assert!(save_captcha_png("http://example.com/captcha.png")
            .unwrap()
            .is_none());
    }
}

//! 粘贴已有 token 登录:输入后立即带证调 /api/user/me 校验,成功才入库。

use crate::auth::credential::Credential;
use crate::auth::flow::{LoginContext, LoginFlow};
use crate::error::{AppError, AppResult, AuthError};

const MAX_ATTEMPTS: u32 = 3;

pub struct TokenPasteFlow;

impl LoginFlow for TokenPasteFlow {
    fn name(&self) -> &'static str {
        "token-paste"
    }

    fn label(&self) -> &'static str {
        "Paste an existing token"
    }

    fn run(&self, ctx: &LoginContext) -> AppResult<Credential> {
        for _ in 0..MAX_ATTEMPTS {
            let token = ctx.prompter.hidden("Paste your token")?;
            match run_with_token(&token, ctx) {
                Ok(cred) => return Ok(cred),
                Err(AppError::Api(crate::error::ApiError::Unauthorized { scene, .. })) => {
                    eprintln!(
                        "[warn] token rejected ({}), paste again",
                        crate::api::model::scene_message(scene)
                    );
                }
                Err(e) => return Err(e),
            }
        }
        Err(AppError::Auth(AuthError::InvalidCredentials))
    }
}

/// gh --with-token 语义:token 已由外部提供(stdin),只做校验,不再提示。
pub fn run_with_token(token: &str, ctx: &LoginContext) -> AppResult<Credential> {
    let token = token.trim();
    if token.is_empty() {
        return Err(AppError::Auth(AuthError::Failed("empty token".into())));
    }
    let user = ctx
        .client
        .whoami(&ctx.profile.token_header, token)
        .map_err(AppError::from)?;
    Ok(Credential {
        account: user.username,
        login_id: user.login_id,
        token_name: ctx.profile.token_header.clone(),
        token_value: token.to_string(),
        api_base: ctx.profile.api_base.clone(),
        token_timeout: 0,
        obtained_at_unix: time::OffsetDateTime::now_utc().unix_timestamp(),
    })
}

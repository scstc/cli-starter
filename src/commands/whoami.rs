//! `whoami`:带证调用 GET /api/user/me,证明完整登录链路。

use crate::api::SatokenClient;
use crate::auth::chain::CredentialChain;
use crate::auth::store::LoginTarget;
use crate::config::Config;
use crate::error::{AppError, AppResult, AuthError};

pub fn run(json: bool, profile_name: Option<&str>) -> AppResult<()> {
    let dir = Config::dir();
    let config = Config::load(&dir)?;
    let (_, profile) = config.resolve(profile_name)?;
    if profile.api_base.is_empty() {
        return Err(AuthError::NotLoggedIn.into());
    }

    let chain = CredentialChain::open(&dir);
    let target = LoginTarget::from_api_base(&profile.api_base)?;
    let effective = chain
        .effective(&target, profile)
        .map_err(AppError::from)?
        .ok_or(AuthError::NotLoggedIn)?;
    let cred = effective.cred;

    let client = SatokenClient::new(profile)?;
    let user = client
        .whoami(&cred.token_name, &cred.token_value)
        .map_err(unauthorized_as_auth)?;

    if json {
        println!("{}", serde_json::to_string_pretty(&user).unwrap());
    } else {
        println!(
            "Logged in as {} (loginId {}, roles: {}, device: {})",
            user.username,
            user.login_id,
            if user.roles.is_empty() {
                "-".to_string()
            } else {
                user.roles.join(",")
            },
            if user.device.is_empty() {
                "default".into()
            } else {
                user.device
            }
        );
        if effective.mode == crate::auth::chain::StorageMode::Env {
            eprintln!("(credential from environment: CLI_STARTER_TOKEN)");
        }
    }
    Ok(())
}

/// whoami 的 401 是"凭证问题"而非"网络问题",映射到认证错误(退出码 4)。
fn unauthorized_as_auth(e: crate::error::ApiError) -> AppError {
    match e {
        crate::error::ApiError::Unauthorized { scene, .. } => AuthError::Failed(format!(
            "token rejected ({})",
            crate::api::model::scene_message(scene)
        ))
        .into(),
        other => other.into(),
    }
}

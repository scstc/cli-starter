//! `auth token`:打印当前实际生效 token(env > keyring > file),stdout 仅含 token 本体。

use crate::auth::chain::CredentialChain;
use crate::auth::store::LoginTarget;
use crate::config::Config;
use crate::error::{AppError, AppResult, AuthError};

pub fn run(account: Option<String>, profile_name: Option<&str>) -> AppResult<()> {
    let dir = Config::dir();
    let config = Config::load(&dir)?;
    let (_, profile) = config.resolve(profile_name)?;
    if profile.api_base.is_empty() {
        return Err(AppError::Other(
            "no api_base configured; run `cli-starter auth login` first".into(),
        ));
    }

    let chain = CredentialChain::open(&dir);
    let target = LoginTarget::from_api_base(&profile.api_base)?;

    let cred = if let Some(account) = account {
        if chain.env_protected() {
            return Err(AuthError::EnvTokenProtected.into());
        }
        chain
            .store()
            .get(&target, &account)
            .map_err(AppError::from)?
            .ok_or_else(|| AppError::Other(format!("not logged in as `{account}`")))?
    } else {
        chain
            .effective(&target, profile)
            .map_err(AppError::from)?
            // 计划约定:auth token 无凭证退 1(区别于 whoami 的 4)
            .ok_or_else(|| {
                AppError::Other("not logged in (run `cli-starter auth login` first)".into())
            })?
            .cred
    };

    println!("{}", cred.token_value);
    Ok(())
}

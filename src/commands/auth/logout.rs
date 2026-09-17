//! `auth logout`:仅本地删凭证,不调服务端(对齐 gh;离线/服务端不可达也要可用)。

use crate::auth::chain::CredentialChain;
use crate::auth::env_source;
use crate::auth::store::LoginTarget;
use crate::config::Config;
use crate::error::{AppError, AppResult, AuthError};

pub fn run(account: Option<String>, all: bool, profile_name: Option<&str>) -> AppResult<()> {
    if env_source::read().is_some() {
        return Err(AuthError::EnvTokenProtected.into());
    }

    let dir = Config::dir();
    let mut config = Config::load(&dir)?;
    let (pname, profile) = {
        let (n, p) = config.resolve(profile_name)?;
        (n.to_string(), p.clone())
    };
    if profile.api_base.is_empty() {
        eprintln!("[ok] nothing to log out (no api_base configured)");
        return Ok(());
    }

    let chain = CredentialChain::open(&dir);
    let target = LoginTarget::from_api_base(&profile.api_base)?;

    if all {
        let removed = chain.store().clear(&target).map_err(AppError::from)?;
        println!("[ok] removed {removed} credential(s) (local only; server token NOT revoked)");
        if let Some(p) = config.profiles.get_mut(&pname) {
            p.last_account = None;
        }
        config.save(&dir)?;
        return Ok(());
    }

    // 默认操作激活账号
    let account = match account {
        Some(a) => a,
        None => match chain.store().get_active(&target).map_err(AppError::from)? {
            Some(cred) => cred.account,
            None => {
                eprintln!("[ok] nothing to log out (no stored credential)");
                return Ok(());
            }
        },
    };

    let existed = chain
        .store()
        .delete(&target, &account)
        .map_err(AppError::from)?;
    if !existed {
        return Err(AppError::Other(format!("not logged in as `{account}`")));
    }
    if profile.last_account.as_deref() == Some(account.as_str()) {
        if let Some(p) = config.profiles.get_mut(&pname) {
            p.last_account = None;
        }
        config.save(&dir)?;
    }
    println!("[ok] removed credential for `{account}` (local only; server token NOT revoked)");
    Ok(())
}

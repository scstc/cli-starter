//! `auth status`:逐账号调 /api/user/me 校验,token 掩码显示;任一失败退 1;--json 恒退 0。

use serde_json::json;

use crate::api::{model::scene_message, SatokenClient};
use crate::auth::chain::{CredentialChain, EffectiveCredential};
use crate::auth::credential::mask;
use crate::auth::store::LoginTarget;
use crate::config::Config;
use crate::error::{AppError, AppResult, AuthError};

pub fn run(json: bool, profile_name: Option<&str>) -> AppResult<()> {
    let dir = Config::dir();
    let config = Config::load(&dir)?;
    let (_, profile) = config.resolve(profile_name)?;
    if profile.api_base.is_empty() {
        let msg = "no api_base configured; run `cli-starter auth login` first";
        if json {
            print_json(&[status_entry("(none)", "(none)", false, Some(msg))]);
            return Ok(());
        }
        return Err(AppError::Other(msg.to_string()));
    }

    let client = SatokenClient::new(profile)?;
    let chain = CredentialChain::open(&dir);
    let target = LoginTarget::from_api_base(&profile.api_base)?;

    let mut entries: Vec<serde_json::Value> = Vec::new();
    let mut any_failed = false;

    // env 凭证
    if let Some(EffectiveCredential { cred, mode }) =
        chain.effective(&target, profile).map_err(AppError::from)?
    {
        if mode == crate::auth::chain::StorageMode::Env {
            let valid = client.whoami(&cred.token_name, &cred.token_value).is_ok();
            if !valid {
                any_failed = true;
            }
            entries.push(status_entry(
                &cred.account,
                mode.label(),
                valid,
                if valid {
                    None
                } else {
                    Some("token rejected by server")
                },
            ));
        }
    }
    // 本地存储的账号
    let accounts = chain
        .store()
        .list_accounts(&target)
        .map_err(AppError::from)?;
    if accounts.is_empty() && entries.is_empty() {
        let msg = "not logged in";
        if json {
            print_json(&[status_entry("(none)", "none", false, Some(msg))]);
            return Ok(());
        }
        return Err(AuthError::NotLoggedIn.into());
    }
    for account in accounts {
        let Some(cred) = chain
            .store()
            .get(&target, &account)
            .map_err(AppError::from)?
        else {
            continue;
        };
        let outcome = client.whoami(&cred.token_name, &cred.token_value);
        let (valid, err): (bool, Option<String>) = match &outcome {
            Ok(_) => (true, None),
            Err(crate::error::ApiError::Unauthorized { scene, .. }) => {
                (false, Some(scene_message(*scene).to_string()))
            }
            Err(e) => (false, Some(e.to_string())),
        };
        if !valid {
            any_failed = true;
        }
        entries.push(status_entry(
            &account,
            &format!("token {}", mask(&cred.token_value)),
            valid,
            err.as_deref(),
        ));
    }

    if json {
        print_json(&entries);
        return Ok(());
    }
    for e in &entries {
        let mark = if e["valid"].as_bool().unwrap_or(false) {
            "[ok]"
        } else {
            "[!!]"
        };
        let detail = e["detail"]
            .as_str()
            .filter(|s| !s.is_empty())
            .map(|s| format!(" -- {s}"))
            .unwrap_or_default();
        println!(
            "{mark} {} ({}){detail}",
            e["account"].as_str().unwrap_or("?"),
            e["source"].as_str().unwrap_or("?")
        );
    }
    if any_failed {
        return Err(AppError::Other(
            "one or more credentials are invalid".into(),
        ));
    }
    Ok(())
}

fn status_entry(
    account: &str,
    source: &str,
    valid: bool,
    detail: Option<&str>,
) -> serde_json::Value {
    json!({
        "account": account,
        "source": source,
        "valid": valid,
        "detail": detail.unwrap_or(""),
    })
}

fn print_json(entries: &[serde_json::Value]) {
    println!("{}", serde_json::to_string_pretty(entries).unwrap());
}

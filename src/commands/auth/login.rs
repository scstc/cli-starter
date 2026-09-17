//! `auth login`:env 写保护 -> 首启引导 api_base -> 选方式 -> Flow -> 存凭证 -> 回写配置。

use std::io::Read;

use crate::api::SatokenClient;
use crate::auth::chain::CredentialChain;
use crate::auth::env_source;
use crate::auth::flow::{self, LoginContext};
use crate::auth::store::LoginTarget;
use crate::auth::token_paste_flow;
use crate::cli::{LoginArgs, DEFAULT_DIRECT_BASE, DEFAULT_GATEWAY_BASE};
use crate::config::{validate_api_base, Config};
use crate::error::{AppError, AppResult, AuthError};
use crate::term::detect_prompter;

pub fn run(args: &LoginArgs, profile_name: Option<&str>) -> AppResult<()> {
    // 1. env 凭证写保护(对齐 gh:GH_TOKEN 存在时拒绝 login/logout)
    if env_source::read().is_some() {
        return Err(AuthError::EnvTokenProtected.into());
    }

    // 2. 加载配置与 profile(立即克隆,避免后续回写时借用冲突)
    let dir = Config::dir();
    let mut config = Config::load(&dir)?;
    let (pname, base_profile) = config.resolve(profile_name)?;
    let pname = pname.to_string();
    let mut profile = base_profile.clone();

    // 3. api_base:--api-base > 已配置 > 首启引导
    let prompter = detect_prompter();
    if let Some(base) = &args.api_base {
        validate_api_base(base).map_err(|e| AppError::Other(format!("--api-base: {e}")))?;
        profile.api_base = base.clone();
    } else if profile.api_base.is_empty() {
        profile.api_base = bootstrap_api_base(prompter.as_ref())?;
    }

    // 4. 客户端 + 登录上下文
    let client = SatokenClient::new(&profile)?;
    let default_username = args
        .username
        .clone()
        .or_else(|| profile.last_account.clone());
    let ctx = LoginContext {
        client: &client,
        prompter: prompter.as_ref(),
        profile: &profile,
        default_username: default_username.as_deref(),
    };

    // 5. 执行登录方式
    let cred = if args.with_token {
        let mut buf = String::new();
        std::io::stdin().read_to_string(&mut buf)?;
        token_paste_flow::run_with_token(&buf, &ctx)?
    } else if let Some(method) = &args.method {
        let flow = flow::find(method)
            .ok_or_else(|| AppError::Other(format!("unknown login method `{method}`")))?;
        flow.run(&ctx)?
    } else {
        let flows = flow::registry();
        let labels: Vec<&str> = flows.iter().map(|f| f.label()).collect();
        let idx = prompter.select("Login method", &labels)?;
        flows[idx].run(&ctx)?
    };

    // 6. 存凭证(账号槽 + 激活槽)
    let chain = CredentialChain::open(&dir);
    let target = LoginTarget::from_api_base(&profile.api_base)?;
    chain.store().set(&target, &cred.account, &cred)?;
    chain.store().set_active(&target, &cred)?;

    // 7. 回写配置
    if !cred.token_name.is_empty() {
        profile.token_header = cred.token_name.clone();
    }
    profile.last_account = Some(cred.account.clone());
    config.profiles.insert(pname.clone(), profile.clone());
    config.save(&dir)?;
    // 8. 收尾
    let expiry = match cred.expires_at_date() {
        Some(d) => format!(" (token expires {d})"),
        None => " (token validity unknown or permanent)".to_string(),
    };
    println!(
        "[ok] Logged in to {} as {}{}",
        profile.api_base, cred.account, expiry
    );
    eprintln!("Run `cli-starter whoami` to verify.");
    Ok(())
}

fn bootstrap_api_base(prompter: &dyn crate::term::Prompter) -> AppResult<String> {
    let items = [
        format!("Via Higress gateway (default {DEFAULT_GATEWAY_BASE})"),
        format!("Direct backend, bypass gateway (default {DEFAULT_DIRECT_BASE})"),
        "Custom URL".to_string(),
    ];
    let refs: Vec<&str> = items.iter().map(|s| s.as_str()).collect();
    let choice = prompter.select("How do you reach the auth server?", &refs)?;
    let url = match choice {
        0 => DEFAULT_GATEWAY_BASE.to_string(),
        1 => DEFAULT_DIRECT_BASE.to_string(),
        _ => loop {
            let input = prompter.input("API base URL (e.g. http://localhost:28080)", None)?;
            match validate_api_base(&input) {
                Ok(()) => break input,
                Err(e) => eprintln!("[warn] {e}"),
            }
        },
    };
    Ok(url)
}

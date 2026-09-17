//! LoginFlow 抽象:新登录方式 = 实现该 trait + 在 registry() 注册。

use crate::api::SatokenClient;
use crate::auth::credential::Credential;
use crate::config::Profile;
use crate::error::AppResult;
use crate::term::Prompter;

pub struct LoginContext<'a> {
    pub client: &'a SatokenClient,
    pub prompter: &'a dyn Prompter,
    pub profile: &'a Profile,
    pub default_username: Option<&'a str>,
}

pub trait LoginFlow {
    /// 稳定标识,对应 `--method` 的取值
    fn name(&self) -> &'static str;
    /// Select 菜单里展示的标签
    fn label(&self) -> &'static str;
    fn run(&self, ctx: &LoginContext) -> AppResult<Credential>;
}

pub fn registry() -> Vec<Box<dyn LoginFlow>> {
    vec![
        Box::new(crate::auth::password_flow::PasswordFlow),
        Box::new(crate::auth::sms_flow::SmsLoginFlow),
        Box::new(crate::auth::token_paste_flow::TokenPasteFlow),
        Box::new(crate::auth::device_flow::DeviceCodeFlow),
    ]
}

pub fn find(name: &str) -> Option<Box<dyn LoginFlow>> {
    // `--method token` 对应粘贴 token 流("token-paste");"phone" 是 "sms" 的别名
    let canonical = match name {
        "token" | "paste" => "token-paste",
        "phone" => "sms",
        other => other,
    };
    registry().into_iter().find(|f| f.name() == canonical)
}

/// 设备码/短信码等服务端登录响应不含用户名,用 whoami 补齐账号展示名后
/// 复用密码流的凭证构造。
pub(crate) fn credential_via_whoami(
    ctx: &LoginContext<'_>,
    info: crate::api::model::SaTokenInfo,
) -> AppResult<Credential> {
    let token_name = if info.token_name.is_empty() {
        ctx.profile.token_header.clone()
    } else {
        info.token_name.clone()
    };
    let user: crate::api::model::UserInfo = ctx.client.whoami(&token_name, &info.token_value)?;
    Ok(crate::auth::password_flow::credential_from(
        info,
        &user.username,
        ctx,
    ))
}

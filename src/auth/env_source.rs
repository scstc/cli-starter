//! CLI_STARTER_TOKEN 环境变量来源(优先级最高,且使 login/logout 写保护,对齐 gh)。
//! 取值:纯 token,或 "headerName:token"(如 "satoken:uuid")。

pub const ENV_TOKEN: &str = "CLI_STARTER_TOKEN";

/// (token_name;空串 = 用 profile 默认), (token_value)
pub fn read() -> Option<(String, String)> {
    let raw = std::env::var(ENV_TOKEN).ok()?;
    let raw = raw.trim();
    if raw.is_empty() {
        return None;
    }
    if let Some((name, value)) = raw.split_once(':') {
        let looks_like_header = !name.is_empty()
            && name.len() <= 32
            && !value.is_empty()
            && name
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
        if looks_like_header {
            return Some((name.to_string(), value.to_string()));
        }
    }
    Some((String::new(), raw.to_string()))
}

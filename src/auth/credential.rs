//! 凭证数据结构。token 落盘(file 回退)时为明文 JSON,文件头有警告,
//! file_store 负责提醒;keyring 路径下不落明文盘。

use serde::{Deserialize, Serialize};

/// 存储用凭证(serde 直接落 keyring/file)。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Credential {
    /// 展示名(username)
    pub account: String,
    #[serde(default)]
    pub login_id: String,
    /// 请求 header 名,默认 "satoken"
    pub token_name: String,
    pub token_value: String,
    pub api_base: String,
    /// 秒;-1 = 永久
    #[serde(default)]
    pub token_timeout: i64,
    /// 获取时间(unix 秒),用于计算有效期展示
    #[serde(default)]
    pub obtained_at_unix: i64,
}

impl Credential {
    /// "yyyy-mm-dd" 或 None(永久/未知)
    pub fn expires_at_date(&self) -> Option<String> {
        if self.token_timeout <= 0 {
            return None;
        }
        let base = if self.obtained_at_unix > 0 {
            time::OffsetDateTime::from_unix_timestamp(self.obtained_at_unix).ok()?
        } else {
            time::OffsetDateTime::now_utc()
        };
        let expires = base + time::Duration::seconds(self.token_timeout);
        let fmt = time::macros::format_description!("[year]-[month]-[day]");
        expires.format(&fmt).ok()
    }
}

/// token 掩码:保留前 4 + 后 4;过短只露前 4。
pub fn mask(token: &str) -> String {
    let chars: Vec<char> = token.chars().collect();
    if chars.len() <= 8 {
        let head: String = chars.into_iter().take(4).collect();
        return format!("{head}...");
    }
    let head: String = token.chars().take(4).collect();
    let tail: String = token.chars().skip(token.chars().count() - 4).collect();
    format!("{head}...{tail}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mask_long_token() {
        assert_eq!(mask("a1b2c3d4e5f6wxyz"), "a1b2...wxyz");
    }

    #[test]
    fn mask_short_token() {
        assert_eq!(mask("short"), "shor...");
        assert_eq!(mask("12345678"), "1234...");
    }

    #[test]
    fn expires_date_from_timeout() {
        let cred = Credential {
            account: "alice".into(),
            login_id: "1".into(),
            token_name: "satoken".into(),
            token_value: "t".into(),
            api_base: "http://x".into(),
            token_timeout: 86400,
            obtained_at_unix: 1760000000,
        };
        assert!(cred.expires_at_date().is_some());
        let forever = Credential {
            token_timeout: -1,
            ..cred
        };
        assert_eq!(forever.expires_at_date(), None);
    }
}

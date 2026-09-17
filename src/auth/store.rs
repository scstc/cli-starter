//! CredentialStore 抽象与 LoginTarget(service 命名对齐 gh 的 "gh:<hostname>" 风格)。

use crate::auth::credential::Credential;
use crate::error::StoreError;

/// 激活槽位的 keyring 用户名(不用空串,规避 keyring v1 对空账号的边界行为)
pub const ACTIVE_SLOT: &str = "__active__";
/// 账号索引的 keyring 用户名(keyring 无法枚举,自行维护账号列表)
const INDEX_SLOT: &str = "__index__";
pub const SERVICE_PREFIX: &str = "cli-starter";

/// 一个登录目标 = 一个 api_base(host 维度)。
#[derive(Debug, Clone)]
pub struct LoginTarget {
    pub host_key: String,
}

impl LoginTarget {
    pub fn from_api_base(api_base: &str) -> Result<Self, StoreError> {
        let url = url::Url::parse(api_base).map_err(|e| StoreError::Backend {
            backend: "target",
            message: format!("invalid api_base `{api_base}`: {e}"),
        })?;
        let host = url.host_str().unwrap_or_default();
        if host.is_empty() {
            return Err(StoreError::Backend {
                backend: "target",
                message: format!("api_base `{api_base}` has no host"),
            });
        }
        let port = url
            .port()
            .or_else(|| match url.scheme() {
                "https" => None, // 443 默认
                _ => None,
            })
            .map(|p| format!(":{p}"))
            .unwrap_or_default();
        Ok(Self {
            host_key: format!("{host}{port}"),
        })
    }

    /// keyring service 名:"cli-starter:<host[:port]>"
    pub fn service(&self) -> String {
        format!("{SERVICE_PREFIX}:{}", self.host_key)
    }

    pub fn index_user(&self) -> &'static str {
        INDEX_SLOT
    }
}

pub trait CredentialStore: Send + Sync {
    fn set(&self, target: &LoginTarget, account: &str, cred: &Credential)
        -> Result<(), StoreError>;
    fn set_active(&self, target: &LoginTarget, cred: &Credential) -> Result<(), StoreError>;
    fn get(&self, target: &LoginTarget, account: &str) -> Result<Option<Credential>, StoreError>;
    fn get_active(&self, target: &LoginTarget) -> Result<Option<Credential>, StoreError>;
    fn list_accounts(&self, target: &LoginTarget) -> Result<Vec<String>, StoreError>;
    /// 返回是否真的删掉了
    fn delete(&self, target: &LoginTarget, account: &str) -> Result<bool, StoreError>;
    /// 清空该 target 全部凭证,返回删除数量
    fn clear(&self, target: &LoginTarget) -> Result<usize, StoreError>;
    fn backend(&self) -> &'static str;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_key_normalization() {
        let t = LoginTarget::from_api_base("http://localhost:28080").unwrap();
        assert_eq!(t.host_key, "localhost:28080");
        assert_eq!(t.service(), "cli-starter:localhost:28080");

        let t2 = LoginTarget::from_api_base("http://localhost/").unwrap();
        assert_eq!(t2.host_key, "localhost");

        let t3 = LoginTarget::from_api_base("https://auth.example.com").unwrap();
        assert_eq!(t3.host_key, "auth.example.com");
    }

    #[test]
    fn invalid_base_rejected() {
        assert!(LoginTarget::from_api_base("not a url").is_err());
    }
}

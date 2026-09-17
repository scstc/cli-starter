//! Windows Credential Manager 实现(keyring v1 API)。
//! keyring 无法枚举条目,账号索引用独立 entry("__index__")维护。

use keyring::Entry;

use crate::auth::credential::Credential;
use crate::auth::store::{CredentialStore, LoginTarget, ACTIVE_SLOT};
use crate::error::StoreError;

pub struct KeyringStore;

/// keyring v1 对"条目不存在"的错误形态在不同平台后端略有差异,
/// 用 Debug/Display 兜底识别,避免依赖具体 ErrorKind API。
fn is_no_entry(e: &keyring::Error) -> bool {
    let dbg = format!("{e:?}");
    dbg.contains("NoEntry") || e.to_string().to_lowercase().contains("no entry")
}

fn map_err(e: keyring::Error) -> StoreError {
    StoreError::Backend {
        backend: "keyring",
        message: e.to_string(),
    }
}

impl KeyringStore {
    /// 探测系统凭据存储可用性(set + get + delete 往返)
    pub fn probe() -> Result<Self, StoreError> {
        let entry = Entry::new("cli-starter:probe", "probe").map_err(map_err)?;
        entry.set_password("ok").map_err(map_err)?;
        let got = entry.get_password().map_err(map_err)?;
        let del = entry.delete_credential();
        if got != "ok" {
            return Err(StoreError::Backend {
                backend: "keyring",
                message: "roundtrip mismatch".into(),
            });
        }
        match del {
            Ok(()) => Ok(Self),
            Err(ref e) if is_no_entry(e) => Ok(Self),
            Err(e) => Err(map_err(e)),
        }
    }

    fn entry(target: &LoginTarget, user: &str) -> Result<Entry, StoreError> {
        Entry::new(&target.service(), user).map_err(map_err)
    }

    fn read_index(&self, target: &LoginTarget) -> Vec<String> {
        Self::entry(target, target.index_user())
            .and_then(|e| e.get_password().map_err(map_err))
            .ok()
            .and_then(|s| serde_json::from_str::<Vec<String>>(&s).ok())
            .unwrap_or_default()
    }

    fn write_index(&self, target: &LoginTarget, accounts: &[String]) -> Result<(), StoreError> {
        let body = serde_json::to_string(accounts)?;
        Self::entry(target, target.index_user())?
            .set_password(&body)
            .map_err(map_err)
    }
}

impl CredentialStore for KeyringStore {
    fn set(
        &self,
        target: &LoginTarget,
        account: &str,
        cred: &Credential,
    ) -> Result<(), StoreError> {
        let body = serde_json::to_string(cred)?;
        Self::entry(target, account)?
            .set_password(&body)
            .map_err(map_err)?;
        let mut idx = self.read_index(target);
        if !idx.iter().any(|a| a == account) {
            idx.push(account.to_string());
            self.write_index(target, &idx)?;
        }
        Ok(())
    }

    fn set_active(&self, target: &LoginTarget, cred: &Credential) -> Result<(), StoreError> {
        let body = serde_json::to_string(cred)?;
        Self::entry(target, ACTIVE_SLOT)?
            .set_password(&body)
            .map_err(map_err)
    }

    fn get(&self, target: &LoginTarget, account: &str) -> Result<Option<Credential>, StoreError> {
        match Self::entry(target, account)?.get_password() {
            Ok(body) => Ok(Some(serde_json::from_str(&body)?)),
            Err(ref e) if is_no_entry(e) => Ok(None),
            Err(e) => Err(map_err(e)),
        }
    }

    fn get_active(&self, target: &LoginTarget) -> Result<Option<Credential>, StoreError> {
        self.get(target, ACTIVE_SLOT)
    }

    fn list_accounts(&self, target: &LoginTarget) -> Result<Vec<String>, StoreError> {
        let mut accounts = self.read_index(target);
        // 兜底:active 存在但索引缺失时也能看到
        if let Some(active) = self.get_active(target)? {
            if !accounts.contains(&active.account) {
                accounts.push(active.account.clone());
            }
        }
        accounts.sort();
        Ok(accounts)
    }

    fn delete(&self, target: &LoginTarget, account: &str) -> Result<bool, StoreError> {
        let existed = match Self::entry(target, account)?.delete_credential() {
            Ok(()) => true,
            Err(ref e) if is_no_entry(e) => false,
            Err(e) => return Err(map_err(e)),
        };
        // 若激活槽位属于该账号,一并清理
        if let Some(active) = self.get_active(target)? {
            if active.account == account {
                let _ = Self::entry(target, ACTIVE_SLOT)?.delete_credential();
            }
        }
        let idx: Vec<String> = self
            .read_index(target)
            .into_iter()
            .filter(|a| a != account)
            .collect();
        self.write_index(target, &idx)?;
        Ok(existed)
    }

    fn clear(&self, target: &LoginTarget) -> Result<usize, StoreError> {
        let accounts = self.list_accounts(target)?;
        let mut removed = 0usize;
        for a in &accounts {
            if self.delete(target, a)? {
                removed += 1;
            }
        }
        let _ = Self::entry(target, ACTIVE_SLOT)?.delete_credential();
        self.write_index(target, &[])?;
        Ok(removed)
    }

    fn backend(&self) -> &'static str {
        "keyring"
    }
}

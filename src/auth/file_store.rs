//! 文件回退存储:{config_dir}/credentials.json,明文 + 原子写。
//! 仅在系统凭据存储不可用或 CLI_STARTER_CREDENTIAL_STORE=file 时启用。

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use crate::auth::credential::Credential;
use crate::auth::store::{CredentialStore, LoginTarget, ACTIVE_SLOT};
use crate::error::StoreError;

const FILE: &str = "credentials.json";
const HEADER_ACCOUNT: &str = "__header__";

/// service -> user -> credential
type FileData = BTreeMap<String, BTreeMap<String, Credential>>;

pub struct FileStore {
    dir: PathBuf,
}

impl FileStore {
    pub fn new(config_dir: &Path) -> Self {
        Self {
            dir: config_dir.to_path_buf(),
        }
    }

    fn path(&self) -> PathBuf {
        self.dir.join(FILE)
    }

    fn load(&self) -> FileData {
        fs::read_to_string(self.path())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default()
    }

    fn save(&self, data: &FileData) -> Result<(), StoreError> {
        fs::create_dir_all(&self.dir)?;
        let tmp = self.dir.join(format!("{FILE}.tmp"));
        let body = serde_json::to_string_pretty(data)?;
        fs::write(&tmp, body.as_bytes())?;
        fs::rename(&tmp, self.path())?;
        Ok(())
    }

    fn users_of<'a>(
        &self,
        data: &'a FileData,
        target: &LoginTarget,
    ) -> &'a BTreeMap<String, Credential> {
        static EMPTY: BTreeMap<String, Credential> = BTreeMap::new();
        data.get(&target.service()).unwrap_or(&EMPTY)
    }
}

impl CredentialStore for FileStore {
    fn set(
        &self,
        target: &LoginTarget,
        account: &str,
        cred: &Credential,
    ) -> Result<(), StoreError> {
        let mut data = self.load();
        data.entry(target.service())
            .or_default()
            .insert(account.to_string(), cred.clone());
        self.save(&data)
    }

    fn set_active(&self, target: &LoginTarget, cred: &Credential) -> Result<(), StoreError> {
        self.set(target, ACTIVE_SLOT, cred)
    }

    fn get(&self, target: &LoginTarget, account: &str) -> Result<Option<Credential>, StoreError> {
        Ok(self
            .load()
            .get(&target.service())
            .and_then(|users| users.get(account))
            .cloned())
    }

    fn get_active(&self, target: &LoginTarget) -> Result<Option<Credential>, StoreError> {
        self.get(target, ACTIVE_SLOT)
    }

    fn list_accounts(&self, target: &LoginTarget) -> Result<Vec<String>, StoreError> {
        let users = self.load();
        let users = self.users_of(&users, target);
        Ok(users
            .keys()
            .filter(|k| !k.starts_with("__"))
            .cloned()
            .collect())
    }

    fn delete(&self, target: &LoginTarget, account: &str) -> Result<bool, StoreError> {
        let mut data = self.load();
        let Some(users) = data.get_mut(&target.service()) else {
            return Ok(false);
        };
        let existed = users.remove(account).is_some();
        if existed && account != ACTIVE_SLOT {
            // active 若指向该账号则一并清掉
            if users
                .get(ACTIVE_SLOT)
                .map(|c| c.account == account)
                .unwrap_or(false)
            {
                users.remove(ACTIVE_SLOT);
            }
        }
        self.save(&data)?;
        Ok(existed)
    }

    fn clear(&self, target: &LoginTarget) -> Result<usize, StoreError> {
        let mut data = self.load();
        let users = self.users_of(&data, target);
        let removed = users.keys().filter(|k| !k.starts_with("__")).count();
        data.remove(&target.service());
        self.save(&data)?;
        Ok(removed)
    }

    fn backend(&self) -> &'static str {
        "file"
    }
}

// HEADER_ACCOUNT 预留:若未来需要在文件头放警示元数据(如创建时间),用它承载。
#[allow(dead_code)]
fn _keep_header_account() -> &'static str {
    HEADER_ACCOUNT
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cred(account: &str) -> Credential {
        Credential {
            account: account.into(),
            login_id: "1".into(),
            token_name: "satoken".into(),
            token_value: format!("tok-{account}"),
            api_base: "http://localhost:28080".into(),
            token_timeout: 3600,
            obtained_at_unix: 1760000000,
        }
    }

    #[test]
    fn set_get_delete_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let store = FileStore::new(dir.path());
        let target = LoginTarget::from_api_base("http://localhost:28080").unwrap();

        store.set(&target, "alice", &cred("alice")).unwrap();
        store.set_active(&target, &cred("alice")).unwrap();

        assert_eq!(
            store.get(&target, "alice").unwrap().unwrap().token_value,
            "tok-alice"
        );
        assert!(store.get_active(&target).unwrap().is_some());
        assert_eq!(store.list_accounts(&target).unwrap(), vec!["alice"]);

        assert!(store.delete(&target, "alice").unwrap());
        assert!(store.get(&target, "alice").unwrap().is_none());
        assert!(store.get_active(&target).unwrap().is_none()); // active 随账号清理
        assert_eq!(store.list_accounts(&target).unwrap().len(), 0);
    }

    #[test]
    fn clear_counts_accounts_only() {
        let dir = tempfile::tempdir().unwrap();
        let store = FileStore::new(dir.path());
        let target = LoginTarget::from_api_base("http://localhost:9").unwrap();
        store.set(&target, "alice", &cred("alice")).unwrap();
        store.set(&target, "bob", &cred("bob")).unwrap();
        store.set_active(&target, &cred("alice")).unwrap();
        assert_eq!(store.clear(&target).unwrap(), 2);
        assert_eq!(store.list_accounts(&target).unwrap().len(), 0);
    }
}

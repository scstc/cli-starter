//! 生效凭证解析:env > keyring > file(对齐 gh 的 GH_TOKEN 语义)。

use std::path::Path;

use crate::auth::credential::Credential;
use crate::auth::env_source;
use crate::auth::file_store::FileStore;
use crate::auth::keyring_store::KeyringStore;
use crate::auth::store::{CredentialStore, LoginTarget};
use crate::config::Profile;
use crate::error::StoreError;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StorageMode {
    Env,
    Keyring,
    File,
}

impl StorageMode {
    pub fn label(&self) -> &'static str {
        match self {
            StorageMode::Env => "env:CLI_STARTER_TOKEN",
            StorageMode::Keyring => "system credential manager",
            StorageMode::File => "plain file (fallback)",
        }
    }
}

pub struct EffectiveCredential {
    pub cred: Credential,
    pub mode: StorageMode,
}

pub struct CredentialChain {
    store: Box<dyn CredentialStore>,
    env: Option<(String, String)>,
}

impl CredentialChain {
    /// 选择顺序:显式 CLI_STARTER_CREDENTIAL_STORE > keyring 探测 > file 回退
    pub fn open(config_dir: &Path) -> Self {
        let env = env_source::read();
        let forced = std::env::var("CLI_STARTER_CREDENTIAL_STORE").unwrap_or_default();
        let store: Box<dyn CredentialStore> = match forced.as_str() {
            "file" => Box::new(FileStore::new(config_dir)),
            "keyring" => Box::new(KeyringStore),
            _ => match KeyringStore::probe() {
                Ok(_) => Box::new(KeyringStore),
                Err(e) => {
                    eprintln!(
                        "[warn] system credential store unavailable ({e}); falling back to plain file, token will be stored in clear text"
                    );
                    Box::new(FileStore::new(config_dir))
                }
            },
        };
        Self { store, env }
    }

    pub fn store(&self) -> &dyn CredentialStore {
        self.store.as_ref()
    }

    pub fn env_protected(&self) -> bool {
        self.env.is_some()
    }

    /// env > active 槽
    pub fn effective(
        &self,
        target: &LoginTarget,
        profile: &Profile,
    ) -> Result<Option<EffectiveCredential>, StoreError> {
        if let Some((name, value)) = &self.env {
            let token_name = if name.is_empty() {
                profile.token_header.clone()
            } else {
                name.clone()
            };
            return Ok(Some(EffectiveCredential {
                cred: Credential {
                    account: "(env)".into(),
                    login_id: String::new(),
                    token_name,
                    token_value: value.clone(),
                    api_base: profile.api_base.clone(),
                    token_timeout: 0,
                    obtained_at_unix: 0,
                },
                mode: StorageMode::Env,
            }));
        }
        let mode = match self.store.backend() {
            "keyring" => StorageMode::Keyring,
            _ => StorageMode::File,
        };
        Ok(self
            .store
            .get_active(target)?
            .map(|cred| EffectiveCredential { cred, mode }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::store::CredentialStore;
    use std::collections::BTreeMap;

    struct MemoryStore {
        map: std::sync::Mutex<BTreeMap<(String, String), Credential>>,
    }

    impl MemoryStore {
        fn new() -> Self {
            Self {
                map: std::sync::Mutex::new(BTreeMap::new()),
            }
        }
    }

    impl CredentialStore for MemoryStore {
        fn set(&self, t: &LoginTarget, a: &str, c: &Credential) -> Result<(), StoreError> {
            self.map
                .lock()
                .unwrap()
                .insert((t.service(), a.into()), c.clone());
            Ok(())
        }
        fn set_active(&self, t: &LoginTarget, c: &Credential) -> Result<(), StoreError> {
            self.set(t, crate::auth::store::ACTIVE_SLOT, c)
        }
        fn get(&self, t: &LoginTarget, a: &str) -> Result<Option<Credential>, StoreError> {
            Ok(self
                .map
                .lock()
                .unwrap()
                .get(&(t.service(), a.into()))
                .cloned())
        }
        fn get_active(&self, t: &LoginTarget) -> Result<Option<Credential>, StoreError> {
            self.get(t, crate::auth::store::ACTIVE_SLOT)
        }
        fn list_accounts(&self, _t: &LoginTarget) -> Result<Vec<String>, StoreError> {
            Ok(vec![])
        }
        fn delete(&self, t: &LoginTarget, a: &str) -> Result<bool, StoreError> {
            Ok(self
                .map
                .lock()
                .unwrap()
                .remove(&(t.service(), a.into()))
                .is_some())
        }
        fn clear(&self, t: &LoginTarget) -> Result<usize, StoreError> {
            let mut guard = self.map.lock().unwrap();
            let keys: Vec<(String, String)> = guard
                .keys()
                .filter(|(s, _)| *s == t.service())
                .cloned()
                .collect();
            let n = keys.len();
            for k in keys {
                guard.remove(&k);
            }
            Ok(n)
        }
        fn backend(&self) -> &'static str {
            "memory"
        }
    }

    fn profile() -> Profile {
        Profile {
            api_base: "http://localhost:28080".into(),
            ..Default::default()
        }
    }

    fn test_chain_with(store: MemoryStore, env: Option<(String, String)>) -> CredentialChain {
        CredentialChain {
            store: Box::new(store),
            env,
        }
    }

    #[test]
    fn env_overrides_store() {
        let target = LoginTarget::from_api_base("http://localhost:28080").unwrap();
        let store = MemoryStore::new();
        store
            .set_active(
                &target,
                &Credential {
                    account: "alice".into(),
                    login_id: "1".into(),
                    token_name: "satoken".into(),
                    token_value: "stored-token".into(),
                    api_base: "http://localhost:28080".into(),
                    token_timeout: 0,
                    obtained_at_unix: 0,
                },
            )
            .unwrap();

        let chain = test_chain_with(store, Some((String::new(), "env-token".into())));
        let eff = chain.effective(&target, &profile()).unwrap().unwrap();
        assert_eq!(eff.cred.token_value, "env-token");
        assert_eq!(eff.mode, StorageMode::Env);
        assert!(chain.env_protected());
    }

    #[test]
    fn falls_back_to_active_slot() {
        let target = LoginTarget::from_api_base("http://localhost:28080").unwrap();
        let store = MemoryStore::new();
        store
            .set_active(
                &target,
                &Credential {
                    account: "alice".into(),
                    login_id: "1".into(),
                    token_name: "satoken".into(),
                    token_value: "stored-token".into(),
                    api_base: "http://localhost:28080".into(),
                    token_timeout: 0,
                    obtained_at_unix: 0,
                },
            )
            .unwrap();

        let chain = test_chain_with(store, None);
        let eff = chain.effective(&target, &profile()).unwrap().unwrap();
        assert_eq!(eff.cred.token_value, "stored-token");
        assert!(!chain.env_protected());
    }

    #[test]
    fn none_when_empty() {
        let target = LoginTarget::from_api_base("http://localhost:28080").unwrap();
        let chain = test_chain_with(MemoryStore::new(), None);
        assert!(chain.effective(&target, &profile()).unwrap().is_none());
    }
}

//! config.toml:profiles 多环境。路径:%APPDATA%\cli-starter\config.toml,
//! CLI_STARTER_CONFIG_DIR 可覆盖(测试/CI 隔离)。原子写(tmp + rename)。

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::error::ConfigError;

pub const CONFIG_FILE: &str = "config.toml";
pub const DEFAULT_TOKEN_HEADER: &str = "satoken";

/// API 端点路径(相对 api_base)。全部可被 profile 覆盖。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Endpoints {
    pub login: String,
    pub logout: String,
    pub token_info: String,
    pub whoami: String,
    #[serde(default = "default_device_code_endpoint")]
    pub device_code: String,
    #[serde(default = "default_device_token_endpoint")]
    pub device_token: String,
    #[serde(default = "default_captcha_endpoint")]
    pub captcha: String,
    #[serde(default = "default_sms_send_endpoint")]
    pub sms_send: String,
    #[serde(default = "default_sms_login_endpoint")]
    pub sms_login: String,
}

fn default_device_code_endpoint() -> String {
    "/api/auth/device/code".into()
}

fn default_device_token_endpoint() -> String {
    "/api/auth/device/token".into()
}

fn default_captcha_endpoint() -> String {
    "/api/auth/captcha".into()
}

fn default_sms_send_endpoint() -> String {
    "/api/auth/sms/send".into()
}

fn default_sms_login_endpoint() -> String {
    "/api/auth/sms/login".into()
}

impl Default for Endpoints {
    fn default() -> Self {
        Self {
            login: "/api/auth/login".into(),
            logout: "/api/auth/logout".into(),
            token_info: "/api/auth/tokenInfo".into(),
            whoami: "/api/user/me".into(),
            device_code: default_device_code_endpoint(),
            device_token: default_device_token_endpoint(),
            captcha: default_captcha_endpoint(),
            sms_send: default_sms_send_endpoint(),
            sms_login: default_sms_login_endpoint(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Profile {
    /// 空 = 尚未配置,login 时触发首启引导
    #[serde(default)]
    pub api_base: String,
    #[serde(default = "default_token_header")]
    pub token_header: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub endpoints: Option<Endpoints>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_account: Option<String>,
}

fn default_token_header() -> String {
    DEFAULT_TOKEN_HEADER.into()
}

impl Default for Profile {
    fn default() -> Self {
        Self {
            api_base: String::new(),
            token_header: default_token_header(),
            endpoints: None,
            last_account: None,
        }
    }
}

impl Profile {
    pub fn endpoints(&self) -> Endpoints {
        self.endpoints.clone().unwrap_or_default()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub version: u32,
    pub active_profile: String,
    pub profiles: BTreeMap<String, Profile>,
}

impl Default for Config {
    fn default() -> Self {
        let mut profiles = BTreeMap::new();
        profiles.insert("default".to_string(), Profile::default());
        Self {
            version: 1,
            active_profile: "default".into(),
            profiles,
        }
    }
}

impl Config {
    /// 配置目录:CLI_STARTER_CONFIG_DIR > %APPDATA%\cli-starter(Windows)
    pub fn dir() -> PathBuf {
        if let Ok(dir) = std::env::var("CLI_STARTER_CONFIG_DIR") {
            return PathBuf::from(dir);
        }
        directories::ProjectDirs::from("", "", "cli-starter")
            .map(|d| d.config_dir().to_path_buf())
            .unwrap_or_else(|| PathBuf::from(".cli-starter"))
    }

    pub fn load(dir: &Path) -> Result<Self, ConfigError> {
        let path = dir.join(CONFIG_FILE);
        if !path.exists() {
            return Ok(Self::default());
        }
        let raw = fs::read_to_string(&path)?;
        let cfg: Config = toml::from_str(&raw)
            .map_err(|e| ConfigError::Corrupted(format!("{}: {e}", path.display())))?;
        cfg.validate()?;
        Ok(cfg)
    }

    fn validate(&self) -> Result<(), ConfigError> {
        if !self.profiles.contains_key(&self.active_profile) {
            return Err(ConfigError::Invalid(format!(
                "active_profile `{}` not found in profiles",
                self.active_profile
            )));
        }
        for (name, p) in &self.profiles {
            if !p.api_base.is_empty() {
                validate_api_base(&p.api_base)
                    .map_err(|e| ConfigError::Invalid(format!("profiles.{name}.api_base: {e}")))?;
            }
        }
        Ok(())
    }

    /// 原子写:先写临时文件再 rename,避免半截配置。
    pub fn save(&self, dir: &Path) -> Result<(), ConfigError> {
        fs::create_dir_all(dir)?;
        let path = dir.join(CONFIG_FILE);
        let tmp = dir.join(format!("{CONFIG_FILE}.tmp"));
        let body = toml::to_string_pretty(self)
            .map_err(|e| ConfigError::Invalid(format!("serialize config: {e}")))?;
        fs::write(&tmp, body.as_bytes())?;
        fs::rename(&tmp, &path)?;
        Ok(())
    }

    /// 解析生效 profile:显式 --profile > active_profile(返回 owned key,避免借用纠缠)
    pub fn resolve(&self, name: Option<&str>) -> Result<(String, &Profile), ConfigError> {
        let key = name.unwrap_or(&self.active_profile);
        self.profiles
            .get(key)
            .map(|p| (key.to_string(), p))
            .ok_or_else(|| ConfigError::Invalid(format!("profile `{key}` not found")))
    }
}

pub fn validate_api_base(s: &str) -> Result<(), String> {
    let url = url::Url::parse(s).map_err(|e| format!("invalid URL: {e}"))?;
    match url.scheme() {
        "http" | "https" => Ok(()),
        other => Err(format!("scheme must be http(s), got `{other}`")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_config_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let cfg = Config::default();
        cfg.save(dir.path()).unwrap();
        let loaded = Config::load(dir.path()).unwrap();
        assert_eq!(loaded.active_profile, "default");
        assert!(loaded.profiles["default"].api_base.is_empty());
        assert_eq!(loaded.profiles["default"].token_header, "satoken");
    }

    #[test]
    fn corrupted_config_errors() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join(CONFIG_FILE), "not = [valid").unwrap();
        assert!(matches!(
            Config::load(dir.path()),
            Err(ConfigError::Corrupted(_))
        ));
    }

    #[test]
    fn invalid_api_base_rejected() {
        let dir = tempfile::tempdir().unwrap();
        let mut cfg = Config::default();
        cfg.profiles.get_mut("default").unwrap().api_base = "ftp://x".into();
        cfg.save(dir.path()).unwrap();
        assert!(matches!(
            Config::load(dir.path()),
            Err(ConfigError::Invalid(_))
        ));
    }

    #[test]
    fn missing_file_returns_default() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(Config::load(dir.path()).unwrap().version, 1);
    }
}

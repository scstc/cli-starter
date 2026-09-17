//! 统一错误分层:核心层各模块 thiserror 枚举,顶层 AppError 映射退出码。
//! 约定:CLI 面向用户的输出仅 ASCII(规避 Windows 控制台 GBK 乱码)。

use thiserror::Error;

/// 认证类错误 -> 退出码 4(EnvTokenProtected 例外,见 AppError::exit_code)
#[derive(Debug, Error)]
pub enum AuthError {
    #[error("authentication failed: {0}")]
    Failed(String),
    #[error("invalid username or password")]
    InvalidCredentials,
    #[error("not logged in (run `cli-starter auth login` first)")]
    NotLoggedIn,
    #[error(
        "refusing to touch local credentials: CLI_STARTER_TOKEN is set. unset it to login/logout"
    )]
    EnvTokenProtected,
}

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("config file is corrupted: {0}")]
    Corrupted(String),
    #[error("invalid config: {0}")]
    Invalid(String),
    #[error("io error on config file: {0}")]
    Io(#[from] std::io::Error),
}

#[derive(Debug, Error)]
pub enum StoreError {
    #[error("credential store error ({backend}): {message}")]
    Backend {
        backend: &'static str,
        message: String,
    },
    #[error("io error on credential file: {0}")]
    Io(#[from] std::io::Error),
    #[error("serialization error: {0}")]
    Serde(#[from] serde_json::Error),
}

#[derive(Debug, Error)]
pub enum ApiError {
    #[error("network error ({url}): {source}")]
    Network { url: String, source: reqwest::Error },
    #[error("server error: HTTP {status}")]
    Server { status: u16, body: String },
    #[error("unauthorized (scene {scene:?}): {msg}")]
    Unauthorized { scene: Option<i32>, msg: String },
    #[error("forbidden: not enough permission")]
    Forbidden,
    /// HTTP 4xx + 统一包裹的业务拒绝(如 414 验证码/415 手机号未注册/416 频率/417 短信码)。
    /// msg 是服务端中文文案,不进 Display(GBK 控制台乱码);-v 调试可见。
    #[error("request rejected by server (business code {code}); run with -v for details")]
    Rejected { code: i32, msg: String },
    #[error("failed to parse response: {0}")]
    Parse(String),
}

impl ApiError {
    /// 网络错误与 5xx 可重试
    pub fn is_retryable(&self) -> bool {
        match self {
            ApiError::Network { .. } => true,
            ApiError::Server { status, .. } => *status >= 500,
            _ => false,
        }
    }
}

/// 顶层错误。main 据此打印并转退出码。
#[derive(Debug, Error)]
pub enum AppError {
    #[error(transparent)]
    Auth(#[from] AuthError),
    #[error(transparent)]
    Api(#[from] ApiError),
    #[error(transparent)]
    Config(#[from] ConfigError),
    #[error(transparent)]
    Store(#[from] StoreError),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("{0}")]
    Other(String),
}

impl AppError {
    /// 0 成功;1 通用/配置/存储/写保护;3 网络/服务端;4 认证失败(含业务 4xx 拒绝);2 归 clap
    pub fn exit_code(&self) -> i32 {
        match self {
            AppError::Api(ApiError::Rejected { .. }) => 4,
            AppError::Api(_) => 3,
            AppError::Auth(AuthError::InvalidCredentials | AuthError::Failed(_)) => 4,
            // 未登录在 gh 语义里也算"认证问题",但 plan 约定 whoami 未登录退 4
            AppError::Auth(AuthError::NotLoggedIn) => 4,
            // 写保护属于"操作被拒绝",退 1
            AppError::Auth(AuthError::EnvTokenProtected) => 1,
            _ => 1,
        }
    }
}

pub type AppResult<T> = Result<T, AppError>;
pub type ApiResult<T> = Result<T, ApiError>;

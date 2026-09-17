//! reqwest blocking 客户端构造:统一超时/UA/禁系统代理。
//! 禁系统代理:内网 CLI 语义,防止系统代理劫持 localhost(WSL 转发的网关地址)。

use std::time::Duration;

pub const USER_AGENT: &str = concat!("cli-starter/", env!("CARGO_PKG_VERSION"));
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

pub fn build_client() -> reqwest::Result<reqwest::blocking::Client> {
    reqwest::blocking::Client::builder()
        .user_agent(USER_AGENT)
        .timeout(REQUEST_TIMEOUT)
        .connect_timeout(CONNECT_TIMEOUT)
        .no_proxy()
        .build()
}

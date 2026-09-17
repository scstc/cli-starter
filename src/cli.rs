//! clap 顶层定义。每个子命令一个模块,各自持有 Args 并自带 run()。

use clap::{Args, Parser, Subcommand};

#[derive(Parser)]
#[command(
    name = "cli-starter",
    version,
    propagate_version = true,
    about = "Rust CLI starter with gh-style Sa-Token login",
    after_help = "Env: CLI_STARTER_TOKEN (credential override, write-protected), CLI_STARTER_CONFIG_DIR, CLI_STARTER_CREDENTIAL_STORE=file|keyring, CLI_STARTER_NO_BROWSER=1 (device login: do not auto-open browser)"
)]
pub struct Cli {
    /// Configuration profile to use (default: active_profile in config.toml)
    #[arg(long, global = true, value_name = "NAME")]
    pub profile: Option<String>,

    /// Verbose error output (full chain to stderr)
    #[arg(long, short = 'v', global = true)]
    pub verbose: bool,

    #[command(subcommand)]
    pub command: Command,
}

#[derive(Subcommand)]
pub enum Command {
    /// Manage login credentials (gh auth style)
    #[command(subcommand)]
    Auth(AuthCommand),

    /// Show the currently logged-in user (GET /api/user/me)
    Whoami {
        /// Print raw JSON
        #[arg(long)]
        json: bool,
    },
}

#[derive(Subcommand)]
pub enum AuthCommand {
    /// Log in interactively and store the credential locally
    Login(LoginArgs),

    /// Validate stored credentials against the server
    Status {
        /// Print JSON (exit code always 0)
        #[arg(long)]
        json: bool,
    },

    /// Delete the LOCAL credential only (server token is NOT revoked)
    Logout {
        /// Account to log out (default: active account)
        account: Option<String>,

        /// Remove all accounts of the current profile
        #[arg(long)]
        all: bool,
    },

    /// Print the token currently in effect (env > keyring > file). For scripts.
    Token {
        /// Print token of this account (default: active)
        #[arg(long)]
        account: Option<String>,
    },
}

#[derive(Args)]
pub struct LoginArgs {
    /// Read token from stdin instead of prompting (gh --with-token style)
    #[arg(long, conflicts_with = "method")]
    pub with_token: bool,

    /// Login method: password | token | device | sms
    #[arg(long, value_parser = ["password", "token", "device", "sms"])]
    pub method: Option<String>,

    /// Pre-fill username (password method) or phone number (sms method)
    #[arg(long)]
    pub username: Option<String>,

    /// API base URL, skips the first-run bootstrap prompt
    #[arg(long, value_name = "URL")]
    pub api_base: Option<String>,
}

/// 默认网关与直连地址(首启引导候选项;M6 网关联调后按实际环境修订)
pub const DEFAULT_GATEWAY_BASE: &str = "http://localhost:80";
pub const DEFAULT_DIRECT_BASE: &str = "http://127.0.0.1:8090";

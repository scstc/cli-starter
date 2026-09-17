//! 入口:解析 -> 分发 -> 错误转退出码。main 只做胶水。

mod api;
mod auth;
mod cli;
mod commands;
mod config;
mod error;
mod http;
mod open;
mod term;

use clap::Parser;
use error::AppResult;

fn main() {
    let cli = cli::Cli::parse();
    let result: AppResult<()> = match &cli.command {
        cli::Command::Auth(action) => commands::auth::run(action, cli.profile.as_deref()),
        cli::Command::Whoami { json } => commands::whoami::run(*json, cli.profile.as_deref()),
    };
    if let Err(e) = result {
        if cli.verbose {
            eprintln!("[error] {e:?}");
        } else {
            eprintln!("[error] {e}");
        }
        std::process::exit(e.exit_code());
    }
}

//! `cli-starter auth ...` 分发。

pub mod login;
pub mod logout;
pub mod status;
pub mod token;

use crate::cli::AuthCommand;
use crate::error::AppResult;

pub fn run(cmd: &AuthCommand, profile: Option<&str>) -> AppResult<()> {
    match cmd {
        AuthCommand::Login(args) => login::run(args, profile),
        AuthCommand::Status { json } => status::run(*json, profile),
        AuthCommand::Logout { account, all } => logout::run(account.clone(), *all, profile),
        AuthCommand::Token { account } => token::run(account.clone(), profile),
    }
}

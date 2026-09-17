//! 错误路径:退出码与人话报错。

use assert_cmd::Command;
use httpmock::MockServer;
use predicates::str::contains;
use tempfile::TempDir;

fn setup() -> (MockServer, TempDir) {
    let server = MockServer::start();
    let dir = TempDir::new().unwrap();
    std::fs::create_dir_all(dir.path()).unwrap();
    std::fs::write(
        dir.path().join("config.toml"),
        format!(
            "version = 1\nactive_profile = \"default\"\n\n[profiles.default]\napi_base = \"{}\"\ntoken_header = \"satoken\"\n",
            server.base_url()
        ),
    )
    .unwrap();
    (server, dir)
}

fn cmd(dir: &TempDir) -> Command {
    let mut c = Command::cargo_bin("cli-starter").unwrap();
    c.env("CLI_STARTER_CONFIG_DIR", dir.path())
        .env("CLI_STARTER_CREDENTIAL_STORE", "file")
        .env("CLI_STARTER_NO_BROWSER", "1")
        .env_remove("CLI_STARTER_TOKEN");
    c
}

#[test]
fn whoami_without_login_exits_4() {
    let (_server, dir) = setup();
    cmd(&dir)
        .args(["whoami"])
        .assert()
        .failure()
        .code(4)
        .stderr(contains("not logged in"));
}

#[test]
fn env_token_write_protects_login_and_logout() {
    let (_server, dir) = setup();
    let mut c = cmd(&dir);
    c.env("CLI_STARTER_TOKEN", "some-env-token");
    c.args(["auth", "login"])
        .assert()
        .failure()
        .code(1)
        .stderr(contains("CLI_STARTER_TOKEN"));

    let mut c2 = cmd(&dir);
    c2.env("CLI_STARTER_TOKEN", "some-env-token");
    c2.args(["auth", "logout"])
        .assert()
        .failure()
        .code(1)
        .stderr(contains("CLI_STARTER_TOKEN"));
}

#[test]
fn env_token_overrides_for_whoami() {
    let (server, dir) = setup();
    let _me = server.mock(|when, then| {
        when.method(httpmock::Method::GET)
            .path("/api/user/me")
            .header("satoken", "env-token-1");
        then.status(200)
            .body(r#"{"code":200,"msg":"ok","data":{"loginId":"9","username":"env-user","roles":[],"device":""}}"#);
    });

    let mut c = cmd(&dir);
    c.env("CLI_STARTER_TOKEN", "env-token-1");
    c.args(["whoami"])
        .assert()
        .success()
        .stdout(contains("Logged in as env-user"));

    // auth token 输出 env 的值
    let mut c2 = cmd(&dir);
    c2.env("CLI_STARTER_TOKEN", "env-token-1");
    c2.args(["auth", "token"])
        .assert()
        .success()
        .stdout("env-token-1\n");
}

#[test]
fn unreachable_server_exits_3() {
    // 端口 1 基本不可能有服务:网络错误路径
    let dir = TempDir::new().unwrap();
    std::fs::create_dir_all(dir.path()).unwrap();
    std::fs::write(
        dir.path().join("config.toml"),
        "version = 1\nactive_profile = \"default\"\n\n[profiles.default]\napi_base = \"http://127.0.0.1:1\"\ntoken_header = \"satoken\"\n",
    )
    .unwrap();
    let host = "127.0.0.1:1";
    let service = format!("cli-starter:{host}");
    let cred = serde_json::json!({
        "account": "alice", "login_id": "1", "token_name": "satoken",
        "token_value": "t", "api_base": "http://127.0.0.1:1",
        "token_timeout": 0, "obtained_at_unix": 0,
    });
    std::fs::write(
        dir.path().join("credentials.json"),
        serde_json::to_string(
            &serde_json::json!({ service: { "__active__": cred, "alice": cred } }),
        )
        .unwrap(),
    )
    .unwrap();

    cmd(&dir)
        .args(["whoami"])
        .assert()
        .failure()
        .code(3)
        .stderr(contains("network error"));
}

#[test]
fn corrupted_config_exits_1() {
    let dir = TempDir::new().unwrap();
    std::fs::create_dir_all(dir.path()).unwrap();
    std::fs::write(dir.path().join("config.toml"), "broken = [").unwrap();
    cmd(&dir)
        .args(["whoami"])
        .assert()
        .failure()
        .code(1)
        .stderr(contains("corrupted"));
}

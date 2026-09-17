//! 端到端:对 httpmock 模拟的 Sa-Token 服务跑通 login -> token/status/whoami -> logout 全链路。
//! 全程强制文件凭证存储(CLI_STARTER_CREDENTIAL_STORE=file)+ 临时配置目录隔离。

use assert_cmd::Command;
use httpmock::MockServer;
use predicates::str::{contains, is_match};
use tempfile::TempDir;

const ME_OK: &str = r#"{"code":200,"msg":"ok","data":{"loginId":"1","username":"alice","roles":["user"],"device":"default"}}"#;
const LOGIN_OK: &str = r#"{"code":200,"msg":"ok","data":{"tokenName":"satoken","tokenValue":"uuid-abc-123","loginId":"1","loginType":"login","loginDevice":"default","tokenTimeout":2592000,"activeTimeout":-1,"sessionTimeout":2592000}}"#;
const CAPTCHA_OK: &str = r#"{"code":200,"msg":"ok","data":{"captchaId":"cap-1","image":"data:image/png;base64,aGVsbG8=","debugCode":"AB2D"}}"#;

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
fn with_token_full_chain() {
    let (server, dir) = setup();
    let _me = server.mock(|when, then| {
        when.method(httpmock::Method::GET)
            .path("/api/user/me")
            .header("satoken", "tok-12345");
        then.status(200).body(ME_OK);
    });

    // login --with-token(stdin 提供 token,服务端校验通过)
    cmd(&dir)
        .args(["auth", "login", "--with-token"])
        .write_stdin("tok-12345")
        .assert()
        .success()
        .stdout(contains("Logged in"))
        .stdout(contains("alice"));

    // auth token 精确输出
    cmd(&dir)
        .args(["auth", "token"])
        .assert()
        .success()
        .stdout("tok-12345\n");

    // whoami
    cmd(&dir)
        .args(["whoami"])
        .assert()
        .success()
        .stdout(contains("Logged in as alice"));

    // auth status
    cmd(&dir)
        .args(["auth", "status"])
        .assert()
        .success()
        .stdout(contains("[ok] alice"))
        .stdout(contains("tok-...2345"));

    // auth logout 后 token 消失
    cmd(&dir)
        .args(["auth", "logout"])
        .assert()
        .success()
        .stdout(contains("removed credential for `alice`"));
    cmd(&dir).args(["auth", "token"]).assert().failure().code(1);
}

#[test]
fn password_flow_over_pipe() {
    let (server, dir) = setup();
    let _captcha = server.mock(|when, then| {
        when.method(httpmock::Method::GET).path("/api/auth/captcha");
        then.status(200).body(CAPTCHA_OK);
    });
    // 带 captchaId 的登录请求(服务端 required=true 语义)
    let login_mock = server.mock(|when, then| {
        when.method(httpmock::Method::POST)
            .path("/api/auth/login")
            .body_includes("cap-1");
        then.status(200).body(LOGIN_OK);
    });
    let _me = server.mock(|when, then| {
        when.method(httpmock::Method::GET)
            .path("/api/user/me")
            .header("satoken", "uuid-abc-123");
        then.status(200).body(ME_OK);
    });

    // 管道模式:用户名已由 --username 给出,stdin 依次喂密码、图形验证码
    cmd(&dir)
        .args([
            "auth",
            "login",
            "--method",
            "password",
            "--username",
            "alice",
        ])
        .write_stdin("alice123\nAB2D\n")
        .assert()
        .success()
        .stdout(contains("Logged in to"))
        .stdout(contains("alice"));
    login_mock.assert();

    cmd(&dir)
        .args(["auth", "token"])
        .assert()
        .success()
        .stdout("uuid-abc-123\n");
}

#[test]
fn sms_flow_over_pipe() {
    let (server, dir) = setup();
    let _send = server.mock(|when, then| {
        when.method(httpmock::Method::POST)
            .path("/api/auth/sms/send")
            .body_includes("13800000001");
        then.status(200)
            .body(r#"{"code":200,"msg":"ok","data":{"debugCode":"654321"}}"#);
    });
    let _login = server.mock(|when, then| {
        when.method(httpmock::Method::POST)
            .path("/api/auth/sms/login")
            .body_includes("654321");
        then.status(200).body(LOGIN_OK);
    });
    let _me = server.mock(|when, then| {
        when.method(httpmock::Method::GET)
            .path("/api/user/me")
            .header("satoken", "uuid-abc-123");
        then.status(200).body(ME_OK);
    });

    // --username 预填手机号,stdin 只喂短信码
    cmd(&dir)
        .args([
            "auth",
            "login",
            "--method",
            "sms",
            "--username",
            "13800000001",
        ])
        .write_stdin("654321\n")
        .assert()
        .success()
        .stdout(contains("Logged in to"))
        .stdout(contains("alice"))
        .stderr(contains("[demo] server echoed the SMS code: 654321"));

    cmd(&dir)
        .args(["auth", "token"])
        .assert()
        .success()
        .stdout("uuid-abc-123\n");
}

#[test]
fn sms_unknown_phone_exits_4() {
    let (server, dir) = setup();
    let _send = server.mock(|when, then| {
        when.method(httpmock::Method::POST)
            .path("/api/auth/sms/send");
        then.status(400)
            .body(r#"{"code":415,"msg":"手机号未注册"}"#);
    });

    cmd(&dir)
        .args([
            "auth",
            "login",
            "--method",
            "sms",
            "--username",
            "19999999999",
        ])
        .write_stdin("\n")
        .assert()
        .failure()
        .code(4)
        .stderr(contains("not registered"));
}

#[test]
fn status_reports_expired_scene() {
    let (server, dir) = setup();
    let _me = server.mock(|when, then| {
        when.method(httpmock::Method::GET).path("/api/user/me");
        then.status(401)
            .body(r#"{"code":401,"msg":"token expired","data":{"scene":-3}}"#);
    });

    seed_credential(&dir, &server.base_url(), "alice", "expired-tok");

    cmd(&dir)
        .args(["auth", "status"])
        .assert()
        .failure()
        .code(1)
        .stdout(contains("[!!] alice"))
        .stdout(contains("token expired"));

    // --json 恒退 0
    cmd(&dir)
        .args(["auth", "status", "--json"])
        .assert()
        .success()
        .stdout(contains("\"valid\": false"));
}

#[test]
fn wrong_password_rejected() {
    let (server, dir) = setup();
    let _login = server.mock(|when, then| {
        when.method(httpmock::Method::POST).path("/api/auth/login");
        then.status(401)
            .body(r#"{"code":401,"msg":"invalid username or password"}"#);
    });

    // 三次错误密码后退出码 4;stdin 喂三行密码
    cmd(&dir)
        .args([
            "auth",
            "login",
            "--method",
            "password",
            "--username",
            "alice",
        ])
        .write_stdin("wrong1\nwrong2\nwrong3\n")
        .assert()
        .failure()
        .code(4)
        .stderr(contains("invalid username or password"));
}

#[test]
fn device_flow_full_chain() {
    let (server, dir) = setup();
    let _code = server.mock(|when, then| {
        when.method(httpmock::Method::POST)
            .path("/api/auth/device/code");
        then.status(200).body(
            r#"{"code":200,"msg":"ok","data":{"deviceCode":"dc-1","userCode":"BDMK-MJHT","verificationUri":"http://x/","verificationUriComplete":"http://x/?user_code=BDMK-MJHT","expiresIn":900,"interval":1}}"#,
        );
    });
    // 轮询序列:先 pending 后 ok。pending mock 延迟删除——删除前命中 pending,
    // 删除后落到 ok mock(对 httpmock 的多 mock 命中顺序无假设)。
    let _ok = server.mock(|when, then| {
        when.method(httpmock::Method::POST)
            .path("/api/auth/device/token");
        then.status(200).body(
            r#"{"code":200,"msg":"ok","data":{"status":"ok","token":{"tokenName":"satoken","tokenValue":"dev-tok-1","loginId":"1","loginType":"login","loginDevice":"default","tokenTimeout":2592000}}}"#,
        );
    });
    let mut pending = server.mock(|when, then| {
        when.method(httpmock::Method::POST)
            .path("/api/auth/device/token");
        then.status(200)
            .body(r#"{"code":200,"msg":"ok","data":{"status":"pending"}}"#);
    });
    let _me = server.mock(|when, then| {
        when.method(httpmock::Method::GET)
            .path("/api/user/me")
            .header("satoken", "dev-tok-1");
        then.status(200).body(ME_OK);
    });
    // 作用域线程延迟删除 pending mock(1.5s):删除前轮询命中 pending,
    // 删除后落到 ok mock——不依赖 httpmock 多 mock 的命中顺序假设。
    std::thread::scope(|s| {
        s.spawn(|| {
            std::thread::sleep(std::time::Duration::from_millis(1500));
            pending.delete();
        });

        // stdin 关闭(非交互);授权页地址与用户码打在 stderr
        cmd(&dir)
            .args(["auth", "login", "--method", "device"])
            .assert()
            .success()
            .stdout(contains("Logged in"))
            .stdout(contains("alice"))
            .stderr(contains("BDMK-MJHT"))
            .stderr(contains("waiting for authorization"));

        // 轮询期间的 pending 已被消化:成功入库的是 ok 分支的 token
        cmd(&dir)
            .args(["auth", "token"])
            .assert()
            .success()
            .stdout("dev-tok-1\n");
    });
}

#[test]
fn device_flow_denied_exits_4() {
    let (server, dir) = setup();
    let _code = server.mock(|when, then| {
        when.method(httpmock::Method::POST)
            .path("/api/auth/device/code");
        then.status(200).body(
            r#"{"code":200,"msg":"ok","data":{"deviceCode":"dc-2","userCode":"AAAA-BBBB","verificationUri":"http://x/","verificationUriComplete":"","expiresIn":900,"interval":1}}"#,
        );
    });
    let _poll = server.mock(|when, then| {
        when.method(httpmock::Method::POST)
            .path("/api/auth/device/token");
        then.status(200)
            .body(r#"{"code":200,"msg":"用户已拒绝授权","data":{"status":"denied"}}"#);
    });

    cmd(&dir)
        .args(["auth", "login", "--method", "device"])
        .assert()
        .failure()
        .code(4)
        .stderr(contains("denied"));
    cmd(&dir).args(["auth", "token"]).assert().failure().code(1);
}

#[test]
fn device_flow_expired_exits_4() {
    let (server, dir) = setup();
    let _code = server.mock(|when, then| {
        when.method(httpmock::Method::POST)
            .path("/api/auth/device/code");
        then.status(200).body(
            r#"{"code":200,"msg":"ok","data":{"deviceCode":"dc-3","userCode":"CCCC-DDDD","verificationUri":"http://x/","verificationUriComplete":"","expiresIn":1,"interval":1}}"#,
        );
    });
    let _poll = server.mock(|when, then| {
        when.method(httpmock::Method::POST)
            .path("/api/auth/device/token");
        then.status(200)
            .body(r#"{"code":200,"msg":"设备码已过期","data":{"status":"expired"}}"#);
    });

    cmd(&dir)
        .args(["auth", "login", "--method", "device"])
        .assert()
        .failure()
        .code(4)
        .stderr(contains("expired"));
}

/// 直接落一份文件凭证(绕过 login),供 status/whoami 类测试使用
fn seed_credential(dir: &TempDir, base_url: &str, account: &str, token: &str) {
    let host = base_url.trim_start_matches("http://").trim_end_matches('/');
    let service = format!("cli-starter:{host}");
    let cred = serde_json::json!({
        "account": account,
        "login_id": "1",
        "token_name": "satoken",
        "token_value": token,
        "api_base": base_url,
        "token_timeout": 3600,
        "obtained_at_unix": 1760000000,
    });
    let data = serde_json::json!({
        service: {
            "__active__": cred,
            account: cred,
        }
    });
    std::fs::write(
        dir.path().join("credentials.json"),
        serde_json::to_string_pretty(&data).unwrap(),
    )
    .unwrap();
}

#[test]
fn seed_helper_itself_is_sane() {
    // 保护 seed_credential 的结构不被无意改坏:种下后 status 能读到账号
    let (server, dir) = setup();
    let _me = server.mock(|when, then| {
        when.method(httpmock::Method::GET).path("/api/user/me");
        then.status(200).body(ME_OK);
    });
    seed_credential(&dir, &server.base_url(), "alice", "tok-9");
    cmd(&dir)
        .args(["auth", "status"])
        .assert()
        .success()
        .stdout(contains("[ok] alice"));
    cmd(&dir)
        .args(["whoami"])
        .assert()
        .success()
        .stdout(contains("Logged in as alice"));
}

#[test]
fn help_and_version_work() {
    let (_server, dir) = setup();
    cmd(&dir)
        .args(["--help"])
        .assert()
        .success()
        .stdout(contains("auth"));
    cmd(&dir)
        .args(["--version"])
        .assert()
        .success()
        .stdout(is_match(r"^cli-starter \d+\.\d+\.\d+").unwrap());
}

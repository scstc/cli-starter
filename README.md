# cli-starter

Rust CLI starter:内置 gh 风格的 Sa-Token 登录模块,作为以后开发内部 CLI 的起点模板。

```
cli-starter(Rust CLI) ──HTTP──> [Higress 网关(暂缓)] ──> server/(Spring Boot 4 + Sa-Token, SQLite)
```

当前联调走**直连模式**(CLI → server :8090);网关接入见 `deploy/README.md`。

## 仓库结构

| 目录 | 说明 |
|---|---|
| `src/` | Rust CLI(仓库主体):`auth/` 登录核心模块、`commands/` 子命令、`api/` Sa-Token 客户端 |
| `tests/` | 端到端测试(assert_cmd + httpmock 模拟 Sa-Token 服务) |
| `web/` | 独立前端(Vite + React 19 + TypeScript + react-router):分屏式 SSO 统一认证门户,密码/短信/**扫码**三种登录 + 自主注册 + 设备码授权控制台,手机端自适应 |
| `android/` | 安卓授权客户端(纯 Java,免 Gradle 手工构建):一键登录/账号密码登录后扫码授权网页登录,产物 `cli-starter-auth.apk` |
| `server/` | Sa-Token 认证服务(Spring Boot 4.1.1 + sa-token-spring-boot4-starter 1.46.0 + SQLite,端口 8090,CORS 放行独立前端) |
| `docs/api-contract.md` | 端点契约(权威):真实后端按此实现即可被 CLI 零改动对接 |
| `deploy/` | 部署与联调说明(当前直连;Higress 网关接入方案) |

## 快速开始

### 1. 起后端(JDK 25 + Maven,scoop `temurin25-jdk` / `maven4-aliyun`)

```powershell
cd server
mvn spring-boot:run          # 监听 8090,用户库 SQLite 落在 server/data/auth.db
```

### 2. 起前端(前后端分离,Vite + React)

```powershell
cd web
npm install        # 首次;依赖走 web/.npmrc 的 npmmirror 镜像(Node 20.19+)
npm run preview    # 服务 dist/ 构建产物于 http://localhost:8000
# 或 npm run dev   # 开发热更新,http://localhost:5173
```

浏览器打开 `http://127.0.0.1:8000`(登录页 `/login`)。

演示账号:`alice/alice123`(13800000001)、`bob/bob123`(13800000002)、`carol/carol123`、`dave/dave123`。

- 登录页双页签:**账号密码**(图形验证码,点图刷新)/**手机验证码**(发送有 60s 冷却)
- **自主注册**(`/register`):用户名/密码/手机号 + 图形验证码 + 短信码验机,注册即自动登录(新用户默认 user 角色)
- **demo 模式**:图形/短信验证码答案随响应回显 + 写服务端日志,便于本地联调;生产关闭 `debug-echo` 并接真实短信
- 控制台:`/` 设备码授权(输入 CLI 用户码后确认/拒绝)+ 查看/复制当前 Token

### 3. 用 CLI 登录(仓库根)

```powershell
# 方式一:浏览器设备码授权(自动打开浏览器,网页确认后 CLI 自动完成登录)
cargo run -- auth login --api-base http://127.0.0.1:8090 --method device

# 方式二:网页登录后复制 Token,粘贴给 CLI(--with-token 校验通过才入库)
cargo run -- auth login --api-base http://127.0.0.1:8090 --with-token

# 方式三:账号密码(服务端默认要求图形验证码:CLI 会把验证码图片存临时目录并自动打开,看图输入即可)
cargo run -- auth login --api-base http://127.0.0.1:8090 --method password --username alice

# 方式四:手机号 + 短信验证码(demo 模式验证码回显在终端提示)
cargo run -- auth login --api-base http://127.0.0.1:8090 --method sms --username 13800000001
# 按提示输入;首启不传 --api-base 会进入引导选择

cargo run -- whoami          # 带证调用 GET /api/user/me
cargo run -- auth status     # 校验凭证 + token 掩码显示
cargo run -- auth token      # 打印当前生效 token(供脚本)
cargo run -- auth logout     # 仅删本地凭证,不调服务端
```

## 命令与退出码

| 命令 | 行为 | 退出码 |
|---|---|---|
| `auth login` | 交互式登录:密码(图形验证码) / 手机+短信 / 粘贴 token / 浏览器设备码授权 | 0 / 4 认证失败(含业务 4xx 拒绝) / 3 网络 / 1 其他 |
| `auth status` | 逐账号校验,`--json` 恒退 0 | 全有效 0,否则 1 |
| `auth logout [账号] [--all]` | 仅本地删凭证 | 0;env token 时拒绝退 1 |
| `auth token [--account]` | 打印生效 token(env > keyring > file) | 无凭证 1 |
| `whoami [--json]` | 带证 GET /api/user/me | 未登录/凭证无效 4 |

登录方式选择:交互菜单,或 `--method password|token|device` / `--with-token < 文件`。

## 环境变量

| 变量 | 作用 |
|---|---|
| `CLI_STARTER_TOKEN` | 凭证覆盖,优先于本地存储;设置后 login/logout **写保护拒绝**(对齐 gh 的 GH_TOKEN) |
| `CLI_STARTER_CONFIG_DIR` | 配置目录覆盖(测试/CI 隔离) |
| `CLI_STARTER_CREDENTIAL_STORE` | `file`(强制明文文件)/ `keyring`(强制系统凭据库);缺省自动探测 |
| `CLI_STARTER_NO_BROWSER` | 设为 1 时**不自动打开**浏览器(设备码授权页)和验证码图片(密码登录),只打印路径(无界面/SSH 环境) |

## 凭证存储(对齐 gh auth)

- **首选系统凭据管理器**:Windows = 凭据管理器(keyring service 名 `cli-starter:<host[:port]>`),token 不落明文盘
- **回退**:系统凭据库不可用时自动落到 `{配置目录}/credentials.json`(明文,CLI 会警告)
- 优先级:`CLI_STARTER_TOKEN` > 激活槽凭证;多账号按 (api_base, account) 键控

## 配置文件

`%APPDATA%\cli-starter\config.toml`(可用 `CLI_STARTER_CONFIG_DIR` 覆盖):

```toml
version = 1
active_profile = "default"

[profiles.default]
api_base = "http://127.0.0.1:8090"
token_header = "satoken"        # 登录成功后按服务端返回回写
last_account = "alice"
# endpoints = { login = "/api/auth/login", ... }   # 全部可省略走默认
```

## 开发与测试

```powershell
cargo test          # 单元 + 端到端(httpmock 模拟 Sa-Token,全程无网络依赖)
cargo clippy        # 0 警告
cargo fmt
mvn -f server/pom.xml test
```

依赖拉取走仓库级 `.cargo/config.toml` 的 rsproxy 镜像(crates.io 直连在本机网络不可达)。

## 设计要点(以后抄这套模板时该看什么)

- **LoginFlow trait**(`src/auth/flow.rs`):新登录方式 = 实现 trait + `registry()` 注册一行;密码(验证码)/短信/粘贴 token/设备码四个现成示例
- **CredentialStore trait**(`src/auth/store.rs`):keyring / file 双实现,`CredentialChain` 做 env > keyring > file 的生效解析
- **端点全可配置**(`Profile.endpoints`):对接真实后端只需改 config,不改代码(前提:后端遵守 `docs/api-contract.md`)
- **凭证只发所属 host**:client 按 api_base 构造、凭证按 api_base 键存,结构上防泄漏
- **设备码会话隔离**:CLI 换发的 token 是服务端 device=cli 的独立会话,网页授权不共享 token
- **业务 4xx 拒绝**(414 验证码/415 手机号未注册/416 频率/417 短信码)映射到退出码 4;服务端中文 msg 不进 CLI 输出(GBK 控制台)
- CLI 输出仅 ASCII(`[ok]`/`[error]`),规避 Windows 控制台 GBK 乱码;交互提示走 stderr,stdout 保持可管道化

## 路线图

- [ ] Higress 网关接入(见 `deploy/README.md`,CLI 侧零改动,只换 api_base;前端与 `/api` 同源后 `web/assets/config.js` 置空)
- [ ] OAuth 授权登录(sa-token-oauth2,authorization_code + 本地回调)
- [x] 设备码登录(RFC 8628 精简版:`/api/auth/device/*` + 前端授权页,CLI 侧 `--method device`)
- [ ] 验证码/短信码/设备码凭据外置存储(Redis 等,多实例部署需要,当前均内存)
- [ ] 真实短信通道(替换 server 的 demo `SmsService` 并关闭 debug-echo,契约不变)
- [ ] `dist`(原 cargo-dist)多平台发布流水线

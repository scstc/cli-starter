# auth-server（Spring Boot 4 + Sa-Token 认证服务）

为 cli-starter 的 Rust CLI 与独立前端 `web/` 提供认证后端。API 契约见仓库根 `docs/api-contract.md`，CLI 按契约对接，与本实现的技术栈无关。

演示用户存 **SQLite**（`org.xerial:sqlite-jdbc:3.53.4.0` + `spring-boot-starter-jdbc`，JdbcTemplate 直用，无 MyBatis/JPA）。仅用于本地演示对接，**密码明文存储，禁止生产使用**。

## 前后端分离

登录/授权页面在仓库 `web/` 目录（纯 HTML+JS，无构建步骤），本服务只提供 `/api/**`：

- CORS 白名单：`starter.cors.allowed-origins`（默认放行 `localhost/127.0.0.1` 的 8000、5173 端口），仅作用于 `/api/**`，放行 `satoken` 头
- 前端部署地址：`starter.device-code.web-base`（设备码授权页回填到 `verificationUri`），默认 `http://127.0.0.1:8000`
- 本地联调：`cd web && python -m http.server 8000`，浏览器开 `http://127.0.0.1:8000/login.html`

## 演示用户（application.yml 的 starter.demo-users，可整体覆盖）

| 账号 | 密码 | 手机号 | loginId | roles |
|---|---|---|---|---|
| alice | alice123 | 13800000001 | 1 | user |
| bob | bob123 | 13800000002 | 2 | user, admin |
| carol | carol123 | 13800000003 | 3 | user |
| dave | dave123 | 13800000004 | 4 | user |

## 自主注册（POST /api/auth/register）

图形验证码 + **purpose=register 的短信验证码**（验证手机号本人）通过后创建用户并自动登录：

- 校验顺序：图形验证码(414) → 格式(420：用户名 3-32 位字母/数字/下划线、密码 6-64 位、手机号 1 开头 11 位) → 用户名唯一(418) → 手机号唯一(419) → 短信码(417)
- 新用户默认角色 `user`，login_id 取现有最大数字 +1；手机号有唯一索引（启动时自动创建，老库缺 phone 列先补列）
- `POST /api/auth/sms/send` 增加 `purpose` 字段：`login`（默认，手机号须已注册）/ `register`（手机号须未注册），两类验证码不跨场景复用

## 图形验证码（starter.captcha）

- `GET /api/auth/captcha` → `{captchaId, image(PNG data URI), debugCode?}`，4 位无歧义大写字母数字
- 登录（`required=true` 默认）必须带 `captchaId`/`captchaCode`；校验失败 HTTP 400 + code 414
- **一次性**（验证即销毁，防重放爆破）、TTL `ttl-seconds`（默认 120s）、大小写不敏感
- `debug-echo=true`（默认，demo 模式）把答案回显在响应里，**生产必须关闭**

## 短信验证码登录（starter.sms，demo 通道）

- `POST /api/auth/sms/send {phone}`：校验手机号已注册（否则 code 415）→ 生成 6 位码，写服务端日志（demo 通道，接入真实短信时替换 `SmsService.send`）；同号 60s 冷却（416）；`debug-echo=true` 时响应回显 `debugCode`
- `POST /api/auth/sms/login {phone, code}`：校验通过 → `StpUtil.login` 返回 SaTokenInfo；错码/过期/重放 → 417（一次性，TTL 300s）
- 验证码均内存态，服务重启失效

## 设备码登录（RFC 8628 精简版）

CLI `--method device` 对应的服务端流程（契约细节见 `docs/api-contract.md` 第 5 节）：

1. `POST /api/auth/device/code` — CLI 申请 `deviceCode` + `userCode`（8 位无歧义大写字母数字）
2. 用户在网页登录后 `POST /api/auth/device/authorize`（或 `/deny`）确认/拒绝
3. CLI 轮询 `POST /api/auth/device/token`：`pending` → `ok`（换发 **device=cli 的独立 token**，与网页会话互不影响）/ `denied` / `expired` / `invalid`（单次消费）

授权凭据**仅存内存**，配置项（`application.yml` 的 `starter.device-code`）：

```yaml
starter:
  device-code:
    web-base: http://127.0.0.1:8000   # 独立前端授权页地址
    ttl-seconds: 900                  # 设备码有效期
    poll-interval-seconds: 3          # CLI 轮询间隔建议值
```

## 环境要求

- JDK 25（scoop `temurin25-jdk`，JAVA_HOME 已指向）
- Maven（本机验证用 Maven 4.0.0-rc-6；Maven 3.9+ 同样可用）
- 端口 **8090**（8080 被 WSL 转发占用、8081 被 WSL 容器占用，勿改）

## 本地运行

```powershell
mvn -f server/pom.xml spring-boot:run
```

启动后监听 `http://localhost:8090`。

## curl 冒烟示例

```powershell
# 1. 登录（alice/alice123）
curl -s -X POST http://localhost:8090/api/auth/login -H "Content-Type: application/json" -d '{"username":"alice","password":"alice123"}'
# → {"code":200,"msg":"ok","data":{"tokenName":"satoken","tokenValue":"<uuid>","loginId":"1",...}}

# 2. 带 token 访问（header 名 satoken，值 = tokenValue，无 Bearer 前缀）
curl -s http://localhost:8090/api/user/me -H "satoken: <tokenValue>"
# → {"code":200,"msg":"ok","data":{"loginId":"1","username":"alice","roles":["user"],"device":"default"}}

# 3. 不带 token
curl -s http://localhost:8090/api/user/me
# → HTTP 401 {"code":401,"msg":"未能读取到有效 token","data":{"scene":-1}}

# 4. 登出后再用原 token
curl -s -X POST http://localhost:8090/api/auth/logout -H "satoken: <tokenValue>"
curl -s http://localhost:8090/api/user/me -H "satoken: <tokenValue>"
# → HTTP 401 {"code":401,"msg":"token 无效：<uuid>","data":{"scene":-2}}

# 5. 图形验证码登录(demo 模式回显答案;生产由用户读图输入)
curl -s http://localhost:8090/api/auth/captcha
# → {"code":200,...,"data":{"captchaId":"<uuid>","image":"data:image/png;base64,...","debugCode":"B2XD"}}

# 6. 短信验证码登录(写服务端日志,debug-echo 回显)
curl -s -X POST http://localhost:8090/api/auth/sms/send -H "Content-Type: application/json" -d '{"phone":"13800000001"}'
curl -s -X POST http://localhost:8090/api/auth/sms/login -H "Content-Type: application/json" -d '{"phone":"13800000001","code":"<6位码>"}'

# 7. 设备码:CLI 申请 -> 网页登录后授权 -> CLI 轮询(状态机 pending/ok/denied/expired/invalid)
curl -s -X POST http://localhost:8090/api/auth/device/code
```

PowerShell 里给 curl.exe 传 JSON 时，单引号字符串内的双引号**不要**加反斜杠转义（`'{\"a\":1}'` 会把 `\` 原样发出去导致非法 JSON → 兜底 500）。

## 集成测试

```powershell
mvn -f server/pom.xml test
```

## SB4 兼容验证记录（2026-09-16）

| 项 | 值 |
|---|---|
| Spring Boot / Spring Framework | 4.1.1 |
| Sa-Token | `cn.dev33:sa-token-spring-boot4-starter:1.46.0`（内部含 sa-token-jackson3，适配 Jackson 3） |
| JDK | OpenJDK 25.0.4（temurin25-jdk） |
| Maven | 4.0.0-rc-6（aliyun 镜像） |
| `mvn test` | 33/33 通过（基础/验证码/注册/短信/CORS/设备码/过期分支） |
| 冒烟 | login 200 / me 带 token 200 / me 无 token 401 scene=-1 / logout 200 / 错误密码 401 / 验证码缺失与错误 414 / 注册 200 且 418/419/420 / 短信 415/416/417 / CORS 预检放行与拒绝 / 设备码全链路 pending→ok→invalid |

**未触发降级**，SB4 + sa-token-spring-boot4-starter 直接可用。

> **注意**：pom 已显式设置 `project.build.sourceEncoding=UTF-8`。中文 Windows 上 javac 默认 GBK，会把源码里的中文常量（如错误文案断言）编歪——2026-09-16 引入静态页测试时发现并修复。

Sa-Token 1.46 相对早期文档的 API 差异（已按实际 API 调整，契约保持不变）：

- 配置键前缀仍为 `sa-token`，与计划一致（`token-name`/`token-style: uuid`/`timeout`/`is-read-header` 等均生效）。
- `SaLoginModel` 已被 `cn.dev33.satoken.stp.parameter.SaLoginParameter` 取代，设备字段为 `setDeviceType("default")`。
- `NotLoginException.getType()` 返回**字符串** `"-1".."-7"`，服务端已转回整数放进 `data.scene`，对外仍是数值。
- `SaTokenInfo` 的设备字段名为 `loginDeviceType`（非 `loginDevice`），另有 `tokenActiveTimeout`/`tokenSessionTimeout`/`tag` 字段，见契约文档的实测响应。

## 降级预案（当前无需）

若未来 SB4 starter 不可用，改两行即可回退 SB3，Java 代码不变：

```xml
<!-- 1) parent 版本：4.1.1 → 3.5.x（aliyun 最新 3.5 稳定版） -->
<version>3.5.x</version>
<!-- 2) starter 坐标：sa-token-spring-boot4-starter → sa-token-spring-boot3-starter -->
<artifactId>sa-token-spring-boot3-starter</artifactId>
```

## Dockerfile

`server/Dockerfile` 为分层构建（构建层 `maven:3.9-eclipse-temurin-25`——即 eclipse-temurin 25 JDK + Maven，运行层 `eclipse-temurin:25-jre`，非 root 用户，EXPOSE 8090）。**镜像当前未构建、不阻塞交付**：本机拉取 Docker Hub 镜像可能失败，该文件仅用于后续 compose 附录。

## SQLite 存储

- **库文件默认路径**：`server/data/auth.db`（相对工作目录 `jdbc:sqlite:data/auth.db`，首次启动自动建父目录、建表 `users(username PK, password, login_id, roles 逗号分隔, phone)`；**老库缺 phone 列时启动自动 ALTER TABLE 补列**，但已存在行不会回填手机号——删除 `data/auth.db` 重启即可按新种子重建）。
- **覆盖方式**（任选其一）：
  - 命令行参数：`--spring.datasource.url=jdbc:sqlite:D:/path/other.db`
  - 环境变量：`SPRING_DATASOURCE_URL=jdbc:sqlite:D:/path/other.db`（Spring Boot 标准 relaxed binding）
  - 或改 `application.yml` 的 `spring.datasource.url`
- **表自初始化**：启动时 `CREATE TABLE IF NOT EXISTS users` + phone 列迁移；**表为空时**才把 `application.yml` 的 `starter.demo-users` 写入作为种子（yml 缺省时代码内置同样的 alice/bob/carol/dave）。重启不重复种子、改动 users 表后 yml 不再生效（种子只进空表）。
- **持久化边界（重要）**：SQLite 持久化的只是**用户数据**（登录凭证/角色）；**token 会话仍存内存**（Sa-Token 默认无持久化），服务重启后所有旧 token 失效（CLI 需重新 login），`data/auth.db` 里的用户不受影响。
- 测试隔离：集成测试通过 `@TempDir` + `@DynamicPropertySource` 各自使用独立临时库，不碰 `data/auth.db`；测试数据源用无连接池的 `SimpleDriverDataSource`（Hikari 常驻连接会锁住 SQLite 文件，导致 Windows 上临时目录无法清理）。
- `server/data/` 为运行产物，不入库。

## 配置覆盖

内存演示用户来自 `application.yml` 的 `starter.demo-users`（默认 alice/alice123 → loginId "1"，bob/bob123 → "2"），可整体覆盖；若该段为空，代码内置同样的默认值。

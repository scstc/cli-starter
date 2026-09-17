# 认证服务 API 契约

> 版本：1.4（2026-09-17）· 参考实现：`server/`（Spring Boot 4 + Sa-Token 1.46，端口 8090）
>
> **声明：真实后端按此契约实现即可被 CLI 零改动对接。** CLI 只依赖本文档描述的路径、请求/响应字段与 HTTP 状态码，不依赖服务端技术栈。`server/` 中的实现即本契约的可运行基准。

## 基础约定

- Base URL：
  - 本地直连：`http://localhost:8090`
  - 经网关：`http://<gateway>/`（80）或 `http://<gateway>:28080/`
- **前后端分离**：登录/授权页面在独立前端 `web/`（默认 `http://127.0.0.1:8000`），跨域白名单由服务端 `starter.cors.allowed-origins` 配置（仅 `/api/**`，放行 `satoken` 头）。
- 请求体与响应体均为 JSON（UTF-8），`Content-Type: application/json`。
- 统一响应包裹：`{"code": <int>, "msg": <string>, "data": <object>}`；`data` 为空时字段整体省略（不返回 `"data":null`）。
- **鉴权规则**：请求头 `satoken: <tokenValue>`。header 名默认 `satoken`，值 = 登录响应中的 `data.tokenValue`，**无 `Bearer ` 等前缀**。服务端只从 header 读取（不读 cookie，不写响应 header）。

## 端点

### 1. POST /api/auth/login — 登录（账号密码）

无鉴权。`starter.captcha.required=true`（默认）时必须携带图形验证码（见第 7 节）。

请求：

```json
{"username": "alice", "password": "alice123", "captchaId": "<7节获取>", "captchaCode": "B2XD"}
```

`captchaId`/`captchaCode` 在 `required=false` 的服务端可整体省略。

成功（HTTP 200）：

```json
{
  "code": 200,
  "msg": "ok",
  "data": {
    "tokenName": "satoken",
    "tokenValue": "45291567-834e-4d63-95d4-9b51813aa9d4",
    "isLogin": true,
    "loginId": "1",
    "loginType": "login",
    "tokenTimeout": 2591999,
    "sessionTimeout": 2591999,
    "tokenSessionTimeout": -2,
    "tokenActiveTimeout": -1,
    "loginDeviceType": "default",
    "tag": null
  }
}
```

> CLI 需读取的字段：`tokenName`（鉴权 header 名）、`tokenValue`（鉴权 header 值）、`loginId`。其余字段为 Sa-Token 会话元信息（`tokenTimeout`/`sessionTimeout` 单位秒，`-1` 表示永不过期，`-2` 表示禁用该项；因登录耗时实测值会比配置值少 1 秒，如 2591999 vs 2592000，请勿按精确值断言）。设备字段实际名称为 `loginDeviceType`（计划文档中的 `loginDevice`/`activeTimeout` 为早期命名，以本实测为准）。

失败 — 错误凭证（HTTP 401）：

```json
{"code": 401, "msg": "用户名或密码错误"}
```

演示账号（可通过服务端 `application.yml` 覆盖）：`alice/alice123` → loginId `"1"`，roles `["user"]`，手机号 13800000001；`bob/bob123` → loginId `"2"`，roles `["user","admin"]`，手机号 13800000002；另有 `carol/carol123`(13800000003)、`dave/dave123`(13800000004)。

### 2. GET /api/auth/tokenInfo — 查询当前会话 token 信息

鉴权：header `satoken`。

成功（HTTP 200）：`data` 为当前 token 的全量会话信息，字段同上表登录响应。

失败（HTTP 401）：未登录统一响应，见「错误码与 scene」。

### 3. POST /api/auth/logout — 登出

鉴权：header `satoken`。无请求体。

成功（HTTP 200）：

```json
{"code": 200, "msg": "ok"}
```

失败（HTTP 401）：未登录统一响应。登出后原 token 立即失效（scene = -2）。

### 4. GET /api/user/me — 当前用户信息

鉴权：header `satoken`。

成功（HTTP 200）：

```json
{
  "code": 200,
  "msg": "ok",
  "data": {
    "loginId": "1",
    "username": "alice",
    "roles": ["user"],
    "device": "default"
  }
}
```

失败（HTTP 401）：未登录统一响应。

## 5. 设备码登录（RFC 8628 精简版）— CLI 浏览器授权

CLI 引导用户到网页完成登录授权：CLI 申请设备码 → 拉起浏览器打开授权页 → 用户在网页登录并确认 → CLI 按间隔轮询换取 token。端点统一 `/api/auth/device/*` 前缀，延续 `code/msg/data` 包裹。**授权凭据仅存内存**（`starter.device-code.ttl-seconds`，默认 900 秒），服务重启后全部失效。

> **会话隔离**：第 5.4 步为 CLI 换发的是**独立新 token**（Sa-Token device = `cli`），与网页登录会话（device = `default`）互不影响；`/api/user/me` 的 `device` 字段可区分。

### 5.1 POST /api/auth/device/code — 申请设备码（CLI 调用，无鉴权）

成功（HTTP 200）：

```json
{
  "code": 200,
  "msg": "ok",
  "data": {
    "deviceCode": "9ef7be0f-6c68-4b85-8f39-e0c90f2f7166",
    "userCode": "GNK3-6PWG",
    "verificationUri": "http://127.0.0.1:8090/",
    "verificationUriComplete": "http://127.0.0.1:8090/?user_code=GNK3-6PWG",
    "expiresIn": 900,
    "interval": 3
  }
}
```

- `userCode`：8 位无歧义大写字母数字（去 0/O/1/I），4+4 分组
- `verificationUri`：**独立前端**授权页地址（来自 `starter.device-code.web-base` 配置）；`verificationUriComplete` 已预填 user_code
- `expiresIn`：设备码剩余秒数（默认 900）；`interval`：CLI 建议轮询间隔秒数（默认 3，可配 `starter.device-code.poll-interval-seconds`）

### 5.2 POST /api/auth/device/authorize — 网页确认授权（浏览器调用）

鉴权：header `satoken`（网页登录后调用）。

请求：`{"userCode": "GNK3-6PWG"}`（容忍小写与缺失连字符）

成功（HTTP 200）：`{"code":200,"msg":"ok"}`

失败：
- 未登录 → HTTP 401 + scene（全局语义）
- user_code 未知/已过期 → HTTP 400：`{"code":412,"msg":"user_code 无效或已过期"}`

### 5.3 POST /api/auth/device/deny — 网页拒绝

鉴权与请求体同 5.2。成功 `{"code":200,"msg":"ok"}`；之后 CLI 轮询得到终态 `denied`。错误分支同 5.2。

### 5.4 POST /api/auth/device/token — CLI 轮询（无鉴权）

请求：`{"deviceCode": "<5.1 的 deviceCode>"}`

恒为 HTTP 200 + `code=200`，以 `data.status` 区分（CLI 勿依赖 `msg` 文案）：

| data.status | 含义 | data 其余字段 |
|---|---|---|
| `pending` | 尚未确认，继续按 `interval` 轮询 | 无 |
| `ok` | 已授权，**授权单次消费** | `token` = 与登录响应同构的 SaTokenInfo（CLI 专属新会话） |
| `denied` | 用户已拒绝（终态） | 无 |
| `expired` | 设备码已过期（终态） | 无 |
| `invalid` | deviceCode 未知或已被消费（终态） | 无 |

`ok` 示例：

```json
{"code":200,"msg":"ok","data":{"status":"ok","token":{"tokenName":"satoken","tokenValue":"29b63698-...","loginId":"1","loginDeviceType":"cli","tokenTimeout":2592000,"sessionTimeout":2592000,"isLogin":true,"loginType":"login","tokenActiveTimeout":-1,"tokenSessionTimeout":-2,"tag":null}}}
```

## 6. 独立前端（web/，前后端分离）

登录/授权页面在仓库 `web/` 目录（Vite + React 19 + TypeScript + react-router，构建产物任意静态服务器可托管），与后端分离部署：

- `GET <web-base>/login` — 登录页：三分段页签「账号密码（+图形验证码）| 手机验证码 | 扫码登录」
- `GET <web-base>/register` — 自主注册（第 9 节）
- `GET <web-base>/` — 控制台：设备码授权（5.2/5.3，支持 `?user_code=` 预填）+ 当前 Token 查看/复制
- **扫码登录**：登录页复用 5.1/5.4 —— 浏览器创建设备码并把 `verificationUriComplete` 渲染成二维码，手机扫码登录确认后浏览器轮询到 `ok` 自动登录；二维码地址即 `starter.device-code.web-base`，跨设备扫码需配置为手机可达的主机
- 前端地址由 `starter.device-code.web-base`（默认 `http://127.0.0.1:8000`）配置并回填到 5.1 的 `verificationUri`；后端跨域白名单见 `starter.cors.allowed-origins`
- 未登录访问控制台由前端跳转登录页（仅站内路径回跳）；token 存浏览器 `sessionStorage`

## 7. GET /api/auth/captcha — 获取图形验证码

无鉴权。`starter.captcha.required=true`（默认）时登录（第 1 节）必须携带。

成功（HTTP 200）：

```json
{
  "code": 200,
  "msg": "ok",
  "data": {
    "captchaId": "uuid",
    "image": "data:image/png;base64,iVBORw0KGgo...",
    "debugCode": "B2XD"
  }
}
```

- `image`：PNG 的 base64 data URI，可直接 `<img src>`；4 位无歧义大写字母数字（去 0/O/1/I），大小写不敏感
- **一次性**：验证即销毁（无论对错）；失败后须重新获取；有效期 `starter.captcha.ttl-seconds`（默认 120s）
- `debugCode`：**仅** `starter.captcha.debug-echo=true`（默认，demo 模式）时返回答案，生产必须关闭

校验失败（HTTP 400）：`{"code":414,"msg":"验证码错误或已过期"}`（缺字段同此）。

## 8. 手机号 + 短信验证码登录

demo 通道：验证码写服务端日志，`starter.sms.debug-echo=true`（默认）时随响应回显；生产必须关闭并接入真实短信服务商。

### 8.1 POST /api/auth/sms/send — 发送验证码

无鉴权。请求：`{"phone": "13800000001", "purpose": "login"}`

`purpose` = `login`（默认，手机号须已注册）| `register`（手机号须**未注册**）；两类验证码分开存储，**不跨场景复用**。

成功（HTTP 200）：`{"code":200,"msg":"ok","data":{"debugCode":"123456"}}`（`debugCode` 仅 demo 模式）

失败（HTTP 400）：`{"code":415,"msg":"手机号未注册"}`（login 场景）；`{"code":419,"msg":"手机号已被注册"}`（register 场景）；60s 内同场景重复发送 → `{"code":416,"msg":"发送太频繁，请稍后再试"}`（冷却 `starter.sms.send-cooldown-seconds`）

### 8.2 POST /api/auth/sms/login — 验证码登录

无鉴权。请求：`{"phone": "13800000001", "code": "123456"}`

成功（HTTP 200）：`data` 与第 1 节登录响应同构（SaTokenInfo，device=`default`）。

失败（HTTP 400）：手机号未注册 → `415`；验证码错误/过期/重放 → `{"code":417,"msg":"验证码错误或已过期"}`（一次性，有效期 `starter.sms.code-ttl-seconds` 默认 300s）。

## 9. POST /api/auth/register — 自主注册

无鉴权。组成：图形验证码（第 7 节，required 语义同登录）+ **purpose=register 的短信验证码**（验证手机号本人）。

请求：

```json
{"username": "newuser", "password": "secret123", "phone": "13800000020",
 "captchaId": "<7节获取>", "captchaCode": "B2XD", "smsCode": "123456"}
```

校验顺序：图形验证码(414) → 格式(420) → 用户名唯一(418) → 手机号唯一(419) → 短信码(417)。全部通过后写入用户（默认角色 `user`，login_id 自增，密码明文存储——演示定位）并**自动登录**。

成功（HTTP 200）：`data` 与登录响应同构（SaTokenInfo，device=`default`）。

失败（HTTP 400）：

| code | msg | 场景 |
|---|---|---|
| 414 | 验证码错误或已过期 | 图形验证码缺失/错误 |
| 417 | 验证码错误或已过期 | 短信码错误/过期/重放 |
| 418 | 用户名已存在 | 用户名唯一冲突 |
| 419 | 手机号已被注册 | 手机号唯一冲突（register 场景发短信同此码） |
| 420 | 注册信息不合法… | 用户名/密码/手机号格式不满足 |

## 错误码

| HTTP | code | 含义 |
|---|---|---|
| 200 | 200 | 成功 |
| 401 | 401 | 登录凭证错误（login）/ 未提供或无效 token（其余端点），`data.scene` 细分原因 |
| 403 | 403 | 已登录但无此权限（`msg` 含缺失的权限串）；CORS 白名单外的预检请求同此状态 |
| 400 | 412 | 设备码登录：user_code 无效或已过期 |
| 400 | 414 | 图形验证码错误、过期或缺失（`starter.captcha.required=true` 时必填） |
| 400 | 415 | 手机号未注册 |
| 400 | 416 | 短信发送太频繁（冷却期内） |
| 400 | 417 | 短信验证码错误、过期或重放 |
| 400 | 418 | 注册：用户名已存在 |
| 400 | 419 | 注册：手机号已被注册（含 register 场景发短信） |
| 400 | 420 | 注册：用户名/密码/手机号格式不合法 |
| 400 | 430 | 第三方/一键登录：授权票或 token 无效、过期、重放 |
| 400 | 431 | 第三方登录：不支持的登录方式（当前支持 wechat/qq） |
| 500 | 500 | 服务器内部错误（兜底，`msg` 为"服务器内部错误"；请求体非法 JSON 也落入此兜底） |

## 10. 第三方登录（微信/QQ，demo 模拟授权）

> demo 通道：不跳转厂商授权页，由服务端直接签发模拟授权票。真实接入时 authorize 改为
> 302 跳厂商授权页、callback 改为调厂商 SDK 换 openid，请求/响应形状与业务码不变。

### 10.1 POST /api/auth/oauth/{provider}/authorize — 发起授权

`{provider}` = `wechat` | `qq`（其他 → HTTP 400 + `431`）。请求：`{"nickname": "可选昵称"}`

成功（HTTP 200）：`{"code":200,"msg":"ok","data":{"ticket":"oauth_…","nickname":"微信用户"}}`

> demo 语义：openId 由 provider+昵称确定性派生，**同昵称重复登录即同一账号**，便于演示身份绑定。

### 10.2 POST /api/auth/oauth/{provider}/callback — 授权回调换登录态

请求：`{"ticket": "<10.1 的 ticket>"}`（一次性，5 分钟有效）

成功（HTTP 200）：`data` 与登录响应同构。首次登录自动建号（用户名 `wechat_xxx`/`qq_xxx`，默认角色 user，无手机号）并把 (provider, openId) 写入 `user_identities` 绑定表，此后同身份登录同账号。

失败（HTTP 400）：票无效/过期/重放 → `430`。

## 11. 运营商一键登录（demo 通道）

> 真实实现：App 内嵌运营商 SDK（移动/联通/电信统一认证）取本机号码 token，
> 服务端调运营商网关换手机号。demo 通道用固定模拟本机号
> `starter.oneclick.demo-phone`（默认 13800000001，即 alice）替代整条链路。

### 11.1 GET /api/auth/oneclick/preview — 预览本机号码

成功（HTTP 200）：`{"code":200,"msg":"ok","data":{"token":"oc_…","maskedPhone":"138****0001"}}`

### 11.2 POST /api/auth/oneclick/login — 一键登录

请求：`{"token": "<11.1 的 token>"}`（一次性，2 分钟有效）

成功（HTTP 200）：`data` 与登录响应同构。本机号未注册时自动建号（用户名 `mobile_xxx`）。

失败（HTTP 400）：token 无效/过期/重放 → `430`。

### scene 取值（HTTP 401 时 `data.scene`）

| scene | 含义 | 参考响应体 |
|---|---|---|
| -1 | 未提供 token | `{"code":401,"msg":"未能读取到有效 token","data":{"scene":-1}}` |
| -2 | token 无效（不存在 / 已登出） | `{"code":401,"msg":"token 无效：<token>","data":{"scene":-2}}` |
| -3 | token 已过期 | `msg` 为过期提示 |
| -4 | token 已被顶下线（同账号新登录顶替，`is-concurrent=false` 时） | 同上 |
| -5 | token 已被踢下线 | 同上 |
| -6 | token 已被冻结 | 预留（当前配置不触发） |
| -7 | 缺少 token 前缀 | 预留（当前配置不触发） |

> 说明：`msg` 文案仅供参考，**CLI 判定请以 HTTP 状态码 + code + data.scene 为准**，不要匹配具体中文文案。

## curl 示例

```bash
TOKEN=$(curl -s -X POST http://localhost:8090/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice123"}' | sed -E 's/.*"tokenValue":"([^"]+)".*/\1/')

# 本地直连 8090
curl -s http://localhost:8090/api/user/me -H "satoken: $TOKEN"

# 经网关 80
curl -s http://<gateway>/api/user/me -H "satoken: $TOKEN"

# 经网关 28080
curl -s http://<gateway>:28080/api/user/me -H "satoken: $TOKEN"

# 登出
curl -s -X POST http://localhost:8090/api/auth/logout -H "satoken: $TOKEN"

# 图形验证码登录(demo 模式回显答案,生产由用户读图输入)
CAP=$(curl -s http://localhost:8090/api/auth/captcha)
CID=$(echo "$CAP" | sed -E 's/.*"captchaId":"([^"]+)".*/\1/')
CCODE=$(echo "$CAP" | sed -E 's/.*"debugCode":"([^"]+)".*/\1/')
curl -s -X POST http://localhost:8090/api/auth/login -H "Content-Type: application/json" \
  -d "{\"username\":\"alice\",\"password\":\"alice123\",\"captchaId\":\"$CID\",\"captchaCode\":\"$CCODE\"}"

# 短信验证码登录
curl -s -X POST http://localhost:8090/api/auth/sms/send -H "Content-Type: application/json" -d '{"phone":"13800000001"}'
curl -s -X POST http://localhost:8090/api/auth/sms/login -H "Content-Type: application/json" -d '{"phone":"13800000001","code":"<收到的6位码>"}'

# 设备码:CLI 申请 -> 网页登录后授权 -> CLI 轮询
DC=$(curl -s -X POST http://localhost:8090/api/auth/device/code | sed -E 's/.*"deviceCode":"([^"]+)".*/\1/')
curl -s -X POST http://localhost:8090/api/auth/device/authorize -H "satoken: $TOKEN" -d '{"userCode":"<CLI显示的用户码>"}'
curl -s -X POST http://localhost:8090/api/auth/device/token -H "Content-Type: application/json" -d "{\"deviceCode\":\"$DC\"}"
```

PowerShell 注意：给 curl.exe 传 `-d '{"username":"alice","password":"alice123"}'` 时单引号内双引号不要加 `\` 转义。

## 扩展点（当前预留，未实现）

- **OAuth2 / OIDC**：未来可引入 `sa-token-oauth2`，支持 `password`、`authorization_code` 模式；届时 `/api/auth/*` 下新增授权端点，本契约的统一包裹与鉴权 header 规则不变。
- **真实短信通道**：`SmsService` 当前为 demo 通道（写日志 + 可选回显）；接入服务商时替换其 `send` 实现并关闭 `starter.sms.debug-echo`，端点契约不变。
- **验证码/短信码外置存储**：当前均内存态（单实例）；多实例部署需外置（Redis 等），语义（一次性 + TTL + 业务码）不变。
- **设备码授权凭据外置存储**：同上，见第 5 节。
- 新增端点时应延续：统一 `code/msg/data` 包裹、`satoken` header、401+scene 语义、业务 4xx 走 HTTP 400。

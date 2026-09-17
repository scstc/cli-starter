# cli-starter web(React 前端)

前后端分离的认证前端:**分屏式 SSO 统一认证门户**(左侧品牌区 + 右侧登录卡)。**Vite + React 19 + TypeScript + react-router v7**,无 UI 组件库(设计系统自持于 `src/index.css`,接 antd/MUI 时替换即可)。

## 页面结构

| 路由 | 组件 | 说明 |
|---|---|---|
| `/login` | `src/pages/LoginPage.tsx` | 登录页,三分段页签:**账号密码**(图形验证码)/ **手机验证码**(60s 倒计时)/ **扫码登录**(二维码轮询,见下) |
| `/register` | `src/pages/RegisterPage.tsx` | 自主注册:用户名/密码/手机号 + 图形验证码 + purpose=register 短信码,成功自动登录进控制台 |
| `/` | `src/pages/ConsolePage.tsx` | 控制台:设备码授权(输入 CLI 用户码,确认/拒绝)+ 当前 Token 查看/复制;未登录自动跳登录页 |

- 布局外壳:`src/components/AuthLayout.tsx`(品牌区:渐变、特性清单、终端窗口装饰、页脚)
- API 封装:`src/api/client.ts`(统一 base、envelope 拆包、satoken 头、`ApiError` 归一化)
- 登录态:`src/auth/AuthContext.tsx`(token 存 `sessionStorage`,加载用户信息,401 自动清会话)

## 扫码登录(设备码授权复用)

登录页「扫码登录」页签:浏览器调 `POST /api/auth/device/code` 生成授权链接 → `qrcode` 渲染成二维码 → 轮询 `POST /api/auth/device/token`;手机扫码在手机上登录并确认后,本浏览器自动登录。

> 二维码地址来自 `starter.device-code.web-base`。**跨设备扫码要求该地址手机可达**:
> 本机演示(127.0.0.1)只能本机扫码;局域网演示把它改成主机局域网 IP(如 `http://192.168.1.150:8000`)即可。

- API 封装:`src/api/client.ts`(统一 base、envelope 拆包、satoken 头、`ApiError` 归一化)
- 登录态:`src/auth/AuthContext.tsx`(token 存 `sessionStorage`,加载用户信息,401 自动清会话)
- 路由守卫:两个页面各自 `Navigate` 重定向,`?redirect=` 仅接受站内路径

## 配置后端地址

`web/.env.local`(或构建环境变量):

```
VITE_API_BASE=http://127.0.0.1:8090
```

缺省 `http://127.0.0.1:8090`;同源网关部署(前端与 `/api` 同域)置空即可。后端跨域白名单见 `server/src/main/resources/application.yml` 的 `starter.cors.allowed-origins`(dev 5173 / preview 8000 已放行),新增部署地址时两边同步。

## 本地运行

```powershell
# 需要 Node 20.19+;依赖走 web/.npmrc 的 npmmirror 镜像
cd web
npm install
npm run dev        # 开发: http://localhost:5173 (热更新)
npm run build      # 类型检查 + 产物构建到 dist/
npm run preview    # 预览构建产物: http://localhost:8000
```

另起后端:`mvn -f server/pom.xml spring-boot:run`(8090)。

- 演示账号:`alice/alice123`(13800000001)、`bob/bob123`(13800000002)、`carol/carol123`、`dave/dave123`
- 图形/短信验证码当前为 **demo 模式**(答案随响应回显 + 写服务端日志),生产必须关闭 `starter.captcha.debug-echo` / `starter.sms.debug-echo` 并接入真实短信通道

## 约定

- 「退出登录」只清本地会话副本,不调服务端登出——避免把已复制给 CLI 的 token 一并作废
- 会话校验走 `GET /api/user/me`,401 清 token 后由路由守卫带回登录页

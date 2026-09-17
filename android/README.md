# cli-starter Android 客户端

手机授权客户端:登录后扫描网页登录页的「扫码登录」二维码,在手机上确认,网页即自动登录。纯 Java 单 Activity,零 AndroidX/Gradle 依赖,用 build-tools 工具链(aapt2 + javac + d8 + apksigner)直接构建签名 APK。

## 功能

- **一键登录(演示)**:调 `/api/auth/oneclick/*` demo 通道,免密登录模拟本机号(默认 alice)
- **账号密码登录**:支持图形验证码(验证码图片直接显示在 App 内)
- **扫一扫**:Camera2 + ZXing 解码网页二维码 → 提取 8 位用户码 → 确认授权(`/api/auth/device/authorize`)→ 网页轮询到 ok 自动登录
- **手动输入用户码**:无相机/相机不可用时的兜底路径
- 服务器地址可配置(默认 `http://192.168.9.143:8090`,即开发机局域网地址)

## 构建

```powershell
# 依赖:%LOCALAPPDATA%/Android/Sdk(build-tools 35 + platforms android-35)、JDK 17(scoop temurin17-jdk)
cd android
bash build-apk.sh
# 产物: android/cli-starter-auth.apk(已用 debug.keystore 签名)
```

## 安装与联调

1. 手机与本机同一局域网;把 App 首页「服务器地址」改成开发机局域网 IP(默认已填,`ipconfig` 可查),端口 8090
2. Windows 防火墙放行 8090 入站(首次会弹窗,或手动加规则)
3. 传输安装:`adb install -r cli-starter-auth.apk`,或把 APK 发到手机直接安装(需允许未知来源)
4. 打开 App → 一键登录(或账号密码)→ 网页登录页切「扫码登录」→ 用 App 扫一扫 → 确认授权 → 网页自动登录

## 结构

| 文件 | 说明 |
|---|---|
| `src/com/starter/authapp/MainActivity.java` | 界面与流程(登录/扫码/授权,程序化 UI) |
| `src/com/starter/authapp/CameraScanner.java` | Camera2 预览 + ZXing 逐帧解码(YUV→亮度平面) |
| `src/com/starter/authapp/HttpApi.java` | HttpURLConnection 极简 REST 客户端 |
| `build-apk.sh` | 手工构建管线(aapt2→javac→d8→zipalign→apksigner) |
| `libs/zxing-core.jar` | 二维码解码(来自阿里云 Maven 镜像) |

> 本地无 Android 系统镜像,模拟器不可用;APK 已通过 aapt badging 与 apksigner verify 校验,真机安装后如有问题优先检查服务器地址与防火墙。

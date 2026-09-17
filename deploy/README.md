# deploy:联调与部署说明

## 当前状态:直连联调(无网关)

按用户决定(2026-09-16),当前阶段**跳过网关**,CLI 直连本地后端:

```powershell
# 终端 1:起后端
cd server; mvn spring-boot:run            # 8090

# 终端 2:CLI 直连
cargo run -- auth login --api-base http://127.0.0.1:8090 --method password --username alice
```

后续接网关时,CLI 侧**零改动**:config.toml 里 `api_base` 从 `http://127.0.0.1:8090` 换成网关地址即可(契约见 `docs/api-contract.md`,网关 1:1 透传 `/api/` 前缀)。

## Higress 网关接入(暂缓,已探明的事实)

本机 WSL(Ubuntu-26.04)与 Higress 相关的现状(2026-09-16 探测):

1. **k3d 集群内有 higress-system 命名空间**(43 天前部署):svc 存在
   - `higress-gateway` LoadBalancer(80→30088、443→31132),经 k3d serverlb 暴露为 WSL 宿主 **80 / 8443**
   - `higress-console` LoadBalancer(8080→30378),暴露为 WSL 宿主 **8080**
   - **但 pod 疑似已不在/异常**:`kubectl get pods -n higress-system` 空,curl 80/8080/LB 直连全部失败 → 接入前需先修复(查 deployment 是否被删,必要时重装)
2. **备胎:`~/higress/docker-compose.yml`**(从未启动过):all-in-one 镜像
   `higress-registry.cn-hangzhou.cr.aliyuncs.com/higress/all-in-one:latest`,端口映射 **28080(网关)/ 28443(https)/ 28001(控制台)**,`./data:/data` 持久化,`restart: always`——与现有服务端口不冲突,一条 `docker compose up -d` 即可拉起
3. 配置后端:Nacos(nacos-server 1.4.1 容器,8848,restart=always)在跑
4. **端口避让**:WSL 侧 8081 被 `wsl-metric-server` 容器占用(经 localhost 转发会顶掉 Windows 的 8081)——这就是后端选 **8090** 的原因

### 接入步骤(恢复执行时)

1. 修复或拉起网关(优先修复 k3d 内实例;不行就 compose 备胎 28080)
2. 后端起在 Windows(`mvn spring-boot:run`,8090);WSL 内取 Windows host IP:`ip route show default | awk '{print $3}'`(NAT 模式,WSL 重启可能漂移;根治:`.wslconfig` 设 `networkingMode=mirrored` 后 WSL 内直接 `127.0.0.1:8090`)
3. 控制台(8080 或 28001)→ 服务来源指向 `<win-ip>:8090` → 路由 `/api/` 前缀 1:1 透传
4. 验证:`curl http://localhost:<网关端口>/api/user/me` 期望 401 JSON(scene -1)
5. CLI:`cargo run -- auth login --api-base http://localhost:<网关端口> ...`

## 环境备忘

- JDK 25(scoop `temurin25-jdk`,JAVA_HOME 已指向)/ Maven 4(scoop `maven4-aliyun`,aliyun 镜像,SB4 与 sa-token 构件均已验证可解析)
- crates.io 直连 TLS 被掐断,仓库已带 rsproxy 镜像配置(`.cargo/config.toml`)

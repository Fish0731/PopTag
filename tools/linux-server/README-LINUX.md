# PopTag 2010 Linux 公网独立服务端安装指南

本目录是从完整客户端分离出来的 **Linux 独立联机服务端**，已包含：
- **Linux Epoll 兼容修复**：修复了原版 `PassportServer`（TCP 47611）与 `UdpRelay`（UDP 4151-4160）在 Linux Epoll 事件循环下绑定失败的问题（同时兼容 x86_64 / ARM64 / NIO 回退）。
- **公网 NAT / 对称型 NAT 中继优化**：`UdpRelay` 支持基于来源 IP 回退匹配与每通道 NAT 打洞回包学习，兼容国内宽带、4G/5G 热点及运营商 CGNAT。
- **公网 IP / 域名 / 环境变量支持**：支持在 `server/Config.yaml` 配置 `ServerIP`，或通过环境变量 `POPTAG_SERVER_IP` 指定公网 IP 或域名。
- **全部既有联机修复**：含多端开房同步、重名账号防顶号、怪物模式单人阵亡继续、以及怪物模式第 2/3 关队友解救同步。

---

## 1. 一键安装（推荐：Ubuntu / Debian / CentOS / Rocky / 阿里云 Linux）

1. 将压缩包 `PopTag-Linux-Server.tar.gz` 上传到 Linux 服务器（例如 `/opt/`）。
2. SSH 登录 Linux 服务器并执行：

```bash
cd /opt
tar -xzf PopTag-Linux-Server.tar.gz
cd PopTag-Linux-Server
sudo bash install.sh
```

安装脚本会自动完成：
- 检查并自动安装 **OpenJDK 21**；
- 自动检测并提示确认你的 **公网 IPv4**，写入 `server/Config.yaml`；
- 自动在 `ufw` / `firewalld` 放行全部所需端口；
- 注册并启动 **systemd 开机自启服务**（`poptag-server.service`）。

---

## 2. 云服务器安全组端口（必须放行！）

阿里云、腾讯云、华为云、AWS 等云厂商控制台的**安全组 / 防火墙**必须放行以下入方向端口：

| 协议 | 端口 | 用途 |
| :--- | :--- | :--- |
| **TCP** | `47611` | Passport 登录认证 |
| **TCP** | `3838`, `4848`, `4849` | 游戏主服 / 频道 / 房间通信 |
| **UDP** | `3839`, `4849` | 玩家 UDP 节点注册与心跳 |
| **UDP** | `4151-4160` | 局内 P2P UDP 中继转发（公网联机必开） |

> 如果只开了 TCP 没开 UDP `4151-4160`，会出现“能进大厅和房间，但开局后互相看不到移动/放泡”。

---

## 3. 常用管理命令

在 `PopTag-Linux-Server` 目录下执行：

```bash
./poptag.sh status             # 查看运行状态、公网 IP 及端口监听
./poptag.sh restart            # 重启服务端
./poptag.sh stop               # 停止服务端
./poptag.sh logs               # 实时查看日志（Ctrl+C 退出查看）
./poptag.sh set-ip 1.2.3.4     # 修改公网 IP（改完后执行 restart）
```

---

## 4. 玩家 Windows 客户端如何连公网服

1. 所有联机玩家的 Windows 客户端均需使用**同一版更新后的 `client\CA.exe`**。
2. 玩家关闭游戏后，双击客户端目录里的 **`client\改服务器地址.cmd`**，输入你的 **Linux 公网 IPv4** 并回车。
3. 直接双击 **`client\CA.exe`** 启动游戏登录即可（**不要**再运行本地的 `Start.cmd`，否则会试图启动本地服务端）。

---

## 5. Docker 部署（可选）

如果你习惯用 Docker：

1. 编辑 `docker-compose.yml`，将 `POPTAG_SERVER_IP` 改为你的公网 IPv4；
2. 运行：

```bash
docker compose up -d
docker compose logs -f
```

---

## 6. 账号存档备份与迁移

- 玩家账号数据保存在 `server/data/local-accounts/`。
- 如需把原先 Windows 本机里的账号迁移到 Linux 服务器，直接将 Windows 下的 `server\data\local-accounts\*` 复制到 Linux 的 `server/data/local-accounts/` 后重启服务端即可。

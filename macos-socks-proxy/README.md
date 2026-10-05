# macos-socks-proxy

通过 SSH 隧道，把本机 SOCKS5 代理的出口放到日本的 Mac（`liangmac-mini`，Tailscale `100.105.59.31`）。
后台看门狗自动保活、断线重连、开机自启，出口 IP 为 **日本大阪（130.62.209.98, mfeed.ad.jp）**。

## 文件说明

| 文件 | 用途 |
| --- | --- |
| `config.ps1` | 配置：SSH 目标、本地端口、密钥路径、深探测目标 |
| `ssh-proxy.ps1` | 看门狗主程序（循环保活，前台/后台都可用） |
| `install.ps1` | 安装：注册开机自启（计划任务；无权限时自动请求 UAC，再失败则回退到启动文件夹） |
| `uninstall.ps1` | 卸载：移除自启项并停止后台进程 |
| `check.ps1` | 状态检查：任务/进程/端口/出口 IP/最近日志 |
| `logs\` | 运行日志（按天产生，自动保留 14 天） |

## 安装

```powershell
cd C:\Users\22771\Desktop\项目\macos-socks-proxy
pwsh -ExecutionPolicy Bypass -File .\install.ps1
```

注册计划任务会弹一次 UAC，点「是」。安装完看门狗立即在后台运行，以后每次登录 Windows 自动启动。

## 常用操作

```powershell
# 查看状态 + 测出口 IP
pwsh -ExecutionPolicy Bypass -File .\check.ps1

# 卸载（不想要了）
pwsh -ExecutionPolicy Bypass -File .\uninstall.ps1

# 前台手动运行（调试用，Ctrl+C 停止）
pwsh -ExecutionPolicy Bypass -File .\ssh-proxy.ps1
```

## 工作原理

- 每 10 秒检查 ssh 进程与 `127.0.0.1:1080` 监听状态；掉线自动重连（指数退避，最长 60 秒）
- ssh 启用 `ServerAliveInterval=30 / ServerAliveCountMax=3`：链路中断约 90 秒内 ssh 自行退出，看门狗随即拉起
- 每 5 分钟做一次端到端深探测（经代理 TCP 连接 `1.1.1.1:443`），隧道「假死」也会被检测并重启
- 进程互斥锁防止重复运行；日志写入 `logs\`

## 客户端接入 SOCKS5（`127.0.0.1:1080`）

- curl 测试：`curl.exe --socks5-hostname 127.0.0.1:1080 https://api.ipify.org`
- Chrome/Edge：快捷方式目标后追加 `--proxy-server="socks5://127.0.0.1:1080"`
- Firefox：设置 → 网络设置 → 手动配置 → SOCKS 主机 `127.0.0.1`、端口 `1080`，勾选「使用 SOCKS v5 时代理 DNS」
- 其他支持 SOCKS5 的软件直接填 `127.0.0.1:1080`
- 注：Windows 设置里的系统代理不支持 SOCKS；如需系统级可装 Proxifier 等工具，或逐应用配置

## 注意事项

- 出口是 Mac 的网络（日本大阪）。Mac 关机/断网时代理会中断，恢复后看门狗自动连回来
- **不要**对目标 Mac 用密码方式反复尝试 SSH（OpenSSH 10 的防爆破惩罚会掐断该 IP 的所有连接，包括正在使用的隧道）。本方案全程用密钥登录，无此问题
- 修改 `config.ps1` 后：重启看门狗进程（卸载后重装，或结束旧进程重新运行）生效
- 手动运行 `ssh -N -D 1080 ...` 报端口占用属正常：后台服务已在监听；用 `check.ps1` 确认即可
- 依赖：Windows OpenSSH 客户端、PowerShell 7、Tailscale、以及 `%USERPROFILE%\.ssh\id_ed25519` 免密密钥（已配置）

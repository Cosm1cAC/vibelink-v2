# yto-portal-auth

`yto2022`（成都正龙汇，Panabit/LFradius 门户）的**自动重认证守护**。

背景：该 WiFi 是网页认证网络，认证会话会周期性掉线——WiFi 显示"已连接、无 Internet"。
一掉线，PC 的整条链路全断：外网、wproxy 代理、Tailscale 直连（IPv6 随网络一起消失）都会受影响。
本组件定时探测联网状态，发现被门户劫持就自动重新登录，把 PC 的联网恢复。

## 原理

1. 每 15 秒探测 `http://connect.rom.miui.com/generate_204`（掉线后 30 秒内完成自动重连）：
   - `204` → 在线，继续
   - `302`（带 `Location: https://wifi.zlhsc.cn/lfradius/...`）→ 被门户劫持，进入登录
2. GET 门户登录页（带 cookie），解析表单（`Panabit_login`）里的全部隐藏字段
3. POST 到 `https://wifi.zlhsc.cn/lfradius/libs/portal/unify/portal.php/login/Panabit_login/`，
   字段：`usrname` / `passwd` / `treaty` + 隐藏字段（nasid / usrmac / usrip / basip / success / fail 等，均从页面实时解析）
4. 复查探测：`204` 即登录成功，写日志；失败则下一轮重试

## 部署（Windows）

1. 填写账号密码（不入库，仅存本机）：
   `%USERPROFILE%\.config\yto-portal-auth\config.psd1`
   ```powershell
   @{
       Username = '你的账号'
       Password = '你的密码'
   }
   ```
   （首次运行 `install.ps1` 会自动生成模板）
2. 安装并启动：
   ```powershell
   pwsh -ExecutionPolicy Bypass -File .\install.ps1            # 计划任务失败自动回退启动文件夹
   pwsh -ExecutionPolicy Bypass -File .\install.ps1 -Elevate   # 想注册计划任务（弹一次 UAC）
   ```
3. 查看状态 / 卸载：
   ```powershell
   pwsh -ExecutionPolicy Bypass -File .\status.ps1
   pwsh -ExecutionPolicy Bypass -File .\uninstall.ps1          # -KeepConfig 保留账号配置
   ```
4. 手动单次测试（不装守护也可用）：
   ```powershell
   pwsh -ExecutionPolicy Bypass -File .\portal-auth.ps1 -Once
   ```

## 文件

| 文件 | 说明 |
| --- | --- |
| `windows/portal-auth.ps1` | 守护主程序（`-Once` 单次模式；默认 60s 间隔，互斥防重复） |
| `windows/install.ps1` | 生成配置模板、注册登录自启（计划任务 → UAC → 启动文件夹回退）、立即启动 |
| `windows/status.ps1` | 配置/任务/看门狗/探测状态 + 最近日志 |
| `windows/uninstall.ps1` | 卸载自启并停止看门狗 |

## 验证记录（2026-10-06）

- ✅ 抓包（切换 WiFi 后 30 秒内完成，已切回）：门户 302 劫持地址
  `https://wifi.zlhsc.cn/lfradius/libs/portal/unify/portal.php/login/main/nasid/2/?wlanuserip=...&clientmac=...&paip=192.168.80.254...`
- ✅ 登录页解析：表单 action `/lfradius/libs/portal/unify/portal.php/login/Panabit_login/`，
  字段 `usrname`/`passwd`/`treaty` + 隐藏字段 `nasid/usrmac/usrip/basip/success/fail/offline/portal_version/portal_papchap/portal_get`
- ✅ 登录 POST 实测：密码正确 → 门户返回成功流程（`panabit_value` 跳转页，base64 含 usrname/usrmac/usrip）；
  密码错误 → 返回"密码错误"提示，脚本可正确区分两种结果
- ✅ 守护部署：启动文件夹自启（计划任务注册需管理员，走回退）、看门狗运行中、在线探测 204
- 备注：NAS 旧版 webauth API（`action=radius`）返回"系统版本太低不支持 radius 登录"，
  实际认证由 LFradius 服务端完成，脚本无需走 NAS
- ⏳ captive→自动重登 的闭环由下一次自然掉线验证（日志：`windows/logs/auth-*.log`）

## 注意

- **同账号多设备可能互踢**：如果手机也用同一账号连 yto2022，会互相顶掉；PC 守护会反复重登
- 探测走直连（`curl --noproxy`），不受 wproxy 系统代理影响
- 账号密码只存 `~/.config/yto-portal-auth/config.psd1`，不进仓库

# captive-portal

yto2022 宿舍网（成都正龙汇 / Panabit 门户）**自动重登看门狗**：门户认证掉线时自动重新登录，避免断网影响代理栈。

## 背景

- 该网络用 Panabit captive portal（门户 `wifi.zlhsc.cn`，认证机 `192.168.80.254`）做认证计费
- 门户会话会不定时掉线（链路正常但流量被重定向到认证页），需手动重登
- 本机（Windows）长期挂代理栈，断认证会连带 Tailscale/代理全挂，所以做自动化

## 原理

`portal-autologin.ps1` 常驻循环（60 秒一次）：

1. 探测 `http://connect.rom.miui.com/generate_204`（在线=204；被门户劫持=非 204，再用百度二次确认防误判）
2. 判定离线时：先 GET 门户登录页拿 cookie，再 POST 登录表单（`Panabit_login`，含动态 usrmac/usrip）
3. 重登后复检；结果写入日志 `%USERPROFILE%\.captive-portal.log`
4. DNS 不通时自动用门户 IP（47.109.134.147）`--resolve` 重试

## 部署（已完成）

- 脚本：`%USERPROFILE%\.captive-portal\portal-autologin.ps1`
- 隐藏自启：启动文件夹 `portal-autologin.vbs`（登录后自动运行）
- 立即启动：`wscript.exe "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\portal-autologin.vbs"`

## 凭据（需要一次手工创建，不进仓库）

```powershell
'{"username":"你的账号","password":"你的密码"}' | Set-Content -Encoding UTF8 "$env:USERPROFILE\.captive-portal.json"
```

## 日志 / 排查

```powershell
Get-Content "$env:USERPROFILE\.captive-portal.log" -Tail 20
```

## 卸载

- 删除启动文件夹里的 `portal-autologin.vbs`
- 结束进程：`Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*portal-autologin*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }`
- 删除 `%USERPROFILE%\.captive-portal\` 与 `%USERPROFILE%\.captive-portal.json`

## 备注

- 探测请求本身也算流量，顺带起到会话保活作用
- 该网络设备的调查记录见对话/根 README（疑似第三方运营的宿舍网，网关 TTL=128 疑为 Windows 软路由）

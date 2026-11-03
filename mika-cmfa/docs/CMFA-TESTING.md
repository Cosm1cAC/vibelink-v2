# CMFA 集成：构建、测试、真机配置

## 构建

项目路径含中文时，AGP 的 `compileDebugAidl` 会抛 `MalformedInputException`。
用 ASCII junction 构建：

```powershell
cmd /c mklink /J C:\mika-cmfa "C:\Users\22771\Desktop\项目\proxy-stack\mika-cmfa"
cd C:\mika-cmfa
.\gradlew.bat :app:assembleDebug --console=plain `
  "-Porg.gradle.java.installations.paths=C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
```

APK：`C:\mika-cmfa\app\build\outputs\apk\debug\app-debug.apk`

## x86_64 内核（仅模拟器需要）

arm64 真机直接用 mika 原有的 `jniLibs/arm64-v8a/*.so`。模拟器是 x86_64，需要按
`third_party/ClashMetaForAndroid/INTEGRATION.md` 的「x86_64 内核」一节重编：

- mihomo commit：`3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`
- Go：`CGO_ENABLED=1 GOOS=android GOARCH=amd64` + NDK 29 clang
- JNI 桥：`core/src/main/cpp/CMakeLists.x64.txt`
- 产物：`app/src/main/jniLibs/x86_64/libclash.so`、`libclash_bridge.so`

## 模拟器测试（本次已验证）

```powershell
adb reverse tcp:18995 tcp:18995   # vibelink hub
adb reverse tcp:18996 tcp:18996   # pchub
adb reverse tcp:18997 tcp:18997   # pcexit
python -m http.server 18080 --bind 127.0.0.1   # 在放 pchub.yaml 的目录
adb reverse tcp:18080 tcp:18080
```

CMFA：Profile → `+` → URL → `http://127.0.0.1:18080/pchub.yaml` → Save → 选中 → 启动。

模拟器没有 GeoIP 数据，测试 profile 用私有网段 CIDR 规则，避免 `GEOIP` 触发
`geoip.metadb` 下载超时。

## 真机配置

真机不能访问 `127.0.0.1` 的 PC 代理。把 `pchub-pcexit.example.yaml` 里的
`<PC_LAN_IP>` 换成**热点宿主 PC 当前 LAN IP**（不要写死 `192.168.137.24`），
`<MESH_TOKEN>` 换成 vibelink 的 token，然后导入 CMFA。

token 可从 App 的 prefs 读：

```powershell
adb shell run-as com.wangzan101.mika cat shared_prefs/vibelink_remote.xml
```

GeoIP：真机建议把 `geoip.metadb` / `geosite.dat` 放进 CMFA 的
`/data/data/com.wangzan101.mika/files/clash/`，否则第一次用 `GEOIP` 规则会尝试从
GitHub 下载（国内可能超时）。

## 许可证

从集成 CMFA 起，合并后的 App 按 **GPL-3.0** 分发。见根目录 `LICENSE`、`NOTICE`、
`THIRD_PARTY_NOTICES.md`。

## 外出使用（不在 PC 局域网）

当前 LAN profile 走 `192.168.5.2:7898`，**离开这个局域网就不通**。
外出时用 `cmfa-tailscale-outside.example.yaml`：

- CMFA 内置 Tailscale（单个 VPN 槽，不需要另开 Tailscale App）
- 出口：日本 Mac `100.105.59.31:7891`（已实测 Google 204，出口 `130.62.209.98`）
- 需要一次性 Tailscale auth key：`https://login.tailscale.com/admin/settings/keys`
- 把 YAML 里的 `<TAILSCALE_AUTH_KEY>` 替换后导入 CMFA，选中并启动

备选：PC 的 tailnet 代理 `http://100.98.206.126:7898`（`wproxy` 认证）也实测 Google 204，
但同样需要手机先加入 tailnet；Mac 的 socks5 更快。

注意：
- 带着 LAN profile 离开局域网会没有外网（代理不可达）。出门前先停止 VPN，
  或切换到 Tailscale profile。
- `socks.vibelink.cloud` 是 cloudflared 的隧道入口，不是公网 SOCKS，手机不能直接填。
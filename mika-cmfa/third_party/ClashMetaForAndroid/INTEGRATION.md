# CMFA 合并方案（B：替换 ClashVpnService）

> 状态：源码已 vendored，native 兼容性已验证；Gradle/Manifest 合并未开始。
> 许可证：ClashMetaForAndroid 是 **GPL-3.0**。合并后整个 App 必须按 GPL-3.0 分发，
> 不能再保持 mika 原来的 Apache-2.0。根目录 `NOTICE` 要写清来源与版本。

## 目标

- 底部第三个 tab「Clash」= CMFA 的完整 UI（状态 / Proxy / 配置文件 / 日志 / 设置 / 帮助 / 关于）。
- 只保留一套内核：mika 现有的 `libclash.so`（mihomo）+ `libclash_bridge.so`（CMFA 的 C 桥）。
- 删除 mika 的 `ClashVpnService`、`ClashConfig`、`VpnNetWatcher`、`VpnRetryReceiver`、
  `VpnTestReceiver`，VPN 生命周期交给 CMFA 的 `service`。

## 已确认

源码：`mika/third_party/ClashMetaForAndroid`（v2.11.36，`main`，2026-11-02 拉取）。

mika 的 `libclash_bridge.so` 已包含 CMFA `Bridge.kt` 需要的全部符号，已抽查：

```
nativeLoad / nativeStartTun / nativeStopTun / nativeQueryTunnelState
nativeQueryGroup / nativePatchSelector / nativeHealthCheckAll
nativeStartHttp / nativeSubscribeLogcat / nativeQueryProviders
nativeFetchAndValid / nativeReadOverride / nativeWriteOverride
```

因此**不需要重编 Go 内核**：把 CMFA `core` 的 `System.loadLibrary("bridge")` 改成
`System.loadLibrary("clash_bridge")`，jniLibs 继续用 mika 的 `app/src/main/jniLibs`。

## 合并步骤

1. **模块**
   - `mika/settings.gradle.kts` 增加 `:cmfa-core`、`:cmfa-common`、`:cmfa-service`、`:cmfa-design`、`:cmfa-app`。
   - 把 `third_party/ClashMetaForAndroid/{core,common,service,design,app}` 作为这些模块的
     `sourceSets` 目录，避免复制源码。
2. **core**
   - 去掉 `golang-android` 插件、CMake、flavor；只保留 Kotlin。
   - `Bridge.kt` 改 `loadLibrary("clash_bridge")`，`Global.application` 由 `MikaApp` 初始化。
   - jniLibs 指向 `mika/app/src/main/jniLibs`（同一份 `.so`，不复制）。
3. **service**
   - 接 ksp（kaidl + Room），Manifest 合并 `ClashService` / `VpnService` / `TileService`。
   - 删除 mika 的 `com.wangzan101.mika.net.ClashVpnService` 及四个 Vpn*.kt。
4. **app → library**
   - `com.android.application` 改 `com.android.library`，去掉 `applicationId`、launcher、
     `MainApplication`（改由 `MikaApp` 初始化 CMFA 的 `Remote`/`Store`）。
   - 保留 `com.github.kr328.clash.MainActivity` 及其 fragment；Manifest 里合并 activity。
5. **tab**
   - `HomeScreen` 底部三栏：消息 / Clash / 我的。
   - Clash tab 用 `Intent` 启动 `com.github.kr328.clash.MainActivity`（单 Activity，全屏）。
   - 删除 mika 的 `ProxyScreen`、`ClashConfig`，pchub/pcexit 迁成 CMFA 的默认 profile。
6. **验证**
   - 冷启动 → Clash tab → CMFA 首页显示「已停止/运行中」。
   - 启动 VPN，访问外网；100.x 走 pchub，其余走 pcexit。
   - mika 聊天/推送/更新在 CMFA service 运行时仍正常。

## 风险

- CMFA 的 `app` 用 kapt + dataBinding + 两个 flavor（alpha/meta）；mika 没有 flavor，
  需要固定成一个 flavor 或去掉 flavor 分支。
- CMFA 的 `desgin`/`app` 资源名和 mika 的资源可能冲突，需要 `tools:replace` 或改名。
- CMFA 的 service 跑在 `:service` 进程，mika 的 `MikaApp` 初始化要放在两个进程都能跑的位置。
- 合并期间 mika 会暂时编不过；建议在 `mika-cmfa/` 副本里做，绿了再替换。

---

## 实施结果（2026-11-02，已验证）

状态：**已完成并在 `emulator-5554`（x86_64 / Android 16）跑通**。线上 `mika/` 未改动。

### 模块与接线
- Gradle 模块：`:cmfa-hideapi`、`:cmfa-common`、`:cmfa-core`、`:cmfa-service`、`:cmfa-design`、`:cmfa-app`
  （均作为 library；`cmfa-app` 不再带 launcher / `MainApplication`）。
- `:app` 依赖 `:cmfa-app`、`:cmfa-common`；`buildFeatures.dataBinding = true` + kapt
  （必须生成 `androidx.databinding.DataBinderMapperImpl`，否则 CMFA 界面启动即崩）。
- `MikaApp`：`attachBaseContext` 调 `Global.init(this)`；主进程 `onCreate` 调 `Remote.launch()`。
- 删除 mika 旧内核桥 `com/github/kr328/clash/core/bridge/*` 与 `ClashVpnService`、`ClashConfig`、
  `VpnNetWatcher`、`VpnRetryReceiver`、`VpnTestReceiver`；Manifest 同步移除。
- `HomeScreen` 底部三栏：消息 / Clash / 我的；Clash 启动 `com.github.kr328.clash.MainActivity`。
- `ProxyScreen` 保留为入口页，按钮打开 CMFA。

### x86_64 内核（模拟器测试用）
CMFA v2.11.36 对应 mihomo commit `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`。

1. 把 mihomo 源码放到 `core/src/foss/golang/clash`。
2. `core/src/main/golang/native/go.mod` 指向该目录；`go mod tidy`。
3. 用 NDK 29 编 Go c-shared：
   `CGO_ENABLED=1 GOOS=android GOARCH=amd64 CC=<ndk>/.../x86_64-linux-android21-clang.cmd`
   `go build -buildmode=c-shared -tags=foss,with_gvisor,cmfa -trimpath -o goout/metaRelease/x86_64/libclash.so .`
4. 用 `core/src/main/cpp/CMakeLists.x64.txt`（独立版，跳过 git 子模块探测）编 JNI 桥，
   产物 `libbridge.so` 重命名为 `libclash_bridge.so`。
5. 产物放到 `app/src/main/jniLibs/x86_64/`；arm64 仍用 mika 原有 `.so`。

注意：项目路径含中文时 AGP 的 `compileDebugAidl` 会 `MalformedInputException`；
构建时用 ASCII junction `C:\mika-cmfa -> ...\mika-cmfa`。

### UI 风格（只改样式）
- `design/src/main/res/values/colors.xml` 改为 mika 调色板（#0A7CFF / #F7F7F8 / #9A9BA3 …）。
- 新增 `AppCardView`：16dp 圆角、0 阴影、1dp 描边；通过 `materialCardViewStyle` 全局应用。
- 布局、文案、功能逻辑未改。

### 验证
- Clash tab → CMFA 首页；Profile 从 `http://127.0.0.1:18080/pchub.yaml` 导入（adb reverse）。
- 启动 VPN → `tun0` 172.19.0.1/30；Chrome 打开 `ipinfo.io/ip` 返回 `130.62.209.98`（经 pcexit）。
- mika 消息页正常，底部三栏正常。
- 模拟器没有 GeoIP 数据文件；测试 profile 用私有网段 CIDR 规则，避免 `GEOIP`
  触发 `geoip.metadb` 下载超时。真机应把 `geoip.metadb` / `geosite.dat` 放进 CMFA 的 `files/clash`。

### 已知限制
- 线上 `mika/` 未替换；`mika-cmfa` 是可运行的集成候选。
- 模拟器 APK 约 175 MB（含 x86_64 80 MB 内核）；真机可只保留 arm64。
- 仍保留 mika 的 `ProxyStore`（应用层 HTTP 代理），与 CMFA VPN 并存；后续可删。
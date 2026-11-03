# CMFA 合并方案（B：替换 ClashVpnService）

> 状态：`mika-cmfa/` 副本；`cmfa-hideapi` / `cmfa-common` / `cmfa-core` / `cmfa-service`
> 已编译通过（2026-11-02），`cmfa-design` / `cmfa-app` 与 mika 接线未开始。
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

## 进度

| 模块 | 状态 |
|---|---|
| `mika-cmfa/` 副本 | 已建立（不含 build/.gradle 产物） |
| `cmfa-hideapi` | 编译通过 |
| `cmfa-common` | 编译通过 |
| `cmfa-core` | 编译通过（改用 `libclash_bridge.so`，复用 mika 的 jniLibs） |
| `cmfa-service` | 编译通过（KSP kaidl + Room 正常生成） |
| `cmfa-design` / `cmfa-app` | 未开始 |
| mika 接线 / 底部 tab / 删旧 ClashVpnService | 未开始 |

## 风险

- CMFA 的 `app` 用 kapt + dataBinding + 两个 flavor（alpha/meta）；mika 没有 flavor，
  需要固定成一个 flavor 或去掉 flavor 分支。
- CMFA 的 `desgin`/`app` 资源名和 mika 的资源可能冲突，需要 `tools:replace` 或改名。
- CMFA 的 service 跑在 `:service` 进程，mika 的 `MikaApp` 初始化要放在两个进程都能跑的位置。
- 合并期间 mika 会暂时编不过；建议在 `mika-cmfa/` 副本里做，绿了再替换。

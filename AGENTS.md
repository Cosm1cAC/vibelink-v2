# AGENTS.md — proxy-stack 交接入口

本仓库有多个子项目；**当前 PC（`HAOSHAO`）的宿舍热点代理策略以
[`wproxy/AGENTS.md`](wproxy/AGENTS.md) 为准**，它优先级高于 `wproxy/README.md`
中历史的固定 IP 配置。

先记住这三条，再动手：

1. PC 是热点宿主，翻墙出口在笔记本；PC 系统代理必须指向**笔记本当前 LAN IP** 的
   `7897`，由 `C:\Users\22771\bin\pc-proxy-failover.ps1` 自动发现。
2. **不要写死 `192.168.137.24`**，也不要把系统代理长期设成 `127.0.0.1:7897`
   （PC 本地 mihomo 只有 18–27KB/s，仅最后兜底）。
3. 笔记本 repo
   `C:\Users\admin\Desktop\项目\proxy-stack\wproxy\` 是源文件；改脚本先在笔记本改，
   再同步到 PC。PC repo 有大量未提交改动，**不要 `git pull` / `git reset --hard`**。

完整拓扑、检查命令、故障决策树、不要做的事项，见
[`wproxy/AGENTS.md`](wproxy/AGENTS.md)。

# 2026-10-25 笔记本 magpie「几秒重连一次」根因：socks-relay 线程泄漏（已修）

## 一句话

不是 DNS、不是 magpie、不是网络 —— 是 Mac 上 `socks-relay.py` 的 **`pipe()` 没有超时**，
一端 EOF 后另一个方向的 `recv()` 永久阻塞，导致 `t1.join()/t2.join()` 永不返回，
线程和 socket 无限堆积（**520 线程 / 58 个 CLOSE_WAIT**），最后把 relay 拖垮。

## 证据链

### 1. Mac 侧 socket 状态异常

```
$ netstat -an -p tcp | grep 7891 | awk '{print $6}' | sort | uniq -c
  58 CLOSE_WAIT      ← 对端已关，本端没 close
  76 ESTABLISHED
  13 FIN_WAIT_2
```

`CLOSE_WAIT` 的含义就是：**对端发了 FIN，本端应用层没调 `close()`**。

### 2. 线程数持续高于连接数

```
01:12:00 threads=533 est=82 fromLaptop=50
01:17:29 threads=526 CW=59
```

而笔记本自己的 mihomo 只有 28~39 条连接 —— **Mac 侧挂着 50 条永不释放的死连接**。

### 3. 线程栈直接坐实

```
$ sample 30847 1
+ 440 __recvfrom  (in libsystem_kernel.dylib)
```

大量线程卡在 `__recvfrom`，且对应 socket 是 `CLOSE_WAIT` / `CLOSED` —— 典型「对端已关、
本端 recv 不返回」的阻塞。

### 4. 用户感知到的现象

- 笔记本 magpie 每次请求几秒就断，反复重连；
- mihomo 日志满屏 `dial PROXY ... error: context deadline exceeded`
  （**健康检查却显示 153ms 正常** —— 因为探活是新连接，不经过泄漏的旧连接）；
- 越是并发（54 条 mtalk.google.com 长轮询）越容易触发。

## 根因代码

```python
def pipe(src, dst):
    try:
        while True:
            data = src.recv(BUF)      # ← 无超时，永久阻塞
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)   # ← 只 shutdown 写端，不 close
        except OSError:
            pass

def handle_connect(client, host, port):
    ...
    t1.start(); t2.start()
    t1.join()          # ← 若 t1 的 pipe 卡在 recv，这里永远不返回
    t2.join()
    target.close()     # ← 走不到这里，client 也从不 close
```

关键点：`target.settimeout(None)` 把目标连接设成了**无限阻塞**。
只要一端静默（长轮询、NAT 超时、对端 FIN），`recv()` 就挂住，`join()` 跟着挂住，
`client.close()` 永远执行不到 → `CLOSE_WAIT` + 线程双泄漏。

## 修复（已上线并入库）

三处改动，都在 `macos-udp-proxy/socks-relay.py`：

```python
PIPE_IDLE_TIMEOUT = 300      # 单方向静默上限（秒）
CONN_MAX_LIFETIME = 3600     # 单条隧道最长存活（秒），兜底

def pipe(src, dst, stop=None):
    # recv 加超时 + 可中断
    src.settimeout(PIPE_IDLE_TIMEOUT)
    while True:
        if stop is not None and stop.is_set():
            break
        try:
            data = src.recv(BUF)
        except socket.timeout:
            if stop is not None and stop.is_set():
                break
            continue
        if not data:
            break
        dst.sendall(data)
    finally:
        dst.shutdown(socket.SHUT_WR)
        if stop is not None:
            stop.set()          # ← 通知另一方向收工

def handle_connect(client, host, port):
    ...
    stop = threading.Event()
    t1 = Thread(target=pipe, args=(client, target, stop)); t1.start()
    t2 = Thread(target=pipe, args=(target, client, stop)); t2.start()
    deadline = time.time() + CONN_MAX_LIFETIME
    while (t1.is_alive() or t2.is_alive()) and time.time() < deadline:
        t1.join(timeout=0.5)
        t2.join(timeout=0.5)
    stop.set()
    for sk in (client, target):        # ← 两个 socket 都强制收尾
        sk.shutdown(socket.SHUT_RDWR)
        sk.close()
```

## 效果对比

| 指标 | 修复前 | 修复后 |
| --- | --- | --- |
| CLOSE_WAIT | 58（持续） | 0~1 |
| 线程数 | 520（持续上涨） | 64~111（随负载浮动） |
| 笔记本 magpie 流式 | 17/20，波动大 | **20/20** |
| 节点延迟 | 366~3451ms 横跳 | **151~156ms 稳定** |

## 部署方式

```bash
# 1. 备份（已做：~/bin/socks-relay.py.bak-20261025）
# 2. 写入新版本后重启
launchctl kickstart -k user/$(id -u)/com.socks-relay
# 3. 验证
netstat -an -p tcp | grep 7891 | awk '{print $6}' | sort | uniq -c   # CLOSE_WAIT 应接近 0
ps -M $(lsof -nP -iTCP:7891 -sTCP:LISTEN | tail -1 | awk '{print $2}') | wc -l   # 线程应 < 150
```

仓库副本：`macos-udp-proxy/socks-relay.py`，与 Mac 上 `~/bin/socks-relay.py`
SHA256 一致（`c9234913b7db...`）。

## 相关但独立的问题（同日记录）

- `2026-10-24-mac-reboot-fix.md`：Mac 重启后 launchd 服务不加载（需 `LimitLoadToSessionType=Background`）
- `2026-10-24-hotspot-dns-tcp.md`：ICS 热点 DNS 中继不响应 + bing 网段 TCP 不通
- `2026-10-24-laptop-magpie-flap.md`：54 条 mtalk 长连接挤 DERP、Mac 重复进程抢端口

## 教训

**「健康检查正常但实际请求全失败」是容量/泄漏类 bug 的典型特征。**
mihomo 的 delay 探活每次新建连接，永远测不到泄漏的旧连接；要看 `CLOSE_WAIT`、
线程数、FD 数这类**存量指标**，而不是只看**新建连接的成功率**。

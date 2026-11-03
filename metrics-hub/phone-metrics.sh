#!/data/data/com.termux/files/usr/bin/bash
# 手机状态上报（Termux 运行，无需 root）
#
# 安装（Termux 里执行一次）：
#   pkg install -y curl
#   curl -sL http://100.98.206.126:18993/static/phone-metrics.sh -o ~/phone-metrics.sh
#   chmod +x ~/phone-metrics.sh
#   termux-wake-lock
#   nohup ~/phone-metrics.sh >> ~/phone-metrics.log 2>&1 &
#
# 开机自启（可选）：安装 Termux:Boot，把下面两行放进 ~/.termux/boot/metrics.sh
#   termux-wake-lock
#   nohup ~/phone-metrics.sh >> ~/phone-metrics.log 2>&1 &
#
# 说明：
# - 手机需开着 ClashMetaForAndroid（其 tsnode 提供 tailnet 连通）
# - Android 10+ 对应用隐藏 /proc/stat、/proc/net/dev、/proc/uptime；脚本已改用
#   /sys/class/net/*/statistics（带宽）、/proc/meminfo（内存）、df（磁盘）、
#   /dev/tcp 计时（延迟）、/proc/loadavg（负载，若可读）；CPU 若被禁显示 --
# - 电量：优先 /sys/class/power_supply；装了 Termux:API 后自动用 termux-battery-status
# - 运行日志：~/phone-metrics.log（只在出错时写诊断）
# - Hub 地址/设备 ID 可覆盖: HUB=... ID=... ~/phone-metrics.sh

HUB="${HUB:-http://100.98.206.126:18993}"
INTERVAL="${INTERVAL:-5}"
IDFILE="$HOME/.metrics-id"

MODEL="$(getprop ro.product.model 2>/dev/null || echo Android)"
if [ -f "$IDFILE" ]; then
  ID="$(cat "$IDFILE")"
else
  ID="android-$(echo "$MODEL" | tr ' A-Z' '-a-z' | tr -cd 'a-z0-9-')"
  echo "$ID" > "$IDFILE"
fi
NC="$(nproc 2>/dev/null || echo 4)"

echo "$(date '+%F %T') phone-metrics started id=$ID hub=$HUB" >&2

prev_total=0; prev_idle=0
prev_rx=0; prev_tx=0; prev_t=0
lat_cache=""; lat_t=0

read_cpu() {
  read -r _ u n s i w irq sirq st _rest 2>/dev/null < /proc/stat || return 1
  total=$((u + n + s + i + w + irq + sirq + st))
  idle=$((i + w))
  return 0
}

read_net() {
  rx=0; tx=0
  netsrc="none"
  sysfs_ok=0
  for d in /sys/class/net/*; do
    iface="${d##*/}"
    case "$iface" in
      lo|ifb*|dummy*|sit*|ip6tnl*|tun*|tap*|rmnet_ipa*|rmnet_mhi*) continue ;;
    esac
    [ -r "$d/statistics/rx_bytes" ] || continue
    r="$(cat "$d/statistics/rx_bytes" 2>/dev/null)"
    t="$(cat "$d/statistics/tx_bytes" 2>/dev/null)"
    if [ -n "$r" ]; then
      sysfs_ok=1
      rx=$((rx + r)); tx=$((tx + ${t:-0}))
    fi
  done
  if [ "$sysfs_ok" -eq 1 ]; then
    netsrc="sysfs"
  else
    while read -r line; do
      case "$line" in
        *:*)
          iface="${line%%:*}"
          iface="$(echo "$iface" | tr -d ' ')"
          [ "$iface" = "lo" ] && continue
          set -- ${line#*:}
          rx=$((rx + $1)); tx=$((tx + $9))
          netsrc="proc"
          ;;
      esac
    done 2>/dev/null < /proc/net/dev
  fi
  # SELinux 下 sysfs/proc 常被拒（-r 只看权限位不反映 MAC），退到 netlink
  if [ "${rx:-0}" -eq 0 ] && [ "${tx:-0}" -eq 0 ] && command -v ip >/dev/null 2>&1; then
    ipout=$(ip -s link show wlan0 2>/dev/null)
    r=$(printf '%s\n' "$ipout" | awk '/RX:/{getline; print $1; exit}')
    t=$(printf '%s\n' "$ipout" | awk '/TX:/{getline; print $1; exit}')
    case "$r" in ''|*[!0-9]*) ;; *) rx=$r; tx=${t:-0}; netsrc="netlink" ;; esac
  fi
}

tcp_ms() {
  local t0 t1
  t0=$(date +%s%N)
  if (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null; then
    t1=$(date +%s%N)
    exec 3>&- 3<&-
    echo $(((t1 - t0) / 1000000))
  else
    echo ""
  fi
}

while true; do
  now=$(date +%s)

  if read_cpu; then
    if [ "$prev_total" -gt 0 ]; then
      dt=$((total - prev_total)); di=$((idle - prev_idle))
      cpu=$(awk -v dt="$dt" -v di="$di" 'BEGIN{ if(dt<=0){print ""} else {v=(dt-di)*100/dt; if(v<0)v=0; if(v>100)v=100; printf "%.1f", v} }')
    else
      cpu=""
    fi
    prev_total=$total; prev_idle=$idle
  else
    cpu=""
  fi

  mem_total=$(awk '/MemTotal/{print $2*1024}' /proc/meminfo 2>/dev/null)
  mem_avail=$(awk '/MemAvailable/{print $2*1024}' /proc/meminfo 2>/dev/null)
  mem_used=$(( ${mem_total:-0} - ${mem_avail:-0} ))
  [ "$mem_used" -lt 0 ] && mem_used=0

  disk_total=$(df -kP /data 2>/dev/null | awk 'NR==2{print $2*1024}')
  disk_used=$(df -kP /data 2>/dev/null | awk 'NR==2{print $3*1024}')

  uptime_s=$(awk '{print int($1)}' /proc/uptime 2>/dev/null)

  load_json="null"
  if read -r l1 l5 l15 _ 2>/dev/null < /proc/loadavg; then
    load_json="[$l1,$l5,$l15]"
  fi

  read_net
  if [ "$prev_t" -gt 0 ]; then
    dts=$((now - prev_t)); [ "$dts" -lt 1 ] && dts=1
    rx_bps=$(( (rx - prev_rx) / dts )); tx_bps=$(( (tx - prev_tx) / dts ))
  else
    rx_bps=0; tx_bps=0
  fi
  prev_rx=$rx; prev_tx=$tx; prev_t=$now

  # sysfs/proc/netlink 均被 SELinux 拒绝时，用 CMFA 内核 API 的实时速率（本机 9090，非流式读取 2s）
  if [ "${rx:-0}" -eq 0 ] && [ "${tx:-0}" -eq 0 ]; then
    mtraffic=$(curl -s -m 2 http://127.0.0.1:9090/traffic 2>/dev/null | tail -n 1)
    mu=$(printf '%s' "$mtraffic" | sed -n 's/.*"up":\([0-9][0-9]*\).*/\1/p')
    md=$(printf '%s' "$mtraffic" | sed -n 's/.*"down":\([0-9][0-9]*\).*/\1/p')
    if [ -n "$md" ]; then rx_bps=$md; tx_bps=${mu:-0}; netsrc="mihomo"; fi
  fi

  batt=""; bstat=""
  for c in /sys/class/power_supply/*/capacity; do
    if [ -r "$c" ]; then batt=$(cat "$c" 2>/dev/null); break; fi
  done
  for s in /sys/class/power_supply/*/status; do
    if [ -r "$s" ]; then bstat=$(cat "$s" 2>/dev/null); break; fi
  done
  if [ -z "$batt" ] && command -v termux-battery-status >/dev/null 2>&1; then
    tb=$(termux-battery-status 2>/dev/null)
    batt=$(printf '%s' "$tb" | sed -n 's/.*"percentage": *\([0-9]*\).*/\1/p')
    bstat=$(printf '%s' "$tb" | sed -n 's/.*"status": *"\([A-Za-z]*\)".*/\1/p')
  fi

  if [ $((now - lat_t)) -ge 15 ]; then
    # 统一口径：直连国内（223.5.5.5:443 TCP 握手）/ 代理链路（经 CMFA 链路到目标的真实请求 TTFB）
    l1=$(tcp_ms 223.5.5.5 443)
    l2=$(curl -s -o /dev/null -m 8 -w '%{time_starttransfer}' http://cp.cloudflare.com/generate_204 2>/dev/null | awk '{printf "%.0f", $1*1000}')
    lat_cache="\"直连国内\":${l1:-null},\"代理链路\":${l2:-null}"
    lat_t=$now
  fi

  disk_pct=$(awk -v u="${disk_used:-0}" -v t="${disk_total:-0}" 'BEGIN{ if(t>0) printf "%.1f", u*100/t; else print "null" }')

  link_mbps=""
  for sp in /sys/class/net/wlan*/speed; do
    if [ -r "$sp" ]; then
      v=$(cat "$sp" 2>/dev/null)
      case "$v" in ''|*[!0-9]*) ;; *) link_mbps="$v"; break ;; esac
    fi
  done
  # sysfs 被 SELinux 拒时，用 Termux:API 取 WiFi 链路速率
  if [ -z "$link_mbps" ] && command -v termux-wifi-connectioninfo >/dev/null 2>&1; then
    link_mbps=$(termux-wifi-connectioninfo 2>/dev/null | tr -d ' \n' | sed -n 's/.*"link_speed_mbps":\([0-9][0-9]*\).*/\1/p')
  fi

  json=$(cat <<EOF
{"id":"$ID","name":"$MODEL","platform":"android","metrics":{"host":"$MODEL","model":"Android","platform":"android","boot_time":null,"uptime_s":${uptime_s:-0},"ncpu":${NC:-4},"cpu_pct":${cpu:-null},"queue":null,"load":$load_json,"gpu":null,"mem":{"total":${mem_total:-0},"used":${mem_used:-0}},"disk":{"mount":"/data","total":${disk_total:-0},"used":${disk_used:-0},"used_pct":${disk_pct:-null}},"net":{"rx_bps":${rx_bps:-0},"tx_bps":${tx_bps:-0},"src":"${netsrc:-none}"},"link_mbps":${link_mbps:-null},"latency":{$lat_cache},"battery":${batt:-null},"charging":"$bstat"}}
EOF
)
  resp=$(curl -s -m 10 --retry 2 --retry-delay 1 --retry-all-errors -o "$HOME/.phone-metrics.resp" -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary "$json" "$HUB/ingest")
  if [ "$resp" != "200" ]; then
    echo "$(date '+%F %T') post=$resp resp=$(head -c 200 "$HOME/.phone-metrics.resp" 2>/dev/null) json=$(printf '%s' "$json" | head -c 400)" >&2
  fi

  sleep "$INTERVAL"
done

#!/data/data/com.termux/files/usr/bin/bash
# 手机状态上报（Termux 运行，无需 root）
#
# 安装（Termux 里执行一次）：
#   pkg install -y curl
#   curl -sL http://100.74.71.80:18993/static/phone-metrics.sh -o ~/phone-metrics.sh
#   chmod +x ~/phone-metrics.sh
#   termux-wake-lock; nohup ~/phone-metrics.sh >/dev/null 2>&1 &
#
# 开机自启（可选）：安装 Termux:Boot，把下面两行放进 ~/.termux/boot/metrics.sh
#   termux-wake-lock
#   nohup ~/phone-metrics.sh >/dev/null 2>&1 &
#
# 说明：手机需开着 ClashMetaForAndroid（其 tsnode 提供 tailnet 连通）；
#       Hub 地址与设备 ID 可用环境变量覆盖: HUB=... ID=... ~/phone-metrics.sh

HUB="${HUB:-http://100.74.71.80:18993}"
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

prev_total=0; prev_idle=0
prev_rx=0; prev_tx=0; prev_t=0
lat_cache=""; lat_t=0

read_cpu() {
  read -r _ u n s i w irq sirq st _rest < /proc/stat
  total=$((u + n + s + i + w + irq + sirq + st))
  idle=$((i + w))
}

read_net() {
  rx=0; tx=0
  while read -r line; do
    case "$line" in
      *:*)
        iface="${line%%:*}"
        iface="$(echo "$iface" | tr -d ' ')"
        [ "$iface" = "lo" ] && continue
        set -- ${line#*:}
        rx=$((rx + $1)); tx=$((tx + $9))
        ;;
    esac
  done < /proc/net/dev
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

  read_cpu
  if [ "$prev_total" -gt 0 ]; then
    dt=$((total - prev_total)); di=$((idle - prev_idle))
    cpu=$(awk -v dt="$dt" -v di="$di" 'BEGIN{ if(dt<=0){print ""} else {v=(dt-di)*100/dt; if(v<0)v=0; if(v>100)v=100; printf "%.1f", v} }')
  else
    cpu=""
  fi
  prev_total=$total; prev_idle=$idle

  mem_total=$(awk '/MemTotal/{print $2*1024}' /proc/meminfo)
  mem_avail=$(awk '/MemAvailable/{print $2*1024}' /proc/meminfo)
  mem_used=$((mem_total - mem_avail))

  disk_total=$(df -k /data 2>/dev/null | awk 'NR==2{print $2*1024}')
  disk_used=$(df -k /data 2>/dev/null | awk 'NR==2{print $3*1024}')
  [ -z "$disk_total" ] && disk_total=0
  [ -z "$disk_used" ] && disk_used=0
  uptime_s=$(awk '{print int($1)}' /proc/uptime)

  read_net
  if [ "$prev_t" -gt 0 ]; then
    dts=$((now - prev_t)); [ "$dts" -lt 1 ] && dts=1
    rx_bps=$(( (rx - prev_rx) / dts )); tx_bps=$(( (tx - prev_tx) / dts ))
  else
    rx_bps=0; tx_bps=0
  fi
  prev_rx=$rx; prev_tx=$tx; prev_t=$now

  batt=""; bstat=""
  [ -r /sys/class/power_supply/battery/capacity ] && batt=$(cat /sys/class/power_supply/battery/capacity 2>/dev/null)
  [ -r /sys/class/power_supply/battery/status ] && bstat=$(cat /sys/class/power_supply/battery/status 2>/dev/null)

  if [ $((now - lat_t)) -ge 15 ]; then
    l1=$(tcp_ms 223.5.5.5 443)
    l2=$(tcp_ms 1.1.1.1 443)
    lat_cache="\"直连223.5.5.5\":${l1:-null},\"直连1.1.1.1\":${l2:-null}"
    lat_t=$now
  fi

  disk_pct=$(awk -v u="$disk_used" -v t="$disk_total" 'BEGIN{ if(t>0) printf "%.1f", u*100/t; else print "null" }')

  json=$(cat <<EOF
{"id":"$ID","name":"$MODEL","platform":"android","metrics":{"host":"$MODEL","model":"Android","platform":"android","boot_time":null,"uptime_s":$uptime_s,"ncpu":$NC,"cpu_pct":${cpu:-null},"queue":null,"gpu":null,"mem":{"total":$mem_total,"used":$mem_used},"disk":{"mount":"/data","total":$disk_total,"used":$disk_used,"used_pct":$disk_pct},"net":{"rx_bps":$rx_bps,"tx_bps":$tx_bps},"latency":{$lat_cache},"battery":${batt:-null},"charging":"$bstat"}}
EOF
)
  curl -s -m 6 -X POST -H 'Content-Type: application/json' --data-binary "$json" "$HUB/ingest" >/dev/null 2>&1

  sleep "$INTERVAL"
done

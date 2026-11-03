#!/bin/bash
# Install / uninstall the vibelink agent (and optionally hub/GUI) on macOS via LaunchAgents.
#
#   ./install-macos.sh --hub http://192.168.137.1:18995 --token <token> --device mac
#   ./install-macos.sh --no-agent --gui                 # only the GUI
#   ./install-macos.sh --uninstall
#
# Layout:
#   ~/Library/Application Support/vibelink/bin/{vibelink-hub,vibelink-agent,vibelink-gui}
#   ~/Library/Application Support/vibelink/data/
#   ~/Library/LaunchAgents/ai.vibelink.{hub,agent,gui}.plist
set -euo pipefail

HUB_URL="http://192.168.137.1:18995"
TOKEN=""
DEVICE="$(hostname -s | tr '[:upper:]' '[:lower:]')"
HUB_PORT="18995"
INSTALL_HUB=0
INSTALL_AGENT=1
INSTALL_GUI=0
UNINSTALL=0
REPO="$(cd "$(dirname "$0")/.." && pwd)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --hub) HUB_URL="$2"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --port) HUB_PORT="$2"; shift 2 ;;
    --hub-local) INSTALL_HUB=1; shift ;;
    --no-agent) INSTALL_AGENT=0; shift ;;
    --gui) INSTALL_GUI=1; shift ;;
    --uninstall) UNINSTALL=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1"; exit 1 ;;
  esac
done

ROOT="$HOME/Library/Application Support/vibelink"
BIN="$ROOT/bin"
DATA="$ROOT/data"
AGENTS="$HOME/Library/LaunchAgents"
LOGS="$HOME/Library/Logs"

uninstall() {
  for name in hub agent gui; do
    local plist="$AGENTS/ai.vibelink.$name.plist"
    if [[ -f "$plist" ]]; then
      launchctl bootout "gui/$(id -u)" "$plist" 2>/dev/null || true
      rm -f "$plist"
      echo "  removed ai.vibelink.$name"
    fi
  done
  echo "卸载完成（数据保留在 $ROOT）"
}

if [[ "$UNINSTALL" == "1" ]]; then uninstall; exit 0; fi

# ---- build if needed ----
for exe in vibelink-hub vibelink-agent vibelink-gui; do
  if [[ ! -x "$REPO/target/release/$exe" ]]; then
    echo "缺少 $exe，正在编译…"
    (cd "$REPO" && cargo build --release)
    break
  fi
done

mkdir -p "$BIN" "$DATA" "$AGENTS" "$LOGS"
cp "$REPO/target/release/vibelink-hub" "$BIN/" 2>/dev/null || true
cp "$REPO/target/release/vibelink-agent" "$BIN/" 2>/dev/null || true
cp "$REPO/target/release/vibelink-gui" "$BIN/" 2>/dev/null || true
chmod +x "$BIN"/vibelink-* 2>/dev/null || true
echo "已安装到 $BIN"

write_plist() {
  local label="$1" program="$2" envjson="$3" log="$4"
  local plist="$AGENTS/$label.plist"
  cat > "$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$label</string>
  <key>ProgramArguments</key><array><string>$program</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$LOGS/$log.out.log</string>
  <key>StandardErrorPath</key><string>$LOGS/$log.err.log</string>
  <key>EnvironmentVariables</key>
  <dict>
$envjson
  </dict>
</dict>
</plist>
EOF
  launchctl bootout "gui/$(id -u)" "$plist" 2>/dev/null || true
  launchctl bootstrap "gui/$(id -u)" "$plist"
  echo "  已加载 $label"
}

if [[ "$INSTALL_HUB" == "1" ]]; then
  write_plist "ai.vibelink.hub" "$BIN/vibelink-hub" "    <key>VIBELINK_PORT</key><string>$HUB_PORT</string>
    <key>VIBELINK_DATA</key><string>$DATA</string>" "vibelink-hub"
  sleep 2
  if [[ -z "$TOKEN" && -f "$DATA/token.txt" ]]; then TOKEN="$(tr -d '[:space:]' < "$DATA/token.txt")"; fi
fi

if [[ "$INSTALL_AGENT" == "1" ]]; then
  if [[ -z "$TOKEN" ]]; then
    echo "缺少 --token（hub 的 data/token.txt）" >&2
    exit 1
  fi
  write_plist "ai.vibelink.agent" "$BIN/vibelink-agent" "    <key>VIBELINK_HUB</key><string>$HUB_URL</string>
    <key>VIBELINK_TOKEN</key><string>$TOKEN</string>
    <key>VIBELINK_DEVICE</key><string>$DEVICE</string>" "vibelink-agent"
fi

if [[ "$INSTALL_GUI" == "1" ]]; then
  write_plist "ai.vibelink.gui" "$BIN/vibelink-gui" "    <key>VIBELINK_HUB</key><string>$HUB_URL</string>" "vibelink-gui"
fi

echo
echo "完成："
launchctl list | grep 'ai\.vibelink\.' || true
echo "Hub:   $HUB_URL"
echo "Agent: $DEVICE"


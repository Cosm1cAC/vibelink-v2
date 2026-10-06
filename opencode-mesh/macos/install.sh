#!/bin/sh
# opencode-mesh / macOS 安装：opencode 官方安装 + LaunchAgent 常驻 serve
set -eu
DIR="$(cd "$(dirname "$0")" && pwd)"
. "$DIR/config.sh"

mkdir -p "$BASE"

# 1) 安装 opencode（官方脚本，无需 node/brew）
if [ ! -x "$OC_BIN" ]; then
  echo "[..] installing opencode ..."
  curl -fsSL https://opencode.ai/install | bash || true
fi
if [ ! -x "$OC_BIN" ]; then
  FOUND="$(command -v opencode || true)"
  if [ -n "$FOUND" ]; then
    OC_BIN="$FOUND"
  else
    echo "[!] opencode not found at $OC_BIN"
    exit 1
  fi
fi
echo "[OK] opencode $("$OC_BIN" --version) ($OC_BIN)"

# 1.5) 徽标代理
mkdir -p "$HOME/bin"
cp "$DIR/../mesh-proxy.py" "$PROXY_BIN"
echo "[OK] proxy installed: $PROXY_BIN"

# 2) 服务密码
if [ ! -f "$PASSWORD_FILE" ]; then
  (umask 077; LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24 > "$PASSWORD_FILE" || true)
  echo "[OK] password generated: $PASSWORD_FILE"
else
  echo "[OK] password exists: $PASSWORD_FILE"
fi

# 3) 凭据提示：auth.json 需从 Windows 侧同步（见 README），否则无法调用模型
if [ ! -f "$HOME/.local/share/opencode/auth.json" ]; then
  echo "[!] ~/.local/share/opencode/auth.json missing: Mac 端暂时无法调用模型（按 README 从 Windows 同步）"
fi

# 4) LaunchAgent
mkdir -p "$HOME/Library/LaunchAgents"
cp "$DIR/$PLIST_LABEL.plist" "$PLIST"
launchctl bootout "gui/$(id -u)/$PLIST_LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
launchctl kickstart -k "gui/$(id -u)/$PLIST_LABEL"
echo "[OK] launchd service started: $PLIST_LABEL"

# 5) 等待健康检查
i=0
while [ "$i" -lt 20 ]; do
  if curl -sf -o /dev/null -u "opencode:$(cat "$PASSWORD_FILE")" "http://127.0.0.1:$PORT/global/health"; then
    echo "[OK] health: $(curl -s -u "opencode:$(cat "$PASSWORD_FILE")" "http://127.0.0.1:$PORT/global/health")"
    echo "[i] URL: http://100.105.59.31:$PORT  (user: opencode)"
    echo "[i] password: $PASSWORD_FILE"
    exit 0
  fi
  i=$((i + 1))
  sleep 1
done
echo "[!] server not healthy after 20s; check $BASE/stderr.log"
exit 1

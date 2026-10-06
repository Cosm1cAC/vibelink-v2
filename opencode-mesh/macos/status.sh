#!/bin/sh
# opencode-mesh / macOS 状态检查
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
. "$DIR/config.sh"

echo "== opencode-mesh status (macOS) =="

if [ -x "$OC_BIN" ]; then
  echo "opencode:  $("$OC_BIN" --version) ($OC_BIN)"
else
  echo "opencode:  not installed"
fi

if launchctl print "gui/$(id -u)/$PLIST_LABEL" >/dev/null 2>&1; then
  echo "launchd:   loaded ($PLIST_LABEL)"
else
  echo "launchd:   not loaded"
fi

if command -v lsof >/dev/null 2>&1; then
  echo "listener:"
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || echo "  none"
else
  netstat -an | grep -i "listen" | grep "\.$PORT " || echo "  none"
fi

if [ -f "$PASSWORD_FILE" ]; then
  echo "health:    $(curl -s -u "opencode:$(cat "$PASSWORD_FILE")" "http://127.0.0.1:$PORT/global/health")"
  echo "badge:     $(curl -s -u "opencode:$(cat "$PASSWORD_FILE")" "http://127.0.0.1:$PORT/" | grep -c '__mesh/badge.js')"
  echo "password:  $PASSWORD_FILE"
  echo "url:       http://100.105.59.31:$PORT/  (user: opencode)"
else
  echo "password:  not generated (run install.sh)"
fi

echo "logs:      $BASE/stdout.log $BASE/stderr.log"

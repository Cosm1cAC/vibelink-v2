#!/bin/sh
# opencode-mesh / macOS 卸载
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
. "$DIR/config.sh"

launchctl bootout "gui/$(id -u)/$PLIST_LABEL" 2>/dev/null || true
echo "[OK] launchd service stopped."

if [ -f "$PLIST" ]; then
  rm -f "$PLIST"
  echo "[OK] plist removed."
fi

if [ "${1:-}" != "--keep-password" ] && [ -f "$PASSWORD_FILE" ]; then
  rm -f "$PASSWORD_FILE"
  echo "[OK] password removed."
fi

echo "Done."

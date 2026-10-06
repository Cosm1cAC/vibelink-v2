#!/bin/sh
# opencode-mesh / macOS 端配置
PORT=4096
UPSTREAM_PORT=4097
WORKDIR="$HOME"
OC_BIN="$HOME/.opencode/bin/opencode"
PROXY_BIN="$HOME/bin/mesh-proxy.py"
BASE="$HOME/.config/opencode-server"
PASSWORD_FILE="$BASE/password"
PLIST_LABEL="com.opencode-server"
PLIST="$HOME/Library/LaunchAgents/$PLIST_LABEL.plist"

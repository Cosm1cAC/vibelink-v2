#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
给 socks-relay.py 增加 TCP 并发上限（幂等，可重复执行）。

背景：2026-10-24 14:11 Mac 代理卡死拖垮整机出口与 SSH。
socks-relay.py 的 accept 循环对每个连接无限制地开线程，
UDP 侧有 MAX_UDP_SESSIONS=64 但 TCP 侧完全没有配额。
手机 (100.106.241.67) 反复建连时线程/FD 无限增长 -> 进程假死。

本补丁加：
  - MAX_TCP_CONNS 上限（默认 256，环境变量 SOCKS_MAX_TCP 可覆盖）
  - 超出上限直接关闭连接，不再创建线程
  - 活动连接计数用锁保护

用法（在 Mac 上）：
  python3 patch-tcp-limit.py [--file ~/bin/socks-relay.py] [--max 256]
"""

import argparse
import re
import sys
import time
import os

MARKER = "# [patch-tcp-limit]"

# 1) 在 MAX_UDP_SESSIONS 之后插入 TCP 上限配置与计数器
ANCHOR_CONST = "UDP_IDLE_TIMEOUT = 300"

INJECT_CONST = """UDP_IDLE_TIMEOUT = 300
# [patch-tcp-limit] TCP 并发上限：防止连接洪水把线程/FD 撑爆导致整机假死
MAX_TCP_CONNS = int(os.environ.get('SOCKS_MAX_TCP', '256'))

_tcp_state = {'count': 0, 'lock': threading.Lock()}


def tcp_acquire():
    with _tcp_state['lock']:
        if _tcp_state['count'] >= MAX_TCP_CONNS:
            return False
        _tcp_state['count'] += 1
        return True


def tcp_release():
    with _tcp_state['lock']:
        if _tcp_state['count'] > 0:
            _tcp_state['count'] -= 1"""

# 2) 替换 accept 循环里的线程启动逻辑
OLD_ACCEPT = """            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()"""

NEW_ACCEPT = """            # [patch-tcp-limit] 超限直接拒绝，避免线程无限增长
            if not tcp_acquire():
                try:
                    conn.close()
                except OSError:
                    pass
                log('tcp conn limit reached (%d), rejecting %s' % (MAX_TCP_CONNS, addr[0]))
                continue
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()"""

# 3) 在 handle_client 结束时释放配额
OLD_HANDLE = """def handle_client(conn, addr):"""
NEW_HANDLE = """def handle_client(conn, addr):
    # [patch-tcp-limit] 确保任何退出路径都归还配额
    try:
        _handle_client_inner(conn, addr)
    finally:
        tcp_release()


def _handle_client_inner(conn, addr):"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--file', default=os.path.expanduser('~/bin/socks-relay.py'))
    ap.add_argument('--max', type=int, default=256)
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    path = os.path.expanduser(args.file)
    if not os.path.exists(path):
        print('ERROR: not found: %s' % path)
        return 1

    with open(path, 'r', encoding='utf-8') as f:
        src = f.read()

    if MARKER in src:
        print('ALREADY_PATCHED: %s' % path)
        return 0

    for anchor, name in ((ANCHOR_CONST, 'UDP_IDLE_TIMEOUT'),
                         (OLD_ACCEPT, 'accept thread start'),
                         (OLD_HANDLE, 'handle_client def')):
        if anchor not in src:
            print('ERROR: anchor not found (%s). Aborting without changes.' % name)
            return 2

    patched = src.replace(ANCHOR_CONST, INJECT_CONST, 1)
    patched = patched.replace(OLD_ACCEPT, NEW_ACCEPT, 1)
    patched = patched.replace(OLD_HANDLE, NEW_HANDLE, 1)

    # 应用 --max 覆盖
    patched = patched.replace("os.environ.get('SOCKS_MAX_TCP', '256')",
                              "os.environ.get('SOCKS_MAX_TCP', '%d')" % args.max)

    # 语法校验
    try:
        compile(patched, path, 'exec')
    except SyntaxError as e:
        print('ERROR: patched source has syntax error: %s' % e)
        return 3

    if args.dry_run:
        print('DRY_RUN_OK: would patch %s (%d -> %d bytes)' % (path, len(src), len(patched)))
        return 0

    backup = '%s.bak-before-tcplimit-%s' % (path, time.strftime('%Y%m%d-%H%M%S'))
    with open(backup, 'w', encoding='utf-8') as f:
        f.write(src)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(patched)

    print('PATCHED: %s' % path)
    print('BACKUP : %s' % backup)
    print('MAX_TCP: %d' % args.max)
    return 0


if __name__ == '__main__':
    sys.exit(main())

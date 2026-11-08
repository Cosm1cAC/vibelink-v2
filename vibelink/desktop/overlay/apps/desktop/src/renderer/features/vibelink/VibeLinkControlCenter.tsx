import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { HubPairQr } from './HubPairQr';
import { requestVibeLink } from './desktopBridge';

interface ProbeSample {
  target?: string;
  tcpMs?: number | null;
  httpMs?: number | null;
  ok?: boolean;
  error?: string | null;
}

interface SystemProxy {
  enabled?: boolean;
  server?: string;
}

interface DeviceInfo {
  id: string;
  hostname?: string;
  os?: string;
  arch?: string;
  agentVersion?: string;
  ips?: string[];
  gateway?: string;
  systemProxy?: SystemProxy;
  listeningPorts?: number[];
  probes?: ProbeSample[];
  memAvailableMb?: number;
  memTotalMb?: number;
  ts?: number;
  lastSeenMs?: number;
  online?: boolean;
}

interface EventEntry {
  ts?: number;
  deviceId?: string;
  kind?: string;
  detail?: string;
}

interface ConfigMeta {
  name: string;
  versions?: { version: number; sha256: string; size: number; createdMs: number }[];
}

interface PolicyDoc {
  enabled?: boolean;
  dryRun?: boolean;
  rules?: unknown[];
  watchdogs?: unknown[];
  [key: string]: unknown;
}

interface TailscaleInfo {
  state: string;
  ip?: string;
}

interface CindyDeviceLinkInfo {
  relayUrl: string;
  hostDeviceId: string;
  hostName: string;
  lanIp: string;
  apkUrl: string;
  pairedMobileCount?: number;
}

interface VibeLinkStateSnapshot {
  hubOk: boolean;
  hubError: string | null;
  hubUrl: string;
  /** 本机服务手里有没有 hub token；token 本身不再下发给渲染层。 */
  hasToken?: boolean;
  devices: DeviceInfo[];
  events: EventEntry[];
  configs: ConfigMeta[];
  policy: PolicyDoc | null;
  tailscale: TailscaleInfo;
  cindyDeviceLink: CindyDeviceLinkInfo;
}

const SERVICES: [string, string][] = [
  ['WProxy', 'WProxy'],
  ['WProxyHysteria', 'Hysteria'],
  ['PcProxyFailover', 'Failover'],
  ['PcLanGateway', 'LAN 网关'],
  ['PhoneConfigHttp', '手机订阅 HTTP'],
  ['VibelinkAgent', 'Agent 自重启'],
];

function msToClock(ms?: number): string {
  if (!ms) return '—';
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export function VibeLinkControlCenter({ embedded = false, fullHeight = false }: { embedded?: boolean; fullHeight?: boolean } = {}) {
  const [open, setOpen] = useState<boolean>(() => {
    if (embedded) return true;
    const params = new URLSearchParams(window.location.search);
    return params.get('vibelink') === '1' || window.location.hash.includes('vibelink');
  });

  const [snapshot, setSnapshot] = useState<VibeLinkStateSnapshot | null>(null);
  const [selectedDeviceId, setSelectedDeviceId] = useState<string>('pc');
  const [proxyInput, setProxyInput] = useState<string>('127.0.0.1:7897');
  const [proxyInputTouched, setProxyInputTouched] = useState<boolean>(false);
  const [actionToast, setActionToast] = useState<string | null>(null);

  // Sub-modals: 'none' | 'configs' | 'policy' | 'settings' | 'mobile'
  const [activeModal, setActiveModal] = useState<'none' | 'configs' | 'policy' | 'settings' | 'mobile'>('none');
  const [cfgName, setCfgName] = useState<string>('cmfa-lan-gateway.yaml');
  const [cfgBody, setCfgBody] = useState<string>('');
  const [policyText, setPolicyText] = useState<string>('');
  const [policyDirty, setPolicyDirty] = useState<boolean>(false);


  const showToast = useCallback((msg: string) => {
    setActionToast(msg);
    setTimeout(() => {
      setActionToast((prev) => (prev === msg ? null : prev));
    }, 3500);
  }, []);

  const fetchState = useCallback(async () => {
    try {
      const data = await requestVibeLink<VibeLinkStateSnapshot>('state');
      setSnapshot(data);
      if (!policyDirty && data.policy) {
        setPolicyText(JSON.stringify(data.policy, null, 2));
      }
    } catch {
      // ignore transient poll error
    }
  }, [policyDirty]);

  useEffect(() => {
    void fetchState();
    const timer = setInterval(() => {
      void fetchState();
    }, open ? 2000 : 5000);
    return () => clearInterval(timer);
  }, [fetchState, open]);

  useEffect(() => {
    if (embedded) return;
    const onToggle = () => setOpen((prev) => !prev);
    const onOpen = () => setOpen(true);
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === 'v') {
        e.preventDefault();
        setOpen((prev) => !prev);
      }
    };
    window.addEventListener('vibelink:toggle', onToggle);
    window.addEventListener('vibelink:open', onOpen);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('vibelink:toggle', onToggle);
      window.removeEventListener('vibelink:open', onOpen);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [embedded]);

  const devices = useMemo(() => {
    const list = [...(snapshot?.devices ?? [])];
    list.sort((a, b) => {
      const rank = (id: string) => (id === 'pc' ? 0 : id === 'laptop' ? 1 : 2);
      const ra = rank(a.id);
      const rb = rank(b.id);
      return ra !== rb ? ra - rb : a.id.localeCompare(b.id);
    });
    return list;
  }, [snapshot?.devices]);

  const selectedDevice = useMemo(() => {
    if (!devices.length) return null;
    return devices.find((d) => d.id === selectedDeviceId) ?? devices[0];
  }, [devices, selectedDeviceId]);

  useEffect(() => {
    if (!proxyInputTouched && selectedDevice?.systemProxy?.server) {
      setProxyInput(selectedDevice.systemProxy.server);
    }
  }, [selectedDevice, proxyInputTouched]);

  const postAction = useCallback(
    async (payload: Record<string, unknown>, successMsg?: string) => {
      try {
        const data = await requestVibeLink<Record<string, unknown>>('action', payload);
        if (successMsg) showToast(successMsg);
        void fetchState();
        return data;
      } catch (err) {
        showToast(`操作失败: ${err instanceof Error ? err.message : String(err)}`);
        return null;
      }
    },
    [fetchState, showToast],
  );

  const sendDeviceCommand = useCallback(
    (deviceId: string, kind: string, args: Record<string, unknown>, label: string) => {
      void postAction(
        {
          action: 'command',
          deviceId,
          kind,
          args,
        },
        `${label} 已发送`,
      );
    },
    [postAction],
  );

  const onlineCount = useMemo(
    () => devices.filter((d) => d.online).length,
    [devices],
  );

  const hubOk = snapshot?.hubOk ?? false;

  if (!embedded && !open) {
    return null;
  }

  const ts = snapshot?.tailscale ?? { state: 'Missing' };
  const policy = snapshot?.policy;
  const policyLabel = !policy
    ? '自愈'
    : !policy.enabled
      ? '自愈 关'
      : policy.dryRun !== false
        ? '自愈 dry-run'
        : '自愈 生效';

  return (
    <div
      style={
        embedded
          ? {
              position: 'relative',
              width: '100%',
              height: fullHeight ? '100%' : 640,
              minHeight: 480,
              borderRadius: fullHeight ? 0 : 14,
              overflow: 'hidden',
              border: '1px solid var(--border-default)',
              background: 'var(--surface)',
              color: 'var(--text-primary)',
              display: 'flex',
              flexDirection: 'column',
              fontFamily: 'system-ui, -apple-system, "Microsoft YaHei", sans-serif',
              userSelect: 'none',
            }
          : {
              position: 'fixed',
              inset: 0,
              zIndex: 9999,
              background: 'var(--surface)',
              color: 'var(--text-primary)',
              display: 'flex',
              flexDirection: 'column',
              fontFamily: 'system-ui, -apple-system, "Microsoft YaHei", sans-serif',
              userSelect: 'none',
            }
      }
    >
      {/* Top Bar */}
      <div
        style={{
          height: 48,
          background: 'var(--surface-elevated)',
          borderBottom: '1px solid var(--border-default)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0 16px',
          paddingRight: embedded ? 16 : 140,
          WebkitAppRegion: embedded ? 'no-drag' : 'drag',
        } as React.CSSProperties}
      >
        <div
          style={{ display: 'flex', alignItems: 'center', gap: 12, WebkitAppRegion: 'no-drag' } as React.CSSProperties}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span
              style={{
                width: 10,
                height: 10,
                borderRadius: '50%',
                background: '#34D399',
                boxShadow: '0 0 10px rgba(52,211,153,0.6)',
              }}
            />
            <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: 0.3 }}>
              设备与连接
            </span>
          </div>

          {/* Hub Pill */}
          <span
            style={{
              padding: '3px 10px',
              borderRadius: 999,
              fontSize: 12,
              background: hubOk ? 'rgba(52, 211, 153, 0.14)' : 'rgba(239, 68, 68, 0.16)',
              color: hubOk ? '#34D399' : '#F87171',
              border: `1px solid ${hubOk ? 'rgba(52,211,153,0.35)' : 'rgba(239,68,68,0.35)'}`,
            }}
          >
            {hubOk ? '● hub 在线' : `● hub: ${snapshot?.hubError || '离线'}`}
          </span>

          {/* Tailscale Pill */}
          {ts.state === 'Running' ? (
            <span
              style={{
                padding: '3px 10px',
                borderRadius: 999,
                fontSize: 12,
                background: 'rgba(52, 211, 153, 0.14)',
                color: '#34D399',
                border: '1px solid rgba(52,211,153,0.35)',
              }}
            >
              ● tailscale {ts.ip || '已连接'}
            </span>
          ) : ts.state === 'Stopped' || ts.state === 'NeedsLogin' ? (
            <button
              type="button"
              onClick={() => void postAction({ action: 'tailscale-up' }, '正在拉起 Tailscale...')}
              style={{
                padding: '3px 10px',
                borderRadius: 999,
                fontSize: 12,
                background: 'rgba(245, 158, 11, 0.16)',
                color: '#FBBF24',
                border: '1px solid rgba(245,158,11,0.4)',
                cursor: 'pointer',
              }}
            >
              ● tailscale {ts.state} · 点此{ts.state === 'Stopped' ? '重连' : '登录'}
            </button>
          ) : (
            <span style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>○ tailscale {ts.state}</span>
          )}

          {/* Cindy DeviceLink Host Status */}
          <span
            style={{
              padding: '3px 10px',
              borderRadius: 999,
              fontSize: 12,
              background: 'rgba(96, 165, 250, 0.14)',
              color: '#93C5FD',
              border: '1px solid rgba(96,165,250,0.35)',
            }}
          >
            本机 · {snapshot?.cindyDeviceLink?.hostName || '正在读取设备'}
          </span>
        </div>

        <div
          style={{ display: 'flex', alignItems: 'center', gap: 8, WebkitAppRegion: 'no-drag' } as React.CSSProperties}
        >
          {actionToast && (
            <span
              style={{
                fontSize: 12,
                color: '#34D399',
                background: 'rgba(52, 211, 153, 0.12)',
                padding: '3px 10px',
                borderRadius: 6,
              }}
            >
              {actionToast}
            </span>
          )}
          <button
            type="button"
            onClick={() => setActiveModal(activeModal === 'mobile' ? 'none' : 'mobile')}
            style={topBtnStyle(activeModal === 'mobile')}
          >
            📱 连接手机
          </button>
          <button
            type="button"
            onClick={() => setActiveModal(activeModal === 'policy' ? 'none' : 'policy')}
            style={topBtnStyle(activeModal === 'policy')}
          >
            {policyLabel}
          </button>
          <button
            type="button"
            onClick={() => setActiveModal(activeModal === 'configs' ? 'none' : 'configs')}
            style={topBtnStyle(activeModal === 'configs')}
          >
            配置库
          </button>
          <button
            type="button"
            onClick={() => setActiveModal(activeModal === 'settings' ? 'none' : 'settings')}
            style={topBtnStyle(activeModal === 'settings')}
          >
            设置
          </button>
          {!embedded && (
            <button
              type="button"
              onClick={() => setOpen(false)}
              style={{
                height: 30,
                padding: '0 14px',
                borderRadius: 999,
                border: '1px solid rgba(96, 165, 250, 0.5)',
                background: 'linear-gradient(135deg, #2563EB, #1D4ED8)',
                color: '#FFFFFF',
                fontSize: 12,
                fontWeight: 600,
                cursor: 'pointer',
              }}
            >
              返回会话工作台
            </button>
          )}
        </div>
      </div>

      {/* Main Content Split: Left Device List + Right Topology & Controls */}
      <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>
        {/* Left Sidebar: Devices List */}
        <div
          style={{
            width: 260,
            background: 'var(--surface-elevated)',
            borderRight: '1px solid var(--border-default)',
            padding: 14,
            display: 'flex',
            flexDirection: 'column',
            gap: 8,
            overflowY: 'auto',
          }}
        >
          <div style={{ fontSize: 12, color: 'var(--text-tertiary)', fontWeight: 600, marginBottom: 2 }}>
            设备节点 ({devices.length})
          </div>
          {devices.length === 0 && (
            <div style={{ fontSize: 13, color: 'var(--text-tertiary)', padding: '12px 4px' }}>
              等待 Agent 上报…
            </div>
          )}
          {devices.map((d) => {
            const selected = selectedDevice?.id === d.id;
            const probe = d.probes?.[0];
            const tcpMs = probe?.tcpMs;
            const httpMs = probe?.httpMs;
            const dotColor = !d.online
              ? 'var(--text-tertiary)'
              : tcpMs != null && tcpMs < 1500
                ? '#34D399'
                : '#F59E0B';
            const proxySummary = d.systemProxy?.enabled && d.systemProxy?.server
              ? `代理 ${d.systemProxy.server}`
              : '直连';
            return (
              <div
                key={d.id}
                onClick={() => {
                  setSelectedDeviceId(d.id);
                  setProxyInputTouched(false);
                  if (d.systemProxy?.server) {
                    setProxyInput(d.systemProxy.server);
                  }
                }}
                style={{
                  padding: '10px 12px',
                  borderRadius: 10,
                  cursor: 'pointer',
                  background: selected ? 'var(--sidebar-item-active)' : 'var(--surface-elevated)',
                  border: selected
                    ? '1px solid rgba(96, 165, 250, 0.65)'
                    : '1px solid rgba(255,255,255,0.05)',
                  transition: 'all 0.15s ease',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span
                      style={{
                        width: 9,
                        height: 9,
                        borderRadius: '50%',
                        background: dotColor,
                      }}
                    />
                    <span style={{ fontWeight: 600, fontSize: 14 }}>
                      {d.hostname || d.id}
                    </span>
                  </div>
                  <span style={{ fontSize: 11, color: 'var(--text-tertiary)', fontFamily: 'monospace' }}>
                    {d.id}
                  </span>
                </div>
                <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                  {d.os || 'unknown'} · {proxySummary}
                </div>
                {(tcpMs != null || httpMs != null) && (
                  <div style={{ fontSize: 11, color: 'var(--text-tertiary)', marginTop: 2, fontFamily: 'monospace' }}>
                    延迟 {tcpMs ?? '—'} / {httpMs ?? '—'} ms
                  </div>
                )}
              </div>
            );
          })}

          {/* Quick Summary Card at bottom of Left Sidebar */}
          <div
            style={{
              marginTop: 'auto',
              padding: 12,
              borderRadius: 10,
              background: 'var(--surface-elevated)',
              border: '1px solid rgba(255,255,255,0.06)',
              fontSize: 12,
              color: 'var(--text-secondary)',
              lineHeight: 1.6,
            }}
          >
            <div style={{ color: 'var(--text-primary)', fontWeight: 600, marginBottom: 4 }}>
              融合桌面端说明
            </div>
            <div>• 左侧会话工作区和设备连接共享同一个窗口</div>
            <div>• 手机融合包 APK:</div>
            <div
              style={{
                fontFamily: 'monospace',
                fontSize: 11,
                color: '#34D399',
                wordBreak: 'break-all',
                marginTop: 2,
              }}
            >
              {snapshot?.cindyDeviceLink?.apkUrl || 'http://127.0.0.1:18995/apk'}
            </div>
          </div>
        </div>

        {/* Center / Right Main Panel */}
        <div
          style={{
            flex: 1,
            padding: 18,
            overflowY: 'auto',
            display: 'flex',
            flexDirection: 'column',
            gap: 16,
          }}
        >
          {/* Interactive Topology Graph */}
          <div
            style={{
              height: 220,
              borderRadius: 12,
              background: 'var(--surface)',
              border: '1px solid rgba(255,255,255,0.06)',
              position: 'relative',
              overflow: 'hidden',
            }}
          >
            <svg width="100%" height="100%" viewBox="0 0 800 220" preserveAspectRatio="xMidYMid meet">
              <defs>
                <pattern id="vl-grid" width="28" height="28" patternUnits="userSpaceOnUse">
                  <circle cx="2" cy="2" r="1" fill="var(--border-default)" />
                </pattern>
              </defs>
              <rect width="800" height="220" fill="url(#vl-grid)" />

              {/* Edges & Device Nodes */}
              {devices.map((d, idx) => {
                const n = Math.max(devices.length, 1);
                const angle = (idx / n) * Math.PI * 2 - Math.PI / 2;
                const cx = 400;
                const cy = 110;
                const rx = 210;
                const ry = 72;
                const x = cx + rx * Math.cos(angle);
                const y = cy + ry * Math.sin(angle);
                const probe = d.probes?.[0];
                const httpMs = probe?.httpMs;
                const color = !d.online
                  ? 'var(--text-tertiary)'
                  : httpMs != null && httpMs < 2500
                    ? '#34D399'
                    : '#F59E0B';
                const isSelected = selectedDevice?.id === d.id;
                const mx = (cx + x) / 2;
                const my = (cy + y) / 2;

                return (
                  <g
                    key={d.id}
                    style={{ cursor: 'pointer' }}
                    onClick={() => setSelectedDeviceId(d.id)}
                  >
                    <line
                      x1={cx}
                      y1={cy}
                      x2={x}
                      y2={y}
                      stroke={color}
                      strokeWidth={isSelected ? 2.5 : 1.5}
                      strokeOpacity={0.75}
                    />
                    {httpMs != null && (
                      <text
                        x={mx}
                        y={my - 6}
                        fill="var(--text-secondary)"
                        fontSize="11"
                        fontFamily="monospace"
                        textAnchor="middle"
                      >
                        {httpMs}ms
                      </text>
                    )}
                    <circle
                      cx={x}
                      cy={y}
                      r={24}
                      fill="var(--surface-elevated)"
                      stroke={isSelected ? '#60A5FA' : color}
                      strokeWidth={isSelected ? 3 : 2}
                    />
                    <text
                      x={x}
                      y={y - 2}
                      fill="var(--text-primary)"
                      fontSize="12"
                      fontWeight="600"
                      textAnchor="middle"
                    >
                      {d.id}
                    </text>
                    <text
                      x={x}
                      y={y + 12}
                      fill="var(--text-tertiary)"
                      fontSize="10"
                      fontFamily="monospace"
                      textAnchor="middle"
                    >
                      {d.ips?.[0] || ''}
                    </text>
                  </g>
                );
              })}

              {/* Center HUB Node */}
              <g>
                <circle
                  cx={400}
                  cy={110}
                  r={26}
                  fill="var(--surface-elevated)"
                  stroke={hubOk ? '#34D399' : '#EF4444'}
                  strokeWidth={2.5}
                />
                <text
                  x={400}
                  y={114}
                  fill="var(--text-primary)"
                  fontSize="12"
                  fontWeight="700"
                  textAnchor="middle"
                >
                  HUB
                </text>
              </g>
            </svg>
          </div>

          {/* Selected Device Control Card */}
          {selectedDevice && (
            <div
              style={{
                background: 'var(--surface-elevated)',
                borderRadius: 12,
                padding: 18,
                border: '1px solid rgba(255,255,255,0.06)',
                display: 'flex',
                flexDirection: 'column',
                gap: 14,
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <div style={{ fontSize: 15, fontWeight: 700 }}>
                  设备详情 · {selectedDevice.hostname || selectedDevice.id} ({selectedDevice.id})
                </div>
                <span
                  style={{
                    fontSize: 12,
                    color: selectedDevice.online ? '#34D399' : '#F87171',
                    fontFamily: 'monospace',
                  }}
                >
                  {selectedDevice.online ? '● ONLINE' : '○ OFFLINE'} · 心跳 {msToClock(selectedDevice.lastSeenMs || selectedDevice.ts)}
                </span>
              </div>

              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))',
                  gap: '8px 20px',
                  fontSize: 13,
                }}
              >
                <InfoRow label="系统 / 架构" value={`${selectedDevice.os || '—'} · ${selectedDevice.arch || '—'}`} />
                <InfoRow label="Agent 版本" value={selectedDevice.agentVersion || '—'} />
                <InfoRow label="IP 地址" value={(selectedDevice.ips || []).join(', ') || '—'} />
                <InfoRow label="默认网关" value={selectedDevice.gateway || '—'} />
                <InfoRow
                  label="当前系统代理"
                  value={
                    selectedDevice.systemProxy?.enabled
                      ? `开启 (${selectedDevice.systemProxy.server || ''})`
                      : `关闭 (${selectedDevice.systemProxy?.server || ''})`
                  }
                />
                <InfoRow
                  label="探测延迟 (TCP / CONNECT)"
                  value={
                    selectedDevice.probes?.[0]
                      ? `${selectedDevice.probes[0].tcpMs ?? '—'} ms / ${selectedDevice.probes[0].httpMs ?? '—'} ms (${selectedDevice.probes[0].target || ''})`
                      : '—'
                  }
                />
                <InfoRow
                  label="监听端口"
                  value={(selectedDevice.listeningPorts || []).join(', ') || '—'}
                />
                <InfoRow
                  label="可用内存"
                  value={`${selectedDevice.memAvailableMb ?? 0} / ${selectedDevice.memTotalMb ?? 0} MB`}
                />
              </div>

              <div style={{ height: 1, background: 'rgba(255,255,255,0.07)' }} />

              {/* System Proxy Controls */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                <span style={{ fontSize: 12, color: 'var(--text-secondary)', width: 64 }}>系统代理</span>
                <input
                  type="text"
                  value={proxyInput}
                  onChange={(e) => {
                    setProxyInput(e.target.value);
                    setProxyInputTouched(true);
                  }}
                  style={{
                    width: 220,
                    height: 30,
                    padding: '0 10px',
                    borderRadius: 6,
                    border: '1px solid rgba(255,255,255,0.14)',
                    background: 'var(--surface)',
                    color: 'var(--text-primary)',
                    fontFamily: 'monospace',
                    fontSize: 13,
                  }}
                />
                <button
                  type="button"
                  onClick={() =>
                    sendDeviceCommand(
                      selectedDevice.id,
                      'set-proxy',
                      { server: proxyInput },
                      '设系统代理',
                    )
                  }
                  style={actionBtnStyle('#2563EB')}
                >
                  设系统代理
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setProxyInput('127.0.0.1:7897');
                    setProxyInputTouched(true);
                  }}
                  style={actionBtnStyle('#374151')}
                >
                  填本机 :7897
                </button>
                <button
                  type="button"
                  onClick={() =>
                    sendDeviceCommand(selectedDevice.id, 'clear-proxy', {}, '清系统代理')
                  }
                  style={actionBtnStyle('#DC2626')}
                >
                  清系统代理
                </button>
              </div>

              {/* Service Controls */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                <span style={{ fontSize: 12, color: 'var(--text-secondary)', width: 64 }}>服务控制</span>
                {SERVICES.map(([svc, label]) => (
                  <button
                    key={svc}
                    type="button"
                    onClick={() =>
                      sendDeviceCommand(
                        selectedDevice.id,
                        'restart-service',
                        { name: svc },
                        `重启 ${label}`,
                      )
                    }
                    style={actionBtnStyle('#283246')}
                  >
                    重启 {label}
                  </button>
                ))}
                <button
                  type="button"
                  onClick={() =>
                    sendDeviceCommand(selectedDevice.id, 'probe', {}, '立即探测')
                  }
                  style={actionBtnStyle('#059669')}
                >
                  立即探测
                </button>
              </div>
            </div>
          )}

          {/* Events Stream */}
          <div
            style={{
              background: 'var(--surface-elevated)',
              borderRadius: 12,
              padding: 16,
              border: '1px solid rgba(255,255,255,0.06)',
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
            }}
          >
            <div style={{ fontSize: 12, color: 'var(--text-tertiary)', fontWeight: 600 }}>
              实时事件流 ({snapshot?.events?.length || 0})
            </div>
            <div
              style={{
                maxHeight: 180,
                overflowY: 'auto',
                display: 'flex',
                flexDirection: 'column',
                gap: 4,
                fontFamily: 'monospace',
                fontSize: 12,
              }}
            >
              {(snapshot?.events || []).slice(0, 60).map((e, i) => {
                const kind = e.kind || '';
                const color =
                  kind.includes('fail') || kind.includes('error')
                    ? '#EF4444'
                    : kind.includes('warn') || kind.includes('offline')
                      ? '#F59E0B'
                      : kind.includes('heal')
                        ? '#34D399'
                        : 'var(--text-primary)';
                return (
                  <div key={`${e.ts}-${i}`} style={{ display: 'flex', gap: 10 }}>
                    <span style={{ color: 'var(--text-tertiary)' }}>{msToClock(e.ts)}</span>
                    <span style={{ color: '#60A5FA', minWidth: 64 }}>{e.deviceId || 'hub'}</span>
                    <span style={{ color }}>{kind}</span>
                    <span style={{ color: 'var(--text-secondary)' }}>{e.detail || ''}</span>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      </div>

      {/* Sub-modals (Configs / Policy / Settings / Mobile & Pairing) */}
      {activeModal !== 'none' && (
        <div
          onClick={() => setActiveModal('none')}
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0,0,0,0.6)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 10000,
          }}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            style={{
              width: 640,
              maxWidth: '92vw',
              maxHeight: '85vh',
              overflowY: 'auto',
              background: 'var(--surface-elevated)',
              borderRadius: 14,
              border: '1px solid rgba(255,255,255,0.12)',
              padding: 20,
              display: 'flex',
              flexDirection: 'column',
              gap: 14,
            }}
          >
            {activeModal === 'configs' && (
              <>
                <div style={{ fontSize: 16, fontWeight: 700 }}>配置版本库</div>
                {(snapshot?.configs || []).map((c) => {
                  // 拉配置需要设备自己的 token：手机端配对后在地址后面加 ?t=<该设备 token>
                  const url = `${(snapshot?.hubUrl || '').replace(/\/$/, '')}/phone/${c.name}`;
                  return (
                    <div
                      key={c.name}
                      style={{
                        padding: 10,
                        borderRadius: 8,
                        background: 'var(--surface-elevated)',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 4,
                      }}
                    >
                      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                        <span style={{ fontFamily: 'monospace', fontWeight: 600 }}>{c.name}</span>
                        <span style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>
                          {c.versions?.length || 0} 个版本
                        </span>
                        <button
                          type="button"
                          onClick={() => {
                            void navigator.clipboard.writeText(url);
                            showToast('下载链接已复制');
                          }}
                          style={actionBtnStyle('#2563EB')}
                        >
                          复制下载链接
                        </button>
                      </div>
                      <div style={{ fontSize: 11, color: 'var(--text-tertiary)', fontFamily: 'monospace', wordBreak: 'break-all' }}>
                        {url}
                      </div>
                    </div>
                  );
                })}
                <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 4 }}>上传新配置版本</div>
                <input
                  type="text"
                  value={cfgName}
                  onChange={(e) => setCfgName(e.target.value)}
                  placeholder="配置名 (如 cmfa-lan-gateway.yaml)"
                  style={{
                    height: 32,
                    padding: '0 10px',
                    borderRadius: 6,
                    border: '1px solid rgba(255,255,255,0.15)',
                    background: 'var(--surface)',
                    color: 'var(--text-primary)',
                  }}
                />
                <textarea
                  rows={8}
                  value={cfgBody}
                  onChange={(e) => setCfgBody(e.target.value)}
                  placeholder="粘贴 YAML 内容…"
                  style={{
                    padding: 10,
                    borderRadius: 6,
                    border: '1px solid rgba(255,255,255,0.15)',
                    background: 'var(--surface)',
                    color: 'var(--text-primary)',
                    fontFamily: 'monospace',
                    fontSize: 12,
                  }}
                />
                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                  <button
                    type="button"
                    onClick={() => {
                      if (!cfgBody.trim()) return;
                      void postAction(
                        { action: 'upload-config', name: cfgName, body: cfgBody },
                        '配置已上传',
                      );
                      setCfgBody('');
                    }}
                    style={actionBtnStyle('#059669')}
                  >
                    上传
                  </button>
                  <button type="button" onClick={() => setActiveModal('none')} style={actionBtnStyle('#374151')}>
                    关闭
                  </button>
                </div>
              </>
            )}

            {activeModal === 'policy' && (
              <>
                <div style={{ fontSize: 16, fontWeight: 700 }}>自愈策略 (enabled / dryRun / rules / watchdogs)</div>
                <textarea
                  rows={16}
                  value={policyText}
                  onChange={(e) => {
                    setPolicyText(e.target.value);
                    setPolicyDirty(true);
                  }}
                  style={{
                    padding: 10,
                    borderRadius: 6,
                    border: '1px solid rgba(255,255,255,0.15)',
                    background: 'var(--surface)',
                    color: 'var(--text-primary)',
                    fontFamily: 'monospace',
                    fontSize: 12,
                  }}
                />
                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                  <button
                    type="button"
                    onClick={() => {
                      void postAction({ action: 'save-policy', body: policyText }, '自愈策略已保存');
                      setPolicyDirty(false);
                    }}
                    style={actionBtnStyle('#059669')}
                  >
                    保存
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      if (snapshot?.policy) {
                        setPolicyText(JSON.stringify(snapshot.policy, null, 2));
                        setPolicyDirty(false);
                      }
                    }}
                    style={actionBtnStyle('#2563EB')}
                  >
                    重新加载
                  </button>
                  <button type="button" onClick={() => setActiveModal('none')} style={actionBtnStyle('#374151')}>
                    关闭
                  </button>
                </div>
              </>
            )}

            {activeModal === 'settings' && (
              <>
                <div style={{ fontSize: 16, fontWeight: 700 }}>Hub 连接</div>
                <p>当前地址：<code>{snapshot?.hubUrl || '正在读取'}</code></p>
                <p>{snapshot?.hasToken ? '已使用本机 Hub 凭据连接。' : '未找到本机 Hub 凭据，请先启动开发版 Hub。'}</p>
                <p style={{ color: 'var(--text-secondary)' }}>在同一个窗口中切换会话、工作区和设备连接。连接凭据由电脑端管理。</p>
                <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <button type="button" onClick={() => setActiveModal('none')} style={actionBtnStyle('#374151')}>关闭</button>
                </div>
              </>
            )}

            {activeModal === 'mobile' && (
              <>
                <div style={{ fontSize: 16, fontWeight: 700 }}>📱 连接手机</div>
                <HubPairQr light />
                <div
                  style={{
                    padding: 12,
                    borderRadius: 8,
                    background: 'var(--surface-elevated)',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 8,
                  }}
                >
                  <div style={{ fontSize: 13, fontWeight: 600, color: '#34D399' }}>
                    安装 VibeLink 手机端
                  </div>
                  <div style={{ fontFamily: 'monospace', fontSize: 13, color: 'var(--text-primary)' }}>
                    {snapshot?.cindyDeviceLink?.apkUrl || 'http://127.0.0.1:18995/apk'}
                  </div>
                  <div>
                    <button
                      type="button"
                      onClick={() => {
                        const url = snapshot?.cindyDeviceLink?.apkUrl || 'http://127.0.0.1:18995/apk';
                        void navigator.clipboard.writeText(url);
                        showToast('APK 下载链接已复制');
                      }}
                      style={actionBtnStyle('#2563EB')}
                    >
                      复制 APK 下载链接
                    </button>
                  </div>
                </div>



                <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <button type="button" onClick={() => setActiveModal('none')} style={actionBtnStyle('#374151')}>
                    关闭
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, borderBottom: '1px solid rgba(255,255,255,0.04)', paddingBottom: 4 }}>
      <span style={{ color: 'var(--text-tertiary)' }}>{label}</span>
      <span style={{ fontFamily: 'monospace', color: 'var(--text-primary)', textAlign: 'right' }}>{value}</span>
    </div>
  );
}

function topBtnStyle(active: boolean): React.CSSProperties {
  return {
    height: 30,
    padding: '0 12px',
    borderRadius: 8,
    border: active ? '1px solid #60A5FA' : '1px solid rgba(255,255,255,0.12)',
    background: active ? 'var(--sidebar-item-active)' : 'var(--surface-elevated)',
    color: 'var(--text-primary)',
    fontSize: 12,
    cursor: 'pointer',
  };
}

function actionBtnStyle(bg: string): React.CSSProperties {
  return {
    height: 30,
    padding: '0 12px',
    borderRadius: 6,
    border: '1px solid var(--border-default)',
    background: bg,
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: 500,
    cursor: 'pointer',
  };
}

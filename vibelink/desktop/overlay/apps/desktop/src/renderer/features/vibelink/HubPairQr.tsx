import { requestVibeLink } from './desktopBridge';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import * as QRCode from 'qrcode';

/**
 * 扫码配对（流程参考 ChatGPT 的远程控制）：
 * 1. 桌面端向 hub 要一个一次性配对链接（vibelink://pair?...，10 分钟），画成二维码；
 * 2. 手机 VibeLink 扫码 → hub 状态变成 scanned，这里提示「请在手机上确认」；
 * 3. 手机上点「连接」→ hub 给手机签一个独立的 client token，状态变成 paired，这里显示「已连接」。
 * 过期前一直没人扫就自动换一个新码；已扫码待确认时不换，避免把正在确认的手机踢掉。
 *
 * 所有 hub 请求都经本机 5174 服务（/api/vibelink/action）转发，管理员 token 不进渲染层。
 */

type PairStatus = 'loading' | 'pending' | 'scanned' | 'paired' | 'expired' | 'error';

interface PairState {
  status: PairStatus;
  id?: string;
  link?: string;
  urls?: string[];
  host?: string;
  expiresAt?: number;
  deviceName?: string;
  error?: string;
}

async function action<T>(payload: Record<string, unknown>): Promise<T> {
  return requestVibeLink<T>('action', payload);
}

export function HubPairQr({ light = false }: { light?: boolean } = {}) {
  // light：嵌在 Cindy 浅色的「远程控制」弹窗里；默认深色给 VibeLink 控制台用
  const fg = light ? 'var(--confirm-title)' : '#E6EAF2';
  const fg2 = light ? 'var(--confirm-desc)' : '#C7CEDB';
  const [state, setState] = useState<PairState>({ status: 'loading' });
  const [qr, setQr] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const generation = useRef(0);

  const generate = useCallback(async () => {
    const gen = ++generation.current;
    setState({ status: 'loading' });
    setQr(null);
    try {
      const r = await action<{ id?: string; link?: string; urls?: string[]; host?: string; expiresAt?: number; error?: string }>({
        action: 'hub-pair',
        name: 'phone',
      });
      if (gen !== generation.current) return;
      if (!r.link || !r.id) {
        setState({ status: 'error', error: r.error || '生成失败' });
        return;
      }
      setState({ status: 'pending', id: r.id, link: r.link, urls: r.urls, host: r.host, expiresAt: r.expiresAt });
    } catch (e) {
      if (gen !== generation.current) return;
      setState({ status: 'error', error: e instanceof Error ? e.message : String(e) });
    }
  }, []);

  // 打开即生成；卸载时让在途请求作废
  useEffect(() => {
    void generate();
    return () => {
      generation.current++;
    };
  }, [generate]);

  // 画二维码
  useEffect(() => {
    if (!state.link) return;
    let cancelled = false;
    QRCode.toDataURL(state.link, { margin: 2, width: 248, errorCorrectionLevel: 'M' })
      .then((url) => {
        if (!cancelled) setQr(url);
      })
      .catch(() => {
        if (!cancelled) setState((s) => ({ ...s, status: 'error', error: '二维码生成失败（链接太长）' }));
      });
    return () => {
      cancelled = true;
    };
  }, [state.link]);

  // 倒计时
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  // 轮询配对进度
  useEffect(() => {
    if (!state.id || (state.status !== 'pending' && state.status !== 'scanned')) return;
    const gen = generation.current;
    const id = state.id;
    const t = setInterval(async () => {
      try {
        const r = await action<{ status?: string; name?: string; error?: string }>({ action: 'hub-pair-status', id });
        if (gen !== generation.current || !r.status) return;
        setState((s) => {
          if (s.id !== id) return s;
          if (r.status === 'paired') return { ...s, status: 'paired', deviceName: r.name || s.deviceName };
          if (r.status === 'scanned') return { ...s, status: 'scanned', deviceName: r.name };
          if (r.status === 'expired') return { ...s, status: 'expired' };
          return s.status === 'pending' ? s : { ...s, status: 'pending' };
        });
      } catch {
        // 临时失败，下一轮再试
      }
    }, 1500);
    return () => clearInterval(t);
  }, [state.id, state.status]);

  const left = state.expiresAt ? Math.max(0, Math.floor((state.expiresAt - now) / 1000)) : 0;
  const expired = state.status === 'expired' || (state.status === 'pending' && state.expiresAt != null && left === 0);

  // 没人扫就自动换码
  useEffect(() => {
    if (expired) void generate();
  }, [expired, generate]);

  return (
    <div
      style={{
        padding: 16,
        borderRadius: 10,
        background: light ? 'transparent' : '#141822',
        color: fg,
        display: 'flex',
        gap: 20,
        alignItems: 'center',
        flexWrap: 'wrap',
      }}
    >
      <div
        style={{
          width: 248,
          height: 248,
          borderRadius: 12,
          background: '#FFFFFF',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          position: 'relative',
          overflow: 'hidden',
          flexShrink: 0,
        }}
      >
        {qr && state.status !== 'error' ? (
          <img
            src={qr}
            alt="vibelink 配对二维码"
            width={248}
            height={248}
            style={{
              display: 'block',
              filter: state.status === 'pending' ? 'none' : 'blur(6px)',
              opacity: state.status === 'pending' ? 1 : 0.35,
              transition: 'all 0.2s ease',
            }}
          />
        ) : (
          <span style={{ color: '#6B7280', fontSize: 13 }}>{state.status === 'error' ? '—' : '生成中…'}</span>
        )}
        {(state.status === 'scanned' || state.status === 'paired') && (
          <div
            style={{
              position: 'absolute',
              inset: 0,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 6,
              color: '#111827',
              textAlign: 'center',
              padding: 16,
            }}
          >
            <span style={{ fontSize: 40 }}>{state.status === 'paired' ? '✅' : '📱'}</span>
            <span style={{ fontSize: 14, fontWeight: 700 }}>
              {state.status === 'paired' ? '已连接' : '已扫码'}
            </span>
          </div>
        )}
      </div>

      <div style={{ flex: 1, minWidth: 220, display: 'flex', flexDirection: 'column', gap: 10 }}>
        {state.status === 'paired' ? (
          <>
            <div style={{ fontSize: 18, fontWeight: 700, color: '#34D399' }}>
              已连接 {state.deviceName || '手机'}
            </div>
            <div style={{ fontSize: 12, color: '#9CA3AF', lineHeight: 1.6 }}>
              这台手机拿到了自己独立的 token，可以随时在 hub 上单独吊销（vibelink-hub token list / token revoke）。
            </div>
            <div>
              <button type="button" onClick={() => void generate()} style={btn('#2563EB')}>
                再连接一台
              </button>
            </div>
          </>
        ) : state.status === 'scanned' ? (
          <>
            <div style={{ fontSize: 18, fontWeight: 700, color: '#60A5FA' }}>
              {state.deviceName || '手机'} 已扫码
            </div>
            <div style={{ fontSize: 13, color: fg }}>请在手机上点「连接」完成配对。</div>
            <div style={{ fontSize: 12, color: '#788296' }}>
              不是你的手机？
              <button type="button" onClick={() => void generate()} style={linkBtn}>
                作废这个码
              </button>
            </div>
          </>
        ) : state.status === 'error' ? (
          <>
            <div style={{ fontSize: 14, fontWeight: 600, color: '#F87171' }}>生成配对二维码失败</div>
            <div style={{ fontSize: 12, color: '#FCA5A5', wordBreak: 'break-all' }}>{state.error}</div>
            <div>
              <button type="button" onClick={() => void generate()} style={btn('#2563EB')}>
                重试
              </button>
            </div>
          </>
        ) : (
          <>
            <div style={{ fontSize: 16, fontWeight: 700 }}>
              用 VibeLink 扫码连接{state.host ? ` ${state.host}` : '这台电脑'}
            </div>
            <ol style={{ margin: 0, paddingLeft: 18, fontSize: 13, color: fg2, lineHeight: 1.8 }}>
              <li>打开手机 VibeLink</li>
              <li>进入「远程会话」，点「扫码连接」</li>
              <li>对准左边的二维码，在手机上确认连接</li>
            </ol>
            <div style={{ fontSize: 12, color: '#788296' }}>
              {state.status === 'loading'
                ? '正在生成…'
                : `一次性二维码，${String(Math.floor(left / 60)).padStart(2, '0')}:${String(left % 60).padStart(2, '0')} 后自动更换`}
              <button type="button" onClick={() => void generate()} style={linkBtn}>
                刷新
              </button>
              {state.link && (
                <button
                  type="button"
                  onClick={() => {
                    if (state.link) void navigator.clipboard.writeText(state.link);
                  }}
                  style={linkBtn}
                  title="手机扫不了码时：把链接发到手机，在 VibeLink「扫不了码？」里粘贴"
                >
                  复制配对链接
                </button>
              )}
            </div>
          </>
        )}

        {state.urls && state.urls.length > 0 && state.status !== 'paired' && (
          <details style={{ fontSize: 11, color: '#788296' }}>
            <summary style={{ cursor: 'pointer' }}>手机会依次尝试这些地址</summary>
            <div style={{ fontFamily: 'monospace', marginTop: 4, lineHeight: 1.6 }}>
              {state.urls.map((u) => (
                <div key={u}>{u}</div>
              ))}
            </div>
            <div style={{ marginTop: 4 }}>
              公网地址写在 hub 数据目录的 public-url.txt（或环境变量 VIBELINK_PUBLIC_URL）。
            </div>
          </details>
        )}
      </div>
    </div>
  );
}

function btn(bg: string): React.CSSProperties {
  return {
    height: 30,
    padding: '0 12px',
    borderRadius: 6,
    border: '1px solid rgba(255,255,255,0.1)',
    background: bg,
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: 500,
    cursor: 'pointer',
  };
}

const linkBtn: React.CSSProperties = {
  marginLeft: 6,
  padding: 0,
  border: 'none',
  background: 'none',
  color: '#60A5FA',
  fontSize: 12,
  cursor: 'pointer',
};

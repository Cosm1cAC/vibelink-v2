import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import type {
  NotificationInput,
  NotificationResult,
  NotificationSnapshot,
} from '../../../shared/vibelinkNotifications';
type Bridge = { notifications(input: NotificationInput): Promise<NotificationResult> };
export function NotificationPanel({ accountKey, bridge }: { accountKey: string; bridge?: Bridge }) {
  const { t } = useTranslation();
  const text = (key: string) => t(`vibelinkNotifications.${key}`);
  const [snapshot, setSnapshot] = useState<NotificationSnapshot | null>(null);
  const [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  const epoch = useRef(0);
  const requestVersion = useRef(0),
    busyRef = useRef(false);
  const apply = useCallback(
    async (input: NotificationInput, version: number) => {
      if (!bridge || !accountKey) return;
      const request = ++requestVersion.current;
      try {
        const result = await bridge.notifications(input);
        if (version !== epoch.current || request !== requestVersion.current) return;
        if (result.success) {
          setSnapshot(result.data);
          setError(result.data.error);
        } else setError(result.error.message);
      } catch {
        if (version === epoch.current && request === requestVersion.current)
          setError(t('vibelinkNotifications.unavailable'));
      }
    },
    [bridge, accountKey, t],
  );
  useEffect(() => {
    const version = ++epoch.current;
    setSnapshot(null);
    setError('');
    setBusy(false);
    busyRef.current = false;
    void apply({ action: 'snapshot' }, version);
    return () => {
      epoch.current++;
    };
  }, [accountKey, apply]);
  useEffect(() => {
    if (!snapshot?.settings.enabled) return;
    let disposed = false;
    let timer: ReturnType<typeof setTimeout>;
    const version = epoch.current;
    const next = async () => {
      if (disposed) return;
      if (document.visibilityState === 'visible' && !busyRef.current)
        await apply({ action: 'snapshot' }, version);
      if (!disposed) timer = setTimeout(() => void next(), 5000);
    };
    timer = setTimeout(() => void next(), 5000);
    return () => {
      disposed = true;
      clearTimeout(timer);
    };
  }, [snapshot?.settings.enabled, apply]);
  const command = async (input: NotificationInput) => {
    if (busyRef.current) return;
    const version = epoch.current;
    setBusy(true);
    busyRef.current = true;
    try {
      await apply(input, version);
    } finally {
      if (version === epoch.current) {
        setBusy(false);
        busyRef.current = false;
      }
    }
  };
  return (
    <details className="rounded-2xl border border-[var(--border-default)] p-5">
      <summary className="cursor-pointer text-14 font-medium">{text('title')}</summary>
      <div className="mt-4 flex flex-col gap-4 text-13">
        <p className="text-[var(--text-secondary)]">{text('description')}</p>
        <label className="flex items-center gap-3">
          <input
            type="checkbox"
            aria-label={text('enabled')}
            disabled={busy || !snapshot}
            checked={snapshot?.settings.enabled ?? false}
            onChange={(event) =>
              void command({
                action: 'configure',
                enabled: event.target.checked,
                preview: snapshot?.settings.preview ?? false,
              })
            }
          />
          {text('enabled')}
        </label>
        <label className="flex items-center gap-3">
          <input
            type="checkbox"
            aria-label={text('preview')}
            disabled={busy || !snapshot?.settings.enabled}
            checked={snapshot?.settings.preview ?? false}
            onChange={(event) =>
              void command({
                action: 'configure',
                enabled: snapshot?.settings.enabled ?? false,
                preview: event.target.checked,
              })
            }
          />
          {text('preview')}
        </label>
        <p className="text-[var(--text-secondary)]">{text('privacy')}</p>
        {snapshot?.capability &&
          (!snapshot.capability.supported || !snapshot.capability.registered) && (
            <p role="status">{text('capabilityUnavailable')}</p>
          )}
        {error && (
          <p role="alert" className="text-[var(--text-primary)]">
            {error}
          </p>
        )}
        <div className="flex items-center justify-between gap-3">
          <h2 className="font-medium">{text('receipts')}</h2>
          <Button
            variant="secondary"
            disabled={busy}
            onClick={() => void command({ action: 'snapshot' })}
          >
            {text('refresh')}
          </Button>
        </div>
        {!snapshot?.entries.length && (
          <p className="text-[var(--text-secondary)]">{text('empty')}</p>
        )}
        <ul className="flex max-h-[340px] flex-col gap-3 overflow-auto">
          {snapshot?.entries.map((entry) => (
            <li key={entry.id} className="rounded-xl border border-[var(--border-default)] p-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <span className="font-medium">{text(`state_${entry.state}`)}</span>
                <time className="text-12 text-[var(--text-secondary)]">
                  {new Date(entry.createdAt).toLocaleString()}
                </time>
              </div>
              <p className="mt-2 break-all text-12 text-[var(--text-secondary)]">
                {text('work')}: {entry.workId}
              </p>
              {entry.state === 'unknown' && <p className="mt-2">{text('unknownHelp')}</p>}
              {entry.state === 'suppressed' && (
                <p className="mt-2 text-[var(--text-secondary)]">{text('suppressedHelp')}</p>
              )}
              {entry.error && (
                <p className="mt-2 break-words text-[var(--text-secondary)]">{entry.error}</p>
              )}
              {entry.receipts.length > 0 && (
                <p className="mt-2 text-12 text-[var(--text-secondary)]">
                  {entry.receipts
                    .map(
                      (receipt) =>
                        `${text(`receipt_${receipt.type}`)} · ${new Date(receipt.at).toLocaleTimeString()}`,
                    )
                    .join(' / ')}
                </p>
              )}
              {['failed', 'unknown'].includes(entry.state) && (
                <Button
                  variant="secondary"
                  className="mt-3"
                  disabled={busy || !snapshot.settings.enabled}
                  onClick={() => void command({ action: 'resend', id: entry.id })}
                >
                  {text('resend')}
                </Button>
              )}
            </li>
          ))}
        </ul>
        <p className="text-12 text-[var(--text-secondary)]">{text('resendHelp')}</p>
      </div>
    </details>
  );
}

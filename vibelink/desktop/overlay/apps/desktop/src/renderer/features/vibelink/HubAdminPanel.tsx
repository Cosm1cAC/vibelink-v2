import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import type { HubSession } from '../../../shared/vibelinkAuth';
import { AdminLimitsSchema } from '../../../shared/vibelinkAdmin';
import type {
  AdminLimits,
  AdminSeat,
  AdminData,
  AdminInput,
  AdminRequest,
  AdminResult,
} from '../../../shared/vibelinkAdmin';
import { type AdminBridge, useAdminCapability } from './useAdminCapability';
import { AdminInviteCode } from './AdminInviteCode';
import { utc8Day } from './adminPresentation';
type Snapshot = Extract<AdminData, { kind: 'snapshot' }>;
const cell = 'px-3 py-3 text-left align-top';
const limitKeys = [
  'dailyRequests',
  'dailyTokens',
  'dailyInputBytes',
  'concurrentRequests',
  'requestsPerMinute',
] as const;
type Editor = {
  userId: string;
  revision: number;
  view: string;
  values: Record<keyof AdminLimits, string>;
};
const panel = 'rounded-2xl border border-[var(--border-default)] p-5';
function utc8Time(value: number) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', {
        timeZone: 'Asia/Shanghai',
        dateStyle: 'short',
        timeStyle: 'medium',
      }).format(value)
    : '—';
}
export function HubAdminPanel({
  session,
  bridge,
}: {
  session: HubSession | null;
  bridge?: AdminBridge;
}) {
  const { t } = useTranslation();
  const text = (key: string) => t(`vibelinkAdmin.${key}`);
  const access = useAdminCapability(session, bridge);
  const [day, setDay] = useState(() => utc8Day());
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null),
    [requests, setRequests] = useState<AdminRequest[]>([]);
  const [editor, setEditor] = useState<Editor | null>(null),
    [notice, setNotice] = useState('');
  const [reloadRequired, setReloadRequired] = useState(false);
  const [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  const view = access.allowed ? `${access.scope}/${day}` : '';
  const viewRef = useRef(view);
  viewRef.current = view;
  const version = useRef(0);
  const [dataView, setDataView] = useState('');
  const editorForm = useRef<HTMLFormElement | null>(null);
  const editTarget = editor?.view === view ? `${editor.view}/${editor.userId}` : '';
  useEffect(() => {
    if (!editTarget || !editorForm.current) return;
    // Ten seat rows place this form below the viewport. Reveal it on open or
    // seat changes; field edits keep the same keys and must not move the page.
    editorForm.current.scrollIntoView?.({ block: 'center', behavior: 'auto' });
    editorForm.current
      .querySelector<HTMLInputElement>('input:not(:disabled)')
      ?.focus({ preventScroll: true });
  }, [editTarget]);
  const clear = () => {
    setReloadRequired(false);
    setSnapshot(null);
    setRequests([]);
    setDataView('');
    setEditor(null);
    setNotice('');
  };
  const read = useCallback(async () => {
    if (!view || !bridge) return;
    const current = ++version.current;
    setBusy(true);
    setReloadRequired(false);
    setError('');
    setEditor(null);
    setNotice('');
    setSnapshot(null);
    setRequests([]);
    setDataView('');
    const valid = () =>
      version.current === current &&
      viewRef.current === view &&
      (session?.expiresAt ?? 0) > Date.now();
    const call = async (input: AdminInput): Promise<AdminData | null> => {
      const result: AdminResult = await bridge.admin(input);
      if (!valid()) return null;
      if (!result.success) {
        if (
          ['AUTH_REQUIRED', 'FORBIDDEN', 'ACCOUNT_CHANGED', 'CREDENTIAL_UNAVAILABLE'].includes(
            result.error.code,
          )
        ) {
          clear();
          access.revoke();
        }
        setError(result.error.code === 'OUTCOME_UNKNOWN' ? text('unknown') : text('unavailable'));
        return null;
      }
      return result.data;
    };
    try {
      const data = await call({ action: 'snapshot', day });
      if (!valid() || data?.kind !== 'snapshot' || data.day !== day) return;
      setSnapshot(data);
      setDataView(view);
      const recent = await call({ action: 'requests' });
      if (valid() && recent?.kind === 'requests') setRequests(recent.requests);
    } catch {
      if (valid()) setError(text('unavailable'));
    } finally {
      if (valid()) setBusy(false);
    }
  }, [view, bridge, day, session?.expiresAt, access.revoke, t]);
  useEffect(() => {
    version.current++;
    clear();
    setError('');
    setBusy(false);
    if (view) void read();
    return () => {
      version.current++;
    };
  }, [view, read]);
  const edit = (seat: AdminSeat) => {
    if (busy || reloadRequired) return;
    setNotice('');
    setError('');
    setEditor({
      userId: seat.tenantId,
      revision: seat.limitsRevision,
      view,
      values: Object.fromEntries(limitKeys.map((key) => [key, String(seat.limits[key])])) as Record<
        keyof AdminLimits,
        string
      >,
    });
  };
  const save = async () => {
    if (!editor || editor.view !== view || !view || !bridge || busy) return;
    const parsed = AdminLimitsSchema.safeParse(
      Object.fromEntries(
        limitKeys.map((key) => [
          key,
          editor.values[key].trim() === '' ? NaN : Number(editor.values[key]),
        ]),
      ),
    );
    if (!parsed.success) {
      setError(text('invalidLimits'));
      return;
    }
    const current = ++version.current,
      captured = editor;
    const valid = () =>
      current === version.current &&
      viewRef.current === view &&
      (session?.expiresAt ?? 0) > Date.now();
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await bridge.admin({
        action: 'setLimits',
        userId: captured.userId,
        expectedRevision: captured.revision,
        limits: parsed.data,
      });
      if (!valid()) return;
      if (!result.success) {
        setEditor(null);
        if (result.error.code === 'CONFLICT' || result.error.code === 'OUTCOME_UNKNOWN')
          setReloadRequired(true);
        if (
          ['AUTH_REQUIRED', 'FORBIDDEN', 'ACCOUNT_CHANGED', 'CREDENTIAL_UNAVAILABLE'].includes(
            result.error.code,
          )
        ) {
          clear();
          access.revoke();
        }
        setError(
          text(
            result.error.code === 'CONFLICT'
              ? 'conflict'
              : result.error.code === 'OUTCOME_UNKNOWN'
                ? 'unknown'
                : 'unavailable',
          ),
        );
        return;
      }
      if (result.data.kind !== 'limitsSaved') return;
      const saved = result.data;
      setSnapshot((previous) =>
        previous
          ? {
              ...previous,
              seats: previous.seats.map((seat) =>
                seat.tenantId === saved.userId
                  ? { ...seat, limits: saved.limits, limitsRevision: saved.limitsRevision }
                  : seat,
              ),
              users: previous.users.map((user) =>
                user.userId === saved.userId
                  ? { ...user, limits: saved.limits, limitsRevision: saved.limitsRevision }
                  : user,
              ),
            }
          : null,
      );
      setEditor(null);
      setNotice(text('saved'));
    } catch {
      if (valid()) {
        setEditor(null);
        setReloadRequired(true);
        setError(text('unknown'));
      }
    } finally {
      if (valid()) setBusy(false);
    }
  };
  if (!access.allowed)
    return (
      <section className={panel}>
        <h1 className="text-20 font-semibold">{text('title')}</h1>
        <p role="status" className="mt-3 text-14 text-[var(--text-secondary)]">
          {text(access.checked ? 'denied' : 'loading')}
        </p>
      </section>
    );
  const data = dataView === view ? snapshot : null;
  return (
    <div className="flex min-w-0 flex-col gap-5">
      <section className={panel}>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="text-24 font-semibold">{text('title')}</h1>
            <p className="mt-2 text-13 text-[var(--text-secondary)]">{text('description')}</p>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <label className="flex items-center gap-2 text-13">
              {text('day')}
              <input
                aria-label={text('day')}
                type="date"
                value={day}
                disabled={busy}
                onChange={(e) => setDay(e.target.value)}
                className="rounded-lg border border-[var(--border-default)] bg-[var(--surface-elevated)] px-2 py-1.5"
              />
            </label>
            <Button variant="secondary" disabled={busy} onClick={() => void read()}>
              {text('refresh')}
            </Button>
          </div>
        </div>
        {error && (
          <p role="alert" className="mt-3 text-13 text-[var(--text-danger)]">
            {error}
          </p>
        )}
        {notice && (
          <p role="status" className="mt-3 text-13">
            {notice}
          </p>
        )}
        {busy && (
          <p role="status" className="mt-3 text-13 text-[var(--text-secondary)]">
            {text('loading')}
          </p>
        )}
      </section>
      <section className={panel}>
        <h2 className="mb-4 text-16 font-semibold">
          {text('seats')} {data ? `· ${data.seats.length}` : ''}
        </h2>
        <div className="overflow-x-auto">
          <table className="w-full text-13">
            <thead className="text-12 text-[var(--text-secondary)]">
              <tr>
                {['user', 'activation', 'lastDevice', 'invite', 'limits'].map((k) => (
                  <th className={cell} key={k}>
                    {text(k)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data?.seats.map((seat) => (
                <tr key={seat.tenantId} className="border-t border-[var(--border-default)]">
                  <td className={cell}>
                    <strong className="font-medium">{seat.label}</strong>
                    <div className="mt-1 text-12 text-[var(--text-secondary)]">{seat.tenantId}</div>
                  </td>
                  <td className={cell}>
                    {seat.activatedAt ? utc8Time(seat.activatedAt) : text('inactive')}
                  </td>
                  <td className={cell}>{seat.lastDevice || '—'}</td>
                  <td className={cell}>
                    <AdminInviteCode
                      key={`${view}/${seat.tenantId}`}
                      scope={view}
                      code={seat.inviteCode}
                      labels={{
                        show: text('show'),
                        hide: text('hide'),
                        copy: text('copy'),
                        copied: text('copied'),
                        copyFailed: text('copyFailed'),
                      }}
                    />
                  </td>
                  <td className={cell}>
                    <Button
                      size="xs"
                      variant="secondary"
                      aria-label={`${text('edit')} ${seat.tenantId}`}
                      disabled={busy || reloadRequired}
                      onClick={() => edit(seat)}
                    >
                      {text('edit')}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      {editor?.view === view && (
        <form
          ref={editorForm}
          className={panel}
          onSubmit={(event) => {
            event.preventDefault();
            void save();
          }}
        >
          <h2 className="text-16 font-semibold">
            {text('edit')} · {editor.userId}
          </h2>
          <p className="mt-2 text-12 text-[var(--text-secondary)]">{text('limitNote')}</p>
          <div className="my-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {limitKeys.map((key) => (
              <label className="flex flex-col gap-2 text-13" key={key}>
                {text(key)}
                <input
                  type="number"
                  required
                  step="1"
                  min={key === 'concurrentRequests' || key === 'requestsPerMinute' ? 1 : 0}
                  max={key === 'concurrentRequests' ? 16 : Number.MAX_SAFE_INTEGER}
                  disabled={busy}
                  value={editor.values[key]}
                  onChange={(event) =>
                    setEditor({
                      ...editor,
                      values: { ...editor.values, [key]: event.target.value },
                    })
                  }
                  className="rounded-lg border border-[var(--border-default)] bg-[var(--surface-elevated)] px-3 py-2"
                />
              </label>
            ))}
          </div>
          <div className="flex items-center gap-3">
            <Button type="submit" disabled={busy}>
              {text('save')}
            </Button>
            <Button variant="secondary" disabled={busy} onClick={() => setEditor(null)}>
              {text('cancel')}
            </Button>
          </div>
        </form>
      )}
      <section className={panel}>
        <h2 className="text-16 font-semibold">{text('usage')}</h2>
        <p className="mb-4 mt-2 text-12 text-[var(--text-secondary)]">{text('cacheNote')}</p>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[1180px] text-13">
            <thead className="text-12 text-[var(--text-secondary)]">
              <tr>
                {[
                  'user',
                  'requests',
                  'input',
                  'output',
                  'cached',
                  'total',
                  'estimated',
                  'reserved',
                  'missing',
                  'status',
                ].map((k) => (
                  <th className={cell} key={k}>
                    {text(k)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data?.users.map((user) => (
                <tr key={user.userId} className="border-t border-[var(--border-default)]">
                  <td className={cell}>
                    <strong>{user.label}</strong>
                    <div className="text-12 text-[var(--text-secondary)]">{user.userId}</div>
                  </td>
                  <td className={cell}>
                    {user.usedRequests} / {user.limits.dailyRequests || text('unlimited')}
                    <div className="mt-1 text-11 text-[var(--text-secondary)]">
                      {user.successfulRequests} / {user.failedRequests} / {user.interruptedRequests}
                    </div>
                  </td>
                  <td className={cell}>{user.inputTokens}</td>
                  <td className={cell}>{user.outputTokens}</td>
                  <td className={cell}>{user.cachedTokens}</td>
                  <td className={cell}>{user.totalTokens}</td>
                  <td className={cell}>{user.estimatedTokens}</td>
                  <td className={cell}>{user.reservedTokens}</td>
                  <td className={cell}>{user.usageMissingRequests}</td>
                  <td className={cell}>
                    {text(user.warning || 'normal')}
                    <div className="mt-1 text-11 text-[var(--text-secondary)]">
                      {text('charged')}: {user.chargedTokens} /{' '}
                      {user.limits.dailyTokens || text('unlimited')}
                    </div>
                    <div className="mt-1 text-11 text-[var(--text-secondary)]">
                      {text('inputBytes')}: {user.inputBytes}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {data && !data.users.length && (
          <p className="mt-3 text-13 text-[var(--text-secondary)]">{text('emptyUsage')}</p>
        )}
        <p className="mt-3 text-12 text-[var(--text-secondary)]">{text('requestLegend')}</p>
      </section>
      <section className={panel}>
        <h2 className="text-16 font-semibold">{text('recent')}</h2>
        <p className="mb-4 mt-2 text-12 text-[var(--text-secondary)]">{text('recentNote')}</p>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[900px] text-13">
            <thead className="text-12 text-[var(--text-secondary)]">
              <tr>
                {[
                  'time',
                  'user',
                  'model',
                  'status',
                  'duration',
                  'total',
                  'estimated',
                  'missing',
                ].map((k) => (
                  <th className={cell} key={k}>
                    {text(k)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {requests
                .filter((row) => row.day === day)
                .map((row) => (
                  <tr key={row.requestId} className="border-t border-[var(--border-default)]">
                    <td className={cell}>{utc8Time(row.startedAt)}</td>
                    <td className={cell}>{row.userId}</td>
                    <td className={`${cell} max-w-[220px] break-words`}>{row.model}</td>
                    <td className={cell}>
                      {row.outcome} {row.httpStatus ?? ''}
                    </td>
                    <td className={cell}>{row.durationMs} ms</td>
                    <td className={cell}>{row.tokens?.total ?? '—'}</td>
                    <td className={cell}>{row.estimatedTokens}</td>
                    <td className={cell}>{row.usageMissing ? text('yes') : text('no')}</td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
        {!requests.some((row) => row.day === day) && (
          <p className="mt-3 text-13 text-[var(--text-secondary)]">{text('emptyRequests')}</p>
        )}
      </section>
    </div>
  );
}

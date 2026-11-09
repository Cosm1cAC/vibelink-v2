import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import type { HubSession } from '../../../shared/vibelinkAuth';
import type {
  HubWork,
  HubOutbox,
  HubWorkInput,
  HubWorkResult,
  HubMessage,
} from '../../../shared/vibelinkWork';
import { workActions } from './workPresentation';

type Bridge = { work(input: HubWorkInput): Promise<HubWorkResult> };
type Group = { id: string; name: string; botIds: string[] };
type Bot = { id: string; name: string; jobTitle: string; archived: boolean };
export function HubWorkPanel({
  session,
  bridge,
  targetGroupId = '',
  targetWorkId = '',
  targetRequestKey = '',
}: {
  session: HubSession | null;
  bridge?: Bridge;
  targetGroupId?: string;
  targetWorkId?: string;
  targetRequestKey?: string;
}) {
  const { t } = useTranslation();
  const text = (key: string) => t(`vibelinkWork.${key}`);
  const [messages, setMessages] = useState<HubMessage[]>([]);
  const [groups, setGroups] = useState<Group[]>([]),
    [bots, setBots] = useState<Bot[]>([]);
  const [gid, setGid] = useState(''),
    [rows, setRows] = useState<HubWork[]>([]),
    [outbox, setOutbox] = useState<HubOutbox[]>([]);
  const [selected, setSelected] = useState(''),
    [query, setQuery] = useState(''),
    [archived, setArchived] = useState(false);
  const [effectiveQuery, setEffectiveQuery] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setEffectiveQuery(query), 300);
    return () => clearTimeout(timer);
  }, [query]);
  const [draft, setDraft] = useState(''),
    [follow, setFollow] = useState(false),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState(false),
    [name, setName] = useState(''),
    [members, setMembers] = useState<string[]>([]);
  const epoch = useRef(0),
    latestRead = useRef(''),
    listVersion = useRef(0);
  const operationRef = useRef(0);
  const handledTarget = useRef('');
  const startingTarget = useRef('');
  const refreshFlights = useRef(new Map<string, Promise<void>>());
  const viewRef = useRef(''),
    draftRef = useRef('');
  draftRef.current = draft;
  const scope = session
    ? `${session.issuer}/${session.edition}/${session.userId}/${session.sessionId}`
    : '';
  const view = `${scope}/${gid}/${effectiveQuery}/${archived}`;
  viewRef.current = view;
  const call = useCallback(
    async (input: HubWorkInput) => {
      const current = epoch.current;
      if (!bridge || !scope) return null;
      try {
        const result = await bridge.work(input);
        if (current !== epoch.current) return null;
        if (!result.success) {
          setError(result.error.message);
          return null;
        }
        if (!['outbox', 'markRead', 'messages'].includes(input.action)) setError('');
        return result.data;
      } catch {
        if (current === epoch.current) setError(t('vibelinkWork.offline'));
        return null;
      }
    },
    [bridge, scope, t],
  );
  useEffect(() => {
    epoch.current++;
    setGroups([]);
    setMessages([]);
    setRows([]);
    setOutbox([]);
    setGid('');
    setSelected('');
    setDraft('');
    setFollow(false);
    setBusy(false);
    setCreating(false);
    setBots([]);
    setMembers([]);
    setName('');
    setQuery('');
    setEffectiveQuery('');
    setArchived(false);
    setError('');
    latestRead.current = '';
    if (scope)
      void call({ action: 'groups' }).then((data) => {
        if (data?.groups) {
          setGroups(data.groups);
          setGid(data.groups[0]?.id || '');
        }
      });
    return () => {
      epoch.current++;
    };
  }, [scope, call]);
  const refresh = useCallback(async () => {
    if (!gid) return;
    const running = refreshFlights.current.get(view);
    if (running) return running;
    const flight = (async () => {
      const version = ++listVersion.current;
      const data = await call({ action: 'list', groupId: gid, q: effectiveQuery, archived });
      if (version !== listVersion.current || viewRef.current !== view || !data) return;
      if (data.work) setRows(data.work);
      const timeline = await call({ action: 'messages', groupId: gid });
      if (timeline?.messages && viewRef.current === view) setMessages(timeline.messages);
      const pending = await call({ action: 'outbox' });
      if (pending?.outbox && viewRef.current === view) setOutbox(pending.outbox);
    })();
    refreshFlights.current.set(view, flight);
    try {
      await flight;
    } finally {
      if (refreshFlights.current.get(view) === flight) refreshFlights.current.delete(view);
    }
  }, [call, gid, effectiveQuery, archived, view]);
  useEffect(() => {
    operationRef.current++;
    setSelected('');
    setRows([]);
    setMessages([]);
    setFollow(false);
    setDraft('');
    setBusy(false);
  }, [gid]);
  useEffect(() => {
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      if (disposed) return;
      if (document.visibilityState === 'visible') await refresh();
      if (!disposed)
        timer = setTimeout(() => {
          void poll();
        }, 5000);
    };
    void poll();
    return () => {
      disposed = true;
      clearTimeout(timer);
      listVersion.current++;
    };
  }, [refresh]);
  useEffect(() => {
    const key = `${scope}/${targetGroupId}/${targetWorkId}/${targetRequestKey}`;
    if (
      !targetGroupId ||
      !targetWorkId ||
      handledTarget.current === key ||
      !groups.some((group) => group.id === targetGroupId)
    )
      return;
    if (startingTarget.current !== key) {
      startingTarget.current = key;
      const needsReset =
        query !== '' || effectiveQuery !== '' || !archived || gid !== targetGroupId;
      setQuery('');
      setEffectiveQuery('');
      setArchived(true);
      setGid(targetGroupId);
      if (needsReset) return;
    }
    if (gid !== targetGroupId) {
      setGid(targetGroupId);
      return;
    }
    if (!archived) {
      setArchived(true);
      return;
    }
    if (rows.some((row) => row.id === targetWorkId && row.groupId === gid)) {
      handledTarget.current = key;
      setSelected(targetWorkId);
      setFollow(false);
    }
  }, [
    scope,
    targetGroupId,
    targetWorkId,
    targetRequestKey,
    groups,
    gid,
    archived,
    rows,
    query,
    effectiveQuery,
  ]);
  const current = rows.find((row) => row.id === selected);
  useEffect(() => {
    if (!current || document.visibilityState !== 'visible' || !document.hasFocus()) return;
    const key = `${scope}/${current.id}/${current.updatedAt}`;
    if (key === latestRead.current) return;
    latestRead.current = key;
    void call({
      action: 'markRead',
      groupId: gid,
      workId: current.id,
      updatedAt: current.updatedAt,
    }).then((result) => {
      if (!result && latestRead.current === key) latestRead.current = '';
    });
  }, [scope, current, gid, call]);
  async function send() {
    if (!gid || !draft.trim() || busy || (follow && !current?.canManage)) return;
    const version = epoch.current;
    const operation = ++operationRef.current;
    setBusy(true);
    const result = await call({
      action: 'submit',
      groupId: gid,
      text: draft,
      ...(follow && current ? { replyToWorkId: current.id } : {}),
    });
    if (version !== epoch.current || operation !== operationRef.current) return;
    if (result && draftRef.current === draft) {
      setDraft('');
      setFollow(false);
    }
    if (viewRef.current !== view) {
      setBusy(false);
      return;
    }
    if (result) {
      setFollow(false);
      await refresh();
    }
    if (version === epoch.current && operation === operationRef.current) setBusy(false);
  }
  async function manage(command: 'cancel' | 'retry' | 'archive') {
    if (!current || !workActions(current).includes(command) || busy) return;
    const version = epoch.current;
    const operation = ++operationRef.current;
    setBusy(true);
    const result = await call({ action: 'manage', groupId: gid, workId: current.id, command });
    if (version !== epoch.current || operation !== operationRef.current) return;
    if (viewRef.current !== view) {
      setBusy(false);
      return;
    }
    if (result) await refresh();
    if (version === epoch.current && operation === operationRef.current) setBusy(false);
  }
  async function exportWork() {
    if (!current) return;
    const result = await call({ action: 'export', groupId: gid, workId: current.id });
    if (!result?.exported) return;
    const url = URL.createObjectURL(new Blob([result.exported], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `vibelink-work-${current.id}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }
  async function createGroup() {
    if (!name.trim() || !members.length || busy) return;
    const version = epoch.current;
    const operation = ++operationRef.current;
    setBusy(true);
    const data = await call({ action: 'createGroup', name, botIds: members });
    if (version !== epoch.current || operation !== operationRef.current) return;
    if (data?.group) {
      setGroups((old) => [...old, data.group!]);
      setGid(data.group.id);
      setCreating(false);
      setName('');
      setMembers([]);
    }
    setBusy(false);
  }
  const control =
    'rounded-lg border border-[var(--border-default)] bg-[var(--surface)] px-3 py-2 text-13 text-[var(--text-primary)]';
  return (
    <section className="flex min-h-0 flex-1 flex-col gap-4" aria-label={text('title')}>
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="mr-auto text-20 font-semibold">{text('title')}</h1>
        <Button
          variant="secondary"
          onClick={() => {
            setCreating(!creating);
            void call({ action: 'bots' }).then((data) => {
              if (data?.bots) setBots(data.bots);
            });
          }}
        >
          {text('createGroup')}
        </Button>
        <Button
          variant="secondary"
          onClick={() => {
            void call({ action: 'groups' }).then((data) => {
              if (data?.groups) {
                setGroups(data.groups);
                if (!data.groups.some((g) => g.id === gid)) setGid(data.groups[0]?.id || '');
              }
            });
            void refresh();
          }}
        >
          {text('refresh')}
        </Button>
      </div>
      {error && (
        <p role="alert" className="text-13 text-[var(--text-secondary)]">
          {error}
        </p>
      )}
      {creating && (
        <div className="space-y-3 rounded-xl border border-[var(--border-default)] p-4">
          <label className="block text-13">
            {text('groupName')}
            <input
              value={name}
              maxLength={100}
              onChange={(e) => setName(e.target.value)}
              className={`${control} ml-3`}
            />
          </label>
          <p className="text-13">{text('members')}</p>
          <div className="flex flex-wrap gap-4">
            {bots
              .filter((b) => !b.archived)
              .map((bot) => (
                <label key={bot.id} className="flex items-center gap-2 text-13">
                  <input
                    type="checkbox"
                    checked={members.includes(bot.id)}
                    onChange={(e) =>
                      setMembers((old) =>
                        e.target.checked ? [...old, bot.id] : old.filter((id) => id !== bot.id),
                      )
                    }
                  />
                  {bot.name}
                </label>
              ))}
          </div>
          <Button
            disabled={busy || !name.trim() || !members.length}
            onClick={() => void createGroup()}
          >
            {text('createGroup')}
          </Button>
        </div>
      )}
      {!groups.length && (
        <div className="rounded-xl border border-[var(--border-default)] px-6 py-12 text-center text-14 text-[var(--text-secondary)]">
          {text('emptyGroups')}
        </div>
      )}
      {!!groups.length && (
        <>
          <div className="flex flex-wrap gap-3">
            <select
              aria-label={text('group')}
              value={gid}
              onChange={(e) => setGid(e.target.value)}
              className={control}
            >
              {groups.map((g) => (
                <option key={g.id} value={g.id}>
                  {g.name}
                </option>
              ))}
            </select>
            <input
              type="search"
              aria-label={text('search')}
              placeholder={text('search')}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              className={`${control} min-w-40 flex-1`}
            />
            <label className="flex items-center gap-2 text-13">
              <input
                type="checkbox"
                checked={archived}
                onChange={(e) => setArchived(e.target.checked)}
              />
              {text('archived')}
            </label>
          </div>
          <div className="grid min-h-64 flex-1 gap-4 md:grid-cols-[260px_1fr]">
            <div className="max-h-[54vh] overflow-auto rounded-xl border border-[var(--border-default)]">
              {!rows.length && (
                <p className="p-5 text-13 text-[var(--text-secondary)]">{text('emptyWork')}</p>
              )}
              {rows.map((row) => (
                <button
                  key={row.id}
                  onClick={() => {
                    setSelected(row.id);
                    setFollow(false);
                  }}
                  className={`block w-full border-b border-[var(--border-default)] p-4 text-left ${row.id === selected ? 'bg-[var(--surface-chip)]' : ''}`}
                >
                  <span title={row.title} className="block truncate text-14 font-medium">
                    {row.unread && <span aria-label={text('unread')}>● </span>}
                    {row.title}
                  </span>
                  <span className="mt-2 block text-12 text-[var(--text-secondary)]">
                    {t(`vibelinkWork.status.${row.status}`, { defaultValue: row.status })}
                  </span>
                </button>
              ))}
            </div>
            <article className="max-h-[54vh] overflow-auto rounded-xl border border-[var(--border-default)] p-5">
              {!current ? (
                <p className="text-13 text-[var(--text-secondary)]">{text('selectWork')}</p>
              ) : (
                <>
                  <h2 className="text-16 font-semibold">{current.title}</h2>
                  <p className="mt-2 text-13 text-[var(--text-secondary)]">
                    {t(`vibelinkWork.status.${current.status}`, { defaultValue: current.status })}
                  </p>
                  {(current.status === 'cancelled_unknown' ||
                    current.steps.some((s) => s.status === 'cancelled_unknown')) && (
                    <p className="mt-3 text-13">{text('unknownCancellation')}</p>
                  )}
                  {!current.canManage && (
                    <p className="mt-3 text-12 text-[var(--text-secondary)]">{text('readonly')}</p>
                  )}
                  <div className="mt-4 flex flex-wrap gap-2">
                    {workActions(current).map((command) => (
                      <Button
                        variant="secondary"
                        disabled={busy}
                        key={command}
                        onClick={() => void manage(command)}
                      >
                        {text(command)}
                      </Button>
                    ))}
                    <Button variant="secondary" onClick={() => void exportWork()}>
                      {text('export')}
                    </Button>
                    {current.canManage && !current.archived && (
                      <Button variant="secondary" onClick={() => setFollow(true)}>
                        {text('follow')}
                      </Button>
                    )}
                  </div>
                  <p className="mt-5 whitespace-pre-wrap break-words text-14">
                    {current.result || current.text}
                  </p>
                  {current.error && (
                    <p className="mt-3 whitespace-pre-wrap text-13">{current.error}</p>
                  )}
                  {current.budget && (
                    <p className="mt-5 text-12 text-[var(--text-secondary)]">
                      {text('budget')}: {text('requests')} {current.budget.requests || '—'} · Token{' '}
                      {current.budget.tokens || '—'}
                    </p>
                  )}
                  {current.mandate && (
                    <details className="mt-4 text-13">
                      <summary>
                        {text('mandate')} ·{' '}
                        {text(current.mandate.revokedAt ? 'revoked' : 'authorized')}
                      </summary>
                      <p className="mt-3 whitespace-pre-wrap">{current.mandate.acceptance}</p>
                      {current.mandate.members.map((member) => (
                        <p
                          className="mt-2 break-words text-12 text-[var(--text-secondary)]"
                          key={member.botId}
                        >
                          {member.botId} · {member.agent} · {member.model || text('cliDefaultModel')} ·{' '}
                          {text(member.allowWrite ? 'canWrite' : 'readOnly')}
                          <br />
                          {member.device} · {member.configuredWorkspace}
                        </p>
                      ))}
                    </details>
                  )}
                  <h3 className="mb-2 mt-6 text-13 font-semibold">{text('steps')}</h3>
                  {current.steps.map((step) => (
                    <details key={step.id} className="border-t border-[var(--border-default)] py-3">
                      <summary className="cursor-pointer text-13">
                        {step.botId} · {step.kind} ·{' '}
                        {t(`vibelinkWork.status.${step.status}`, { defaultValue: step.status })}
                      </summary>
                      <p className="mt-3 whitespace-pre-wrap break-words text-13">
                        {step.output || step.error}
                      </p>
                      {step.sessionId && (
                        <p className="mt-2 break-all text-11 text-[var(--text-tertiary)]">
                          {text('session')}: {step.sessionId}
                        </p>
                      )}
                    </details>
                  ))}
                  {current.billing !== undefined && (
                    <details className="mt-4">
                      <summary className="text-13">{text('usage')}</summary>
                      <pre className="mt-2 whitespace-pre-wrap break-words text-11">
                        {formatBilling(current.billing, text)}
                      </pre>
                    </details>
                  )}
                </>
              )}
            </article>
          </div>
          {!!messages.length && (
            <details className="rounded-xl border border-[var(--border-default)] p-4">
              <summary className="cursor-pointer text-13 font-medium">{text('messages')}</summary>
              <div className="mt-3 max-h-64 space-y-4 overflow-auto">
                {messages.slice(-30).map((message) => (
                  <div key={message.id}>
                    <p className="text-12 text-[var(--text-secondary)]">
                      {message.senderName} · {new Date(message.at).toLocaleString()}
                    </p>
                    <p className="mt-1 whitespace-pre-wrap break-words text-13">{message.text}</p>
                  </div>
                ))}
              </div>
            </details>
          )}
          <div className="space-y-2">
            <label htmlFor="hub-work-input" className="text-13 font-medium">
              {follow ? text('follow') : text('newWork')}
              {follow && (
                <button className="ml-3 underline" onClick={() => setFollow(false)}>
                  {text('newWork')}
                </button>
              )}
            </label>
            <div className="flex items-end gap-3">
              <textarea
                id="hub-work-input"
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                maxLength={24000}
                rows={3}
                className={`${control} flex-1 resize-y`}
              />
              <Button disabled={busy || !draft.trim() || !gid} onClick={() => void send()}>
                {text('send')}
              </Button>
            </div>
          </div>
        </>
      )}
      {!!outbox.length && (
        <details>
          <summary className="text-13">
            {text('localRecords')} ({outbox.length})
          </summary>
          {outbox.map((item) => (
            <p key={item.requestId} className="mt-2 text-12 text-[var(--text-secondary)]">
              {item.text.slice(0, 100)} · {item.error || text('pending')}
              {item.status === 'failed' && (
                <Button
                  variant="secondary"
                  className="ml-3"
                  onClick={() => {
                    void call({ action: 'dismissFailed', requestId: item.requestId }).then(
                      (data) => {
                        if (data?.outbox) setOutbox(data.outbox);
                      },
                    );
                  }}
                >
                  {text('dismissFailed')}
                </Button>
              )}
            </p>
          ))}
        </details>
      )}
    </section>
  );
}

function formatBilling(value: unknown, label: (key: string) => string): string {
  if (!value || typeof value !== 'object') return '—';
  const data = value as Record<string, unknown>;
  return (
    ['requests', 'totalTokens', 'estimatedTokens', 'usageMissingRequests']
      .map(
        (key) =>
          `${label(key)}: ${typeof data[key] === 'number' ? data[key].toLocaleString() : '—'}`,
      )
      .join(' · ') + `\n${label('subscriptionUnreported')}`
  );
}

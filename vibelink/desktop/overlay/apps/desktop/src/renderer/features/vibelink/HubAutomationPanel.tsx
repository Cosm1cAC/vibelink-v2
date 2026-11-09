import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import type { HubSession } from '../../../shared/vibelinkAuth';
import { AutomationInputSchema } from '../../../shared/vibelinkAutomation';
import type {
  AutomationInput,
  AutomationResult,
  AutomationView,
} from '../../../shared/vibelinkAutomation';
import type { HubWorkInput, HubWorkResult } from '../../../shared/vibelinkWork';
export interface AutomationBridge {
  automation(input: AutomationInput): Promise<AutomationResult>;
  work(input: HubWorkInput): Promise<HubWorkResult>;
}
type Status = Extract<AutomationInput, { action: 'saveRule' }>['statuses'][number];
const statuses = ['completed', 'failed', 'needs_input'] as const;
type Draft = {
  groupId: string;
  ruleId: string;
  label: string;
  statuses: Status[];
  revision: number;
};
function newRuleId(): string {
  // getRandomValues remains available in source previews outside secure contexts.
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return `rule_${Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')}`;
}
function atUtc8(at: number): string {
  return at && Number.isFinite(new Date(at).getTime())
    ? new Intl.DateTimeFormat('zh-CN', {
        timeZone: 'Asia/Shanghai',
        dateStyle: 'short',
        timeStyle: 'medium',
      }).format(at)
    : '—';
}
function sessionScope(session: HubSession | null): string {
  return session && session.expiresAt > Date.now()
    ? `${session.issuer}/${session.edition}/${session.userId}/${session.sessionId}`
    : '';
}
export function HubAutomationPanel({
  session,
  bridge,
}: {
  session: HubSession | null;
  bridge?: AutomationBridge;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false),
    [expired, setExpired] = useState(false);
  useEffect(() => {
    setExpired(false);
    if (!session) return;
    const timer = setTimeout(
      () => setExpired(true),
      Math.min(2147483647, Math.max(0, session.expiresAt - Date.now())),
    );
    return () => clearTimeout(timer);
  }, [session?.sessionId, session?.expiresAt]);
  const scope = expired ? '' : sessionScope(session);
  return (
    <section className="rounded-2xl border border-[var(--border-default)] p-5">
      <button
        type="button"
        className="flex w-full items-center justify-between text-left text-14 font-medium"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        {t('vibelinkAutomation.title')}
        <span aria-hidden="true">{open ? '−' : '+'}</span>
      </button>
      {open &&
        (scope && session ? (
          <AutomationContents key={scope} session={session} bridge={bridge} />
        ) : (
          <p role="status" className="mt-4 text-13 text-[var(--text-secondary)]">
            {t('vibelinkAutomation.denied')}
          </p>
        ))}
    </section>
  );
}
function AutomationContents({
  session,
  bridge,
}: {
  session: HubSession;
  bridge?: AutomationBridge;
}) {
  const { t } = useTranslation();
  const text = (key: string) => t(`vibelinkAutomation.${key}`);
  const [groups, setGroups] = useState<{ id: string; name: string }[]>([]),
    [gid, setGid] = useState('');
  const [snapshot, setSnapshot] = useState<AutomationView | null>(null),
    [mode, setMode] = useState('');
  const [groupsLoaded, setGroupsLoaded] = useState(false);
  const [draft, setDraft] = useState<Draft | null>(null);
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  const [error, setError] = useState(''),
    [busy, setBusy] = useState(false),
    [locked, setLocked] = useState(false),
    [notice, setNotice] = useState('');
  const version = useRef(0),
    groupVersion = useRef(0),
    currentGroup = useRef(gid);
  currentGroup.current = gid;
  const loadGroups = useCallback(async () => {
    const current = ++groupVersion.current;
    setGroupsLoaded(false);
    setError('');
    setBusy(true);
    if (!bridge?.automation) {
      setError('unavailable');
      setBusy(false);
      return;
    }
    try {
      const result = await bridge.work({ action: 'groups' });
      if (current !== groupVersion.current || session.expiresAt <= Date.now()) return;
      if (!result.success) {
        setError('unavailable');
        return;
      }
      const rows = result.data.groups ?? [];
      setGroupsLoaded(true);
      setGroups(rows);
      setGid(rows[0]?.id ?? '');
    } catch {
      if (current === groupVersion.current) setError('unavailable');
    } finally {
      if (current === groupVersion.current) setBusy(false);
    }
  }, [bridge, session.expiresAt]);
  useEffect(() => {
    void loadGroups();
    return () => {
      groupVersion.current++;
    };
  }, [loadGroups]);
  const read = useCallback(async () => {
    if (!gid || !bridge?.automation) return;
    const current = ++version.current;
    setSnapshot(null);
    setDraft(null);
    setMode('');
    setError('');
    setNotice('');
    setBusy(true);
    const valid = () =>
      version.current === current && currentGroup.current === gid && session.expiresAt > Date.now();
    try {
      const result = await bridge.automation({ action: 'snapshot', groupId: gid });
      if (!valid()) return;
      if (!result.success) {
        setError('unavailable');
        return;
      }
      if (result.data.groupId !== gid) {
        setError('unavailable');
        return;
      }
      setSnapshot(result.data);
      setMode(result.data.policy.mode);
      setLocked(false);
    } catch {
      if (valid()) setError('unavailable');
    } finally {
      if (valid()) setBusy(false);
    }
  }, [gid, bridge, session.expiresAt]);
  useEffect(() => {
    version.current++;
    setSnapshot(null);
    setDraft(null);
    setMode('');
    setBusy(false);
    setLocked(false);
    setNotice('');
    if (gid) void read();
    return () => {
      version.current++;
    };
  }, [gid, read]);
  const data = snapshot?.groupId === gid ? snapshot : null;
  const latestRuleVersions = new Map<string, number>();
  for (const rule of data?.rules ?? [])
    latestRuleVersions.set(rule.id, Math.max(latestRuleVersions.get(rule.id) ?? 0, rule.version));
  const change = async (input: AutomationInput) => {
    if (
      !active.current ||
      !bridge ||
      !data ||
      busy ||
      locked ||
      currentGroup.current !== gid ||
      session.expiresAt <= Date.now()
    )
      return;
    const current = ++version.current;
    setBusy(true);
    setError('');
    setNotice('');
    const valid = () =>
      current === version.current && currentGroup.current === gid && session.expiresAt > Date.now();
    try {
      const result = await bridge.automation(input);
      if (!valid()) return;
      if (!result.success) {
        setDraft(null);
        if (['AUTH_REQUIRED', 'FORBIDDEN', 'ACCOUNT_CHANGED'].includes(result.error.code)) {
          setSnapshot(null);
          setLocked(true);
          setError('denied');
        } else if (result.error.code === 'CONFLICT' || result.error.code === 'OUTCOME_UNKNOWN') {
          setLocked(true);
          setError(result.error.code === 'CONFLICT' ? 'conflict' : 'unknown');
        } else setError('unavailable');
        return;
      }
      if (result.data.groupId !== gid) {
        setLocked(true);
        setError('unknown');
        return;
      }
      setSnapshot(result.data);
      setMode(result.data.policy.mode);
      setDraft(null);
      setNotice('saved');
    } catch {
      if (valid()) {
        setLocked(true);
        setDraft(null);
        setError('unknown');
      }
    } finally {
      if (valid()) setBusy(false);
    }
  };

  const saveRule = () => {
    if (!draft || draft.groupId !== gid) return;
    const parsed = AutomationInputSchema.safeParse({
      action: 'saveRule',
      groupId: gid,
      ruleId: draft.ruleId,
      label: draft.label,
      statuses: draft.statuses,
      expectedRevision: draft.revision,
    });
    if (!parsed.success) {
      setError('invalidRule');
      return;
    }
    void change(parsed.data);
  };
  const newRule = () => {
    if (!data || busy || locked) return;
    setError('');
    setNotice('');
    setDraft({
      groupId: gid,
      ruleId: newRuleId(),
      label: '',
      statuses: [],
      revision: data.policy.revision,
    });
  };
  return (
    <div className="mt-4 flex flex-col gap-4 text-13">
      <p className="text-[var(--text-secondary)]">{text('description')}</p>
      <div className="flex flex-wrap items-center gap-3">
        <label>
          {text('group')}
          <select
            aria-label={text('group')}
            value={gid}
            onChange={(e) => setGid(e.target.value)}
            className="ml-2 rounded-lg border border-[var(--border-default)] bg-[var(--surface-elevated)] px-3 py-2"
          >
            <option value="" disabled>
              {text('selectGroup')}
            </option>
            {groups.map((group) => (
              <option key={group.id} value={group.id}>
                {group.name}
              </option>
            ))}
          </select>
        </label>
        <Button
          variant="secondary"
          disabled={busy}
          onClick={() => void (gid ? read() : loadGroups())}
        >
          {text('refresh')}
        </Button>
      </div>
      {groupsLoaded && !groups.length && !error && (
        <p className="text-12 text-[var(--text-secondary)]">{text('emptyGroups')}</p>
      )}
      {error && (
        <p role="alert" className="text-[var(--text-danger)]">
          {text(error)}
        </p>
      )}
      {notice && <p role="status">{text(notice)}</p>}
      <div className="flex flex-wrap items-center gap-3">
        <label>
          {text('mode')}
          <select
            aria-label={text('mode')}
            value={data ? mode : ''}
            disabled={!data || busy || locked}
            onChange={(e) => setMode(e.target.value)}
            className="ml-2 rounded-lg border border-[var(--border-default)] bg-[var(--surface-elevated)] px-3 py-2"
          >
            <option value="" disabled>
              {text('unloaded')}
            </option>
            <option value="observe">{text('observe')}</option>
            <option value="off">{text('off')}</option>
          </select>
        </label>
        <Button
          disabled={!data || busy || locked || mode === data.policy.mode}
          onClick={() =>
            data &&
            void change({
              action: 'setMode',
              groupId: gid,
              mode: mode as 'observe' | 'off',
              expectedRevision: data.policy.revision,
            })
          }
        >
          {text('saveMode')}
        </Button>
        {data && (
          <span className="text-12 text-[var(--text-secondary)]">
            {text('policyVersion')} v{data.policy.revision}
          </span>
        )}
      </div>
      <p className="text-12 text-[var(--text-secondary)]">{text('snapshotNote')}</p>
      {data && (
        <div className="flex flex-wrap gap-4 text-12 text-[var(--text-secondary)]">
          <span>
            {text('baseline')}: {atUtc8(data.observer.baselineAt)}
          </span>
          <span>
            {text('lastScan')}: {atUtc8(data.observer.lastScanAt)}
          </span>
        </div>
      )}
      {data && data.observer.error !== '' && (
        <p
          role="alert"
          className="rounded-xl border border-[var(--border-default)] p-3 text-13 text-[var(--text-danger)]"
        >
          {text(data.observer.error === 'capacity' ? 'capacityPaused' : 'persistencePaused')}
        </p>
      )}

      <div className="flex items-center justify-between gap-3">
        <h3 className="text-14 font-medium">{text('rules')}</h3>
        <Button variant="secondary" disabled={!data || busy || locked} onClick={newRule}>
          {text('newRule')}
        </Button>
      </div>
      {!data && (
        <p role="status" className="text-12 text-[var(--text-secondary)]">
          {text(busy ? 'loading' : 'unloaded')}
        </p>
      )}
      {data && !data.rules.length && (
        <p className="text-12 text-[var(--text-secondary)]">{text('emptyRules')}</p>
      )}
      {data?.rules.map((rule) => (
        <article
          key={`${rule.id}/${rule.version}`}
          className="flex flex-wrap items-start justify-between gap-3 rounded-xl border border-[var(--border-default)] p-4"
        >
          <div className="min-w-0">
            <h4 className="break-words font-medium">
              <span>{rule.label}</span>{' '}
              <span className="text-12 text-[var(--text-secondary)]">· v{rule.version}</span>
            </h4>
            <div className="mt-1 break-all text-12 text-[var(--text-secondary)]">{rule.id}</div>
            <div className="mt-2 text-12">
              {rule.statuses.map((status) => text(status)).join(' · ')} ·{' '}
              {text(
                rule.version !== latestRuleVersions.get(rule.id)
                  ? 'superseded'
                  : rule.revokedAt
                    ? 'revoked'
                    : 'active',
              )}
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            {!rule.revokedAt && rule.version === latestRuleVersions.get(rule.id) && (
              <>
                <Button
                  variant="secondary"
                  size="xs"
                  disabled={busy || locked}
                  aria-label={`${text('editRule')} ${rule.id}`}
                  onClick={() => {
                    setError('');
                    setDraft({
                      groupId: gid,
                      ruleId: rule.id,
                      label: rule.label,
                      statuses: [...rule.statuses],
                      revision: data.policy.revision,
                    });
                  }}
                >
                  {text('editRule')}
                </Button>
                <Button
                  variant="secondary"
                  size="xs"
                  disabled={busy || locked}
                  aria-label={`${text('revoke')} ${rule.id}`}
                  onClick={() =>
                    void change({
                      action: 'revokeRule',
                      groupId: gid,
                      ruleId: rule.id,
                      expectedRevision: data.policy.revision,
                    })
                  }
                >
                  {text('revoke')}
                </Button>
              </>
            )}
          </div>
        </article>
      ))}
      {data && draft?.groupId === gid && (
        <form
          className="rounded-xl border border-[var(--border-default)] p-4"
          onSubmit={(event) => {
            event.preventDefault();
            saveRule();
          }}
        >
          <h3 className="font-medium">{text('ruleForm')}</h3>
          <p className="mb-4 mt-2 text-12 text-[var(--text-secondary)]">{text('newVersionNote')}</p>
          <label className="flex flex-col gap-2">
            {text('ruleLabel')}
            <input
              required
              maxLength={120}
              disabled={busy || locked}
              value={draft.label}
              onChange={(event) => setDraft({ ...draft, label: event.target.value })}
              className="rounded-lg border border-[var(--border-default)] bg-[var(--surface-elevated)] px-3 py-2"
            />
          </label>
          <fieldset className="my-4" disabled={busy || locked}>
            <legend className="mb-2">{text('statuses')}</legend>
            <div className="flex flex-wrap gap-4">
              {statuses.map((status) => (
                <label key={status} className="flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={draft.statuses.includes(status)}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        statuses: event.target.checked
                          ? [...draft.statuses, status]
                          : draft.statuses.filter((value) => value !== status),
                      })
                    }
                  />
                  {text(status)}
                </label>
              ))}
            </div>
          </fieldset>
          <div className="flex flex-wrap items-center gap-3">
            <Button
              type="submit"
              disabled={busy || locked || !draft.label.trim() || !draft.statuses.length}
            >
              {text('saveRule')}
            </Button>
            <Button variant="secondary" disabled={busy} onClick={() => setDraft(null)}>
              {text('cancel')}
            </Button>
          </div>
        </form>
      )}
      <h3 className="mt-2 text-14 font-medium">{text('decisions')}</h3>
      <p className="text-12 text-[var(--text-secondary)]">{text('decisionsNote')}</p>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] text-12">
          <thead className="text-[var(--text-secondary)]">
            <tr>
              {['at', 'work', 'ruleVersion', 'status', 'outcome', 'reason'].map((key) => (
                <th key={key} className="px-3 py-2 text-left">
                  {text(key)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {data?.decisions
              .slice()
              .sort((a, b) => b.at - a.at || b.id.localeCompare(a.id))
              .slice(0, 200)
              .map((decision) => (
                <tr key={decision.id} className="border-t border-[var(--border-default)]">
                  <td className="px-3 py-3">{atUtc8(decision.at)}</td>
                  <td className="max-w-[220px] break-all px-3 py-3">
                    {decision.workId} · r{decision.workRevision}
                  </td>
                  <td className="max-w-[220px] break-all px-3 py-3">
                    {decision.ruleId} · v{decision.ruleVersion}
                  </td>
                  <td className="px-3 py-3">
                    {statuses.includes(decision.status as Status)
                      ? text(decision.status)
                      : decision.status}
                  </td>
                  <td className="px-3 py-3">{text(decision.outcome)}</td>
                  <td className="px-3 py-3">{text(`reason_${decision.reason}`)}</td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>
      {data && !data.decisions.length && (
        <p className="text-12 text-[var(--text-secondary)]">{text('emptyDecisions')}</p>
      )}
    </div>
  );
}

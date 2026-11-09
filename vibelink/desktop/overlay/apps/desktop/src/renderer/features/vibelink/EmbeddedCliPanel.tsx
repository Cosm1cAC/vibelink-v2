import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import type { TerminalBridge } from '../../../shared/terminal-bridge';
import {
  getOrCreateXterm,
  disposeXterm,
  type XtermEntry,
} from '../right-sidebar/plugins/terminal/lib/xtermPool';

const agents = ['codex', 'opencode2', 'claude', 'gemini'] as const;
type Agent = (typeof agents)[number];
type Tab = { id: string; agent: Agent; mode: 'chat' | 'login'; number: number };
const names: Record<Agent, string> = {
  codex: 'Codex',
  opencode2: 'OpenCode2 · DeepSeek',
  claude: 'Claude Code',
  gemini: 'Gemini CLI',
};
const failureText = (error: unknown) =>
  error instanceof Error ? error.message.slice(0, 1000) : '';

/** Independent native CLI conversations, never a claim to attach to a Hub Bot SID. */
export function EmbeddedCliPanel({
  bridge,
  expiresAt,
}: {
  bridge?: TerminalBridge;
  expiresAt?: number;
}) {
  const { t } = useTranslation();
  const text = (key: string) => t(`vibelinkCli.${key}`);
  const [agent, setAgent] = useState<Agent>('codex');
  const [tabs, setTabs] = useState<Tab[]>([]),
    [selected, setSelected] = useState('');
  const [expired, setExpired] = useState(false);
  const sequence = useRef(0);
  useEffect(() => {
    setExpired(false);
    if (expiresAt === undefined) return;
    const timer = setTimeout(
      () => setExpired(true),
      Math.max(0, Math.min(2147483647, expiresAt - Date.now())),
    );
    return () => clearTimeout(timer);
  }, [expiresAt]);
  const add = (mode: Tab['mode']) => {
    if (!bridge || expired || tabs.length >= 8 || (mode === 'login' && agent === 'opencode2'))
      return;
    const tab = {
      id: `vibelink-cli-${crypto.randomUUID()}`,
      agent,
      mode,
      number: ++sequence.current,
    };
    setTabs((previous) => [...previous, tab]);
    setSelected(tab.id);
  };
  const close = (id: string) => {
    setTabs((previous) => previous.filter((tab) => tab.id !== id));
    if (selected === id) setSelected(tabs.find((tab) => tab.id !== id)?.id ?? '');
  };
  return (
    <section className="rounded-2xl border border-[var(--border-default)] p-5">
      <h2 className="text-16 font-semibold">{text('title')}</h2>
      <p className="mt-2 text-13 text-[var(--text-secondary)]">{text('independent')}</p>
      <div className="my-4 flex flex-wrap items-center gap-3">
        <label className="text-13">
          {text('agent')}{' '}
          <select
            aria-label={text('agent')}
            value={agent}
            onChange={(event) => setAgent(event.target.value as Agent)}
            className="rounded-lg border border-[var(--border-default)] bg-[var(--surface-elevated)] px-3 py-2"
          >
            {agents.map((value) => (
              <option key={value} value={value}>
                {names[value]}
              </option>
            ))}
          </select>
        </label>
        <Button
          variant="secondary"
          disabled={!bridge || expired || tabs.length >= 8}
          onClick={() => add('chat')}
        >
          {text('new')}
        </Button>
        {agent !== 'opencode2' && (
          <Button
            variant="secondary"
            disabled={!bridge || expired || tabs.length >= 8}
            onClick={() => add('login')}
          >
            {text('login')}
          </Button>
        )}
        {agent === 'opencode2' && (
          <span className="text-12 text-[var(--text-secondary)]">{text('noLogin')}</span>
        )}
      </div>
      {(!bridge || expired) && (
        <p role="alert" className="text-13 text-[var(--text-danger)]">
          {text('unavailable')}
        </p>
      )}
      {!expired && bridge && (
        <>
          <div role="tablist" aria-label={text('title')} className="mb-3 flex flex-wrap gap-2">
            {tabs.map((tab) => (
              <div
                key={tab.id}
                className="flex gap-1 rounded-lg border border-[var(--border-default)] p-1"
              >
                <button
                  type="button"
                  role="tab"
                  aria-selected={selected === tab.id}
                  aria-controls={tab.id}
                  onClick={() => setSelected(tab.id)}
                  className="px-2 text-12"
                >
                  {names[tab.agent]} · {text(tab.mode)} {tab.number}
                </button>
                <button
                  type="button"
                  aria-label={`${text('close')} ${tab.number}`}
                  onClick={() => close(tab.id)}
                  className="px-2"
                >
                  ×
                </button>
              </div>
            ))}
          </div>
          {tabs.map((tab) => (
            <CliTerminal key={tab.id} tab={tab} bridge={bridge} active={selected === tab.id} />
          ))}
        </>
      )}
    </section>
  );
}

function CliTerminal({
  tab,
  bridge,
  active,
}: {
  tab: Tab;
  bridge: TerminalBridge;
  active: boolean;
}) {
  const { t } = useTranslation();
  const slot = useRef<HTMLDivElement | null>(null),
    entry = useRef<XtermEntry | null>(null);
  const activeRef = useRef(active);
  activeRef.current = active;
  const [phase, setPhase] = useState<'starting' | 'running' | 'exited' | 'error'>('starting');
  const [exitCode, setExitCode] = useState<number | null>(null);
  const [error, setError] = useState('');
  const generation = useRef(0),
    runtimeId = useRef(''),
    exitVersion = useRef(0);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let disposed = false;
    const current = ++generation.current;
    const id = `vibelink-cli-${crypto.randomUUID()}`;
    runtimeId.current = id;
    const beforeExit = exitVersion.current;
    const terminal = getOrCreateXterm(tab.id);
    entry.current = terminal;
    if (slot.current) {
      if (terminal.terminal.element) slot.current.appendChild(terminal.terminal.element);
      else terminal.terminal.open(slot.current);
    }
    const keyboard = terminal.terminal.onData((data) => {
      if (!disposed && terminal.ptyAttached)
        void bridge.write(id, data).catch((error) => {
          if (!disposed) {
            setError(failureText(error));
            setPhase('error');
          }
        });
    });
    const offData = bridge.onData((event) => {
      if (!disposed && event.id === id) terminal.terminal.write(event.chunk);
    });
    const offExit = bridge.onExit((event) => {
      if (!disposed && event.id === id) {
        exitVersion.current++;
        terminal.ptyAttached = false;
        setExitCode(event.exit.code);
        setPhase('exited');
      }
    });
    setPhase('starting');
    setError('');
    void bridge
      .create({
        id,
        cwd: '',
        cols: terminal.lastSize.cols,
        rows: terminal.lastSize.rows,
        cli: { agent: tab.agent, mode: tab.mode },
      })
      .then(() => {
        if (disposed || current !== generation.current) {
          void bridge.dispose(id).catch(() => {});
          return;
        }
        // An immediate CLI exit may have arrived before create's IPC receipt.
        if (exitVersion.current === beforeExit) {
          terminal.ptyAttached = true;
          setPhase('running');
        }
      })
      .catch((error) => {
        if (!disposed) {
          setError(failureText(error));
          setPhase('error');
        }
      });
    return () => {
      disposed = true;
      generation.current++;
      terminal.ptyAttached = false;
      keyboard.dispose();
      offData();
      offExit();
      entry.current = null;
      void bridge.dispose(id).catch(() => {});
      disposeXterm(tab.id);
    };
  }, [tab, bridge, attempt]);
  useEffect(() => {
    const resize = () => {
      const element = slot.current,
        terminal = entry.current;
      if (
        !activeRef.current ||
        !element ||
        element.clientWidth <= 0 ||
        element.clientHeight <= 0 ||
        !terminal?.ptyAttached
      )
        return;
      try {
        terminal.fitAddon.fit();
        const cols = terminal.terminal.cols,
          rows = terminal.terminal.rows;
        if (!Number.isInteger(cols) || !Number.isInteger(rows) || cols < 1 || rows < 1) return;
        terminal.lastSize = { cols, rows };
        void bridge.resize(runtimeId.current, cols, rows).catch(() => {});
      } catch {
        /* A hidden pane can temporarily have no cell measurements. */
      }
    };
    const observer = new ResizeObserver(resize);
    if (slot.current) observer.observe(slot.current);
    if (active) {
      resize();
      if (phase === 'running') entry.current?.terminal.focus();
    }
    return () => observer.disconnect();
  }, [active, phase, bridge, tab.id]);
  const reopen = async () => {
    const current = generation.current,
      id = runtimeId.current,
      beforeExit = exitVersion.current;
    setPhase('starting');
    setError('');
    try {
      await bridge.restart(id);
      if (generation.current !== current) {
        void bridge.dispose(id).catch(() => {});
        return;
      }
      if (exitVersion.current === beforeExit) {
        if (entry.current) entry.current.ptyAttached = true;
        setPhase('running');
      }
    } catch (error) {
      if (generation.current === current) {
        setError(failureText(error));
        setPhase('error');
      }
    }
  };
  return (
    <div role="tabpanel" id={tab.id} hidden={!active}>
      <div className="mb-2 flex items-center gap-3">
        <Button
          variant="secondary"
          disabled={phase !== 'running'}
          onClick={() => {
            const current = generation.current;
            void bridge.write(runtimeId.current, '\u0003').catch((error) => {
              if (generation.current === current) {
                setError(failureText(error));
                setPhase('error');
              }
            });
          }}
        >
          Ctrl+C
        </Button>
        {phase === 'exited' && (
          <>
            <span role="status" className="text-12">
              {t('vibelinkCli.exited')} {exitCode ?? '—'}
            </span>
            <Button variant="secondary" onClick={() => void reopen()}>
              {t('vibelinkCli.reopen')}
            </Button>
          </>
        )}
        {phase === 'starting' && (
          <span role="status" className="text-12">
            {t('vibelinkCli.starting')}
          </span>
        )}
      </div>
      {phase === 'error' && (
        <div role="alert" className="mb-3 text-13 text-[var(--text-danger)]">
          {t('vibelinkCli.failed')}
          <pre className="my-2 whitespace-pre-wrap break-words">{error}</pre>
          <Button variant="secondary" onClick={() => setAttempt((value) => value + 1)}>
            {t('vibelinkCli.retry')}
          </Button>
        </div>
      )}
      <div
        ref={slot}
        className="h-[440px] min-h-[260px] w-full overflow-hidden rounded-xl bg-[#1c1c1c] p-2"
      />
    </div>
  );
}

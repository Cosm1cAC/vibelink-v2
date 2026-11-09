// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { TerminalBridge } from '../../../shared/terminal-bridge';
import { EmbeddedCliPanel } from './EmbeddedCliPanel';
const mock = vi.hoisted(() => ({
  input: null as null | ((data: string) => void),
  write: vi.fn(),
  focus: vi.fn(),
  fit: vi.fn(),
  dispose: vi.fn(),
}));
const observers: ResizeObserverCallback[] = [];
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock('../right-sidebar/plugins/terminal/lib/xtermPool', () => ({
  getOrCreateXterm: () => ({
    terminal: {
      cols: 90,
      rows: 25,
      element: undefined,
      open: vi.fn(),
      onData: (callback: (data: string) => void) => {
        mock.input = callback;
        return { dispose: vi.fn() };
      },
      write: mock.write,
      focus: mock.focus,
    },
    fitAddon: { fit: mock.fit },
    lastSize: { cols: 80, rows: 24 },
    ptyAttached: false,
  }),
  disposeXterm: mock.dispose,
}));
function fixture() {
  return {
    create: vi.fn(async () => ({ shellId: 'cmd', shellDisplayName: 'CLI', pid: 1 })),
    write: vi.fn(async () => {}),
    resize: vi.fn(async () => {}),
    dispose: vi.fn(async () => {}),
    restart: vi.fn(async () => ({ shellId: 'cmd', shellDisplayName: 'CLI', pid: 2 })),
    onData: vi.fn(() => vi.fn()),
    onExit: vi.fn(() => vi.fn()),
    listAvailableShells: vi.fn(),
    getDefaultShellPref: vi.fn(),
    setDefaultShellPref: vi.fn(),
  } as unknown as TerminalBridge;
}
beforeEach(() => {
  vi.clearAllMocks();
  observers.length = 0;
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(callback: ResizeObserverCallback) {
        observers.push(callback);
      }
      observe() {}
      disconnect() {}
    },
  );
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(800);
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(480);
});
it('resizes only the visible terminal and keeps an older CLI alive when opening another tab', async () => {
  const bridge = fixture();
  render(<EmbeddedCliPanel bridge={bridge} />);
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkCli.new' }));
  await waitFor(() => expect(bridge.resize).toHaveBeenCalled());
  const first = vi.mocked(bridge.create).mock.calls[0][0].id;
  fireEvent.change(screen.getByLabelText('vibelinkCli.agent'), { target: { value: 'claude' } });
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkCli.new' }));
  await waitFor(() => expect(bridge.create).toHaveBeenCalledTimes(2));
  const second = vi.mocked(bridge.create).mock.calls[1][0].id;
  vi.mocked(bridge.resize).mockClear();
  act(() => observers.forEach((callback) => callback([], {} as ResizeObserver)));
  expect(bridge.resize).not.toHaveBeenCalledWith(first, expect.anything(), expect.anything());
  expect(bridge.resize).toHaveBeenCalledWith(second, 90, 25);
  expect(bridge.dispose).not.toHaveBeenCalled();
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
it('launches a selected CLI only on explicit action and forwards keyboard input', async () => {
  const bridge = fixture();
  render(<EmbeddedCliPanel bridge={bridge} />);
  expect(bridge.create).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkCli.new' }));
  await waitFor(() =>
    expect(bridge.create).toHaveBeenCalledWith(
      expect.objectContaining({ cwd: '', cli: { agent: 'codex', mode: 'chat' } }),
    ),
  );
  const id = vi.mocked(bridge.create).mock.calls[0][0].id;
  act(() => mock.input?.('hello\r'));
  expect(bridge.write).toHaveBeenCalledWith(id, 'hello\r');
});
it('keeps a newer mount alive when an old create receipt arrives after cleanup', async () => {
  const bridge = fixture();
  let release!: (value: { shellId: 'cmd'; shellDisplayName: string; pid: number }) => void;
  vi.mocked(bridge.create).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  render(
    <React.StrictMode>
      <EmbeddedCliPanel bridge={bridge} />
    </React.StrictMode>,
  );
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkCli.new' }));
  await waitFor(() => expect(bridge.create).toHaveBeenCalledTimes(2));
  const first = vi.mocked(bridge.create).mock.calls[0][0].id;
  const current = vi.mocked(bridge.create).mock.calls[1][0].id;
  expect(current).not.toBe(first);
  await act(async () => release({ shellId: 'cmd', shellDisplayName: 'CLI', pid: 1 }));
  expect(bridge.dispose).toHaveBeenCalledWith(first);
  expect(bridge.dispose).not.toHaveBeenCalledWith(current);
});
it('shows a rejected CLI start message and offers an explicit retry', async () => {
  const bridge = fixture();
  vi.mocked(bridge.create).mockRejectedValueOnce(
    new Error('CLI_NOT_INSTALLED: install Claude Code'),
  );
  render(<EmbeddedCliPanel bridge={bridge} />);
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkCli.new' }));
  await screen.findByText('CLI_NOT_INSTALLED: install Claude Code');
  expect(bridge.create).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkCli.retry' }));
  await waitFor(() => expect(bridge.create).toHaveBeenCalledTimes(2));
});
it('renders PTY output, interrupts, reopens an exited CLI, and disposes a closed tab', async () => {
  const bridge = fixture();
  render(<EmbeddedCliPanel bridge={bridge} />);
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkCli.new' }));
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Ctrl+C' }) as HTMLButtonElement).disabled).toBe(
      false,
    ),
  );
  const id = vi.mocked(bridge.create).mock.calls[0][0].id;
  act(() => vi.mocked(bridge.onData).mock.calls[0][0]({ id, chunk: 'CLI output' }));
  expect(mock.write).toHaveBeenCalledWith('CLI output');
  fireEvent.click(screen.getByRole('button', { name: 'Ctrl+C' }));
  expect(bridge.write).toHaveBeenCalledWith(id, '\u0003');
  act(() => vi.mocked(bridge.onExit).mock.calls[0][0]({ id, exit: { code: 0, signal: null } }));
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkCli.reopen' }));
  await waitFor(() => expect(bridge.restart).toHaveBeenCalledWith(id));
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkCli.close 1' }));
  expect(bridge.dispose).toHaveBeenCalledWith(id);
  expect(mock.dispose).toHaveBeenCalled();
  expect(screen.queryByRole('tabpanel')).toBeNull();
});
it('offers official CLI login mode but never a DeepSeek login action', async () => {
  const bridge = fixture();
  render(<EmbeddedCliPanel bridge={bridge} />);
  fireEvent.change(screen.getByLabelText('vibelinkCli.agent'), { target: { value: 'claude' } });
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkCli.login' }));
  await waitFor(() =>
    expect(bridge.create).toHaveBeenCalledWith(
      expect.objectContaining({ cli: { agent: 'claude', mode: 'login' } }),
    ),
  );
  fireEvent.change(screen.getByLabelText('vibelinkCli.agent'), { target: { value: 'opencode2' } });
  expect(screen.queryByRole('button', { name: 'vibelinkCli.login' })).toBeNull();
  expect(screen.getByText('vibelinkCli.noLogin')).toBeTruthy();
});
it('expires the account view and disposes its terminal instead of accepting more input', async () => {
  vi.useFakeTimers();
  const bridge = fixture();
  render(<EmbeddedCliPanel bridge={bridge} expiresAt={Date.now() + 1000} />);
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkCli.new' }));
  await act(async () => {});
  const id = vi.mocked(bridge.create).mock.calls[0][0].id;
  await act(async () => vi.advanceTimersByTimeAsync(1001));
  expect(bridge.dispose).toHaveBeenCalledWith(id);
  expect(screen.getByRole('alert').textContent).toContain('vibelinkCli.unavailable');
  act(() => mock.input?.('late input'));
  expect(bridge.write).not.toHaveBeenCalled();
});

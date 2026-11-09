// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { HubWorkspace } from './HubWorkspace';
const state = vi.hoisted(() => ({ edition: 'dev', search: '' }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  useLocation: () => ({ search: state.search }),
}));
vi.mock('./useHubAuth', () => ({
  useHubAuth: () => ({
    snapshot: {
      session: {
        issuer: 'hub_owner',
        sessionId: 'session',
        edition: state.edition,
        expiresAt: Date.now() + 60000,
      },
    },
  }),
}));
vi.mock('./EmbeddedCliPanel', () => ({ EmbeddedCliPanel: () => <div>Embedded CLI fixture</div> }));
vi.mock('./AccountInfo', () => ({ VibeLinkAccountInfo: () => null }));
vi.mock('./HubWorkPanel', () => ({ HubWorkPanel: () => null }));
vi.mock('./HubAdminEntry', () => ({ HubAdminEntry: () => null }));
vi.mock('./HubAutomationPanel', () => ({ HubAutomationPanel: () => null }));
vi.mock('./NotificationPanel', () => ({ NotificationPanel: () => null }));
vi.mock('@/components/title-bar/WindowControls', () => ({ WindowControls: () => null }));
beforeEach(() => {
  state.edition = 'dev';
  state.search = '';
  Object.defineProperty(window, 'electronAPI', {
    configurable: true,
    value: { terminal: {}, vibelink: {} },
  });
});
afterEach(cleanup);
it('opens the embedded CLI from the development workspace and closes it explicitly', () => {
  render(<HubWorkspace />);
  expect(screen.queryByText('Embedded CLI fixture')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkCli.open' }));
  expect(screen.getByText('Embedded CLI fixture')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkCli.closePanel' }));
  expect(screen.queryByText('Embedded CLI fixture')).toBeNull();
});
it('accepts the native-home deep link but never renders host terminals for beta', () => {
  state.search = '?panel=cli';
  const page = render(<HubWorkspace />);
  expect(screen.getByText('Embedded CLI fixture')).toBeTruthy();
  state.edition = 'beta';
  page.rerender(<HubWorkspace />);
  expect(screen.queryByText('Embedded CLI fixture')).toBeNull();
  expect(screen.queryByRole('button', { name: 'vibelinkCli.open' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'vibelinkCli.closePanel' })).toBeNull();
});

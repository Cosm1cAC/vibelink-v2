// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { HubAdminEntry } from './HubAdminEntry';
import type { HubSession } from '../../../shared/vibelinkAuth';
vi.mock('react-i18next', () => {
  const t = (key: string) => key;
  return { useTranslation: () => ({ t }) };
});
afterEach(cleanup);
const owner: HubSession = {
  userId: 'owner',
  label: 'Owner',
  tenantId: null,
  edition: 'dev',
  sessionId: 'fixture-entry',
  expiresAt: Date.now() + 60000,
  issuer: 'hub_aaaaaaaaaaaaaaaaaaaaaaaa',
  hubId: 'hub_aaaaaaaaaaaaaaaaaaaaaaaa',
  permissions: ['client'],
};
it('shows the navigation only after Main allows it, and navigation performs no admin write', async () => {
  const onOpen = vi.fn();
  const admin = vi.fn(async () => ({
    success: true as const,
    data: { kind: 'capability' as const, allowed: true, reason: '' },
  }));
  const bridge = { admin };
  const view = render(
    <HubAdminEntry session={{ ...owner, edition: 'beta' }} bridge={bridge} onOpen={onOpen} />,
  );
  expect(admin).not.toHaveBeenCalled();
  expect(screen.queryByRole('button')).toBeNull();
  view.rerender(<HubAdminEntry session={owner} bridge={bridge} onOpen={onOpen} />);
  fireEvent.click(await screen.findByRole('button', { name: 'vibelinkAdmin.title' }));
  expect(onOpen).toHaveBeenCalledTimes(1);
  await waitFor(() => expect(admin).toHaveBeenCalledExactlyOnceWith({ action: 'capability' }));
});

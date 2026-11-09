// @vitest-environment jsdom
// Protects the real unified login form: beta activation is distinct from explicit
// local history access, and an invite never remains in UI state after submission.
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { VibeLinkLoginPage } from './LoginPage';
vi.mock('@/components/title-bar/WindowControls', () => ({ WindowControls: () => null }));
vi.mock('react-i18next', async () => {
  const resources = (await import('../../i18n/locales/zh-CN/common.json')).default;
  return { useTranslation: () => ({ t: (key: string) => key.split('.').reduce<unknown>((value, part) => (value as Record<string, unknown>)?.[part], resources) ?? key }) };
});

it('offers local history explicitly and clears a submitted beta code', async () => {
  const auth = vi.fn(async () => ({ success: true as const, data: { status: 'signed_out' as const, session: null, qr: null } }));
  const bridge = { enabled: true, auth, work: vi.fn(), notifications:vi.fn(), onNotificationOpen:()=>()=>{}, onAuthState: () => () => undefined, request: vi.fn() };
  render(<VibeLinkLoginPage bridge={bridge} />);
  expect(screen.getByRole('button', { name: '使用本机工作区' })).toBeTruthy();
  fireEvent.change(screen.getByLabelText('内测码'), { target: { value: 'fake-invite' } });
  fireEvent.click(screen.getByRole('button', { name: '使用内测码登录' }));
  await waitFor(() => expect(auth).toHaveBeenCalledWith({ action: 'activateBeta', code: 'fake-invite' }));
  await waitFor(() => expect((screen.getByLabelText('内测码') as HTMLInputElement).value).toBe(''));
  expect(screen.queryByText('登录 Cindy')).toBeNull();
});

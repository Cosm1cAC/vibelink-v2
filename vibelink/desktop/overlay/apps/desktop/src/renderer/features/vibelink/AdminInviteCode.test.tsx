// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { AdminInviteCode } from './AdminInviteCode';
const labels = {
  show: '显示邀请码',
  hide: '隐藏邀请码',
  copy: '复制邀请码',
  copied: '已复制',
  copyFailed: '复制失败',
};
afterEach(cleanup);
it('masks an invite by default and does not carry reveal state into a new scope', () => {
  const view = render(<AdminInviteCode scope="owner-a" code="fixture-invite-a" labels={labels} />);
  expect(screen.queryByText('fixture-invite-a')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '显示邀请码' }));
  expect(screen.getByText('fixture-invite-a')).toBeTruthy();
  view.rerender(<AdminInviteCode scope="owner-b" code="fixture-invite-b" labels={labels} />);
  expect(screen.queryByText('fixture-invite-a')).toBeNull();
  expect(screen.queryByText('fixture-invite-b')).toBeNull();
});
it('copies only after an explicit click and ignores an old scope clipboard receipt', async () => {
  let finish!: () => void;
  const writeText = vi.fn(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
  );
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
  const view = render(<AdminInviteCode scope="owner-a" code="fixture-invite-a" labels={labels} />);
  expect(writeText).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '复制邀请码' }));
  expect(writeText).toHaveBeenCalledExactlyOnceWith('fixture-invite-a');
  view.rerender(<AdminInviteCode scope="owner-b" code="fixture-invite-b" labels={labels} />);
  await act(async () => finish());
  expect(screen.queryByText('已复制')).toBeNull();
});

import React, { useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
export interface InviteLabels {
  show: string;
  hide: string;
  copy: string;
  copied: string;
  copyFailed: string;
}
/** Scope-bound presentation only; the parent must already hold Main's admin capability. */
export function AdminInviteCode({
  scope,
  code,
  labels,
}: {
  scope: string;
  code: string;
  labels: InviteLabels;
}) {
  const [visible, setVisible] = useState('');
  const key = `${scope}\0${code}`;
  const current = useRef(key);
  current.current = key;
  const [receipt, setReceipt] = useState({ key: '', value: '' });
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      if (current.current === key) setReceipt({ key, value: labels.copied });
    } catch {
      if (current.current === key) setReceipt({ key, value: labels.copyFailed });
    }
  };
  return (
    <div className="flex flex-wrap items-center gap-2">
      <code className="break-all text-12">{visible === key ? code : '••••••••••••'}</code>
      <Button size="xs" variant="secondary" onClick={() => setVisible(visible === key ? '' : key)}>
        {visible === key ? labels.hide : labels.show}
      </Button>
      <Button size="xs" variant="secondary" onClick={() => void copy()}>
        {labels.copy}
      </Button>
      {receipt.key === key && (
        <span role="status" className="text-12 text-[var(--text-secondary)]">
          {receipt.value}
        </span>
      )}
    </div>
  );
}

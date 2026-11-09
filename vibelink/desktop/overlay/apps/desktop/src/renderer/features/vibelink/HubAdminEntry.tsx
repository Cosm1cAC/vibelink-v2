import React from 'react';
import { ShieldCheck } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import type { HubSession } from '../../../shared/vibelinkAuth';
import { type AdminBridge, useAdminCapability } from './useAdminCapability';
export function HubAdminEntry({
  session,
  bridge,
  onOpen,
}: {
  session: HubSession | null;
  bridge?: AdminBridge;
  onOpen: () => void;
}) {
  const { t } = useTranslation();
  const access = useAdminCapability(session, bridge);
  if (!access.allowed) return null;
  return (
    <Button variant="secondary" className="gap-2" onClick={onOpen}>
      <ShieldCheck size={15} aria-hidden="true" />
      {t('vibelinkAdmin.title')}
    </Button>
  );
}

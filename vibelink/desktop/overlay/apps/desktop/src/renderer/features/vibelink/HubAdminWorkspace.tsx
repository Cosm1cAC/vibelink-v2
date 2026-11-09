import React from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { WindowControls } from '@/components/title-bar/WindowControls';
import { Button } from '@/components/ui/button';
import { HubAdminPanel } from './HubAdminPanel';
import { useHubAuth } from './useHubAuth';
export function HubAdminWorkspace() {
  const { snapshot } = useHubAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();
  return (
    <main className="flex h-screen flex-col bg-[var(--surface)] text-[var(--text-primary)]">
      <header className="flex h-14 shrink-0 items-center justify-between border-b border-[var(--border-default)] px-6">
        <span className="text-16 font-semibold">VibeLink</span>
        <div className="flex items-center gap-4">
          <Button variant="secondary" onClick={() => navigate('/hub')}>
            {t('vibelinkAdmin.back')}
          </Button>
          <WindowControls />
        </div>
      </header>
      <div className="min-h-0 flex-1 overflow-auto px-6 py-5">
        <div className="mx-auto w-full max-w-[1440px]">
          <HubAdminPanel
            key={snapshot.session?.sessionId || 'signed-out'}
            session={snapshot.session}
            bridge={window.electronAPI?.vibelink}
          />
        </div>
      </div>
    </main>
  );
}

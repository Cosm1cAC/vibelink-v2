import React from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { Link2 } from 'lucide-react';
import { WindowControls } from '@/components/title-bar/WindowControls';
import { Button } from '@/components/ui/button';
import { VibeLinkAccountInfo } from './AccountInfo';
import { HubWorkPanel } from './HubWorkPanel';
import { useHubAuth } from './useHubAuth';

/** All work is mirrored from the authenticated Hub. Native history remains a dev capability. */
export function HubWorkspace() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { snapshot } = useHubAuth();
  const session = snapshot.session;
  return (
    <main className="flex h-screen flex-col bg-[var(--surface)] text-[var(--text-primary)]">
      <header className="flex h-14 shrink-0 items-center justify-between border-b border-[var(--border-default)] px-6">
        <span className="flex items-center gap-2 text-16 font-semibold">
          <Link2 size={20} aria-hidden="true" />
          VibeLink
        </span>
        <div className="flex items-center gap-4">
          {session?.edition === 'dev' && (
            <Button variant="secondary" onClick={() => navigate('/')}>
              {t('vibelinkWork.localHistory')}
            </Button>
          )}
          <WindowControls />
        </div>
      </header>
      <div className="min-h-0 flex-1 overflow-auto px-6 py-5">
        <div className="mx-auto flex min-h-full w-full max-w-[1280px] flex-col gap-5">
          <VibeLinkAccountInfo />
          <HubWorkPanel
            key={session?.sessionId || 'signed-out'}
            session={session}
            bridge={window.electronAPI?.vibelink}
          />
        </div>
      </div>
    </main>
  );
}

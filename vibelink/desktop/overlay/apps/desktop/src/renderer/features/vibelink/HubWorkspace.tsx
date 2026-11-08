import React from 'react';
import { useTranslation } from 'react-i18next';
import { Link2, ShieldCheck } from 'lucide-react';
import { WindowControls } from '@/components/title-bar/WindowControls';
import { VibeLinkAccountInfo } from './AccountInfo';
import { useHubAuth } from './useHubAuth';

/** A beta principal enters the Hub workspace, never the machine owner's engine. */
export function HubWorkspace() {
  const { t } = useTranslation();
  const { snapshot } = useHubAuth();
  return <main className="flex h-screen flex-col bg-[var(--surface)] text-[var(--text-primary)]">
    <header className="flex h-14 shrink-0 items-center justify-between border-b border-[var(--border-default)] px-6"><span className="flex items-center gap-2 text-16 font-semibold"><Link2 size={20} aria-hidden="true" />{t('vibelinkAuth.workspace')}</span><WindowControls /></header>
    <div className="mx-auto w-full max-w-[960px] px-6 py-9">
      <VibeLinkAccountInfo />
      <div className="mt-8 flex items-center gap-3 text-14"><ShieldCheck size={20} aria-hidden="true" /><span>{t('vibelinkAuth.loggedIn', { label: snapshot.session?.label || t('vibelinkAuth.account') })}</span></div>
      <p className="mt-3 text-13 text-[var(--text-secondary)]">{t('vibelinkAuth.sharedProgress')}</p>
    </div>
  </main>;
}

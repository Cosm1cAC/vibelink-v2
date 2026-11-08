import React from 'react';
import { useTranslation } from 'react-i18next';
import { LogOut, Settings, UserRound } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '@/contexts/AuthContext';
import { Button } from '@/components/ui/button';
import { useHubAuth } from './useHubAuth';

export function VibeLinkAccountInfo({ compact = false }: { compact?: boolean }) {
  const { t } = useTranslation();
  const { snapshot } = useHubAuth();
  const { logout } = useAuth();
  const navigate = useNavigate();
  const session = snapshot.session;
  return <div className={`flex items-center gap-3 ${compact ? 'px-2 py-3' : 'rounded-2xl border border-[var(--border-default)] p-5'}`}>
    <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-[var(--surface-chip)] text-[var(--text-secondary)]"><UserRound size={18} aria-hidden="true" /></span>
    <div className="min-w-0 flex-1"><p className="truncate text-13 font-medium text-[var(--text-primary)]">{session?.label || t('vibelinkAuth.account')}</p><p className="mt-0.5 text-11 text-[var(--text-tertiary)]">{t(session?.edition === 'beta' ? 'vibelinkAuth.beta' : 'vibelinkAuth.dev')}</p></div>
    {compact && <Button variant="secondary" aria-label={t('vibelinkAuth.accountSettings')} onClick={() => navigate('/settings?tab=account')}><Settings size={15} aria-hidden="true" /></Button>}
    <Button variant="secondary" aria-label={t('vibelinkAuth.logout')} onClick={() => void logout().then(() => navigate('/login'))}><LogOut size={15} aria-hidden="true" />{!compact && <span className="ml-2">{t('vibelinkAuth.logout')}</span>}</Button>
  </div>;
}

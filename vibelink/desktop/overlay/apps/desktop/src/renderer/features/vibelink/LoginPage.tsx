import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import * as QRCode from 'qrcode';
import { ArrowRight, Laptop, Link2, RefreshCw, Smartphone } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { WindowControls } from '@/components/title-bar/WindowControls';
import { useLoginHandoff } from '@/contexts/LoginHandoffContext';
import { endLoginFirstLaunchLightGate } from '@/hooks/useTheme';
import { CredentialStoreHelpDialog } from '@/components/auth/CredentialStoreHelpDialog';
import type { VibeLinkDesktopBridge } from '../../../shared/vibelinkDesktop';
import { useHubAuth } from './useHubAuth';
import {
  armFirstPhonePairing,
  beginFirstPhonePairing,
  cancelFirstPhonePairing,
  isFirstPhoneOwner,
} from './firstPhonePairing';

/** Hub login joins desktop and phone identities; it never creates a Cindy cloud user. */
export function VibeLinkLoginPage({ bridge }: { bridge?: VibeLinkDesktopBridge }) {
  const { t } = useTranslation();
  const { snapshot, error, busy, execute } = useHubAuth(bridge);
  const [edition, setEdition] = useState<'dev' | 'beta'>('dev');
  const [code, setCode] = useState('');
  const [qrImage, setQrImage] = useState<string | null>(null);
  const [helpOpen, setHelpOpen] = useState(false);
  const [firstPhonePending, setFirstPhonePending] = useState(false);
  const firstPhoneEpoch = useRef(0);
  const currentBridge = bridge ?? window.electronAPI?.vibelink;
  const otherAction = (input: Parameters<typeof execute>[0]) => {
    firstPhoneEpoch.current++;
    cancelFirstPhonePairing();
    setFirstPhonePending(false);
    return execute(input);
  };
  const connectFirstPhone = async () => {
    if (edition !== 'dev' || busy || firstPhonePending) return;
    const epoch = ++firstPhoneEpoch.current,
      token = beginFirstPhonePairing();
    setFirstPhonePending(true);
    try {
      const result = await execute({ action: 'useLocalWorkspace' });
      if (epoch !== firstPhoneEpoch.current) return;
      if (!result?.success || !isFirstPhoneOwner(result.data.session)) {
        cancelFirstPhonePairing(token);
        return;
      }
      const current = await currentBridge?.auth({ action: 'snapshot' });
      if (epoch !== firstPhoneEpoch.current) return;
      const actual = current?.success ? current.data.session : null,
        expected = result.data.session;
      if (
        !current?.success ||
        current.data.status !== 'authenticated' ||
        !actual ||
        actual.issuer !== expected.issuer ||
        actual.edition !== expected.edition ||
        actual.userId !== expected.userId ||
        actual.sessionId !== expected.sessionId ||
        !armFirstPhonePairing(token, actual)
      )
        cancelFirstPhonePairing(token);
    } catch {
      cancelFirstPhonePairing(token);
    } finally {
      if (epoch === firstPhoneEpoch.current) setFirstPhonePending(false);
    }
  };
  const { reportLoginPanelMounted, reportLoginPanelUnmounted } = useLoginHandoff();
  useEffect(() => {
    reportLoginPanelMounted();
    return () => {
      reportLoginPanelUnmounted();
      endLoginFirstLaunchLightGate();
    };
  }, [reportLoginPanelMounted, reportLoginPanelUnmounted]);
  useEffect(() => {
    let disposed = false;
    setQrImage(null);
    if (snapshot.qr?.link) {
      void QRCode.toDataURL(snapshot.qr.link, { margin: 2, width: 256, errorCorrectionLevel: 'M' })
        .then((image) => {
          if (!disposed) setQrImage(image);
        })
        .catch(() => undefined);
    }
    return () => {
      disposed = true;
    };
  }, [snapshot.qr?.link]);
  const qr = snapshot.qr;
  const statusText =
    qr?.status === 'scanned'
      ? t('vibelinkAuth.scanned')
      : qr?.status === 'denied'
        ? t('vibelinkAuth.denied')
        : qr?.status === 'expired'
          ? t('vibelinkAuth.expired')
          : qr
            ? t('vibelinkAuth.scanHint')
            : t('vibelinkAuth.phoneHint');
  return (
    <div className="fixed inset-0 z-[9990] overflow-auto bg-[var(--surface)] text-[var(--text-primary)]">
      <header
        className="flex h-14 items-center justify-between px-7"
        style={{ WebkitAppRegion: 'drag' } as React.CSSProperties}
      >
        <span className="flex items-center gap-2.5 text-16 font-semibold tracking-tight">
          <Link2 size={20} aria-hidden="true" /> VibeLink
        </span>
        <div style={{ WebkitAppRegion: 'no-drag' } as React.CSSProperties}>
          <WindowControls />
        </div>
      </header>
      <main className="mx-auto flex min-h-[calc(100vh-112px)] max-w-[900px] flex-col justify-center px-7 py-10">
        <div
          className="mb-9 flex items-center gap-3 text-[var(--text-secondary)]"
          aria-hidden="true"
        >
          <Laptop size={24} />
          <span className="h-px w-14 bg-[var(--border-default)]" />
          <Link2 size={20} />
          <span className="h-px w-14 bg-[var(--border-default)]" />
          <Smartphone size={22} />
        </div>
        <h1 className="text-[32px] font-semibold leading-tight tracking-tight">
          {t('vibelinkAuth.title')}
        </h1>
        <p className="mt-3 text-14 leading-relaxed text-[var(--text-secondary)]">
          {t('vibelinkAuth.subtitle')}
        </p>
        <div className="mt-9 grid gap-8 md:grid-cols-[1fr_1fr] md:gap-12">
          <section
            aria-label={t('vibelinkAuth.qrTitle')}
            className="rounded-2xl border border-[var(--border-default)] bg-[var(--surface-elevated)] p-6"
          >
            <div className="mb-5 flex items-center justify-between gap-3">
              <h2 className="text-16 font-medium">{t('vibelinkAuth.qrTitle')}</h2>
              <label className="flex items-center gap-2 text-12 text-[var(--text-secondary)]">
                {t('vibelinkAuth.connection')}
                <select
                  aria-label={t('vibelinkAuth.connection')}
                  value={edition}
                  disabled={busy}
                  onChange={(event) => {
                    setEdition(event.target.value as 'dev' | 'beta');
                    void otherAction({ action: 'cancelQr' });
                  }}
                  className="rounded-full border border-[var(--border-default)] bg-[var(--surface-elevated)] px-3 py-1.5 text-12 text-[var(--text-primary)]"
                >
                  <option value="dev">{t('vibelinkAuth.dev')}</option>
                  <option value="beta">{t('vibelinkAuth.beta')}</option>
                </select>
              </label>
            </div>
            <div className="flex min-h-[238px] flex-col items-center justify-center gap-4">
              {qrImage && qr && qr.status !== 'expired' && qr.status !== 'denied' ? (
                <img
                  src={qrImage}
                  alt={t('vibelinkAuth.qrAlt')}
                  width={208}
                  height={208}
                  className="rounded-lg"
                />
              ) : (
                <div className="flex h-[188px] w-[188px] items-center justify-center rounded-xl border border-dashed border-[var(--border-default)] text-[var(--text-tertiary)]">
                  <Smartphone size={48} strokeWidth={1.25} aria-hidden="true" />
                </div>
              )}
              {qr && (
                <div className="text-13 text-[var(--text-secondary)]">
                  {t('vibelinkAuth.verificationCode')}{' '}
                  <strong className="ml-2 font-mono text-16 tracking-[0.18em] text-[var(--text-primary)]">
                    {qr.verificationCode}
                  </strong>
                </div>
              )}
            </div>
            <p
              role="status"
              className="mt-4 min-h-5 text-center text-12 text-[var(--text-secondary)]"
            >
              {statusText}
            </p>
            <Button
              className="mt-5 w-full gap-2"
              variant="secondary"
              loading={busy}
              onClick={() => void otherAction({ action: 'startQr', edition })}
            >
              {qr ? (
                <RefreshCw size={14} aria-hidden="true" />
              ) : (
                <Smartphone size={14} aria-hidden="true" />
              )}
              {qr ? t('vibelinkAuth.refreshQr') : t('vibelinkAuth.createQr')}
            </Button>
          </section>
          <div className="flex flex-col justify-center">
            <form
              onSubmit={(event) => {
                event.preventDefault();
                const submitted = code.trim();
                setCode('');
                if (submitted) void otherAction({ action: 'activateBeta', code: submitted });
              }}
            >
              <h2 className="text-18 font-medium">{t('vibelinkAuth.betaTitle')}</h2>
              <p className="mb-6 mt-2 text-13 leading-relaxed text-[var(--text-secondary)]">
                {t('vibelinkAuth.betaIntro')}
              </p>
              <label htmlFor="vibelink-invite" className="mb-2 block text-13 font-medium">
                {t('vibelinkAuth.inviteLabel')}
              </label>
              <input
                id="vibelink-invite"
                type="password"
                autoComplete="off"
                spellCheck={false}
                maxLength={256}
                value={code}
                onChange={(event) => setCode(event.target.value)}
                placeholder={t('vibelinkAuth.invitePlaceholder')}
                className="h-10 w-full rounded-xl border border-[var(--border-default)] bg-[var(--surface-elevated)] px-3.5 text-14 outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus-ring-soft)]"
              />
              <Button
                type="submit"
                variant="cta"
                size="lg"
                className="mt-4 w-full gap-2"
                disabled={!code.trim() || busy}
                loading={busy}
              >
                {t('vibelinkAuth.betaLogin')} <ArrowRight size={15} aria-hidden="true" />
              </Button>
            </form>
            <div className="mt-8 border-t border-[var(--border-default)] pt-6">
              <Button
                variant="secondary"
                className="w-full gap-2"
                loading={busy}
                onClick={() => void otherAction({ action: 'useLocalWorkspace' })}
              >
                <Laptop size={15} aria-hidden="true" />
                {t('vibelinkAuth.localLogin')}
              </Button>
              <p className="mt-2.5 text-center text-12 leading-relaxed text-[var(--text-tertiary)]">
                {t('vibelinkAuth.localHint')}
              </p>
              {edition === 'dev' && (
                <div className="mt-5 rounded-xl border border-[var(--border-default)] p-4">
                  <Button
                    variant="secondary"
                    className="w-full gap-2"
                    data-testid="vibelink-first-phone"
                    disabled={busy || firstPhonePending}
                    onClick={() => void connectFirstPhone()}
                  >
                    <Smartphone size={15} aria-hidden="true" />
                    {t('vibelinkAuth.firstPhone')}
                  </Button>
                  <p className="mt-2 text-12 leading-relaxed text-[var(--text-secondary)]">
                    {t('vibelinkAuth.firstPhoneHint')}
                  </p>
                  {firstPhonePending && (
                    <Button
                      variant="secondary"
                      className="mt-2"
                      data-testid="vibelink-cancel-first-phone"
                      onClick={() => void otherAction({ action: 'logout' })}
                    >
                      {t('vibelinkAuth.cancelFirstPhone')}
                    </Button>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
        {error && (
          <div
            role="alert"
            className="mt-6 rounded-xl border border-[var(--border-default)] bg-[var(--surface-elevated)] px-4 py-3 text-13 leading-relaxed"
          >
            <strong className="font-medium">
              {error.code === 'CREDENTIAL_STORE_UNAVAILABLE'
                ? t('vibelinkAuth.storageUnavailable')
                : t('vibelinkAuth.loginFailed')}
            </strong>
            <p className="mt-1 text-[var(--text-secondary)]">
              {t(
                error.code === 'CREDENTIAL_STORE_UNAVAILABLE'
                  ? 'vibelinkAuth.errorVault'
                  : error.code === 'NETWORK_UNAVAILABLE'
                    ? 'vibelinkAuth.errorNetwork'
                    : error.code === 'AUTH_EXPIRED'
                      ? 'vibelinkAuth.errorExpired'
                      : 'vibelinkAuth.errorLogin',
              )}
            </p>
            {error.code === 'CREDENTIAL_STORE_UNAVAILABLE' && (
              <Button variant="secondary" className="mt-3" onClick={() => setHelpOpen(true)}>
                {t('vibelinkAuth.help')}
              </Button>
            )}
          </div>
        )}
      </main>
      <CredentialStoreHelpDialog open={helpOpen} onOpenChange={setHelpOpen} />
    </div>
  );
}

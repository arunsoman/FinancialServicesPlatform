/**
 * FAPI 2.0 compliant login — the browser NEVER collects credentials.
 * Clicking "Sign in" redirects to BFF /bff/auth/login which initiates PAR flow
 * and redirects to the IdP hosted sign-in page.
 */
import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Spinner } from '../web-core/guards';

export function LoginForm({ portalLabel }: { portalLabel: string }) {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const error = searchParams.get('error');
  const signedOut = searchParams.get('signed_out');

  // back to the portal's home after sign-in (never to this page again)
  const home = window.location.pathname.replace(/\/login\/?$/, '') || '/';
  const signIn = () => { window.location.href = `/bff/auth/login?return_to=${encodeURIComponent(home)}`; };
  // A reason to tell (signed out for inactivity, session ended, sign-in failed) → stay here and say
  // so, with a button; otherwise straight on to the IdP's hosted sign-in (PAR flow via the BFF).
  const hold = signedOut !== null || error !== null;
  useEffect(() => {
    if (!hold) signIn();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hold, navigate]);

  return (
    <div className="grid min-h-screen place-items-center">
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <div className="fsp-eyebrow mb-2">FSP Financial Services Platform</div>
          <h1 className="text-2xl">{portalLabel}</h1>
        </div>
        <div className="fsp-panel p-6 text-center">
          {!hold && (
            <div className="flex items-center justify-center gap-3 mb-4">
              <Spinner />
              <span className="text-sm">Redirecting to secure sign-in…</span>
            </div>
          )}
          <p className="text-dim text-xs">
            {hold ? 'You sign in at the FSP Identity Provider.' : 'You will be redirected to the FSP Identity Provider for authentication.'}
            {' '}The browser never sends credentials to this application.
          </p>
          {signedOut === 'idle' && !error && (
            <p role="status" className="fsp-chip mt-4 block whitespace-normal text-sm">
              You were signed out because there was no activity. Sign in again to continue.
            </p>
          )}
          {error && (
            <p role="alert" className="fsp-chip fsp-chip-bad mt-4 block whitespace-normal text-sm">
              {error === 'session_expired' ? 'Your session ended. Please sign in again.' :
               error === 'invalid_state' ? 'Invalid login state. Please try again.' :
               'Sign-in failed: ' + error}
            </p>
          )}
          {hold && (
            <button type="button" className="fsp-btn fsp-btn-primary mt-5 w-full py-2 text-sm" data-action="session.sign-in" onClick={signIn}>
              Sign in again
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
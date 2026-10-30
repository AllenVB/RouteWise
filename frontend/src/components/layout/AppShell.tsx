import { NavLink, Outlet, useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { api } from '../../lib/apiClient';
import { authStore, useSession } from '../../lib/authStore';

export interface NavItem {
  to: string;
  label: string;
}

export function AppShell({ nav }: { nav: NavItem[] }) {
  const session = useSession();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  async function logout() {
    const refreshToken = session?.refreshToken;
    try {
      // Sunucuda refresh token'ı gerçekten geçersizler; başarısız olsa da yerel oturum kapanır.
      if (refreshToken) await api('/auth/logout', { method: 'POST', body: { refreshToken } });
    } finally {
      authStore.signOut();
      queryClient.clear();
      navigate('/giris', { replace: true });
    }
  }

  return (
    <div className="shell">
      <nav className="sidebar" aria-label="Ana menü">
        <div className="brand">RouteWise</div>
        {nav.map((n) => (
          <NavLink key={n.to} to={n.to} end>
            {n.label}
          </NavLink>
        ))}
        <div className="spacer" />
        <div className="who">{session?.displayName}</div>
        <button type="button" onClick={() => void logout()}>
          Çıkış
        </button>
      </nav>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}

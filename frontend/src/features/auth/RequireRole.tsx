import type { ReactNode } from 'react';
import { Navigate } from 'react-router';
import { useSession } from '../../lib/authStore';
import type { Role } from '../../types/api';
import { homeOf } from './homeOf';

/**
 * Yalnızca yönlendirme: yanlış roldeki kullanıcıyı kendi ekranına gönderir.
 * Güvenlik değildir — her uç yetkiyi sunucuda yeniden denetler.
 */
export function RequireRole({ role, children }: { role: Role; children: ReactNode }) {
  const session = useSession();
  if (!session) return <Navigate to="/giris" replace />;
  if (session.role !== role) return <Navigate to={homeOf(session.role)} replace />;
  return <>{children}</>;
}

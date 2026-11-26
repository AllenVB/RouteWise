import { useSyncExternalStore } from 'react';
import type { Role, TokenResponse } from '../types/api';

/**
 * Oturum durumu — küçük bir dış depo; React dışından (API istemcisi) da okunur.
 *
 * Tokenlar localStorage'da tutulur ki sayfa yenilenince oturum düşmesin. Bedeli:
 * XSS olursa token okunabilir. Uygulama üçüncü taraf betik yüklemediği ve
 * kullanıcı girdisini HTML olarak basmadığı için bu kapsamda kabul edildi.
 * Rol yalnızca arayüzü yönlendirmek içindir; yetki her istekte sunucuda denetlenir.
 */

export interface Session {
  accessToken: string;
  refreshToken: string;
  role: Role;
  displayName: string;
}

const KEY = 'routewise.session';
const listeners = new Set<() => void>();
let current: Session | null = read();

function read(): Session | null {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;   // erişilemeyen depolama (gizli pencere) oturumsuz başlamak demektir
  }
}

function write(next: Session | null) {
  current = next;
  try {
    if (next) localStorage.setItem(KEY, JSON.stringify(next));
    else localStorage.removeItem(KEY);
  } catch {
    // Kalıcılık yoksa oturum bu sekmenin ömrüyle sınırlıdır; bellekteki değer geçerli.
  }
  listeners.forEach((l) => l());
}

export const authStore = {
  get: (): Session | null => current,
  signIn: (t: TokenResponse) =>
    write({ accessToken: t.accessToken, refreshToken: t.refreshToken, role: t.role, displayName: t.displayName }),
  signOut: () => write(null),
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};

export function useSession(): Session | null {
  return useSyncExternalStore(authStore.subscribe, authStore.get, authStore.get);
}

import { AppShell, type NavItem } from '../../components/layout/AppShell';
import { useInbox } from './api';

/** Alıcı kabuğu: menüde okunmamış bildirim sayısı görünsün. */
export function RecipientShell() {
  const unread = useInbox().data?.unreadCount ?? 0;
  const nav: NavItem[] = [
    { to: '/alici/kargolar', label: 'Kargolarım' },
    { to: '/alici/adresler', label: 'Adreslerim' },
    { to: '/alici/bildirimler', label: unread > 0 ? `Bildirimler (${unread})` : 'Bildirimler' },
  ];
  return <AppShell nav={nav} />;
}

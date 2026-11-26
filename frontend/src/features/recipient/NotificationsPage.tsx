import { formatDateTime } from '../../lib/format';
import { useInbox, useMarkRead } from './api';
import { notificationText } from './notificationText';

/** A5 — bildirimler. Okunmamışlar kalın; tıklamak okundu sayar. */
export function NotificationsPage() {
  const inbox = useInbox();
  const mark = useMarkRead();

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Bildirimler</h1>
          <p className="muted">Teslim saatleri ve adres doğrulama sonuçları.</p>
        </div>
        {inbox.data && inbox.data.unreadCount > 0 && (
          <button type="button" disabled={mark.isPending} onClick={() => mark.mutate({ all: true })}>
            Tümünü okundu say
          </button>
        )}
      </div>
      <section className="card stack">
        {inbox.isPending && <div className="empty">Yükleniyor…</div>}
        {inbox.error && <div role="alert" className="alert danger">Bildirimler yüklenemedi: {inbox.error.message}</div>}
        {inbox.data?.items.length === 0 && <div className="empty">Bildiriminiz yok.</div>}
        {inbox.data?.items.map((n) => {
          const t = notificationText(n);
          return (
            <button key={n.id} type="button" className="stack" aria-label={t.title}
              style={{ textAlign: 'left', fontWeight: n.read ? 400 : 600, gap: 4, border: 0, borderBottom: '1px solid var(--border)', borderRadius: 0 }}
              onClick={() => { if (!n.read) mark.mutate({ ids: [n.id] }); }}>
              <span>{t.title}</span>
              <span className="muted" style={{ fontWeight: 400 }}>{t.body}</span>
              <span className="muted" style={{ fontWeight: 400, fontSize: 12 }}>{formatDateTime(n.createdAt)}</span>
            </button>
          );
        })}
      </section>
    </>
  );
}

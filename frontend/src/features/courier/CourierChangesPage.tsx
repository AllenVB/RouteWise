import { useEffect } from 'react';
import { Link } from 'react-router';
import { Badge } from '../../components/ui/Badge';
import { formatClock, removedReasonLabel } from '../../lib/format';
import { useMyRoute, useRouteChanges, type RouteChange } from './api';
import { changeSummary, markChangesSeen } from './routeChanges';

/**
 * K5 — rota değişti. Neyin değiştiği açıkça: çıkan durak ve sebebi, adresi
 * taşınan durak, numarası değişen durak eski ve yeni yeriyle. En yeni en üstte.
 */
export function CourierChangesPage() {
  const route = useMyRoute();
  const changes = useRouteChanges(true);
  const data = changes.data;
  const latest = data?.changes.at(-1)?.id;

  // Bu ekranı açan kurye değişiklikleri görmüştür: rota ekranındaki bant kapanır.
  useEffect(() => {
    if (data && latest !== undefined) markChangesSeen(data.routeId, latest);
  }, [data, latest]);

  if (changes.isPending) return <div className="empty">Yükleniyor…</div>;
  if (changes.error) return <div role="alert" className="alert danger">Değişiklikler yüklenemedi: {changes.error.message}</div>;
  if (!data) return <div className="card empty">Bugün sana sevk edilmiş bir rota yok.</div>;

  // Durağın adresi rota verisinden: değişiklik kaydı yalnızca kimlik ve sipariş numarası taşır.
  const stops = new Map((route.data?.routes[0]?.stops ?? []).map((s) => [s.id, s]));
  const where = (stopId: number, orderNumber: string | null) => stops.get(stopId)?.addressText ?? orderNumber ?? `Durak ${stopId}`;

  return (
    <div className="stack" style={{ maxWidth: 640 }}>
      <Link to="/kurye">← Rotaya dön</Link>
      <div className="page-head">
        <div>
          <h1>Rota değişiklikleri</h1>
          <p className="muted">Bugün rotanda yapılan değişiklikler, en yenisi üstte.</p>
        </div>
      </div>
      {data.recomputing && <div role="status" className="alert info">Rota yeniden hesaplanıyor…</div>}
      {data.changes.length === 0 && <div className="card empty">Bugün rotanda değişiklik olmadı.</div>}
      {[...data.changes].reverse().map((c) => (
        <ChangeCard key={c.id} change={c} where={where} />
      ))}
    </div>
  );
}

function ChangeCard({ change: c, where }: { change: RouteChange; where: (stopId: number, orderNumber: string | null) => string }) {
  return (
    <section className="card stack" aria-label={`Değişiklik ${formatClock(c.at)}`}>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <b>{formatClock(c.at)}</b>
        <Badge text={c.trigger === 'ERTELEME' ? 'Erteleme' : 'Adres değişikliği'} tone={c.orderKept ? 'warn' : 'info'} />
      </div>
      <p className="muted">{changeSummary(c)}</p>
      {c.removed.length > 0 && (
        <div>
          <b>Rotadan çıkan</b>
          <ul>
            {c.removed.map((r) => (
              <li key={r.stopId}>
                <s>{where(r.stopId, r.orderNumber)}</s>{' '}
                <span className="muted">— {removedReasonLabel[r.reason] ?? 'Rotaya sığmadı'}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {c.moved.length > 0 && (
        <div>
          <b>Adresi değişen</b>
          <ul>
            {c.moved.map((m) => (
              <li key={m.stopId}>
                <Link to={`/kurye/durak/${m.stopId}`}>{where(m.stopId, m.orderNumber)}</Link>{' '}
                <span className="muted">— yeni adrese git</span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {c.reordered.length > 0 && (
        <div>
          <b>Sırası değişen</b>
          <ul>
            {c.reordered.map((r) => (
              <li key={r.stopId}>
                {where(r.stopId, r.orderNumber)} <span className="muted">— {r.fromSeq}. duraktan {r.toSeq}. durağa</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

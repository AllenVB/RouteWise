import { Link, useParams } from 'react-router';
import { Badge } from '../../components/ui/Badge';
import { ApiError } from '../../lib/apiClient';
import { formatClock, formatDateTime, formatNumber, shipmentLabel } from '../../lib/format';
import { useMyShipment, useMyShipments, type MyShipment } from './api';

/** A2 — kargolarım. */
export function MyShipmentsPage() {
  const list = useMyShipments();

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Kargolarım</h1>
          <p className="muted">Kargo yola çıktığında tahmini teslim saati burada görünür.</p>
        </div>
      </div>
      <section className="card stack">
        {list.isPending && <div className="empty">Yükleniyor…</div>}
        {list.error && <div role="alert" className="alert danger">Kargolar yüklenemedi: {list.error.message}</div>}
        {list.data?.length === 0 && <div className="empty">Henüz kargonuz yok.</div>}
        {list.data?.map((s) => (
          <Link key={s.id} to={`/alici/kargolar/${s.id}`} className="row" style={{ justifyContent: 'space-between', textDecoration: 'none', color: 'inherit' }}>
            <span>
              <b>{s.orderNumber}</b> <span className="muted">{s.addressText}</span>
              <br />
              <span className="muted">{expectation(s)}</span>
            </span>
            <Badge {...shipmentLabel[s.status]} />
          </Link>
        ))}
      </section>
    </>
  );
}

/** A3 — kargo detayı. */
export function MyShipmentDetailPage() {
  const id = Number(useParams().id);
  const q = useMyShipment(id);

  if (q.isPending) return <div className="empty">Yükleniyor…</div>;
  if (q.error) {
    return (
      <div className="stack">
        <div role="alert" className="alert danger">
          {q.error instanceof ApiError && q.error.status === 404 ? 'Kargo bulunamadı.' : q.error.message}
        </div>
        <Link to="/alici/kargolar">← Kargolarım</Link>
      </div>
    );
  }
  const s = q.data;
  return (
    <div className="stack" style={{ maxWidth: 560 }}>
      <Link to="/alici/kargolar">← Kargolarım</Link>
      <div className="card stack">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h1>{s.orderNumber}</h1>
          <Badge {...shipmentLabel[s.status]} />
        </div>
        <p>{s.addressText}</p>
        <p className="muted">{formatNumber(s.weightKg)} kg · oluşturuldu {formatDateTime(s.createdAt)}</p>
        <div className="alert info">{expectation(s)}</div>
        {s.stopStatus === 'ULASILAMADI' && s.failureReason && (
          <div className="alert warn">Kurye notu: {s.failureReason}. Kargonuz başka bir güne planlanacak.</div>
        )}
      </div>
    </div>
  );
}

/** Alıcının bilmek istediği tek cümle: ne zaman geliyor ya da ne oldu. */
function expectation(s: MyShipment): string {
  if (s.status === 'TESLIM_EDILDI') return s.actualArrival ? `Teslim edildi · ${formatClock(s.actualArrival)}` : 'Teslim edildi';
  if (s.stopStatus === 'ULASILAMADI') return 'Teslim edilemedi; yeniden planlanacak';
  if (s.status === 'ERTELENDI' && s.postponedTo) return `Ertelendi · ${s.postponedTo}`;
  if (s.eta) return `Tahmini teslim ${formatClock(s.eta)}`;
  return 'Henüz planlanmadı';
}

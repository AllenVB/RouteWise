import { useMemo, useState } from 'react';
import { Link } from 'react-router';
import { MapView, type LngLat, type MapLine, type MapMarker } from '../../components/map/MapView';
import { Badge } from '../../components/ui/Badge';
import { formatClock, formatDuration, formatKm, stopStatusLabel } from '../../lib/format';
import { useMyRoute, useRouteChanges, type RouteChanges } from './api';
import { changeSummary, markChangesSeen, seenChangeId } from './routeChanges';

/**
 * K2 + K4 — bugünkü rotam ve haritası.
 *
 * Sıradaki durak, sıra numarası en küçük açık duraktır; kurye sırayı bozabilir
 * (adreste kimse yoksa geçer), ekran yine ilk açık durağı öne çıkarır.
 */
export function CourierRoutePage() {
  const route = useMyRoute();
  const r = route.data?.routes[0];
  const changes = useRouteChanges(Boolean(r));
  const next = r?.stops.find((s) => s.status === 'BEKLEMEDE') ?? null;
  // Ertelenen durağa uğranmaz: ne "tamamlandı" sayılır ne toplamda yer alır.
  const visits = r ? r.stops.filter((s) => s.status !== 'ERTELENDI') : [];
  const closed = visits.filter((s) => s.status !== 'BEKLEMEDE').length;

  const { markers, lines } = useMemo(() => {
    const d = route.data;
    if (!d || !r) return { markers: [] as MapMarker[], lines: [] as MapLine[] };
    const ms: MapMarker[] = [{ id: 'depot', lat: d.depot.lat, lon: d.depot.lon, label: 'D', tone: 'depot', title: d.depot.name }];
    for (const s of r.stops) {
      if (s.lat === null || s.lon === null || s.status === 'ERTELENDI') continue;
      ms.push({
        id: s.id, lat: s.lat, lon: s.lon, label: String(s.sequenceNo),
        // Kapanan duraklar soluk, sıradaki vurgulu
        tone: s.id === next?.id ? 'selected' : s.status === 'BEKLEMEDE' ? 'stop' : 'pending',
        title: `${s.sequenceNo}. ${s.addressText ?? ''}`,
      });
    }
    const ls: MapLine[] = r.geometry ? [{ id: r.id, coordinates: r.geometry.coordinates as LngLat[], color: '#1f5fa8' }] : [];
    return { markers: ms, lines: ls };
  }, [route.data, r, next?.id]);

  if (route.isPending) return <div className="empty">Yükleniyor…</div>;
  if (route.error) return <div role="alert" className="alert danger">Rota yüklenemedi: {route.error.message}</div>;
  if (!r) {
    return (
      <div className="card empty">
        Bugün sana sevk edilmiş bir rota yok. Rota sevk edildiğinde burada görünecek.
      </div>
    );
  }

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Bugünkü rotam</h1>
          <p className="muted">
            {r.plate ?? `Araç ${r.vehicleId}`} · {visits.length} durak · {formatKm(r.totalDistanceM)} · {formatDuration(r.totalDurationS)}
          </p>
        </div>
        <Badge text={`${closed}/${visits.length} tamamlandı`} tone={closed === visits.length ? 'ok' : 'info'} />
      </div>

      <ChangeBanner data={changes.data ?? null} />

      {next ? (
        <Link to={`/kurye/durak/${next.id}`} className="card stack" style={{ textDecoration: 'none', color: 'inherit', borderColor: 'var(--primary)', marginBottom: 16 }}>
          <span className="muted">Sıradaki durak · {next.sequenceNo}. durak{next.eta ? ` · tahmini ${formatClock(next.eta)}` : ''}</span>
          <b style={{ fontSize: 18 }}>{next.addressText}</b>
          <span>{next.recipientName ?? ''}</span>
        </Link>
      ) : (
        <div className="alert ok" style={{ marginBottom: 16 }}>Bütün duraklar kapandı. Depoya dönebilirsin.</div>
      )}

      <div className="grid-2">
        <section className="card" aria-label="Duraklar">
          <ol className="stack" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
            {r.stops.map((s) => (
              <li key={s.id}>
                <Link to={`/kurye/durak/${s.id}`} className="row" style={{ justifyContent: 'space-between', textDecoration: 'none', color: 'inherit' }}>
                  <span>
                    <b>{s.sequenceNo}.</b> {s.addressText}
                    <span className="muted"> {s.eta ? `· ${formatClock(s.eta)}` : ''}</span>
                  </span>
                  <Badge {...(stopStatusLabel[s.status] ?? { text: s.status, tone: '' })} />
                </Link>
              </li>
            ))}
          </ol>
        </section>
        <section className="card" aria-label="Harita">
          <MapView markers={markers} lines={lines} height={420} />
        </section>
      </div>
    </>
  );
}

/**
 * K5'in rota ekranındaki yüzü: hesaplama sürerken bekle de, yeni bir değişiklik
 * varsa ne olduğunu tek satırda söyle. "Tamam" yalnızca bu cihazda kapatır.
 */
function ChangeBanner({ data }: { data: RouteChanges | null }) {
  const [ack, setAck] = useState<number | null>(null);
  if (!data) return null;
  if (data.recomputing) {
    return <div role="status" className="alert info" style={{ marginBottom: 16 }}>Rota yeniden hesaplanıyor… Sıra birazdan güncellenecek.</div>;
  }
  const latest = data.changes.at(-1);
  const seen = ack ?? seenChangeId(data.routeId);
  if (!latest || latest.id <= seen) return null;
  return (
    <div role="status" className="alert warn stack" style={{ marginBottom: 16 }}>
      <span><b>Rota değişti · {formatClock(latest.at)}.</b> {changeSummary(latest)}</span>
      <span className="row">
        <Link to="/kurye/degisiklikler">Ne değişti?</Link>
        <button type="button" onClick={() => { markChangesSeen(data.routeId, latest.id); setAck(latest.id); }}>Tamam</button>
      </span>
    </div>
  );
}

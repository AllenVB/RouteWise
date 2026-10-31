import { useMemo, useState } from 'react';
import { MapView, type LngLat, type MapLine, type MapMarker } from '../../components/map/MapView';
import { ApiError } from '../../lib/apiClient';
import {
  algorithmLabel,
  excludedReasonLabel,
  formatDuration,
  formatKm,
  formatNumber,
  formatTry,
  routeColor,
  todayLocal,
} from '../../lib/format';
import type { Algorithm, OptimizeResponse, RunDetail } from '../../types/api';
import { useAppliedRunId, useDispatch, useOpenPlan, useOptimize, usePlanForDate, useRunDetail } from './api';

/**
 * F6 — rota planlama, F7 — rota maliyet kırılımı.
 *
 * Kargolar tüm aktif filoya dağıtılır; optimize çözüm her zaman aynı kargo
 * kümesindeki naive (geliş sırası, ilk sığan araç) çözümle birlikte gösterilir.
 * Tasarruf gerçek TL maliyet üzerindendir; öncelik cezası para değildir ve
 * maliyete katılmaz, yalnızca bilgi olarak gösterilir.
 */
export function PlanningPage() {
  const [date, setDate] = useState(todayLocal());
  const [algorithm, setAlgorithm] = useState<Algorithm>('ROUTEWISE');
  const [warm, setWarm] = useState(false);
  const [lastResult, setLastResult] = useState<OptimizeResponse | null>(null);
  const [focus, setFocus] = useState<number | null>(null);

  const plan = usePlanForDate(date);
  const open = useOpenPlan();
  const applied = useAppliedRunId(plan.data?.id);
  const optimize = useOptimize(plan.data?.id);
  const dispatch = useDispatch(plan.data?.id, date);
  const run = useRunDetail(lastResult?.runId ?? applied.data);

  const { markers, lines } = useMemo(() => drawing(run.data, focus), [run.data, focus]);
  const error = open.error ?? optimize.error ?? dispatch.error;
  const canDispatch = plan.data?.status === 'TASLAK' && typeof applied.data === 'number' && !optimize.isPending;
  const canWarm = algorithm === 'ROUTEWISE' && typeof applied.data === 'number';

  function runOptimize() {
    optimize.mutate(
      { algorithm, warmStartFromRunId: warm && canWarm ? (applied.data as number) : undefined },
      { onSuccess: (r) => { setLastResult(r); setFocus(null); } },
    );
  }

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Rota planlama</h1>
          <p className="muted">Doğrulanmış adresli bekleyen kargolar, kapasiteye uyarak tüm filoya dağıtılır.</p>
        </div>
        <div className="row">
          <label>
            Tarih
            <input type="date" value={date} onChange={(e) => { setDate(e.target.value); setLastResult(null); setFocus(null); }} />
          </label>
          {plan.data && (
            <label>
              Algoritma
              <select value={algorithm} onChange={(e) => setAlgorithm(e.target.value as Algorithm)}>
                {(['ROUTEWISE', 'SAVINGS', 'GREEDY_NN', 'NAIVE'] as const).map((a) => (
                  <option key={a} value={a}>{algorithmLabel[a]}</option>
                ))}
              </select>
            </label>
          )}
        </div>
      </div>

      <div className="grid-2">
        <section className="card stack" aria-label="Plan">
          {plan.isPending && <div className="empty">Yükleniyor…</div>}
          {plan.data === null && (
            <div className="stack">
              <p>Bu gün için plan yok.</p>
              <button type="button" className="primary" disabled={open.isPending} onClick={() => open.mutate(date)}>
                Planı aç
              </button>
            </div>
          )}
          {plan.data && (
            <>
              <div className="row">
                <button type="button" className="primary" disabled={optimize.isPending || plan.data.status !== 'TASLAK'}
                  onClick={runOptimize}>
                  {optimize.isPending ? 'Hesaplanıyor…' : 'Optimize et'}
                </button>
                {canWarm && (
                  <label className="row" style={{ fontWeight: 400 }}>
                    <input type="checkbox" checked={warm} onChange={(e) => setWarm(e.target.checked)} />
                    Mevcut rotadan devam et
                  </label>
                )}
                {canDispatch && (
                  <button type="button" disabled={dispatch.isPending}
                    onClick={() => {
                      if (confirm('Uygulanan rota kuryelere sevk edilsin mi? Bu işlemden sonra plan yeniden optimize edilemez.')) {
                        dispatch.mutate();
                      }
                    }}>
                    {dispatch.isPending ? 'Sevk ediliyor…' : 'Kuryelere sevk et'}
                  </button>
                )}
                {plan.data.status !== 'TASLAK' && <span className="muted">Plan sevk edildi; yeniden optimize edilemez.</span>}
              </div>
              {dispatch.data && (
                <div role="status" className="alert ok">
                  Sevk edildi:
                  <ul>
                    {dispatch.data.routes.map((r) => (
                      <li key={r.routeId}>{r.stopCount} durak → {r.courierName ?? `kurye #${r.courierId}`}</li>
                    ))}
                  </ul>
                </div>
              )}
              {lastResult && <ResultSummary r={lastResult} />}
            </>
          )}
          {error instanceof ApiError && <div role="alert" className="alert danger">{error.message}</div>}

          {run.data && <RouteList detail={run.data} focus={focus} onFocus={setFocus} />}
          {plan.data && !run.data && !run.isFetching && !lastResult && <p className="muted">Henüz optimizasyon yapılmadı.</p>}
        </section>

        <section className="card stack" aria-label="Harita">
          <MapView markers={markers} lines={lines} height={560} />
          {run.data && !run.data.geometryAvailable && (
            <div className="alert warn">Rota servisine ulaşılamadı; yol çizimi gösterilemiyor. Durak sırası günceldir.</div>
          )}
        </section>
      </div>
    </>
  );
}

/** Haritada araç başına bir renk; bir rota seçiliyse yalnızca o çizilir. */
function drawing(d: RunDetail | undefined, focus: number | null): { markers: MapMarker[]; lines: MapLine[] } {
  if (!d) return { markers: [], lines: [] };
  const markers: MapMarker[] = [{ id: 'depot', lat: d.depot.lat, lon: d.depot.lon, label: 'D', tone: 'depot', title: d.depot.name }];
  const lines: MapLine[] = [];
  d.routes.forEach((route, idx) => {
    if (focus !== null && focus !== route.id) return;
    const color = routeColor(idx);
    for (const s of route.stops) {
      if (s.lat === null || s.lon === null) continue;
      markers.push({ id: s.id, lat: s.lat, lon: s.lon, label: String(s.sequenceNo), color,
        title: `${route.plate ?? ''} · ${s.sequenceNo}. ${s.orderNumber ?? ''} ${s.addressText ?? ''}` });
    }
    if (route.geometry) lines.push({ id: route.id, coordinates: route.geometry.coordinates as LngLat[], color });
  });
  return { markers, lines };
}

function ResultSummary({ r }: { r: OptimizeResponse }) {
  return (
    <div className="stack" aria-label="Optimizasyon sonucu">
      <div className="stats">
        <div className="stat">
          <b>{r.totalCostTry === null ? '—' : formatTry(r.totalCostTry)}</b>
          <span>{algorithmLabel[r.algorithm] ?? r.algorithm} maliyeti</span>
        </div>
        <div className="stat">
          <b>{r.baseline.totalCostTry === null ? '—' : formatTry(r.baseline.totalCostTry)}</b>
          <span>geliş sırasıyla</span>
        </div>
        <div className="stat">
          <b>{r.savingsPct === null ? '—' : `%${formatNumber(r.savingsPct)}`}</b>
          <span>maliyet tasarrufu</span>
        </div>
        <div className="stat"><b>{formatKm(r.totalDistanceM)}</b><span>{r.vehicleCount} araç, toplam yol</span></div>
      </div>
      <p className="muted">
        Mesafe tasarrufu %{r.distanceSavingsPct === null ? '—' : formatNumber(r.distanceSavingsPct)}
        {r.totalFuelL !== null && <> · {formatNumber(r.totalFuelL)} L yakıt</>}
        {r.randomSeed !== null && <> · tohum {r.randomSeed}</>}
      </p>
      {r.excluded.length > 0 && (
        <div className="alert warn">
          {r.excluded.length} kargo rotaya alınmadı:
          <ul>
            {r.excluded.map((e) => (
              <li key={e.shipmentId}>{e.orderNumber ?? `#${e.shipmentId}`} — {excludedReasonLabel[e.reason] ?? e.reason}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

/** F7 — araç başına maliyet kırılımı ve durak sırası. */
function RouteList({ detail, focus, onFocus }: { detail: RunDetail; focus: number | null; onFocus: (id: number | null) => void }) {
  return (
    <div className="stack">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h2 style={{ margin: 0 }}>Rotalar</h2>
        {focus !== null && <button type="button" onClick={() => onFocus(null)}>Tümünü göster</button>}
      </div>
      {detail.routes.map((route, idx) => (
        <details key={route.id} open={focus === route.id} onToggle={(e) => {
            // Açılan rota odaklanır; odaktaki rota kapanınca tümü yeniden gösterilir.
            if (e.currentTarget.open) onFocus(route.id);
            else if (focus === route.id) onFocus(null);
          }}>
          <summary style={{ cursor: 'pointer' }}>
            <span className="row" style={{ display: 'inline-flex' }}>
              <span aria-hidden style={{ width: 12, height: 12, borderRadius: 3, background: routeColor(idx), display: 'inline-block' }} />
              <b>{route.plate ?? `Araç ${route.vehicleId}`}</b>
              <span className="muted">
                {route.stops.length} durak · {formatKm(route.totalDistanceM)} · {formatDuration(route.totalDurationS)}
                {' · '}{formatNumber(route.totalLoadKg)} kg
                {route.totalCostTry !== null && <> · <b>{formatTry(route.totalCostTry)}</b></>}
              </span>
            </span>
          </summary>
          <div className="table-wrap">
            <table>
              <thead><tr><th>#</th><th>Sipariş</th><th>Adres</th><th>Önceki duraktan</th></tr></thead>
              <tbody>
                {route.stops.map((s) => (
                  <tr key={s.id}>
                    <td>{s.sequenceNo}</td>
                    <td>{s.orderNumber}</td>
                    <td>{s.addressText}</td>
                    <td className="muted">{formatKm(s.distanceFromPrevM)} · {formatDuration(s.durationFromPrevS)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      ))}
    </div>
  );
}

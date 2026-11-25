import { useMemo, useState } from 'react';
import { MapView, type MapLine, type MapMarker } from '../../components/map/MapView';
import { formatDuration, formatKm, formatNumber, formatTry, todayLocal } from '../../lib/format';
import { useShipments } from '../shipments/api';
import { usePlanForDate } from '../planning/api';
import {
  useComparePath,
  useCompareTour,
  useVehicles,
  type PathAlgorithm,
  type PathComparison,
  type TourAlgorithm,
  type TourComparison,
} from './api';
import { BarMetricChart } from './BarMetricChart';

export const pathLabel: Record<PathAlgorithm, string> = {
  DIJKSTRA: 'Dijkstra',
  ASTAR: 'A*',
  BFS: 'BFS',
  DFS: 'DFS',
};

export const tourLabel: Record<TourAlgorithm, string> = {
  NAIVE: 'Geliş sırası',
  GREEDY_NN: 'En yakın komşu',
  SAVINGS: 'Clarke-Wright',
  ROUTEWISE: 'RouteWise',
  DFS_EXHAUSTIVE: 'Kesin arama (DFS)',
};

const PATH_COLORS: Record<PathAlgorithm, string> = {
  DIJKSTRA: '#1f5fa8',
  ASTAR: '#15803d',
  BFS: '#c2410c',
  DFS: '#7c3aed',
};

const pct = (v: number | null) => (v === null ? '—' : `%${formatNumber(Math.round(v * 100) / 100)}`);
const ms = (v: number) => `${formatNumber(Math.round(v * 10) / 10)} ms`;

/**
 * F8 — algoritma karşılaştırması. Seviye 1: iki nokta arasında yol bulma
 * (Dijkstra referans). Seviye 2: tek aracın tur sırası (n ≤ 10'da kanıtlı optimum
 * referans). Her sonuç kaydedilir; sayılar analiz ve rapor için birikir.
 */
export function ComparisonPage() {
  return (
    <>
      <div className="page-head">
        <div>
          <h1>Algoritma karşılaştırması</h1>
          <p className="muted">Aynı girdi, farklı algoritmalar: ne kadar iyi ve ne kadar hızlı?</p>
        </div>
      </div>
      <div className="stack">
        <PathSection />
        <TourSection />
      </div>
    </>
  );
}

// --- Seviye 1 ------------------------------------------------------------------

type Point = { lat: number; lon: number };

function PathSection() {
  const [from, setFrom] = useState<Point | null>(null);
  const [to, setTo] = useState<Point | null>(null);
  const [weight, setWeight] = useState<'DISTANCE' | 'DURATION'>('DISTANCE');
  const [repeats, setRepeats] = useState(5);
  const [shown, setShown] = useState<Set<PathAlgorithm>>(new Set(['DIJKSTRA', 'BFS', 'DFS']));
  const compare = useComparePath();
  const result = compare.data;

  // Tıklama sırası: başlangıç, bitiş; üçüncü tıklama yeni bir başlangıçtır.
  function pick(lat: number, lon: number) {
    if (!from || to) {
      setFrom({ lat, lon });
      setTo(null);
    } else {
      setTo({ lat, lon });
    }
    compare.reset();
  }

  const markers = useMemo<MapMarker[]>(() => {
    const out: MapMarker[] = [];
    if (from) out.push({ id: 'from', lat: result?.from.lat ?? from.lat, lon: result?.from.lon ?? from.lon, tone: 'depot', label: 'A', title: 'Başlangıç' });
    if (to) out.push({ id: 'to', lat: result?.to.lat ?? to.lat, lon: result?.to.lon ?? to.lon, tone: 'selected', label: 'B', title: 'Bitiş' });
    return out;
  }, [from, to, result]);

  const lines = useMemo<MapLine[]>(() => (result?.results ?? [])
    .filter((r) => r.found && shown.has(r.algorithm))
    .map((r) => ({ id: r.algorithm, color: PATH_COLORS[r.algorithm], coordinates: r.coordinates })), [result, shown]);

  function toggle(a: PathAlgorithm) {
    setShown((s) => {
      const next = new Set(s);
      if (next.has(a)) next.delete(a); else next.add(a);
      return next;
    });
  }

  return (
    <section className="card stack" aria-label="Seviye 1 — yol bulma">
      <div>
        <h2 style={{ margin: 0 }}>Seviye 1 — yol bulma</h2>
        <p className="muted">
          Haritada önce başlangıcı (A), sonra bitişi (B) seç. Noktalar en yakın yol düğümüne bağlanır;
          Dijkstra kanıtlı en kısa yoldur, diğerleri ona göre ölçülür.
        </p>
      </div>
      <div className="grid-2">
        <div className="stack">
          <div className="row" style={{ flexWrap: 'wrap' }}>
            <label>
              Ağırlık
              <select value={weight} onChange={(e) => setWeight(e.target.value as 'DISTANCE' | 'DURATION')}>
                <option value="DISTANCE">Mesafe (m)</option>
                <option value="DURATION">Süre (sn)</option>
              </select>
            </label>
            <label>
              Tekrar
              <input type="number" min={1} max={20} value={repeats}
                onChange={(e) => setRepeats(Math.min(20, Math.max(1, Number(e.target.value) || 1)))} />
            </label>
            <button type="button" className="primary" disabled={!from || !to || compare.isPending}
              onClick={() => from && to && compare.mutate({ from, to, weight, repeats })}>
              {compare.isPending ? 'Hesaplanıyor…' : 'Karşılaştır'}
            </button>
          </div>
          <p className="muted" aria-live="polite">
            {!from && 'Başlangıç noktası seçilmedi.'}
            {from && !to && 'Şimdi bitiş noktasını seç.'}
            {from && to && !result && 'İki nokta seçildi.'}
            {result && `Düğüm ${result.from.nodeId} → ${result.to.nodeId} · her algoritma ${result.repeats} kez koştu, süre medyanı.`}
          </p>
          {compare.error && <div role="alert" className="alert danger">{compare.error.message}</div>}
          {result && <PathTable result={result} shown={shown} onToggle={toggle} />}
        </div>
        <MapView markers={markers} lines={lines} onPick={pick} height={380} />
      </div>
      {result && (
        <div className="grid-2">
          <BarMetricChart title="Gezilen düğüm" unit="düğüm"
            data={result.results.map((r) => ({ name: pathLabel[r.algorithm], value: r.nodesVisited }))} />
          <BarMetricChart title="Dijkstra'dan sapma" unit="%"
            data={result.results.map((r) => ({ name: pathLabel[r.algorithm], value: r.deviationPct ?? 0 }))} />
        </div>
      )}
    </section>
  );
}

function PathTable({ result, shown, onToggle }: {
  result: PathComparison;
  shown: Set<PathAlgorithm>;
  onToggle: (a: PathAlgorithm) => void;
}) {
  return (
    <div className="table-wrap">
      <table aria-label="Yol bulma sonuçları">
        <thead>
          <tr><th>Haritada</th><th>Algoritma</th><th>Uzunluk</th><th>Süre</th><th>Gezilen düğüm</th><th>Hesap</th><th>Sapma</th></tr>
        </thead>
        <tbody>
          {result.results.map((r) => (
            <tr key={r.algorithm}>
              <td>
                <input type="checkbox" aria-label={`${pathLabel[r.algorithm]} haritada`} checked={shown.has(r.algorithm)}
                  disabled={!r.found} onChange={() => onToggle(r.algorithm)} />
                <span aria-hidden style={{ display: 'inline-block', width: 12, height: 4, marginLeft: 6, background: PATH_COLORS[r.algorithm] }} />
              </td>
              <td>{pathLabel[r.algorithm]}{r.algorithm !== 'DIJKSTRA' && r.samePathAsReference && <span className="muted"> · aynı yol</span>}</td>
              <td>{r.found ? formatKm(r.lengthM) : 'yol yok'}</td>
              <td>{r.found ? formatDuration(r.durationS) : '—'}</td>
              <td>{formatNumber(r.nodesVisited)}</td>
              <td>{ms(r.computationTimeMs)}</td>
              <td>{pct(r.deviationPct)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// --- Seviye 2 ------------------------------------------------------------------

const MAX_EXACT = 10;

function TourSection() {
  const [date, setDate] = useState(todayLocal());
  const [vehicleId, setVehicleId] = useState<number | ''>('');
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [repeats, setRepeats] = useState(3);
  const plan = usePlanForDate(date);
  const vehicles = useVehicles();
  const waiting = useShipments({ status: 'BEKLEMEDE', page: 0 });
  const compare = useCompareTour(plan.data?.id);
  const routable = (waiting.data?.content ?? []).filter((s) => s.address.verificationStatus === 'DOGRULANDI');

  function toggle(id: number) {
    setPicked((p) => {
      const next = new Set(p);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  function run() {
    compare.mutate({
      vehicleId: vehicleId === '' ? undefined : vehicleId,
      shipmentIds: picked.size > 0 ? [...picked] : undefined,
      repeats,
    });
  }

  return (
    <section className="card stack" aria-label="Seviye 2 — tur sırası">
      <div>
        <h2 style={{ margin: 0 }}>Seviye 2 — tur sırası</h2>
        <p className="muted">
          Tek araç, aynı kargolar: her algoritma aynı matris, maliyet ve çalışma günüyle. En fazla {MAX_EXACT} kargoda
          kesin arama gerçek optimumu bulur ve diğerleri ona göre ölçülür. Plan değişmez.
        </p>
      </div>
      <div className="row" style={{ flexWrap: 'wrap' }}>
        <label>
          Plan günü
          <input type="date" value={date} onChange={(e) => { setDate(e.target.value); compare.reset(); }} />
        </label>
        <label>
          Araç
          <select value={vehicleId} onChange={(e) => setVehicleId(e.target.value === '' ? '' : Number(e.target.value))}>
            <option value="">İlk aktif araç</option>
            {(vehicles.data ?? []).map((v) => (
              <option key={v.id} value={v.id}>{v.plate} · {formatNumber(v.capacityKg)} kg</option>
            ))}
          </select>
        </label>
        <label>
          Tekrar
          <input type="number" min={1} max={10} value={repeats}
            onChange={(e) => setRepeats(Math.min(10, Math.max(1, Number(e.target.value) || 1)))} />
        </label>
        <button type="button" className="primary" disabled={!plan.data || compare.isPending} onClick={run}>
          {compare.isPending ? 'Hesaplanıyor…' : 'Karşılaştır'}
        </button>
      </div>
      {plan.data === null && <div className="alert info">Bu gün için plan yok. Rota planlama ekranından planı aç.</div>}

      <details>
        <summary>
          Kargolar: {picked.size === 0 ? 'planın doğrulanmış tüm kargoları' : `${picked.size} kargo seçildi`}
          {picked.size > MAX_EXACT && ` — ${MAX_EXACT}'dan fazla, kesin arama koşmaz`}
        </summary>
        {routable.length === 0 && <p className="muted">Adresi doğrulanmış bekleyen kargo yok.</p>}
        <ul className="stack" style={{ listStyle: 'none', padding: 0 }}>
          {routable.map((s) => (
            <li key={s.id}>
              <label className="row">
                <input type="checkbox" checked={picked.has(s.id)} onChange={() => toggle(s.id)} />
                {s.orderNumber} · {s.address.text ?? '—'} <span className="muted">· {formatNumber(s.weightKg)} kg</span>
              </label>
            </li>
          ))}
        </ul>
      </details>

      {compare.error && <div role="alert" className="alert danger">{compare.error.message}</div>}
      {compare.data && <TourResult result={compare.data} />}
    </section>
  );
}

function TourResult({ result }: { result: TourComparison }) {
  const feasible = result.results.filter((r) => r.feasible);
  return (
    <div className="stack">
      <div role="status" className={`alert ${result.referenceIsOptimal ? 'info' : 'warn'}`}>
        {result.stopCount} durak · {result.repeats} tekrar ·{' '}
        {result.referenceIsOptimal
          ? 'sapma kanıtlı optimuma göre (kesin arama).'
          : `sapma bulunan en iyi çözüme göre (${result.referenceAlgorithm ? tourLabel[result.referenceAlgorithm] : '—'}); ${result.exhaustiveSkipped ?? ''}`}
        {result.unassigned.length > 0 && ` ${result.unassigned.length} kargo rotaya alınamadı.`}
      </div>
      <div className="table-wrap">
        <table aria-label="Tur sırası sonuçları">
          <thead>
            <tr><th>Algoritma</th><th>Maliyet</th><th>Amaç</th><th>Mesafe</th><th>Süre</th><th>Hesap</th><th>Sapma</th></tr>
          </thead>
          <tbody>
            {result.results.map((r) => (
              <tr key={r.algorithm}>
                <td>
                  {tourLabel[r.algorithm]}
                  {r.algorithm === 'ROUTEWISE' && r.objectiveMin !== null && r.objectiveMax !== null && r.objectiveMin !== r.objectiveMax && (
                    <div className="muted">tekrarlarda {formatNumber(r.objectiveMin)}–{formatNumber(r.objectiveMax)}</div>
                  )}
                  {r.explored !== null && <div className="muted">{formatNumber(r.explored)} arama düğümü</div>}
                </td>
                {r.solution ? (
                  <>
                    <td>{r.solution.totalCostTry === null ? '—' : formatTry(r.solution.totalCostTry)}</td>
                    <td>{formatNumber(Math.round(r.solution.objective * 100) / 100)}</td>
                    <td>{formatKm(r.solution.totalDistanceM)}</td>
                    <td>{formatDuration(r.solution.totalDurationS)}</td>
                  </>
                ) : (
                  <td colSpan={4} className="muted">{r.error ?? 'olurlu tur yok'}</td>
                )}
                <td>{ms(r.computationTimeMs)}</td>
                <td>{pct(r.gapPct)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="grid-2">
        <BarMetricChart title={result.referenceIsOptimal ? 'Optimumdan sapma' : 'En iyiden sapma'} unit="%"
          data={feasible.map((r) => ({ name: tourLabel[r.algorithm], value: r.gapPct ?? 0 }))} />
        <BarMetricChart title="Hesap süresi (medyan)" unit="ms"
          data={feasible.map((r) => ({ name: tourLabel[r.algorithm], value: r.computationTimeMs }))} />
      </div>
    </div>
  );
}

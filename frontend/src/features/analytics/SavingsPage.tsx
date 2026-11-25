import { useState } from 'react';
import { Bar, BarChart, CartesianGrid, Legend, Tooltip, XAxis, YAxis } from 'recharts';
import { formatKm, formatNumber, formatTry } from '../../lib/format';
import { useDownloadReport, useSavings, type Bucket, type Format, type Period, type Range } from './api';

const periodLabel: Record<Period, string> = { WEEK: 'Haftalık', MONTH: 'Aylık', YEAR: 'Yıllık' };
const pct = (v: number | null) => (v === null ? '—' : `${v < 0 ? '-' : ''}%${formatNumber(Math.abs(v))}`);

/** Kova etiketi: hafta başlangıç günü, ay ay.yıl, yıl yıl. */
function bucketLabel(b: Bucket, period: Period): string {
  const [y, m, d] = b.start.split('-');
  if (period === 'WEEK') return `${d}.${m}`;
  if (period === 'MONTH') return `${m}.${y}`;
  return y ?? b.start;
}

const shortDate = (iso: string) => iso.split('-').reverse().join('.');

/**
 * F9 — tasarruf raporları. Yalnızca sevk edilmiş planlar: gerçekten sürülen rota,
 * aynı kargoların geliş sırası (naive) çözümüyle karşılaştırılır. Dönem yüzdesi
 * toplamlardan hesaplanır (ağırlıklı); plan yapılmamış dönem listede yer almaz.
 */
export function SavingsPage() {
  const [range, setRange] = useState<Range>({ period: 'MONTH', from: '', to: '' });
  const savings = useSavings(range);
  const download = useDownloadReport();
  const data = savings.data;
  const total = data?.result.total ?? null;

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Tasarruf raporları</h1>
          <p className="muted">Sevk edilen her plan, aynı kargoların geliş sırasıyla gidilseydi ne tutacağıyla karşılaştırılır.</p>
        </div>
        <div className="row" style={{ flexWrap: 'wrap' }}>
          <label>
            Dönem
            <select value={range.period} onChange={(e) => setRange({ ...range, period: e.target.value as Period })}>
              {(['WEEK', 'MONTH', 'YEAR'] as const).map((p) => <option key={p} value={p}>{periodLabel[p]}</option>)}
            </select>
          </label>
          <label>
            Başlangıç
            <input type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} />
          </label>
          <label>
            Bitiş
            <input type="date" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} />
          </label>
        </div>
      </div>

      <div className="stack">
        {savings.isPending && <div className="empty">Yükleniyor…</div>}
        {savings.error && <div role="alert" className="alert danger">{savings.error.message}</div>}

        {data && (
          <section className="card stack" aria-label="Özet">
            <p className="muted">{shortDate(data.from)} – {shortDate(data.to)} · {periodLabel[data.period].toLowerCase()}</p>
            {total === null ? (
              <div className="empty">Bu aralıkta sevk edilmiş plan yok.</div>
            ) : (
              <dl className="row" style={{ flexWrap: 'wrap', gap: 24, margin: 0 }}>
                <Stat label="Tasarruf" value={formatTry(total.savingsTry)} />
                <Stat label="Tasarruf oranı" value={pct(total.savingsPct)} />
                <Stat label="Mesafe tasarrufu" value={pct(total.distanceSavingsPct)} />
                <Stat label="Sevk edilen plan" value={formatNumber(total.plans)} />
                <Stat label="Teslimat" value={formatNumber(total.shipments)} />
              </dl>
            )}
            <div className="row">
              {(['XLSX', 'PDF'] as const).map((f: Format) => (
                <button key={f} type="button" disabled={download.isPending}
                  onClick={() => download.mutate({ range, format: f })}>
                  {f === 'XLSX' ? 'Excel indir' : 'PDF indir'}
                </button>
              ))}
              {download.isPending && <span className="muted" role="status">Rapor hazırlanıyor…</span>}
              {download.isSuccess && !download.isPending && <span className="muted" role="status">{download.data} indirildi.</span>}
            </div>
            {download.error && <div role="alert" className="alert danger">Rapor indirilemedi: {download.error.message}</div>}
          </section>
        )}

        {data && data.result.buckets.length > 0 && (
          <>
            <figure className="card" style={{ margin: 0 }} aria-label="Dönemsel maliyet">
              <figcaption><b>{periodLabel[data.period]} maliyet: geliş sırası ve uygulanan rota</b></figcaption>
              <BarChart responsive style={{ width: '100%', height: 260 }} margin={{ top: 12, right: 8, bottom: 0, left: 0 }}
                data={data.result.buckets.map((b) => ({
                  name: bucketLabel(b, data.period), naive: b.baselineCostTry, applied: b.optimizedCostTry,
                }))}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="name" tick={{ fontSize: 12 }} />
                <YAxis width="auto" tick={{ fontSize: 12 }} />
                <Tooltip formatter={(v) => formatTry(Number(v))} />
                <Legend />
                <Bar dataKey="naive" name="Geliş sırası (naive)" fill="#94a3b8" radius={[4, 4, 0, 0]} />
                <Bar dataKey="applied" name="Uygulanan rota" fill="#3b6fb5" radius={[4, 4, 0, 0]} />
              </BarChart>
            </figure>
            <div className="card table-wrap">
              <table aria-label="Dönemsel tasarruf">
                <thead>
                  <tr><th>Dönem</th><th>Plan</th><th>Teslimat</th><th>Geliş sırası</th><th>Uygulanan</th><th>Tasarruf</th><th>Oran</th><th>Mesafe</th></tr>
                </thead>
                <tbody>
                  {data.result.buckets.map((b) => (
                    <tr key={b.start}>
                      <td>{shortDate(b.start)} – {shortDate(b.end)}</td>
                      <td>{b.plans}</td>
                      <td>{b.shipments}</td>
                      <td>{formatTry(b.baselineCostTry)}</td>
                      <td>{formatTry(b.optimizedCostTry)}</td>
                      <td>{formatTry(b.savingsTry)}</td>
                      <td>{pct(b.savingsPct)}</td>
                      <td>{formatKm(b.optimizedDistanceM)} <span className="muted">({pct(b.distanceSavingsPct)})</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>
    </>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="muted">{label}</dt>
      <dd style={{ margin: 0, fontSize: 22, fontWeight: 600 }}>{value}</dd>
    </div>
  );
}

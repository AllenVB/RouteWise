import { useState } from 'react';
import { formatDateTime, formatNumber } from '../../lib/format';
import { useExperiments, useStartExperiment, type Experiment } from './api';
import { BarMetricChart } from './BarMetricChart';
import { tourLabel } from './labels';

// formatNumber tek ondalıklıdır: sapmalar (%0,73) ve p değerleri (0,0123) için yetmez.
const two = new Intl.NumberFormat('tr-TR', { maximumFractionDigits: 2 });
const four = new Intl.NumberFormat('tr-TR', { maximumFractionDigits: 4 });
const pct = (v: number) => `${v < 0 ? '-' : ''}%${two.format(Math.abs(v))}`;
const pValue = (v: number) => (v < 0.0001 ? '< 0,0001' : four.format(v));

/**
 * Deney — tohumdan N sentetik senaryo (gerçek yol grafı), her birinde Seviye 2;
 * sonunda RouteWise ile her sezgisel arasında tek yönlü Wilcoxon ve eşleştirilmiş t.
 * Arka planda koşar: ekran ilerlemeyi izler, bittiğinde iddia cümlesini gösterir.
 */
export function ExperimentSection() {
  // Alanlar ham metin tutar: her tuşta sınıra kırpmak "40" yazmayı imkânsız kılar ("4" → 5 → "50").
  const [scenarios, setScenarios] = useState('30');
  const [stopCount, setStopCount] = useState('10');
  const [radiusM, setRadiusM] = useState('3000');
  const [repeats, setRepeats] = useState('3');
  const [seed, setSeed] = useState('');
  const [invalid, setInvalid] = useState<string | null>(null);
  const list = useExperiments();
  const start = useStartExperiment();
  const latest = list.data?.[0];
  const running = latest?.status === 'QUEUED' || latest?.status === 'RUNNING';

  function run() {
    const fields = [
      ['Senaryo', scenarios, 5, 100], ['Durak', stopCount, 3, 10], ['Yarıçap', radiusM, 500, 15000], ['Tekrar', repeats, 1, 5],
    ] as const;
    for (const [name, raw, min, max] of fields) {
      const v = Number(raw);
      if (raw.trim() === '' || !Number.isInteger(v) || v < min || v > max) {
        setInvalid(`${name} ${min} ile ${formatNumber(max)} arasında bir tam sayı olmalı.`);
        return;
      }
    }
    setInvalid(null);
    start.mutate({
      scenarios: Number(scenarios), stopCount: Number(stopCount), radiusM: Number(radiusM), repeats: Number(repeats),
      seed: seed.trim() === '' ? undefined : Number(seed),
    });
  }

  return (
    <section className="card stack" aria-label="Deney — anlamlılık testi">
      <div>
        <h2 style={{ margin: 0 }}>Deney — anlamlılık testi</h2>
        <p className="muted">
          Bandırma yol grafından tohumla üretilen senaryolarda tüm tur algoritmaları koşar; RouteWise her birine karşı
          eşleştirilmiş testle sınanır. Aynı tohum aynı deneyi verir.
        </p>
      </div>
      <div className="row" style={{ flexWrap: 'wrap' }}>
        <NumberField label="Senaryo" value={scenarios} min={5} max={100} onChange={setScenarios} />
        <NumberField label="Durak" value={stopCount} min={3} max={10} onChange={setStopCount} />
        <NumberField label="Yarıçap (m)" value={radiusM} min={500} max={15000} step={500} onChange={setRadiusM} />
        <NumberField label="Tekrar" value={repeats} min={1} max={5} onChange={setRepeats} />
        <label>
          Tohum
          <input inputMode="numeric" placeholder="rastgele" value={seed}
            onChange={(e) => setSeed(e.target.value.replace(/\D/g, ''))} />
        </label>
        <button type="button" className="primary" disabled={running || start.isPending} onClick={run}>
          {start.isPending ? 'Başlatılıyor…' : 'Deneyi başlat'}
        </button>
      </div>
      {invalid && <div role="alert" className="alert danger">{invalid}</div>}
      {start.error && <div role="alert" className="alert danger">{start.error.message}</div>}
      {list.error && <div role="alert" className="alert danger">Deneyler yüklenemedi: {list.error.message}</div>}
      {latest && <ExperimentResult e={latest} />}
    </section>
  );
}

function NumberField({ label, value, min, max, step = 1, onChange }: {
  label: string; value: string; min: number; max: number; step?: number; onChange: (v: string) => void;
}) {
  return (
    <label>
      {label}
      <input type="number" min={min} max={max} step={step} value={value} onChange={(e) => onChange(e.target.value)} />
    </label>
  );
}

function ExperimentResult({ e }: { e: Experiment }) {
  const done = e.completed + e.failed;
  const head = `Deney ${e.id} · ${e.scenarioCount} senaryo · ${e.stopCount} durak · ${formatNumber(e.radiusM)} m · tohum ${e.baseSeed}`;
  if (e.status === 'QUEUED' || e.status === 'RUNNING') {
    return (
      <div className="stack">
        <b>{head}</b>
        <progress max={e.scenarioCount} value={done} aria-label="Deney ilerlemesi" />
        <p className="muted" role="status">
          {e.status === 'QUEUED' ? 'Sırada…' : `${done}/${e.scenarioCount} senaryo koştu`}
          {e.failed > 0 && ` · ${e.failed} senaryo çözülemedi`}
        </p>
      </div>
    );
  }
  if (e.status === 'FAILED') {
    return (
      <div className="stack">
        <b>{head}</b>
        <div role="alert" className="alert danger">
          Deney yarıda kaldı ({done}/{e.scenarioCount}): {e.lastError ?? 'sebep kaydedilmedi'}
        </div>
      </div>
    );
  }
  const s = e.summary;
  return (
    <div className="stack">
      <b>{head}</b>
      <p className="muted">
        {e.finishedAt && `Bitti: ${formatDateTime(e.finishedAt)}`}
        {e.failed > 0 && ` · ${e.failed} senaryo çözülemedi ve atlandı`}
      </p>
      {s && (
        <>
          <div role="status" className="alert info">{s.claim}</div>
          <div className="table-wrap">
            <table aria-label="Optimumdan sapma">
              <thead>
                <tr><th>Algoritma</th><th>Sapma medyan</th><th>Ortalama</th><th>En kötü</th><th>Optimum</th><th>Süre medyan</th></tr>
              </thead>
              <tbody>
                {s.algorithms.map((a) => (
                  <tr key={a.algorithm}>
                    <td>{tourLabel[a.algorithm]}</td>
                    <td>{pct(a.medianGapPct)}</td>
                    <td>{pct(a.meanGapPct)}</td>
                    <td>{pct(a.maxGapPct)}</td>
                    <td>{a.optimalCount}/{a.scenarios}</td>
                    <td>{a.medianTimeMs < 0.1 ? '< 0,1' : formatNumber(Math.round(a.medianTimeMs * 10) / 10)} ms</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {s.tests.length > 0 && (
            <div className="table-wrap">
              <table aria-label="Anlamlılık testleri">
                <thead>
                  <tr><th>RouteWise karşısında</th><th>n</th><th>Daha ucuz / eşit / pahalı</th><th>Ort. fark</th><th>Wilcoxon p</th><th>t p</th><th>Sonuç</th></tr>
                </thead>
                <tbody>
                  {s.tests.map((t) => (
                    <tr key={t.reference}>
                      <td>{tourLabel[t.reference]}</td>
                      <td>{t.n}</td>
                      <td>{t.candidateBetter} / {t.ties} / {t.candidateWorse}</td>
                      <td>{pct(t.meanDiffPct)}</td>
                      <td>{pValue(t.wilcoxonP)}</td>
                      <td>{pValue(t.tP)}</td>
                      <td>{t.significant ? `anlamlı (α = ${formatNumber(s.alpha)})` : 'anlamlı değil'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <BarMetricChart title="Optimumdan sapma (ortalama)" unit="%"
            data={s.algorithms.map((a) => ({ name: tourLabel[a.algorithm], value: a.meanGapPct }))} />
        </>
      )}
    </div>
  );
}

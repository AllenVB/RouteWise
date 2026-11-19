import { formatClock, formatTry } from '../../lib/format';
import { useDecideSuggestion, usePostponeSuggestions, type PostponeSuggestion } from './api';

/**
 * Maliyet artışı eşiği — onaylanan adres değişikliği kuryenin bugünkü turunu
 * eşikten fazla pahalılaştırdıysa burada öneri olarak görünür. Karar firmanındır:
 * sistem kendiliğinden ertelemez. Öneri yoksa hiçbir şey çizilmez.
 */
export function PostponeSuggestions() {
  const list = usePostponeSuggestions();
  const decide = useDecideSuggestion();

  if (!list.data || list.data.length === 0) return null;

  return (
    <section className="card stack" aria-label="Erteleme önerileri">
      <div>
        <h2 style={{ margin: 0 }}>Erteleme önerileri</h2>
        <p className="muted">
          Bu adres değişiklikleri kuryenin bugünkü turunu belirgin biçimde uzatıyor. Yarına ertelersen alıcıya
          bildirilir ve rota yeniden hesaplanır.
        </p>
      </div>
      {decide.error && <div role="alert" className="alert danger">{decide.error.message}</div>}
      {list.data.map((s) => (
        <SuggestionRow key={s.id} s={s} busy={decide.isPending}
          onDecide={(decision) => decide.mutate({ id: s.id, decision })} />
      ))}
    </section>
  );
}

function SuggestionRow({ s, busy, onDecide }: {
  s: PostponeSuggestion;
  busy: boolean;
  onDecide: (decision: 'postpone' | 'keep') => void;
}) {
  const pct = s.extraRatio === null ? null : Math.round(s.extraRatio * 100);
  return (
    <div className="row" role="group" aria-label={`Öneri ${s.orderNumber ?? s.shipmentId}`}
      style={{ justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
      <div>
        <b>{s.orderNumber ?? `Kargo ${s.shipmentId}`}</b> · {s.addressText ?? '—'}
        <div className="muted">
          {s.vehiclePlate ?? 'Araç'} · kalan tur {formatTry(s.costWithoutTry)} → {formatTry(s.costWithTry)}
          {' '}(<b>+{formatTry(s.extraCostTry)}{pct === null ? '' : `, %${pct}`}</b>) · {formatClock(s.createdAt)}
        </div>
      </div>
      <div className="row">
        <button type="button" className="primary" disabled={busy} onClick={() => onDecide('postpone')}>
          Yarına ertele
        </button>
        <button type="button" disabled={busy} onClick={() => onDecide('keep')}>
          Bugün kalsın
        </button>
      </div>
    </div>
  );
}

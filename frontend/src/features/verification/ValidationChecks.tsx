import type { ValidationResult } from '../../types/api';
import { formatNumber } from '../../lib/format';

/** Dört otomatik katmanın sonucu — F5 ve A4 aynı gösterimi kullanır. */
export function ValidationChecks({ result }: { result: ValidationResult }) {
  return (
    <div className="stack">
      {result.blockers.length > 0 && (
        <div role="alert" className="alert danger">
          Bu nokta kabul edilemez:
          <ul>{result.blockers.map((b) => <li key={b}>{b}</li>)}</ul>
        </div>
      )}
      {result.warnings.length > 0 && (
        <div className="alert warn">
          <ul>{result.warnings.map((w) => <li key={w}>{w}</li>)}</ul>
        </div>
      )}
      {result.acceptable && result.warnings.length === 0 && <div className="alert ok">Otomatik kontrollerin tümü geçti.</div>}
      <div className="stats">
        <div className="stat"><b>{result.withinServiceArea ? 'Evet' : 'Hayır'}</b><span>Bandırma sınırı içinde</span></div>
        <div className="stat">
          <b>{result.snapDistanceM === null ? '—' : `${formatNumber(result.snapDistanceM)} m`}</b>
          <span>En yakın yola uzaklık</span>
        </div>
        <div className="stat"><b>{result.reverseGeocoded ?? '—'}</b><span>En yakın sokak</span></div>
        <div className="stat">
          <b>{result.distanceToNearestM === null ? '—' : `${formatNumber(result.distanceToNearestM / 1000)} km`}</b>
          <span>En yakın onaylı adres</span>
        </div>
      </div>
    </div>
  );
}

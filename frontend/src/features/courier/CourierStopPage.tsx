import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { Badge } from '../../components/ui/Badge';
import { ApiError } from '../../lib/apiClient';
import { formatClock, stopStatusLabel } from '../../lib/format';
import { useCloseStop, useStop } from './api';

/**
 * K3 — durak detayı. "Ulaşılamadı" sebepsiz gönderilemez: firma neden teslim
 * edilemediğini bilmeli, yoksa kargo ertesi gün aynı sebeple yine döner.
 */
export function CourierStopPage() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const stop = useStop(id);
  const close = useCloseStop(id);
  const [failing, setFailing] = useState(false);
  const [reason, setReason] = useState('');

  if (stop.isPending) return <div className="empty">Yükleniyor…</div>;
  if (stop.error) {
    return (
      <div className="stack">
        <div role="alert" className="alert danger">
          {stop.error instanceof ApiError && stop.error.status === 404 ? 'Durak bulunamadı.' : stop.error.message}
        </div>
        <Link to="/kurye">← Rotaya dön</Link>
      </div>
    );
  }

  const s = stop.data;
  const open = s.status === 'BEKLEMEDE';
  const back = () => navigate('/kurye');

  return (
    <div className="stack" style={{ maxWidth: 560 }}>
      <Link to="/kurye">← Rotaya dön</Link>
      <div className="card stack">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <span className="muted">{s.sequenceNo}. durak · {s.orderNumber}</span>
          <Badge {...(stopStatusLabel[s.status] ?? { text: s.status, tone: '' })} />
        </div>
        <h1>{s.addressText}</h1>
        <p>{s.recipientName ?? 'Alıcı bilgisi yok'}</p>
        {s.recipientPhone && <a href={`tel:${s.recipientPhone}`} className="button">Alıcıyı ara · {s.recipientPhone}</a>}
        {s.eta && <p className="muted">Planlanan varış {formatClock(s.eta)}</p>}
        {s.actualArrival && <p className="muted">Kapanış {formatClock(s.actualArrival)}</p>}
        {s.failureReason && <div className="alert warn">Sebep: {s.failureReason}</div>}
      </div>

      {close.error && (
        <div role="alert" className="alert danger">
          {close.error instanceof ApiError ? close.error.message : 'İşlem kaydedilemedi.'}
        </div>
      )}

      {open && !failing && (
        <div className="stack">
          <button type="button" className="primary" style={{ padding: 14, fontSize: 16 }} disabled={close.isPending}
            onClick={() => close.mutate({ kind: 'deliver' }, { onSuccess: back })}>
            Teslim edildi
          </button>
          <button type="button" className="danger" style={{ padding: 14 }} onClick={() => setFailing(true)}>
            Ulaşılamadı
          </button>
        </div>
      )}

      {open && failing && (
        <form className="card stack" onSubmit={(e) => {
          e.preventDefault();
          close.mutate({ kind: 'fail', reason: reason.trim() }, { onSuccess: back });
        }}>
          <label>
            Neden teslim edilemedi?
            <textarea rows={3} maxLength={300} value={reason} placeholder="Ör. adreste kimse yok"
              onChange={(e) => setReason(e.target.value)} />
          </label>
          <div className="row">
            <button type="submit" className="danger" disabled={!reason.trim() || close.isPending}>Ulaşılamadı olarak kaydet</button>
            <button type="button" onClick={() => setFailing(false)}>Vazgeç</button>
          </div>
        </form>
      )}
    </div>
  );
}

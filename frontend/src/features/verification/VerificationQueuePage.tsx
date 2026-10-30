import { useMemo, useState } from 'react';
import { MapView, type MapMarker } from '../../components/map/MapView';
import { Badge } from '../../components/ui/Badge';
import { ApiError } from '../../lib/apiClient';
import { formatDateTime, formatNumber, verificationLabel } from '../../lib/format';
import type { VerificationStatus } from '../../types/api';
import { useAddressQueue, useRejectAddress, useValidatePoint, useVerifyAddress } from './api';
import { ValidationChecks } from './ValidationChecks';

/** F5 — adres doğrulama kuyruğu; beşinci katman olan insan onayı burada verilir. */
export function VerificationQueuePage() {
  const [status, setStatus] = useState<VerificationStatus>('DOGRULAMA_BEKLIYOR');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [reason, setReason] = useState('');
  const queue = useAddressQueue(status);
  const verify = useVerifyAddress();
  const reject = useRejectAddress();

  const selected = queue.data?.find((a) => a.id === selectedId) ?? null;
  const checks = useValidatePoint(selected?.lat ?? null, selected?.lon ?? null, selected?.id);

  const markers = useMemo<MapMarker[]>(
    () => (queue.data ?? []).map((a) => ({
      id: a.id, lat: a.lat, lon: a.lon, tone: a.id === selectedId ? 'selected' : 'pending', title: a.addressText,
    })),
    [queue.data, selectedId],
  );

  const actionError = verify.error ?? reject.error;
  const done = () => {
    setSelectedId(null);
    setReason('');
  };

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Adres doğrulama</h1>
          <p className="muted">Doğrulanmamış adres rotaya giremez. Konumu haritada kontrol et, sonra onayla ya da reddet.</p>
        </div>
        <label>
          Durum
          <select value={status} onChange={(e) => { setStatus(e.target.value as VerificationStatus); done(); }}>
            <option value="DOGRULAMA_BEKLIYOR">Bekleyenler</option>
            <option value="REDDEDILDI">Reddedilenler</option>
            <option value="DOGRULANDI">Onaylananlar</option>
          </select>
        </label>
      </div>

      <div className="grid-2">
        <section className="card stack" aria-label="Kuyruk">
          {queue.isPending && <div className="empty">Yükleniyor…</div>}
          {queue.error && <div role="alert" className="alert danger">Kuyruk yüklenemedi: {queue.error.message}</div>}
          {queue.data?.length === 0 && <div className="empty">Bu durumda adres yok.</div>}
          {queue.data && queue.data.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead><tr><th>Alıcı</th><th>Adres</th><th>Yola uzaklık</th><th>Geliş</th></tr></thead>
                <tbody>
                  {queue.data.map((a) => (
                    <tr
                      key={a.id}
                      className={`selectable ${a.id === selectedId ? 'selected' : ''}`}
                      onClick={() => setSelectedId(a.id)}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setSelectedId(a.id); }}
                      tabIndex={0}
                      aria-selected={a.id === selectedId}
                    >
                      <td>{a.recipientName ?? '—'}</td>
                      <td>{a.addressText}</td>
                      <td>{a.snapDistanceM === null ? '—' : `${formatNumber(a.snapDistanceM)} m`}</td>
                      <td className="muted">{formatDateTime(a.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="card stack" aria-label="Detay">
          <MapView markers={markers} height={340} />
          {!selected && <p className="muted">Ayrıntı için listeden bir adres seç.</p>}
          {selected && (
            <>
              <div className="row">
                <h2 style={{ margin: 0 }}>{selected.addressText}</h2>
                <Badge {...verificationLabel[selected.verificationStatus]} />
              </div>
              {selected.rejectionReason && <div className="alert danger">Ret gerekçesi: {selected.rejectionReason}</div>}
              {checks.data && <ValidationChecks result={checks.data} />}
              {actionError instanceof ApiError && <div role="alert" className="alert danger">{actionError.message}</div>}
              {selected.verificationStatus === 'DOGRULAMA_BEKLIYOR' && (
                <div className="stack">
                  <button type="button" className="primary" disabled={verify.isPending}
                    onClick={() => verify.mutate(selected.id, { onSuccess: done })}>
                    Onayla
                  </button>
                  <label>
                    Ret gerekçesi
                    <input value={reason} maxLength={300} placeholder="Ör. nokta yanlış mahallede"
                      onChange={(e) => setReason(e.target.value)} />
                  </label>
                  <button type="button" className="danger" disabled={!reason.trim() || reject.isPending}
                    onClick={() => reject.mutate({ id: selected.id, reason: reason.trim() }, { onSuccess: done })}>
                    Reddet
                  </button>
                </div>
              )}
            </>
          )}
        </section>
      </div>
    </>
  );
}

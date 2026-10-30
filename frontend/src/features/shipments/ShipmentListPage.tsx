import { useState } from 'react';
import { Link } from 'react-router';
import { Badge } from '../../components/ui/Badge';
import { ApiError } from '../../lib/apiClient';
import { formatDateTime, formatNumber, shipmentLabel, verificationLabel } from '../../lib/format';
import { shipmentStatusSchema, type ShipmentStatus } from '../../types/api';
import { useDeleteShipment, useShipments } from './api';

/** F3 — kargo listesi. */
export function ShipmentListPage() {
  const [status, setStatus] = useState<ShipmentStatus | ''>('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const list = useShipments({ status: status || undefined, q, page });
  const remove = useDeleteShipment();

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Kargolar</h1>
          <p className="muted">Sipariş numaraları sistem tarafından verilir.</p>
        </div>
        <Link to="/firma/kargolar/yeni" className="button primary">Yeni kargo</Link>
      </div>

      <div className="card stack">
        <div className="row">
          <label style={{ flex: '1 1 220px' }}>
            Ara
            <input
              placeholder="Sipariş no, alıcı, adres"
              value={q}
              onChange={(e) => {
                setQ(e.target.value);
                setPage(0);
              }}
            />
          </label>
          <label>
            Durum
            <select
              value={status}
              onChange={(e) => {
                setStatus(e.target.value as ShipmentStatus | '');
                setPage(0);
              }}
            >
              <option value="">Tümü</option>
              {shipmentStatusSchema.options.map((s) => (
                <option key={s} value={s}>{shipmentLabel[s].text}</option>
              ))}
            </select>
          </label>
        </div>

        {remove.error instanceof ApiError && <div role="alert" className="alert danger">{remove.error.message}</div>}
        {list.isPending && <div className="empty">Yükleniyor…</div>}
        {list.error && <div role="alert" className="alert danger">Kargolar yüklenemedi: {list.error.message}</div>}
        {list.data && list.data.content.length === 0 && (
          <div className="empty">{q || status ? 'Filtreye uyan kargo yok.' : 'Henüz kargo yok. İlk kargoyu oluştur.'}</div>
        )}
        {list.data && list.data.content.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Sipariş no</th><th>Alıcı</th><th>Adres</th><th>Ağırlık</th><th>Öncelik</th><th>Durum</th><th>Oluşturma</th><th />
                </tr>
              </thead>
              <tbody>
                {list.data.content.map((s) => {
                  const v = s.address.verificationStatus;
                  const deletable = s.status === 'BEKLEMEDE' || s.status === 'ERTELENDI';
                  return (
                    <tr key={s.id}>
                      <td><b>{s.orderNumber}</b></td>
                      <td>{s.recipient.fullName ?? '—'}</td>
                      <td>
                        {s.address.text ?? '—'}{' '}
                        {v && v !== 'DOGRULANDI' && <Badge {...verificationLabel[v]} />}
                      </td>
                      <td>{formatNumber(s.weightKg)} kg</td>
                      <td>{s.priority}</td>
                      <td><Badge {...shipmentLabel[s.status]} /></td>
                      <td className="muted">{formatDateTime(s.createdAt)}</td>
                      <td>
                        {deletable && (
                          <button
                            type="button"
                            className="danger"
                            disabled={remove.isPending}
                            onClick={() => {
                              if (confirm(`${s.orderNumber} silinsin mi?`)) remove.mutate(s.id);
                            }}
                          >
                            Sil
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {list.data && list.data.totalPages > 1 && (
          <div className="row">
            <button type="button" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Önceki</button>
            <span className="muted">Sayfa {page + 1} / {list.data.totalPages} · {list.data.totalElements} kargo</span>
            <button type="button" disabled={page + 1 >= list.data.totalPages} onClick={() => setPage((p) => p + 1)}>Sonraki</button>
          </div>
        )}
      </div>
    </>
  );
}

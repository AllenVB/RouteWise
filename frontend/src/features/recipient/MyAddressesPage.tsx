import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { MapView, type MapMarker } from '../../components/map/MapView';
import { Badge } from '../../components/ui/Badge';
import { api, ApiError } from '../../lib/apiClient';
import { verificationLabel } from '../../lib/format';
import { recipientAddressSchema } from '../../types/api';
import { useValidatePoint } from '../verification/api';
import { ValidationChecks } from '../verification/ValidationChecks';

/**
 * A4 — adreslerim. Haritaya tıklanan nokta kaydetmeden önce sınanır; kabul
 * edilmeyen nokta kaydedilemez. Taşınan adres firma onayına geri düşer.
 */
export function MyAddressesPage() {
  const qc = useQueryClient();
  const [editingId, setEditingId] = useState<number | null>(null);
  const [point, setPoint] = useState<{ lat: number; lon: number } | null>(null);
  const [text, setText] = useState('');

  const list = useQuery({
    queryKey: ['me', 'addresses'],
    queryFn: () => api('/me/addresses', { schema: z.array(recipientAddressSchema) }),
  });
  const check = useValidatePoint(point?.lat ?? null, point?.lon ?? null, editingId ?? undefined);

  const save = useMutation({
    mutationFn: () => {
      const body = { addressText: text.trim(), location: point };
      return editingId === null
        ? api('/me/addresses', { method: 'POST', body, schema: recipientAddressSchema })
        : api(`/me/addresses/${editingId}`, { method: 'PUT', body, schema: recipientAddressSchema });
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['me', 'addresses'] });
      reset();
    },
  });

  function reset() {
    setEditingId(null);
    setPoint(null);
    setText('');
  }

  const markers = useMemo<MapMarker[]>(() => {
    const saved: MapMarker[] = (list.data ?? [])
      .filter((a) => a.id !== editingId)
      .map((a) => ({ id: a.id, lat: a.lat, lon: a.lon, tone: a.usableForRouting ? 'stop' : 'pending', title: a.addressText }));
    return point ? [...saved, { id: 'new', lat: point.lat, lon: point.lon, tone: 'selected' }] : saved;
  }, [list.data, editingId, point]);

  const canSave = !!point && !!text.trim() && check.data?.acceptable === true && !save.isPending;

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Adreslerim</h1>
          <p className="muted">Teslimat noktanı haritaya tıklayarak işaretle. Değişen adres kargo firmasının onayına gider.</p>
        </div>
      </div>
      <div className="grid-2">
        <section className="card stack" aria-label="Adres listesi">
          {list.data?.length === 0 && <div className="empty">Kayıtlı adresin yok. Haritada bir nokta seçerek ekle.</div>}
          {list.data?.map((a) => (
            <div key={a.id} className="row" style={{ justifyContent: 'space-between' }}>
              <span>{a.addressText} <Badge {...verificationLabel[a.verificationStatus]} /></span>
              <button type="button" onClick={() => { setEditingId(a.id); setText(a.addressText); setPoint({ lat: a.lat, lon: a.lon }); }}>
                Taşı
              </button>
            </div>
          ))}
          <hr style={{ border: 0, borderTop: '1px solid var(--border)', width: '100%' }} />
          <h2>{editingId === null ? 'Yeni adres' : 'Adresi taşı'}</h2>
          <label>
            Adres tarifi
            <input value={text} maxLength={500} placeholder="Mahalle, sokak, no" onChange={(e) => setText(e.target.value)} />
          </label>
          {!point && <p className="muted">Haritada bir nokta seç.</p>}
          {check.isFetching && <p className="muted">Konum kontrol ediliyor…</p>}
          {check.data && <ValidationChecks result={check.data} />}
          {save.error && (
            <div role="alert" className="alert danger">
              {save.error instanceof ApiError ? save.error.message : 'Adres kaydedilemedi.'}
            </div>
          )}
          <div className="row">
            <button type="button" className="primary" disabled={!canSave} onClick={() => save.mutate()}>Kaydet</button>
            {(point || editingId !== null) && <button type="button" onClick={reset}>Vazgeç</button>}
          </div>
        </section>
        <section className="card" aria-label="Harita">
          <MapView markers={markers} height={520} fit={false} onPick={(lat, lon) => setPoint({ lat, lon })} />
        </section>
      </div>
    </>
  );
}

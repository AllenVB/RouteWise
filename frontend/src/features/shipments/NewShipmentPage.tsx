import { useDeferredValue, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate } from 'react-router';
import { z } from 'zod';
import { Badge } from '../../components/ui/Badge';
import { ApiError } from '../../lib/apiClient';
import { verificationLabel } from '../../lib/format';
import { useCreateShipment, useRecipientAddresses, useRecipientSearch } from './api';

/**
 * F4 — yeni kargo. Sipariş numarası sunucuda üretilir; formda alanı yoktur.
 *
 * Doğrulanmamış adres seçilemez. Kural sunucudadır (`usableForRouting`);
 * istemci onu yeniden yazmaz, yalnızca gösterir.
 */

// Sınırlar backend'deki ShipmentDtos.CreateRequest ile aynı.
export const shipmentFormSchema = z.object({
  recipientId: z.number({ required_error: 'Alıcı seçilmeli.', invalid_type_error: 'Alıcı seçilmeli.' }),
  addressId: z.number({ required_error: 'Teslimat adresi seçilmeli.', invalid_type_error: 'Teslimat adresi seçilmeli.' }),
  weightKg: z.number({ invalid_type_error: 'Ağırlık gerekli.' }).min(0.01, 'Ağırlık sıfırdan büyük olmalı.'),
  priority: z.number({ invalid_type_error: 'Öncelik gerekli.' }).int().min(0, 'Öncelik 0 ile 10 arasında olmalı.').max(10, 'Öncelik 0 ile 10 arasında olmalı.'),
  serviceTimeMin: z.number({ invalid_type_error: 'Teslim süresi gerekli.' }).int().min(0, 'Teslim süresi negatif olamaz.'),
});
export type ShipmentForm = z.infer<typeof shipmentFormSchema>;

export function NewShipmentPage() {
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const search = useRecipientSearch(useDeferredValue(q));
  const create = useCreateShipment();

  const form = useForm<ShipmentForm>({
    resolver: zodResolver(shipmentFormSchema),
    defaultValues: { priority: 0, serviceTimeMin: 5 },
  });
  const recipientId = form.watch('recipientId') ?? null;
  const addresses = useRecipientAddresses(recipientId);
  const { errors } = form.formState;

  function onSubmit(values: ShipmentForm) {
    create.mutate(values, {
      onSuccess: () => navigate('/firma/kargolar'),
      onError: (e) => {
        // Sunucunun alan hatalarını ilgili alanın altına yerleştir.
        if (e instanceof ApiError) {
          for (const fe of e.fieldErrors) {
            if (fe.field in shipmentFormSchema.shape) {
              form.setError(fe.field as keyof ShipmentForm, { message: fe.message });
            }
          }
        }
      },
    });
  }

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Yeni kargo</h1>
          <p className="muted">Yalnızca doğrulanmış adreslere kargo oluşturulabilir.</p>
        </div>
      </div>

      <form className="grid-2" onSubmit={form.handleSubmit(onSubmit)} noValidate>
        <section className="card stack" aria-label="Alıcı">
          <h2>1. Alıcı</h2>
          <label>
            Alıcı ara
            <input placeholder="Ad, e-posta veya telefon" value={q} onChange={(e) => setQ(e.target.value)} />
          </label>
          {search.data?.content.length === 0 && <p className="muted">Eşleşen alıcı yok.</p>}
          <div role="radiogroup" aria-label="Alıcılar" className="stack">
            {search.data?.content.map((r) => (
              <label key={r.id} className="row" style={{ fontWeight: 400 }}>
                <input
                  type="radio"
                  name="recipient"
                  checked={recipientId === r.id}
                  onChange={() => {
                    form.setValue('recipientId', r.id, { shouldValidate: true });
                    form.resetField('addressId');
                  }}
                />
                <span><b>{r.fullName}</b> <span className="muted">{r.email}</span></span>
              </label>
            ))}
          </div>
          {errors.recipientId && <span className="field-error">{errors.recipientId.message}</span>}
        </section>

        <section className="card stack" aria-label="Teslimat">
          <h2>2. Adres ve paket</h2>
          {recipientId === null && <p className="muted">Önce alıcı seç.</p>}
          {addresses.data?.length === 0 && <div className="alert warn">Bu alıcının kayıtlı adresi yok.</div>}
          <div role="radiogroup" aria-label="Adresler" className="stack">
            {addresses.data?.map((a) => (
              <label key={a.id} className="row" style={{ fontWeight: 400 }}>
                <input
                  type="radio"
                  name="address"
                  value={a.id}
                  disabled={!a.usableForRouting}
                  checked={form.watch('addressId') === a.id}
                  onChange={() => form.setValue('addressId', a.id, { shouldValidate: true })}
                />
                <span>{a.addressText}</span>
                <Badge {...verificationLabel[a.verificationStatus]} />
              </label>
            ))}
          </div>
          {errors.addressId && <span className="field-error">{errors.addressId.message}</span>}

          <div className="row">
            <label>
              Ağırlık (kg)
              <input type="number" step="0.01" {...form.register('weightKg', { valueAsNumber: true })} />
              {errors.weightKg && <span className="field-error">{errors.weightKg.message}</span>}
            </label>
            <label>
              Öncelik (0–10)
              <input type="number" {...form.register('priority', { valueAsNumber: true })} />
              {errors.priority && <span className="field-error">{errors.priority.message}</span>}
            </label>
            <label>
              Teslim süresi (dk)
              <input type="number" {...form.register('serviceTimeMin', { valueAsNumber: true })} />
              {errors.serviceTimeMin && <span className="field-error">{errors.serviceTimeMin.message}</span>}
            </label>
          </div>

          {create.error && (
            <div role="alert" className="alert danger">
              {create.error instanceof ApiError ? create.error.message : 'Kargo oluşturulamadı.'}
            </div>
          )}
          <div className="row">
            <button type="submit" className="primary" disabled={create.isPending}>
              {create.isPending ? 'Kaydediliyor…' : 'Kargoyu oluştur'}
            </button>
            <button type="button" onClick={() => navigate('/firma/kargolar')}>Vazgeç</button>
          </div>
        </section>
      </form>
    </>
  );
}

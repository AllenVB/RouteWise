import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useParams } from 'react-router';
import { z } from 'zod';
import { Badge } from '../../components/ui/Badge';
import { ApiError } from '../../lib/apiClient';
import {
  addDays, formatClock, formatDate, formatDateTime, formatNumber, shipmentLabel, todayInDepot,
} from '../../lib/format';
import { useMyShipment, useMyShipments, usePostpone, type MyShipment } from './api';

/** Sunucudaki PostponeService.MAX_DAYS_AHEAD ile aynı: daha ilerisi erteleme değil yeni sipariş. */
export const MAX_POSTPONE_DAYS = 14;

/** Yarın … +14 gün; tarihler YYYY-MM-DD olduğu için metin karşılaştırması takvim sırasıdır. */
export function postponeSchema(today: string) {
  const min = addDays(today, 1);
  const max = addDays(today, MAX_POSTPONE_DAYS);
  return z.object({
    date: z.string().min(1, 'Bir gün seçin.')
      .refine((d) => d >= min, 'En erken yarın olabilir.')
      .refine((d) => d <= max, `En fazla ${MAX_POSTPONE_DAYS} gün sonrası seçilebilir.`),
  });
}
type PostponeForm = z.infer<ReturnType<typeof postponeSchema>>;

/** A2 — kargolarım. */
export function MyShipmentsPage() {
  const list = useMyShipments();

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Kargolarım</h1>
          <p className="muted">Kargo yola çıktığında tahmini teslim saati burada görünür.</p>
        </div>
      </div>
      <section className="card stack">
        {list.isPending && <div className="empty">Yükleniyor…</div>}
        {list.error && <div role="alert" className="alert danger">Kargolar yüklenemedi: {list.error.message}</div>}
        {list.data?.length === 0 && <div className="empty">Henüz kargonuz yok.</div>}
        {list.data?.map((s) => (
          <Link key={s.id} to={`/alici/kargolar/${s.id}`} className="row" style={{ justifyContent: 'space-between', textDecoration: 'none', color: 'inherit' }}>
            <span>
              <b>{s.orderNumber}</b> <span className="muted">{s.addressText}</span>
              <br />
              <span className="muted">{expectation(s)}</span>
            </span>
            <Badge {...shipmentLabel[s.status]} />
          </Link>
        ))}
      </section>
    </>
  );
}

/** A3 — kargo detayı. */
export function MyShipmentDetailPage() {
  const id = Number(useParams().id);
  const q = useMyShipment(id);

  if (q.isPending) return <div className="empty">Yükleniyor…</div>;
  if (q.error) {
    return (
      <div className="stack">
        <div role="alert" className="alert danger">
          {q.error instanceof ApiError && q.error.status === 404 ? 'Kargo bulunamadı.' : q.error.message}
        </div>
        <Link to="/alici/kargolar">← Kargolarım</Link>
      </div>
    );
  }
  const s = q.data;
  return (
    <div className="stack" style={{ maxWidth: 560 }}>
      <Link to="/alici/kargolar">← Kargolarım</Link>
      <div className="card stack">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h1>{s.orderNumber}</h1>
          <Badge {...shipmentLabel[s.status]} />
        </div>
        <p>{s.addressText}</p>
        <p className="muted">{formatNumber(s.weightKg)} kg · oluşturuldu {formatDateTime(s.createdAt)}</p>
        <div className="alert info">{expectation(s)}</div>
        {s.stopStatus === 'ULASILAMADI' && s.failureReason && (
          <div className="alert warn">Kurye notu: {s.failureReason}. Kargonuz başka bir güne planlanacak.</div>
        )}
      </div>
      {s.status !== 'TESLIM_EDILDI' && <PostponeCard shipment={s} />}
    </div>
  );
}

/** A3 — "Başka gün gelsin". Kurye yoldaysa durak rotasından çıkar; bunu alıcıya önceden söyleriz. */
function PostponeCard({ shipment }: { shipment: MyShipment }) {
  const [open, setOpen] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const today = todayInDepot();
  const postpone = usePostpone(shipment.id);
  const form = useForm<PostponeForm>({
    resolver: zodResolver(postponeSchema(today)),
    defaultValues: { date: shipment.postponedTo ?? addDays(today, 1) },
  });
  const { errors } = form.formState;

  function onSubmit({ date }: PostponeForm) {
    postpone.mutate(date, {
      onSuccess: () => {
        setDone(date);
        setOpen(false);
      },
      onError: (e) => {
        const fe = e instanceof ApiError ? e.fieldErrors.find((f) => f.field === 'date') : undefined;
        if (fe) form.setError('date', { message: fe.message });
      },
    });
  }

  if (!open) {
    return (
      <div className="card stack">
        {done && <div role="status" className="alert ok">Kargonuz {formatDate(done)} tarihine ertelendi.</div>}
        <button type="button" onClick={() => { setDone(null); setOpen(true); }}>
          {shipment.status === 'ERTELENDI' ? 'Teslim gününü değiştir' : 'Başka gün gelsin'}
        </button>
      </div>
    );
  }

  return (
    <form className="card stack" noValidate onSubmit={form.handleSubmit(onSubmit)}>
      <h2>Başka gün gelsin</h2>
      {shipment.status === 'YOLDA' && (
        <p className="alert warn">Kargonuz bugün yolda. Ertelerseniz kurye bugün size uğramaz.</p>
      )}
      <label>
        Teslim günü
        <input type="date" min={addDays(today, 1)} max={addDays(today, MAX_POSTPONE_DAYS)}
          aria-invalid={errors.date ? true : undefined} {...form.register('date')} />
      </label>
      {errors.date && <span role="alert" className="field-error">{errors.date.message}</span>}
      {postpone.error && !(postpone.error instanceof ApiError && postpone.error.fieldErrors.length > 0) && (
        <div role="alert" className="alert danger">
          {postpone.error instanceof ApiError ? postpone.error.message : 'Erteleme kaydedilemedi.'}
        </div>
      )}
      <div className="row">
        <button type="submit" className="primary" disabled={postpone.isPending}>Ertele</button>
        <button type="button" onClick={() => { postpone.reset(); setOpen(false); }}>Vazgeç</button>
      </div>
    </form>
  );
}

/** Alıcının bilmek istediği tek cümle: ne zaman geliyor ya da ne oldu. */
function expectation(s: MyShipment): string {
  if (s.status === 'TESLIM_EDILDI') return s.actualArrival ? `Teslim edildi · ${formatClock(s.actualArrival)}` : 'Teslim edildi';
  if (s.stopStatus === 'ULASILAMADI') return 'Teslim edilemedi; yeniden planlanacak';
  if (s.status === 'ERTELENDI' && s.postponedTo) return `Ertelendi · ${formatDate(s.postponedTo)}`;
  if (s.eta) return `Tahmini teslim ${formatClock(s.eta)}`;
  return 'Henüz planlanmadı';
}

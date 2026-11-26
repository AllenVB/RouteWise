import { formatClock } from '../../lib/format';
import type { AppNotification } from './api';

/**
 * Bildirim metni ekranda kurulur; sunucu yalnızca veriyi saklar. Böylece metin
 * değişikliği eski bildirimleri de günceller, veri göçü gerekmez.
 * (E-posta metni backend'de EmailNotifier'da; anlamları aynı tutulmalı.)
 */
const postponeReason: Record<string, string> = {
  EXCEEDS_WORKDAY: ' Kurye bugün mesai içinde adresinize ulaşamayacaktı.',
  FIRMA_ERTELEDI: ' Yeni adresiniz kuryenin bugünkü güzergâhının dışında kaldı.',
};

export function notificationText(n: AppNotification): { title: string; body: string } {
  const p = n.payload ?? {};
  const str = (k: string) => (typeof p[k] === 'string' ? (p[k] as string) : '');
  switch (n.type) {
    case 'ETA':
      return p.updated === true
        ? {
            title: 'Tahmini teslim saatiniz değişti',
            body: `Kuryenin rotası güncellendi; ${str('orderNumber')} numaralı kargonuzun yeni tahmini teslim saati ${str('eta') ? formatClock(str('eta')) : 'belirlenecek'}.`,
          }
        : {
            title: 'Kargonuz bugün yola çıkıyor',
            body: `${str('orderNumber')} numaralı kargonuzun tahmini teslim saati ${str('eta') ? formatClock(str('eta')) : 'belirlenecek'}.`,
          };
    case 'DOGRULAMA':
      return str('result') === 'DOGRULANDI'
        ? { title: 'Adresiniz onaylandı', body: `"${str('addressText')}" adresiniz onaylandı.` }
        : {
            title: 'Adresiniz onaylanmadı',
            body: `"${str('addressText')}" onaylanmadı. Sebep: ${str('reason') || 'belirtilmedi'}. Konumu düzeltip yeniden gönderebilirsiniz.`,
          };
    case 'ERTELEME': {
      const when = str('postponedTo') ? ` ${str('postponedTo')} tarihine` : '';
      const why = postponeReason[str('reason')] ?? '';
      return { title: 'Kargonuz ertelendi', body: `${str('orderNumber')} numaralı kargonuz${when} ertelendi.${why}` };
    }
    case 'ROTA_DEGISTI':
      return { title: 'Teslimat planınız değişti', body: `${str('orderNumber')} numaralı kargonuzun teslim planı değişti.` };
  }
}

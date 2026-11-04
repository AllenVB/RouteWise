import { formatClock } from '../../lib/format';
import type { AppNotification } from './api';

/**
 * Bildirim metni ekranda kurulur; sunucu yalnızca veriyi saklar. Böylece metin
 * değişikliği eski bildirimleri de günceller, veri göçü gerekmez.
 * (E-posta metni backend'de EmailNotifier'da; anlamları aynı tutulmalı.)
 */
export function notificationText(n: AppNotification): { title: string; body: string } {
  const p = n.payload ?? {};
  const str = (k: string) => (typeof p[k] === 'string' ? (p[k] as string) : '');
  switch (n.type) {
    case 'ETA':
      return {
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
    case 'ERTELEME':
      return { title: 'Kargonuz ertelendi', body: `${str('orderNumber')} numaralı kargonuz ertelendi.` };
    case 'ROTA_DEGISTI':
      return { title: 'Teslimat planınız değişti', body: `${str('orderNumber')} numaralı kargonuzun teslim planı değişti.` };
  }
}

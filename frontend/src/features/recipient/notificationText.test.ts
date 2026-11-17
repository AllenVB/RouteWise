import { describe, expect, it } from 'vitest';
import type { AppNotification } from './api';
import { notificationText } from './notificationText';

const n = (type: AppNotification['type'], payload: Record<string, unknown>) =>
  ({ id: 1, type, shipmentId: 5, payload, read: false, createdAt: '2026-11-17T08:00:00Z' }) satisfies AppNotification;

describe('notificationText', () => {
  it('should tell the recipient why the system postponed the shipment', () => {
    const t = notificationText(n('ERTELEME', {
      orderNumber: 'BND-2026-00042', postponedTo: '2026-11-18', reason: 'EXCEEDS_WORKDAY',
    }));

    expect(t.title).toBe('Kargonuz ertelendi');
    expect(t.body).toBe('BND-2026-00042 numaralı kargonuz 2026-11-18 tarihine ertelendi. '
      + 'Kurye bugün mesai içinde adresinize ulaşamayacaktı.');
  });

  it('should keep the old wording for a postponement without details', () => {
    expect(notificationText(n('ERTELEME', { orderNumber: 'BND-2026-00042' })).body)
      .toBe('BND-2026-00042 numaralı kargonuz ertelendi.');
  });

  it('should announce a changed ETA differently from the first one', () => {
    const first = notificationText(n('ETA', { orderNumber: 'X', eta: '2026-11-17T09:30:00Z' }));
    const updated = notificationText(n('ETA', { orderNumber: 'X', eta: '2026-11-17T09:30:00Z', updated: true }));

    expect(first.title).toBe('Kargonuz bugün yola çıkıyor');
    expect(updated.title).toBe('Tahmini teslim saatiniz değişti');
    expect(updated.body).toContain('12:30');
  });
});

import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderAt, signedInAs } from '../../test/render';
import { apiError, server } from '../../test/server';
import { MyShipmentDetailPage, MyShipmentsPage } from './MyShipmentsPage';
import { NotificationsPage } from './NotificationsPage';

const shipment = (extra: object = {}) => ({
  id: 51, orderNumber: 'BND-2026-00051', status: 'PLANLANDI', addressText: 'Yeni Mah. 1204 Sk.', weightKg: 4.2,
  createdAt: '2026-11-02T07:00:00Z', postponedTo: null, eta: '2026-11-03T05:25:00Z', stopStatus: 'BEKLEMEDE',
  actualArrival: null, failureReason: null, ...extra,
});

describe('recipient screens', () => {
  beforeEach(() => signedInAs('RECIPIENT'));

  describe('MyShipmentsPage (A2)', () => {
    it('should state the expectation in one sentence per shipment', async () => {
      server.use(http.get('/api/v1/me/shipments', () => HttpResponse.json([
        shipment(),
        shipment({ id: 52, orderNumber: 'BND-2026-00052', status: 'TESLIM_EDILDI', stopStatus: 'TESLIM_EDILDI', actualArrival: '2026-11-03T05:31:00Z' }),
        shipment({ id: 53, orderNumber: 'BND-2026-00053', status: 'BEKLEMEDE', eta: null, stopStatus: null }),
      ])));
      renderAt('/alici/kargolar', <MyShipmentsPage />);

      expect(await screen.findByText('Tahmini teslim 08:25')).toBeInTheDocument();
      expect(screen.getByText('Teslim edildi · 08:31')).toBeInTheDocument();
      expect(screen.getByText('Henüz planlanmadı')).toBeInTheDocument();
    });

    it('should show an empty state', async () => {
      server.use(http.get('/api/v1/me/shipments', () => HttpResponse.json([])));
      renderAt('/alici/kargolar', <MyShipmentsPage />);

      expect(await screen.findByText('Henüz kargonuz yok.')).toBeInTheDocument();
    });
  });

  describe('MyShipmentDetailPage (A3)', () => {
    it("should show the courier's note when delivery failed", async () => {
      server.use(http.get('/api/v1/me/shipments/51', () => HttpResponse.json(shipment({
        status: 'BEKLEMEDE', stopStatus: 'ULASILAMADI', failureReason: 'Adreste kimse yok',
      }))));
      renderAt('/alici/kargolar/:id', <MyShipmentDetailPage />, [], '/alici/kargolar/51');

      expect(await screen.findByText(/Kurye notu: Adreste kimse yok/)).toBeInTheDocument();
      expect(screen.getByText('Teslim edilemedi; yeniden planlanacak')).toBeInTheDocument();
    });

    it("should treat someone else's shipment as not found", async () => {
      server.use(http.get('/api/v1/me/shipments/99', () =>
        HttpResponse.json(apiError('NOT_FOUND', 'Kargo bulunamadı.'), { status: 404 })));
      renderAt('/alici/kargolar/:id', <MyShipmentDetailPage />, [], '/alici/kargolar/99');

      expect(await screen.findByRole('alert')).toHaveTextContent('Kargo bulunamadı.');
    });
  });

  describe('NotificationsPage (A5)', () => {
    const inbox = (read: boolean) => ({
      items: [
        { id: 7, type: 'ETA', shipmentId: 51, payload: { orderNumber: 'BND-2026-00051', eta: '2026-11-03T05:25:00Z' }, createdAt: '2026-11-03T04:00:00Z', read },
        { id: 8, type: 'DOGRULAMA', shipmentId: null, payload: { addressText: 'Yeni Mah.', result: 'REDDEDILDI', reason: 'Yanlış mahalle' }, createdAt: '2026-11-02T09:00:00Z', read: true },
      ],
      unreadCount: read ? 0 : 1,
    });

    it('should render wording from payload, including the rejection reason', async () => {
      server.use(http.get('/api/v1/me/notifications', () => HttpResponse.json(inbox(false))));
      renderAt('/alici/bildirimler', <NotificationsPage />);

      expect(await screen.findByText(/BND-2026-00051 numaralı kargonuzun tahmini teslim saati 08:25/)).toBeInTheDocument();
      expect(screen.getByText(/Sebep: Yanlış mahalle/)).toBeInTheDocument();
    });

    it('should mark one as read on click and all with the button', async () => {
      const sent: unknown[] = [];
      server.use(
        http.get('/api/v1/me/notifications', () => HttpResponse.json(inbox(false))),
        http.post('/api/v1/me/notifications/read', async ({ request }) => {
          sent.push(await request.json());
          return new HttpResponse(null, { status: 204 });
        }),
      );
      const user = userEvent.setup();
      renderAt('/alici/bildirimler', <NotificationsPage />);

      await user.click(await screen.findByRole('button', { name: 'Kargonuz bugün yola çıkıyor' }));
      await user.click(screen.getByRole('button', { name: 'Tümünü okundu say' }));

      await expect.poll(() => sent).toEqual([{ ids: [7] }, { all: true }]);
    });

    it('should not send anything when clicking an already read notification', async () => {
      server.use(http.get('/api/v1/me/notifications', () => HttpResponse.json(inbox(true))));
      renderAt('/alici/bildirimler', <NotificationsPage />);

      // Tanımsız uç çağrılırsa MSW testi düşürür.
      await userEvent.setup().click(await screen.findByRole('button', { name: 'Adresiniz onaylanmadı' }));
      expect(screen.queryByRole('button', { name: 'Tümünü okundu say' })).not.toBeInTheDocument();
    });
  });
});

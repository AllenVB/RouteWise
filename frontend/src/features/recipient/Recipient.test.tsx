import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { addDays, formatDate, todayInDepot } from '../../lib/format';
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

    describe('postpone (Başka gün gelsin)', () => {
      const today = todayInDepot();

      function renderDetail(extra: object = {}) {
        server.use(http.get('/api/v1/me/shipments/51', () => HttpResponse.json(shipment(extra))));
        renderAt('/alici/kargolar/:id', <MyShipmentDetailPage />, [], '/alici/kargolar/51');
      }

      it('should postpone to the chosen day and confirm it', async () => {
        const target = addDays(today, 3);
        let sent: unknown;
        server.use(http.post('/api/v1/me/shipments/51/postpone', async ({ request }) => {
          sent = await request.json();
          return HttpResponse.json(shipment({ status: 'ERTELENDI', postponedTo: target, eta: null, stopStatus: 'ERTELENDI' }));
        }));
        const user = userEvent.setup();
        renderDetail({ status: 'YOLDA' });

        await user.click(await screen.findByRole('button', { name: 'Başka gün gelsin' }));
        expect(screen.getByText(/Ertelerseniz kurye bugün size uğramaz/)).toBeInTheDocument();
        const day = screen.getByLabelText('Teslim günü');
        expect(day).toHaveValue(addDays(today, 1));
        await user.clear(day);
        await user.type(day, target);
        await user.click(screen.getByRole('button', { name: 'Ertele' }));

        expect(await screen.findByRole('status')).toHaveTextContent(`Kargonuz ${formatDate(target)} tarihine ertelendi.`);
        expect(sent).toEqual({ date: target });
        expect(screen.getByText(`Ertelendi · ${formatDate(target)}`)).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Teslim gününü değiştir' })).toBeInTheDocument();
      });

      it('should reject a day beyond the window without calling the server', async () => {
        const user = userEvent.setup();
        renderDetail();

        await user.click(await screen.findByRole('button', { name: 'Başka gün gelsin' }));
        const day = screen.getByLabelText('Teslim günü');
        await user.clear(day);
        await user.type(day, addDays(today, 15));
        await user.click(screen.getByRole('button', { name: 'Ertele' }));

        // Tanımsız POST çağrılsaydı MSW testi düşürürdü.
        expect(await screen.findByRole('alert')).toHaveTextContent('En fazla 14 gün sonrası seçilebilir.');
      });

      it('should show the server message when the courier has just closed the stop', async () => {
        server.use(http.post('/api/v1/me/shipments/51/postpone', () => HttpResponse.json(
          apiError('SHIPMENT_NOT_POSTPONABLE', 'Kurye bu teslimatı az önce sonuçlandırdı; kargo durumunu yenileyin.'),
          { status: 409 })));
        const user = userEvent.setup();
        renderDetail({ status: 'YOLDA' });

        await user.click(await screen.findByRole('button', { name: 'Başka gün gelsin' }));
        await user.click(screen.getByRole('button', { name: 'Ertele' }));

        expect(await screen.findByRole('alert')).toHaveTextContent('Kurye bu teslimatı az önce sonuçlandırdı');
      });

      it('should not offer postponing a delivered shipment', async () => {
        renderDetail({ status: 'TESLIM_EDILDI', stopStatus: 'TESLIM_EDILDI', actualArrival: '2026-11-03T05:31:00Z' });

        expect(await screen.findByText('Teslim edildi · 08:31')).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: 'Başka gün gelsin' })).not.toBeInTheDocument();
      });
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

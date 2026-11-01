import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderAt, signedInAs } from '../../test/render';
import { apiError, server } from '../../test/server';
import { CourierRoutePage } from './CourierRoutePage';
import { CourierStopPage } from './CourierStopPage';

const stop = (id: number, seq: number, status: string, extra: object = {}) => ({
  id, sequenceNo: seq, shipmentId: 60 + id, orderNumber: `BND-2026-0006${id}`, addressText: `Adres ${id}`,
  lat: 40.35, lon: 27.97, distanceFromPrevM: 900, durationFromPrevS: 90,
  eta: '2026-11-01T05:10:00Z', status, recipientName: 'Elif Demir', recipientPhone: '05321112233',
  actualArrival: null, failureReason: null, ...extra,
});

const route = (stops: object[]) => ({
  run: { runId: 50, planId: 4, algorithm: 'ROUTEWISE', isApplied: true, isBaseline: false, totalDistanceM: 12000,
    totalDurationS: 3600, totalCostTry: 300, totalFuelL: 1, randomSeed: 7 },
  depot: { name: 'Bandırma Şube', lat: 40.3521, lon: 27.9767 },
  routes: [{ id: 7, vehicleId: 3, plate: '10 ABC 003', totalDistanceM: 12000, totalDurationS: 3600, totalLoadKg: 10,
    totalFuelL: 1, totalCostTry: 300, stops, geometry: null }],
  geometryAvailable: false,
});

describe('courier screens', () => {
  beforeEach(() => signedInAs('COURIER'));

  describe('CourierRoutePage (K2/K4)', () => {
    it('should explain when nothing has been dispatched today', async () => {
      server.use(http.get('/api/v1/courier/route', () =>
        HttpResponse.json(apiError('NOT_FOUND', 'Bu gün için sevk edilmiş rota bulunamadı.'), { status: 404 })));
      renderAt('/kurye', <CourierRoutePage />);

      expect(await screen.findByText(/Bugün sana sevk edilmiş bir rota yok/)).toBeInTheDocument();
    });

    it('should feature the first open stop and show progress', async () => {
      server.use(http.get('/api/v1/courier/route', () => HttpResponse.json(route([
        stop(1, 1, 'TESLIM_EDILDI'), stop(2, 2, 'BEKLEMEDE'), stop(3, 3, 'BEKLEMEDE'),
      ]))));
      renderAt('/kurye', <CourierRoutePage />);

      expect(await screen.findByText('1/3 tamamlandı')).toBeInTheDocument();
      expect(screen.getByText(/Sıradaki durak · 2\. durak · tahmini 08:10/)).toBeInTheDocument();
      // Kapanan durak soluk, sıradaki vurgulu: işaretçi sırası değişmez
      expect(screen.getByTestId('map-markers')).toHaveTextContent('D,1,2,3');
    });

    it('should say the day is done when every stop is closed', async () => {
      server.use(http.get('/api/v1/courier/route', () => HttpResponse.json(route([
        stop(1, 1, 'TESLIM_EDILDI'), stop(2, 2, 'ULASILAMADI', { failureReason: 'Kimse yok' }),
      ]))));
      renderAt('/kurye', <CourierRoutePage />);

      expect(await screen.findByText(/Bütün duraklar kapandı/)).toBeInTheDocument();
    });
  });

  describe('CourierStopPage (K3)', () => {
    function renderStop() {
      return renderAt('/kurye/durak/:id', <CourierStopPage />, [{ path: '/kurye', text: 'rota ekranı' }], '/kurye/durak/2');
    }

    beforeEach(() => {
      server.use(http.get('/api/v1/courier/stops/2', () => HttpResponse.json(stop(2, 2, 'BEKLEMEDE'))));
    });

    it('should mark delivered and return to the route', async () => {
      let delivered = false;
      server.use(http.post('/api/v1/courier/stops/2/deliver', () => {
        delivered = true;
        return HttpResponse.json(stop(2, 2, 'TESLIM_EDILDI', { actualArrival: '2026-11-01T05:12:00Z' }));
      }));
      renderStop();

      expect(await screen.findByRole('link', { name: /Alıcıyı ara · 05321112233/ })).toHaveAttribute('href', 'tel:05321112233');
      await userEvent.setup().click(screen.getByRole('button', { name: 'Teslim edildi' }));

      expect(await screen.findByText('rota ekranı')).toBeInTheDocument();
      expect(delivered).toBe(true);
    });

    it('should require a reason before marking unreachable', async () => {
      let sent: unknown;
      server.use(http.post('/api/v1/courier/stops/2/fail', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(stop(2, 2, 'ULASILAMADI', { failureReason: 'Adreste kimse yok' }));
      }));
      const user = userEvent.setup();
      renderStop();

      await user.click(await screen.findByRole('button', { name: 'Ulaşılamadı' }));
      const save = screen.getByRole('button', { name: 'Ulaşılamadı olarak kaydet' });
      expect(save).toBeDisabled();

      await user.type(screen.getByLabelText('Neden teslim edilemedi?'), '  Adreste kimse yok  ');
      await user.click(save);

      expect(await screen.findByText('rota ekranı')).toBeInTheDocument();
      expect(sent).toEqual({ reason: 'Adreste kimse yok' });
    });

    it('should show the server message when the stop was already closed', async () => {
      server.use(http.post('/api/v1/courier/stops/2/deliver', () =>
        HttpResponse.json(apiError('STOP_ALREADY_CLOSED', 'Bu durak zaten kapatıldı.'), { status: 409 })));
      renderStop();

      await userEvent.setup().click(await screen.findByRole('button', { name: 'Teslim edildi' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('Bu durak zaten kapatıldı.');
    });

    it('should hide actions on a closed stop', async () => {
      server.use(http.get('/api/v1/courier/stops/2', () =>
        HttpResponse.json(stop(2, 2, 'ULASILAMADI', { failureReason: 'Kimse yok', actualArrival: '2026-11-01T05:12:00Z' }))));
      renderStop();

      expect(await screen.findByText('Sebep: Kimse yok')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Teslim edildi' })).not.toBeInTheDocument();
    });
  });
});

import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderAt, signedInAs } from '../../test/render';
import { apiError, server } from '../../test/server';
import { CourierChangesPage } from './CourierChangesPage';
import { CourierRoutePage } from './CourierRoutePage';
import { CourierStopPage } from './CourierStopPage';

const stop = (id: number, seq: number, status: string, extra: object = {}) => ({
  id, sequenceNo: seq, shipmentId: 60 + id, orderNumber: `BND-2026-0006${id}`, addressText: `Adres ${id}`,
  lat: 40.35, lon: 27.97, distanceFromPrevM: 900, durationFromPrevS: 90,
  eta: '2026-11-01T05:10:00Z', status, recipientName: 'Elif Demir', recipientPhone: '05321112233',
  actualArrival: null, failureReason: null, ...extra,
});

const change = (id: number, extra: object = {}) => ({
  id, trigger: 'ERTELEME', at: '2026-11-01T06:40:00Z', orderKept: false, openStops: 2,
  removed: [{ stopId: 2, shipmentId: 62, orderNumber: 'BND-2026-00062', reason: 'ALICI_ERTELEDI' }],
  moved: [], reordered: [{ stopId: 4, shipmentId: 64, orderNumber: 'BND-2026-00064', fromSeq: 4, toSeq: 2 }],
  ...extra,
});
const changes = (list: object[], recomputing = false) => ({ routeId: 7, recomputing, changes: list });

const route = (stops: object[]) => ({
  run: { runId: 50, planId: 4, algorithm: 'ROUTEWISE', isApplied: true, isBaseline: false, totalDistanceM: 12000,
    totalDurationS: 3600, totalCostTry: 300, totalFuelL: 1, randomSeed: 7 },
  depot: { name: 'Bandırma Şube', lat: 40.3521, lon: 27.9767 },
  routes: [{ id: 7, vehicleId: 3, plate: '10 ABC 003', totalDistanceM: 12000, totalDurationS: 3600, totalLoadKg: 10,
    totalFuelL: 1, totalCostTry: 300, stops, geometry: null }],
  geometryAvailable: false,
});

describe('courier screens', () => {
  beforeEach(() => {
    signedInAs('COURIER');
    server.use(http.get('/api/v1/courier/route/changes', () => HttpResponse.json(changes([]))));
  });

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

    it('should leave a postponed stop out of progress and the map', async () => {
      server.use(http.get('/api/v1/courier/route', () => HttpResponse.json(route([
        stop(1, 1, 'TESLIM_EDILDI'), stop(3, 2, 'BEKLEMEDE'), stop(2, 3, 'ERTELENDI', { eta: null }),
      ]))));
      renderAt('/kurye', <CourierRoutePage />);

      expect(await screen.findByText('1/2 tamamlandı')).toBeInTheDocument();
      expect(screen.getByText(/Sıradaki durak · 2\. durak/)).toBeInTheDocument();
      expect(screen.getByText('Ertelendi · rotadan çıktı')).toBeInTheDocument();
      expect(screen.getByTestId('map-markers')).toHaveTextContent('D,1,2');
    });

    it('should say the day is done when every stop is closed', async () => {
      server.use(http.get('/api/v1/courier/route', () => HttpResponse.json(route([
        stop(1, 1, 'TESLIM_EDILDI'), stop(2, 2, 'ULASILAMADI', { failureReason: 'Kimse yok' }),
      ]))));
      renderAt('/kurye', <CourierRoutePage />);

      expect(await screen.findByText(/Bütün duraklar kapandı/)).toBeInTheDocument();
    });
  });

  describe('route changes on the route screen (K5)', () => {
    const today = route([stop(1, 1, 'TESLIM_EDILDI'), stop(4, 2, 'BEKLEMEDE'), stop(3, 3, 'BEKLEMEDE'), stop(2, 4, 'ERTELENDI')]);

    it('should say what changed until the courier acknowledges it', async () => {
      server.use(
        http.get('/api/v1/courier/route', () => HttpResponse.json(today)),
        http.get('/api/v1/courier/route/changes', () => HttpResponse.json(changes([change(5)]))),
      );
      const user = userEvent.setup();
      renderAt('/kurye', <CourierRoutePage />);

      const banner = await screen.findByText(/Rota değişti · 09:40/);
      expect(banner.closest('[role=status]')).toHaveTextContent('1 durak çıktı, 1 durağın sırası değişti · 2 açık durak kaldı.');
      expect(screen.getByRole('link', { name: 'Ne değişti?' })).toHaveAttribute('href', '/kurye/degisiklikler');

      await user.click(screen.getByRole('button', { name: 'Tamam' }));

      expect(screen.queryByText(/Rota değişti/)).not.toBeInTheDocument();
      expect(localStorage.getItem('routewise.courier.changesSeen.7')).toBe('5');
    });

    it('should not show a change the courier has already seen', async () => {
      localStorage.setItem('routewise.courier.changesSeen.7', '5');
      server.use(
        http.get('/api/v1/courier/route', () => HttpResponse.json(today)),
        http.get('/api/v1/courier/route/changes', () => HttpResponse.json(changes([change(5)]))),
      );
      renderAt('/kurye', <CourierRoutePage />);

      expect(await screen.findByText('1/3 tamamlandı')).toBeInTheDocument();
      expect(screen.queryByText(/Rota değişti/)).not.toBeInTheDocument();
    });

    it('should show progress while recomputing and reload the route when it is done', async () => {
      let routeCalls = 0;
      let busy = true;
      server.use(
        http.get('/api/v1/courier/route', () => {
          routeCalls++;
          return HttpResponse.json(today);
        }),
        http.get('/api/v1/courier/route/changes', () => {
          const body = busy ? changes([], true) : changes([change(6)]);
          busy = false;
          return HttpResponse.json(body);
        }),
      );
      renderAt('/kurye', <CourierRoutePage />);

      expect(await screen.findByText(/Rota yeniden hesaplanıyor/)).toBeInTheDocument();

      // Hesaplama sürerken sık sorulur; bitince yeni sıra için rota tazelenir.
      expect(await screen.findByText(/Rota değişti/, {}, { timeout: 5_000 })).toBeInTheDocument();
      await expect.poll(() => routeCalls).toBe(2);
    });

    it('should say when the daily limit kept the order', async () => {
      server.use(
        http.get('/api/v1/courier/route', () => HttpResponse.json(today)),
        http.get('/api/v1/courier/route/changes', () =>
          HttpResponse.json(changes([change(8, { orderKept: true, reordered: [] })]))),
      );
      renderAt('/kurye', <CourierRoutePage />);

      expect(await screen.findByText(/Bugünkü değişiklik limiti doldu; kalan sıran korunuyor/)).toBeInTheDocument();
    });
  });

  describe('CourierChangesPage (K5)', () => {
    it('should list removed, moved and reordered stops with addresses, newest first', async () => {
      server.use(
        http.get('/api/v1/courier/route', () => HttpResponse.json(route([
          stop(1, 1, 'TESLIM_EDILDI'), stop(4, 2, 'BEKLEMEDE'), stop(3, 3, 'BEKLEMEDE'), stop(2, 4, 'ERTELENDI'),
        ]))),
        http.get('/api/v1/courier/route/changes', () => HttpResponse.json(changes([
          change(5),
          change(6, {
            trigger: 'ADRES_DEGISIKLIGI', at: '2026-11-01T07:15:00Z',
            removed: [], reordered: [], moved: [{ stopId: 3, shipmentId: 63, orderNumber: 'BND-2026-00063' }],
          }),
        ]))),
      );
      renderAt('/kurye/degisiklikler', <CourierChangesPage />);

      const cards = await screen.findAllByRole('region', { name: /Değişiklik/ });
      expect(cards.map((c) => c.getAttribute('aria-label'))).toEqual(['Değişiklik 10:15', 'Değişiklik 09:40']);
      expect(cards[0]).toHaveTextContent('Adresi değişen');
      expect(await screen.findByRole('link', { name: 'Adres 3' })).toHaveAttribute('href', '/kurye/durak/3');
      expect(cards[1]).toHaveTextContent('Adres 2 — Alıcı başka güne erteledi');
      expect(cards[1]).toHaveTextContent('Adres 4 — 4. duraktan 2. durağa');
      expect(localStorage.getItem('routewise.courier.changesSeen.7')).toBe('6');
    });

    it('should say when nothing changed today', async () => {
      server.use(http.get('/api/v1/courier/route', () => HttpResponse.json(route([stop(1, 1, 'BEKLEMEDE')]))));
      renderAt('/kurye/degisiklikler', <CourierChangesPage />);

      expect(await screen.findByText('Bugün rotanda değişiklik olmadı.')).toBeInTheDocument();
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

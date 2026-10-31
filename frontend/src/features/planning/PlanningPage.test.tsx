import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderAt, signedInAs } from '../../test/render';
import { apiError, server } from '../../test/server';
import { PlanningPage } from './PlanningPage';

const plan = { id: 3, date: '2026-10-30', status: 'TASLAK', createdAt: '2026-10-30T07:00:00Z' };
const optimized = {
  runId: 41, algorithm: 'ROUTEWISE', isApplied: true, totalDistanceM: 24911.47, totalDurationS: 4200,
  totalFuelL: 2.6, totalCostTry: 712.4, priorityPenalty: 3.1, vehicleCount: 2, computationTimeMs: 600, randomSeed: 1234,
  baseline: { runId: 40, algorithm: 'NAIVE', totalDistanceM: 40615.97, totalDurationS: 5900, totalCostTry: 1031.0 },
  savingsPct: 30.9, distanceSavingsPct: 38.7,
  routes: [{ id: 7, vehicleId: 2, courierId: null, stopCount: 1 }, { id: 8, vehicleId: 3, courierId: null, stopCount: 1 }],
  excluded: [{ shipmentId: 13, orderNumber: 'BND-2026-00012', reason: 'ADDRESS_NOT_VERIFIED' }],
};
const detail = {
  run: { runId: 41, planId: 3, algorithm: 'ROUTEWISE', isApplied: true, isBaseline: false, totalDistanceM: 24911.47,
    totalDurationS: 4200, totalCostTry: 712.4, totalFuelL: 2.6, randomSeed: 1234 },
  depot: { name: 'Bandırma Şube', lat: 40.3521, lon: 27.9767 },
  routes: [{
    id: 7, vehicleId: 2, plate: '10 ABC 002', totalDistanceM: 14000, totalDurationS: 2400, totalLoadKg: 10,
    totalFuelL: 1.6, totalCostTry: 420.5,
    stops: [
      { id: 1, sequenceNo: 1, shipmentId: 5, orderNumber: 'BND-2026-00004', addressText: 'A', lat: 40.35, lon: 27.97, distanceFromPrevM: 900, durationFromPrevS: 90, eta: null, status: 'BEKLEMEDE' },
    ],
    geometry: { type: 'LineString', coordinates: [[27.9767, 40.3521], [27.97, 40.35], [27.9767, 40.3521]] },
  }, {
    id: 8, vehicleId: 3, plate: '10 ABC 003', totalDistanceM: 10911.47, totalDurationS: 1800, totalLoadKg: 6,
    totalFuelL: 1.0, totalCostTry: 291.9,
    stops: [
      { id: 2, sequenceNo: 1, shipmentId: 6, orderNumber: 'BND-2026-00005', addressText: 'B', lat: 40.36, lon: 27.98, distanceFromPrevM: 1200, durationFromPrevS: 120, eta: null, status: 'BEKLEMEDE' },
    ],
    geometry: { type: 'LineString', coordinates: [[27.9767, 40.3521], [27.98, 40.36], [27.985, 40.365], [27.9767, 40.3521]] },
  }],
  geometryAvailable: true,
};

describe('PlanningPage', () => {
  beforeEach(() => signedInAs('COMPANY'));

  it('should offer to open the plan when the day has none', async () => {
    let opened = false;
    server.use(
      http.get('/api/v1/plans', () => HttpResponse.json(apiError('NOT_FOUND', 'Bu günün planı bulunamadı.'), { status: 404 })),
      http.post('/api/v1/plans', () => { opened = true; return HttpResponse.json(plan, { status: 201 }); }),
      http.get('/api/v1/plans/3/runs', () => HttpResponse.json([])),
    );
    renderAt('/firma/planlama', <PlanningPage />);

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Planı aç' }));

    expect(await screen.findByRole('button', { name: 'Optimize et' })).toBeInTheDocument();
    expect(opened).toBe(true);
  });

  it('should show cost savings, excluded shipments with reasons, and draw each vehicle in its own color', async () => {
    server.use(
      http.get('/api/v1/plans', () => HttpResponse.json(plan)),
      http.get('/api/v1/plans/3/runs', () => HttpResponse.json([])),
      http.post('/api/v1/plans/3/optimize', () => HttpResponse.json(optimized)),
      http.get('/api/v1/runs/41', () => HttpResponse.json(detail)),
    );
    renderAt('/firma/planlama', <PlanningPage />);

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Optimize et' }));

    expect(await screen.findByText('%30,9')).toBeInTheDocument();          // TL tasarrufu, mesafe değil
    expect(screen.getByText(/Mesafe tasarrufu %38,7/)).toBeInTheDocument();
    expect(screen.getByText(/tohum 1234/)).toBeInTheDocument();
    expect(screen.getByText(/BND-2026-00012 — Adres onaylı değil/)).toBeInTheDocument();
    expect(await screen.findByText('10 ABC 003')).toBeInTheDocument();
    expect(screen.getByTestId('map-markers')).toHaveTextContent('D,1,1');
    // İki araç, iki farklı renk
    expect(screen.getByTestId('map-lines')).toHaveTextContent('#1f5fa8:3,#c2410c:4');
  });

  it('should show the reason when nothing can be planned', async () => {
    server.use(
      http.get('/api/v1/plans', () => HttpResponse.json(plan)),
      http.get('/api/v1/plans/3/runs', () => HttpResponse.json([])),
      http.post('/api/v1/plans/3/optimize', () => HttpResponse.json(
        apiError('NO_FEASIBLE_SOLUTION', 'Planlanacak kargoların hiçbirinin adresi doğrulanmamış.'), { status: 409 })),
    );
    renderAt('/firma/planlama', <PlanningPage />);

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Optimize et' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('hiçbirinin adresi doğrulanmamış');
  });

  it('should load the applied run on revisit', async () => {
    server.use(
      http.get('/api/v1/plans', () => HttpResponse.json(plan)),
      http.get('/api/v1/plans/3/runs', () => HttpResponse.json([
        { runId: 41, isApplied: true }, { runId: 40, isApplied: false },
      ])),
      http.get('/api/v1/runs/41', () => HttpResponse.json(detail)),
    );
    renderAt('/firma/planlama', <PlanningPage />);

    expect(await screen.findByText('10 ABC 002')).toBeInTheDocument();
  });

  it('should continue from the applied run when asked (warm start)', async () => {
    let sent: unknown;
    server.use(
      http.get('/api/v1/plans', () => HttpResponse.json(plan)),
      http.get('/api/v1/plans/3/runs', () => HttpResponse.json([{ runId: 41, isApplied: true }])),
      http.get('/api/v1/runs/41', () => HttpResponse.json(detail)),
      http.post('/api/v1/plans/3/optimize', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(optimized);
      }),
    );
    const user = userEvent.setup();
    renderAt('/firma/planlama', <PlanningPage />);

    await user.click(await screen.findByRole('checkbox', { name: 'Mevcut rotadan devam et' }));
    await user.click(screen.getByRole('button', { name: 'Optimize et' }));

    await expect.poll(() => sent).toEqual({ algorithm: 'ROUTEWISE', warmStartFromRunId: 41 });
  });

  describe('dispatch', () => {
    function withAppliedRun() {
      server.use(
        http.get('/api/v1/plans', () => HttpResponse.json(plan)),
        http.get('/api/v1/plans/3/runs', () => HttpResponse.json([{ runId: 41, isApplied: true }])),
        http.get('/api/v1/runs/41', () => HttpResponse.json(detail)),
      );
    }

    it('should dispatch after confirmation and show which courier got each route', async () => {
      withAppliedRun();
      let dispatched = false;
      server.use(http.post('/api/v1/plans/3/dispatch', () => {
        dispatched = true;
        return HttpResponse.json({
          planId: 3, status: 'SEVK_EDILDI', runId: 41,
          routes: [{ routeId: 7, vehicleId: 2, courierId: 1, courierName: 'Test Kurye', stopCount: 1 }],
        });
      }));
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      renderAt('/firma/planlama', <PlanningPage />);

      await userEvent.setup().click(await screen.findByRole('button', { name: 'Kuryelere sevk et' }));

      expect(await screen.findByRole('status')).toHaveTextContent('1 durak → Test Kurye');
      expect(dispatched).toBe(true);
    });

    it('should not dispatch when the user cancels', async () => {
      withAppliedRun();
      vi.spyOn(window, 'confirm').mockReturnValue(false);
      renderAt('/firma/planlama', <PlanningPage />);

      // Tanımsız uç çağrılırsa MSW testi düşürür.
      await userEvent.setup().click(await screen.findByRole('button', { name: 'Kuryelere sevk et' }));

      expect(screen.queryByRole('status')).not.toBeInTheDocument();
    });

    it('should explain when there are not enough couriers', async () => {
      withAppliedRun();
      server.use(http.post('/api/v1/plans/3/dispatch', () => HttpResponse.json(
        apiError('NOT_ENOUGH_COURIERS', '2 rota için 1 müsait kurye var.'), { status: 409 })));
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      renderAt('/firma/planlama', <PlanningPage />);

      await userEvent.setup().click(await screen.findByRole('button', { name: 'Kuryelere sevk et' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('2 rota için 1 müsait kurye var.');
    });
  });
});

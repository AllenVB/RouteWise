import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderAt, signedInAs } from '../../test/render';
import { apiError, server } from '../../test/server';
import { ComparisonPage } from './ComparisonPage';

const coords = [[27.9767, 40.3521], [27.98, 40.355], [27.99, 40.36]];
const pathResult = {
  id: 12, level: 'PATH', createdAt: '2026-11-20T08:00:00Z',
  from: { nodeId: 11, lat: 40.3521, lon: 27.9767, snapDistanceM: 4.3 },
  to: { nodeId: 42, lat: 40.36, lon: 27.99, snapDistanceM: 7.0 },
  weight: 'DISTANCE', repeats: 5,
  results: [
    { algorithm: 'DIJKSTRA', found: true, lengthM: 1200, durationS: 140, edgeCount: 2, nodesVisited: 6276, computationTimeMs: 19.07, deviationPct: 0, samePathAsReference: true, path: [11, 20, 42], coordinates: coords },
    { algorithm: 'ASTAR', found: true, lengthM: 1200, durationS: 140, edgeCount: 2, nodesVisited: 1395, computationTimeMs: 7.95, deviationPct: 0, samePathAsReference: true, path: [11, 20, 42], coordinates: coords },
    { algorithm: 'BFS', found: true, lengthM: 1306.7, durationS: 160, edgeCount: 1, nodesVisited: 5558, computationTimeMs: 7.62, deviationPct: 8.89, samePathAsReference: false, path: [11, 42], coordinates: coords.slice(0, 2) },
    { algorithm: 'DFS', found: true, lengthM: 11886.6, durationS: 1400, edgeCount: 140, nodesVisited: 4752, computationTimeMs: 8.27, deviationPct: 890.55, samePathAsReference: false, path: [11, 20, 42], coordinates: coords },
  ],
};

const plan = { id: 3, date: '2026-11-20', status: 'TASLAK', createdAt: '2026-11-20T07:00:00Z' };
const solution = (objective: number) => ({ totalDistanceM: 4200, totalDurationS: 900, totalCostTry: objective - 2, priorityPenalty: 2, objective });
const tourResult = {
  id: 13, level: 'TOUR', createdAt: '2026-11-20T08:05:00Z', planId: 3, vehicleId: 2, stopCount: 2, repeats: 3,
  seeds: [7, 8, 9], referenceAlgorithm: 'DFS_EXHAUSTIVE', referenceIsOptimal: true, exhaustiveSkipped: null,
  results: [
    { algorithm: 'NAIVE', feasible: false, error: 'Geliş sırası çalışma gününe sığmıyor.', solution: null, computationTimeMs: 0, gapPct: null, randomSeed: null, objectiveMin: null, objectiveMax: null, explored: null },
    { algorithm: 'GREEDY_NN', feasible: true, error: null, solution: solution(110), computationTimeMs: 0.4, gapPct: 10, randomSeed: null, objectiveMin: 110, objectiveMax: 110, explored: null },
    { algorithm: 'SAVINGS', feasible: true, error: null, solution: solution(104), computationTimeMs: 0.9, gapPct: 4, randomSeed: null, objectiveMin: 104, objectiveMax: 104, explored: null },
    { algorithm: 'ROUTEWISE', feasible: true, error: null, solution: solution(100), computationTimeMs: 410, gapPct: 0, randomSeed: 8, objectiveMin: 100, objectiveMax: 101.5, explored: null },
    { algorithm: 'DFS_EXHAUSTIVE', feasible: true, error: null, solution: solution(100), computationTimeMs: 35, gapPct: 0, randomSeed: null, objectiveMin: null, objectiveMax: null, explored: 4321 },
  ],
  unassigned: [], excluded: [],
};

const shipment = (id: number, verified = true) => ({
  id, orderNumber: `BND-2026-000${id}`, recipient: { id: 1, fullName: 'Ayşe' },
  address: { id: id + 100, text: `Sokak ${id}`, verificationStatus: verified ? 'DOGRULANDI' : 'DOGRULAMA_BEKLIYOR' },
  weightKg: 2.5, priority: 0, serviceTimeMin: 3, status: 'BEKLEMEDE', postponedTo: null, createdAt: '2026-11-19T08:00:00Z',
});
const page = (items: object[]) => ({ content: items, totalElements: items.length, totalPages: 1, page: 0, size: 25 });

describe('ComparisonPage (F8)', () => {
  beforeEach(() => {
    signedInAs('COMPANY');
    server.use(
      http.get('/api/v1/plans', () => HttpResponse.json(plan)),
      http.get('/api/v1/vehicles', () => HttpResponse.json([
        { id: 2, plate: '10 ABC 002', capacityKg: 500 }, { id: 3, plate: '10 ABC 003', capacityKg: 800 },
      ])),
      http.get('/api/v1/shipments', () => HttpResponse.json(page([shipment(5), shipment(6), shipment(7, false)]))),
    );
  });

  describe('Seviye 1 — yol bulma', () => {
    it('should compare the four algorithms between two picked points', async () => {
      let sent: unknown;
      server.use(http.post('/api/v1/compare/path', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(pathResult);
      }));
      const user = userEvent.setup();
      renderAt('/firma/karsilastirma', <ComparisonPage />);
      const level1 = screen.getByRole('region', { name: 'Seviye 1 — yol bulma' });

      const compare = within(level1).getByRole('button', { name: 'Karşılaştır' });
      expect(compare).toBeDisabled();
      await user.click(within(level1).getByRole('button', { name: "Haritada Bandırma'ya tıkla" }));
      expect(within(level1).getByText('Şimdi bitiş noktasını seç.')).toBeInTheDocument();
      await user.click(within(level1).getByRole('button', { name: "Haritada Bandırma'ya tıkla" }));
      await user.selectOptions(within(level1).getByLabelText('Ağırlık'), 'DURATION');
      await user.click(compare);

      const table = await within(level1).findByRole('table', { name: 'Yol bulma sonuçları' });
      expect(sent).toEqual({
        from: { lat: 40.3521, lon: 27.9767 }, to: { lat: 40.3521, lon: 27.9767 }, weight: 'DURATION', repeats: 5,
      });
      const rows = within(table).getAllByRole('row').slice(1);
      expect(rows.map((r) => within(r).getAllByRole('cell')[1]?.textContent)).toEqual(['Dijkstra', 'A* · aynı yol', 'BFS', 'DFS']);
      expect(rows[2]).toHaveTextContent('%8,9');
      expect(rows[3]).toHaveTextContent('%890,6');
      expect(rows[1]).toHaveTextContent('1.395');
      expect(within(level1).getByText(/Düğüm 11 → 42/)).toBeInTheDocument();
    });

    it('should draw only the algorithms ticked for the map', async () => {
      server.use(http.post('/api/v1/compare/path', () => HttpResponse.json(pathResult)));
      const user = userEvent.setup();
      renderAt('/firma/karsilastirma', <ComparisonPage />);
      const level1 = screen.getByRole('region', { name: 'Seviye 1 — yol bulma' });
      await user.click(within(level1).getByRole('button', { name: "Haritada Bandırma'ya tıkla" }));
      await user.click(within(level1).getByRole('button', { name: "Haritada Bandırma'ya tıkla" }));
      await user.click(within(level1).getByRole('button', { name: 'Karşılaştır' }));
      await within(level1).findByRole('table', { name: 'Yol bulma sonuçları' });

      // Varsayılan: Dijkstra, BFS, DFS (A* Dijkstra ile aynı yol, üst üste biner).
      expect(within(level1).getByTestId('map-lines')).toHaveTextContent('#1f5fa8:3,#c2410c:2,#7c3aed:3');
      await user.click(within(level1).getByRole('checkbox', { name: 'DFS haritada' }));
      await user.click(within(level1).getByRole('checkbox', { name: 'A* haritada' }));
      expect(within(level1).getByTestId('map-lines')).toHaveTextContent('#1f5fa8:3,#15803d:3,#c2410c:2');
    });

    it('should show the server message for a point outside the service area', async () => {
      server.use(http.post('/api/v1/compare/path', () => HttpResponse.json(
        apiError('OUTSIDE_SERVICE_AREA', 'Başlangıç noktası hizmet alanının dışında.'), { status: 422 })));
      const user = userEvent.setup();
      renderAt('/firma/karsilastirma', <ComparisonPage />);
      const level1 = screen.getByRole('region', { name: 'Seviye 1 — yol bulma' });
      await user.click(within(level1).getByRole('button', { name: "Haritada İstanbul'a tıkla" }));
      await user.click(within(level1).getByRole('button', { name: "Haritada Bandırma'ya tıkla" }));
      await user.click(within(level1).getByRole('button', { name: 'Karşılaştır' }));

      expect(await within(level1).findByRole('alert')).toHaveTextContent('hizmet alanının dışında');
    });
  });

  describe('Seviye 2 — tur sırası', () => {
    it('should compare the chosen shipments on the chosen vehicle against the proven optimum', async () => {
      let sent: unknown;
      server.use(http.post('/api/v1/plans/3/compare/tour', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(tourResult);
      }));
      const user = userEvent.setup();
      renderAt('/firma/karsilastirma', <ComparisonPage />);
      const level2 = screen.getByRole('region', { name: 'Seviye 2 — tur sırası' });

      await within(level2).findByRole('option', { name: /10 ABC 003/ });
      await user.selectOptions(within(level2).getByLabelText('Araç'), '3');
      await user.click(within(level2).getByText(/planın doğrulanmış tüm kargoları/));
      // Adresi doğrulanmamış kargo seçilemez: listede yok.
      expect(within(level2).queryByText(/BND-2026-0007/)).not.toBeInTheDocument();
      await user.click(within(level2).getByRole('checkbox', { name: /BND-2026-0006/ }));
      await user.click(within(level2).getByRole('checkbox', { name: /BND-2026-0005/ }));
      await user.click(within(level2).getByRole('button', { name: 'Karşılaştır' }));

      expect(await within(level2).findByRole('status')).toHaveTextContent('2 durak · 3 tekrar · sapma kanıtlı optimuma göre');
      expect(sent).toEqual({ vehicleId: 3, shipmentIds: [6, 5], repeats: 3 });
      const rows = within(within(level2).getByRole('table', { name: 'Tur sırası sonuçları' })).getAllByRole('row').slice(1);
      expect(rows[0]).toHaveTextContent('Geliş sırası çalışma gününe sığmıyor.');
      expect(rows[1]).toHaveTextContent('%10');
      expect(rows[3]).toHaveTextContent('tekrarlarda 100–101,5');
      expect(rows[4]).toHaveTextContent('4.321 arama düğümü');
      expect(rows[4]).toHaveTextContent('%0');
    });

    it('should say when the reference is only the best found', async () => {
      server.use(http.post('/api/v1/plans/3/compare/tour', () => HttpResponse.json({
        ...tourResult, stopCount: 14, referenceAlgorithm: 'ROUTEWISE', referenceIsOptimal: false,
        exhaustiveSkipped: '14 durak; kesin arama en fazla 10 durakta koşar.',
        results: tourResult.results.filter((r) => r.algorithm !== 'DFS_EXHAUSTIVE'),
      })));
      const user = userEvent.setup();
      renderAt('/firma/karsilastirma', <ComparisonPage />);
      const level2 = screen.getByRole('region', { name: 'Seviye 2 — tur sırası' });
      await user.click(await within(level2).findByRole('button', { name: 'Karşılaştır' }));

      expect(await within(level2).findByRole('status'))
        .toHaveTextContent('sapma bulunan en iyi çözüme göre (RouteWise); 14 durak; kesin arama en fazla 10 durakta koşar.');
    });

    it('should not offer the comparison when the day has no plan', async () => {
      server.use(http.get('/api/v1/plans', () => HttpResponse.json(apiError('NOT_FOUND', 'Plan bulunamadı.'), { status: 404 })));
      renderAt('/firma/karsilastirma', <ComparisonPage />);
      const level2 = screen.getByRole('region', { name: 'Seviye 2 — tur sırası' });

      expect(await within(level2).findByText(/Bu gün için plan yok/)).toBeInTheDocument();
      expect(within(level2).getByRole('button', { name: 'Karşılaştır' })).toBeDisabled();
    });

    it('should show the server message when the shipments do not fit one vehicle', async () => {
      server.use(http.post('/api/v1/plans/3/compare/tour', () => HttpResponse.json(
        apiError('NO_FEASIBLE_SOLUTION', 'Kargolar tek araca sığmıyor; kesin tur hesaplanamaz.'), { status: 409 })));
      const user = userEvent.setup();
      renderAt('/firma/karsilastirma', <ComparisonPage />);
      const level2 = screen.getByRole('region', { name: 'Seviye 2 — tur sırası' });
      await user.click(await within(level2).findByRole('button', { name: 'Karşılaştır' }));

      expect(await within(level2).findByRole('alert')).toHaveTextContent('tek araca sığmıyor');
    });
  });
});

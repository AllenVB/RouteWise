import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderAt, signedInAs } from '../../test/render';
import { apiError, server } from '../../test/server';
import { ExperimentSection } from './ExperimentSection';

const base = {
  id: 4, scenarioCount: 30, stopCount: 10, radiusM: 3000, repeats: 3, baseSeed: 2026, vehicleId: 2,
  createdAt: '2026-11-21T11:00:00Z', startedAt: '2026-11-21T11:00:01Z', lastError: null, scenarios: null,
};
const running = { ...base, status: 'RUNNING', completed: 12, failed: 1, finishedAt: null, summary: null };
const done = {
  ...base, status: 'DONE', completed: 30, failed: 0, finishedAt: '2026-11-21T11:04:31Z',
  summary: {
    usableScenarios: 30, alpha: 0.05,
    algorithms: [
      { algorithm: 'NAIVE', scenarios: 30, medianGapPct: 17.268, meanGapPct: 17.16, maxGapPct: 33.15, optimalCount: 0, medianTimeMs: 0.012 },
      { algorithm: 'ROUTEWISE', scenarios: 30, medianGapPct: 0, meanGapPct: 0, maxGapPct: 0, optimalCount: 30, medianTimeMs: 239.18 },
    ],
    tests: [
      { candidate: 'ROUTEWISE', reference: 'NAIVE', n: 30, candidateBetter: 30, ties: 0, candidateWorse: 0,
        meanDiffPct: -14.47, wilcoxonP: 9.3e-10, tP: 2.1e-17, significant: true, note: null },
      { candidate: 'ROUTEWISE', reference: 'SAVINGS', n: 30, candidateBetter: 26, ties: 4, candidateWorse: 0,
        meanDiffPct: -0.88, wilcoxonP: 0.0123, tP: 0.02, significant: true, note: null },
    ],
    claim: 'RouteWise, 30 senaryoda kanıtlı optimumun ortalama %0,00, en kötü %0,00 içinde; 30 senaryoda optimumu buldu.',
  },
};

describe('ExperimentSection (deney)', () => {
  beforeEach(() => signedInAs('COMPANY'));

  it('should start an experiment with the chosen parameters and show its progress', async () => {
    let sent: unknown;
    let list: object[] = [];
    server.use(
      http.get('/api/v1/compare/experiments', () => HttpResponse.json(list)),
      http.post('/api/v1/compare/experiments', async ({ request }) => {
        sent = await request.json();
        list = [running];
        return HttpResponse.json(running, { status: 202 });
      }),
    );
    const user = userEvent.setup();
    renderAt('/firma/karsilastirma', <ExperimentSection />);

    const scenarios = screen.getByLabelText('Senaryo');
    await user.clear(scenarios);
    await user.type(scenarios, '40');
    await user.type(screen.getByLabelText('Tohum'), '20a26');
    await user.click(screen.getByRole('button', { name: 'Deneyi başlat' }));

    expect(await screen.findByRole('status')).toHaveTextContent('13/30 senaryo koştu · 1 senaryo çözülemedi');
    expect(sent).toEqual({ scenarios: 40, stopCount: 10, radiusM: 3000, repeats: 3, seed: 2026 });
    expect(screen.getByRole('progressbar', { name: 'Deney ilerlemesi' })).toHaveAttribute('value', '13');
    // Çalışan deney varken ikincisi başlatılamaz.
    expect(screen.getByRole('button', { name: 'Deneyi başlat' })).toBeDisabled();
  });

  it('should show the claim, the gap table and the significance tests of a finished experiment', async () => {
    server.use(http.get('/api/v1/compare/experiments', () => HttpResponse.json([done])));
    renderAt('/firma/karsilastirma', <ExperimentSection />);

    expect(await screen.findByRole('status')).toHaveTextContent('RouteWise, 30 senaryoda kanıtlı optimumun');
    const gaps = within(screen.getByRole('table', { name: 'Optimumdan sapma' })).getAllByRole('row').slice(1);
    expect(gaps[0]).toHaveTextContent('Geliş sırası%17,27%17,16%33,150/30');
    expect(gaps[1]).toHaveTextContent('RouteWise%0%0%030/30');
    const tests = within(screen.getByRole('table', { name: 'Anlamlılık testleri' })).getAllByRole('row').slice(1);
    expect(tests[0]).toHaveTextContent('Geliş sırası3030 / 0 / 0-%14,47< 0,0001< 0,0001anlamlı');
    expect(tests[1]).toHaveTextContent('Clarke-Wright3026 / 4 / 0-%0,880,0123');
    expect(screen.getByRole('button', { name: 'Deneyi başlat' })).toBeEnabled();
  });

  it('should say why an experiment stopped', async () => {
    server.use(http.get('/api/v1/compare/experiments', () => HttpResponse.json([
      { ...base, status: 'FAILED', completed: 7, failed: 0, finishedAt: '2026-11-21T11:01:00Z', summary: null,
        lastError: 'Sunucu yeniden başladı; deney yarıda kaldı.' },
    ])));
    renderAt('/firma/karsilastirma', <ExperimentSection />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Deney yarıda kaldı (7/30): Sunucu yeniden başladı');
  });

  it('should show the server message when another experiment is running', async () => {
    server.use(
      http.get('/api/v1/compare/experiments', () => HttpResponse.json([])),
      http.post('/api/v1/compare/experiments', () => HttpResponse.json(
        apiError('EXPERIMENT_RUNNING', 'Bir deney zaten çalışıyor; bitince yenisini başlatabilirsin.'), { status: 409 })),
    );
    const user = userEvent.setup();
    renderAt('/firma/karsilastirma', <ExperimentSection />);

    await user.click(screen.getByRole('button', { name: 'Deneyi başlat' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Bir deney zaten çalışıyor');
  });
});

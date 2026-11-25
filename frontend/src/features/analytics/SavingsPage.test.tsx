import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { formatTry } from '../../lib/format';
import { renderAt, signedInAs } from '../../test/render';
import { apiError, server } from '../../test/server';
import { SavingsPage } from './SavingsPage';

const bucket = (start: string, end: string, base: number, opt: number) => ({
  start, end, plans: 2, shipments: 14, baselineCostTry: base, optimizedCostTry: opt, savingsTry: base - opt,
  savingsPct: Math.round(((base - opt) / base) * 10000) / 100, baselineDistanceM: 40000, optimizedDistanceM: 30000,
  distanceSavingsPct: 25,
});
const savings = (period: string, buckets: object[], total: object | null) => ({
  period, from: '2026-01-01', to: '2026-11-30', result: { period, buckets, total },
});
const monthly = savings('MONTH',
  [bucket('2026-10-01', '2026-10-31', 600.28, 416.56), bucket('2026-11-01', '2026-11-30', 797.9, 635.1)],
  { ...bucket('2026-10-02', '2026-11-20', 1398.18, 1051.66), plans: 3, savingsPct: 24.78 });

describe('SavingsPage (F9)', () => {
  beforeEach(() => signedInAs('COMPANY'));
  afterEach(() => vi.restoreAllMocks());

  it('should show the weighted savings, the period table and ask again when the period changes', async () => {
    const asked: string[] = [];
    server.use(http.get('/api/v1/analytics/savings', ({ request }) => {
      const url = new URL(request.url);
      asked.push(url.search);
      return HttpResponse.json(url.searchParams.get('period') === 'WEEK' ? savings('WEEK', [], null) : monthly);
    }));
    const user = userEvent.setup();
    renderAt('/firma/raporlar', <SavingsPage />);

    const summary = await screen.findByRole('region', { name: 'Özet' });
    expect(summary).toHaveTextContent(`Tasarruf${formatTry(346.52)}`);
    expect(summary).toHaveTextContent('Tasarruf oranı%24,8');
    expect(summary).toHaveTextContent('Sevk edilen plan3');
    expect(summary).toHaveTextContent('01.01.2026 – 30.11.2026');
    const rows = within(screen.getByRole('table', { name: 'Dönemsel tasarruf' })).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('01.10.2026 – 31.10.2026');
    expect(rows[0]).toHaveTextContent('%30,6');
    expect(asked[0]).toBe('?period=MONTH');

    await user.selectOptions(screen.getByLabelText('Dönem'), 'WEEK');

    expect(await screen.findByText('Bu aralıkta sevk edilmiş plan yok.')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Dönemsel tasarruf' })).not.toBeInTheDocument();
    expect(asked.at(-1)).toBe('?period=WEEK');
  });

  it('should send the chosen dates', async () => {
    const asked: string[] = [];
    server.use(http.get('/api/v1/analytics/savings', ({ request }) => {
      asked.push(new URL(request.url).search);
      return HttpResponse.json(monthly);
    }));
    const user = userEvent.setup();
    renderAt('/firma/raporlar', <SavingsPage />);
    await screen.findByRole('region', { name: 'Özet' });

    await user.type(screen.getByLabelText('Başlangıç'), '2026-03-01');
    await user.type(screen.getByLabelText('Bitiş'), '2026-09-30');

    await vi.waitFor(() => expect(asked.at(-1)).toBe('?period=MONTH&from=2026-03-01&to=2026-09-30'));
  });

  it('should download the report with the same range under the server file name', async () => {
    let asked = '';
    server.use(
      http.get('/api/v1/analytics/savings', () => HttpResponse.json(monthly)),
      http.get('/api/v1/analytics/export', ({ request }) => {
        asked = new URL(request.url).search;
        return new HttpResponse(new Uint8Array([37, 80, 68, 70]), {
          headers: { 'Content-Type': 'application/pdf',
            'Content-Disposition': 'attachment; filename="routewise-rapor-2026-01-01-2026-11-30.pdf"' },
        });
      }),
    );
    const createUrl = vi.fn(() => 'blob:rapor');
    const revokeUrl = vi.fn();
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: createUrl, revokeObjectURL: revokeUrl }));
    const saved: string[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      saved.push(this.download);
    });
    const user = userEvent.setup();
    renderAt('/firma/raporlar', <SavingsPage />);

    await user.click(await screen.findByRole('button', { name: 'PDF indir' }));

    expect(await screen.findByText('routewise-rapor-2026-01-01-2026-11-30.pdf indirildi.')).toBeInTheDocument();
    expect(asked).toBe('?period=MONTH&format=PDF');
    expect(saved).toEqual(['routewise-rapor-2026-01-01-2026-11-30.pdf']);
    expect(revokeUrl).toHaveBeenCalledWith('blob:rapor');
  });

  it('should show why a report could not be built', async () => {
    server.use(
      http.get('/api/v1/analytics/savings', () => HttpResponse.json(monthly)),
      http.get('/api/v1/analytics/export', () => HttpResponse.json(
        apiError('OPTIMIZER_UNAVAILABLE', 'Rota hesaplama servisine ulaşılamadı.'), { status: 502 })),
    );
    const user = userEvent.setup();
    renderAt('/firma/raporlar', <SavingsPage />);

    await user.click(await screen.findByRole('button', { name: 'Excel indir' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Rapor indirilemedi: Rota hesaplama servisine ulaşılamadı.');
  });

  it('should show the server message for an invalid range', async () => {
    server.use(http.get('/api/v1/analytics/savings', () => HttpResponse.json(
      apiError('VALIDATION_FAILED', 'Başlangıç tarihi bitişten sonra olamaz.'), { status: 400 })));
    renderAt('/firma/raporlar', <SavingsPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Başlangıç tarihi bitişten sonra olamaz.');
  });
});

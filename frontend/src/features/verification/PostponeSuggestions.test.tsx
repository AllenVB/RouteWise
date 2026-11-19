import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { formatTry } from '../../lib/format';
import { renderAt, signedInAs } from '../../test/render';
import { apiError, server } from '../../test/server';
import { PostponeSuggestions } from './PostponeSuggestions';

const suggestion = (extra: object = {}) => ({
  id: 3, routeId: 16, stopId: 43, shipmentId: 48, orderNumber: 'BND-2026-08003', addressText: 'Yeni Sokak 9',
  vehiclePlate: '10 R 1', costWithoutTry: 100, costWithTry: 125, extraCostTry: 25, extraRatio: 0.25,
  status: 'ACIK', createdAt: '2026-11-19T09:30:00Z', ...extra,
});

describe('PostponeSuggestions (maliyet artışı eşiği)', () => {
  beforeEach(() => signedInAs('COMPANY'));

  it('should show what the move adds to the round', async () => {
    server.use(http.get('/api/v1/reroute/suggestions', () => HttpResponse.json([suggestion()])));
    renderAt('/firma/dogrulama', <PostponeSuggestions />);

    const row = await screen.findByRole('group', { name: 'Öneri BND-2026-08003' });
    expect(row).toHaveTextContent('Yeni Sokak 9');
    expect(row).toHaveTextContent(`kalan tur ${formatTry(100)} → ${formatTry(125)}`);
    expect(row).toHaveTextContent(`+${formatTry(25)}, %25`);
    expect(row).toHaveTextContent('10 R 1');
  });

  it('should postpone on the company decision and drop the suggestion', async () => {
    let open = [suggestion()];
    let decided: string | undefined;
    server.use(
      http.get('/api/v1/reroute/suggestions', () => HttpResponse.json(open)),
      http.post('/api/v1/reroute/suggestions/3/postpone', () => {
        decided = 'postpone';
        open = [];
        return HttpResponse.json(suggestion({ status: 'ERTELENDI' }));
      }),
    );
    const user = userEvent.setup();
    renderAt('/firma/dogrulama', <PostponeSuggestions />);

    const row = await screen.findByRole('group', { name: 'Öneri BND-2026-08003' });
    await user.click(within(row).getByRole('button', { name: 'Yarına ertele' }));

    expect(decided).toBe('postpone');
    await expect.poll(() => screen.queryByRole('region', { name: 'Erteleme önerileri' })).toBeNull();
  });

  it('should keep the shipment on today when asked', async () => {
    let decided = false;
    server.use(
      http.get('/api/v1/reroute/suggestions', () => HttpResponse.json(decided ? [] : [suggestion()])),
      http.post('/api/v1/reroute/suggestions/3/keep', () => {
        decided = true;
        return HttpResponse.json(suggestion({ status: 'KALDI' }));
      }),
    );
    const user = userEvent.setup();
    renderAt('/firma/dogrulama', <PostponeSuggestions />);

    await user.click(await screen.findByRole('button', { name: 'Bugün kalsın' }));

    await expect.poll(() => screen.queryByRole('region', { name: 'Erteleme önerileri' })).toBeNull();
    expect(decided).toBe(true);
  });

  it('should show the server message when the courier has just closed the stop', async () => {
    server.use(
      http.get('/api/v1/reroute/suggestions', () => HttpResponse.json([suggestion()])),
      http.post('/api/v1/reroute/suggestions/3/postpone', () => HttpResponse.json(
        apiError('STOP_ALREADY_CLOSED', 'Kurye bu teslimatı az önce sonuçlandırdı; öneri artık geçerli değil.'),
        { status: 409 })),
    );
    const user = userEvent.setup();
    renderAt('/firma/dogrulama', <PostponeSuggestions />);

    await user.click(await screen.findByRole('button', { name: 'Yarına ertele' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Kurye bu teslimatı az önce sonuçlandırdı');
  });

  it('should render nothing when there is no suggestion', async () => {
    let asked = false;
    server.use(http.get('/api/v1/reroute/suggestions', () => {
      asked = true;
      return HttpResponse.json([]);
    }));
    renderAt('/firma/dogrulama', <PostponeSuggestions />);

    await expect.poll(() => asked).toBe(true);
    expect(screen.queryByRole('region', { name: 'Erteleme önerileri' })).not.toBeInTheDocument();
  });
});

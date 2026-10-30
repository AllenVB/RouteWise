import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderAt, signedInAs } from '../../test/render';
import { apiError, server } from '../../test/server';
import { MyAddressesPage } from './MyAddressesPage';

const inside = {
  withinServiceArea: true, snappedNodeId: 1902, snapDistanceM: 7.4, reverseGeocoded: '1204. Sokak',
  distanceToNearestM: 1800, outlier: false, acceptable: true, warnings: [], blockers: [],
};
const outside = {
  withinServiceArea: false, snappedNodeId: null, snapDistanceM: null, reverseGeocoded: null,
  distanceToNearestM: null, outlier: false, acceptable: false, warnings: [],
  blockers: ['Nokta Bandırma hizmet alanının dışında.'],
};

describe('MyAddressesPage', () => {
  beforeEach(() => {
    signedInAs('RECIPIENT');
    server.use(
      http.get('/api/v1/me/addresses', () => HttpResponse.json([])),
      http.post('/api/v1/addresses/validate', async ({ request }) => {
        const p = (await request.json()) as { lat: number };
        return HttpResponse.json(p.lat > 40.9 ? outside : inside);
      }),
    );
  });

  it('should reject a point outside Bandırma and keep save disabled', async () => {
    const user = userEvent.setup();
    renderAt('/alici/adresler', <MyAddressesPage />);

    await user.type(await screen.findByLabelText('Adres tarifi'), 'Kadıköy');
    await user.click(screen.getByRole('button', { name: "Haritada İstanbul'a tıkla" }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Bandırma hizmet alanının dışında');
    expect(screen.getByRole('button', { name: 'Kaydet' })).toBeDisabled();
  });

  it('should save an accepted point and send it pending verification', async () => {
    let sent: unknown;
    server.use(http.post('/api/v1/me/addresses', async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json({
        id: 40, addressText: 'Yeni Mah. 1204 Sk.', district: null, neighbourhood: null, lat: 40.3521, lon: 27.9767,
        verificationStatus: 'DOGRULAMA_BEKLIYOR', usableForRouting: false, isDefault: false,
      }, { status: 201 });
    }));
    const user = userEvent.setup();
    renderAt('/alici/adresler', <MyAddressesPage />);

    await user.type(await screen.findByLabelText('Adres tarifi'), 'Yeni Mah. 1204 Sk.');
    await user.click(screen.getByRole('button', { name: "Haritada Bandırma'ya tıkla" }));
    expect(await screen.findByText('Otomatik kontrollerin tümü geçti.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Kaydet' }));

    await expect.poll(() => sent).toEqual({ addressText: 'Yeni Mah. 1204 Sk.', location: { lat: 40.3521, lon: 27.9767 } });
  });

  it('should show the daily limit message when moving too often', async () => {
    server.use(
      http.get('/api/v1/me/addresses', () => HttpResponse.json([{
        id: 31, addressText: 'Eski adres', district: null, neighbourhood: null, lat: 40.35, lon: 27.97,
        verificationStatus: 'DOGRULANDI', usableForRouting: true, isDefault: true,
      }])),
      http.put('/api/v1/me/addresses/31', () => HttpResponse.json(
        apiError('CHANGE_LIMIT_REACHED', 'Bugün için adres değiştirme hakkın doldu. Yarın tekrar deneyebilirsin.'),
        { status: 409 })),
    );
    const user = userEvent.setup();
    renderAt('/alici/adresler', <MyAddressesPage />);

    await user.click(await screen.findByRole('button', { name: 'Taşı' }));
    await user.click(screen.getByRole('button', { name: "Haritada Bandırma'ya tıkla" }));
    await screen.findByText('Otomatik kontrollerin tümü geçti.');
    await user.click(screen.getByRole('button', { name: 'Kaydet' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('adres değiştirme hakkın doldu');
  });
});

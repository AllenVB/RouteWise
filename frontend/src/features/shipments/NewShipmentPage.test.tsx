import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderAt, signedInAs } from '../../test/render';
import { apiError, server } from '../../test/server';
import { NewShipmentPage } from './NewShipmentPage';

const recipients = {
  content: [{ id: 12, fullName: 'Elif Demir', email: 'elif@ornek.com', phone: null, defaultAddressId: 31 }],
  page: 0, size: 10, totalElements: 1, totalPages: 1,
};
const address = (id: number, text: string, verificationStatus: string, usableForRouting: boolean) => ({
  id, addressText: text, district: null, neighbourhood: null, lat: 40.35, lon: 27.97,
  verificationStatus, usableForRouting, isDefault: id === 31,
});
const shipment = {
  id: 417, orderNumber: 'BND-2026-00417', recipient: { id: 12, fullName: 'Elif Demir' },
  address: { id: 31, text: 'Yeni Mah.', verificationStatus: 'DOGRULANDI' },
  weightKg: 4.2, priority: 2, serviceTimeMin: 5, status: 'BEKLEMEDE', postponedTo: null, createdAt: '2026-10-30T07:40:00Z',
};

function renderPage() {
  return renderAt('/firma/kargolar/yeni', <NewShipmentPage />, [{ path: '/firma/kargolar', text: 'kargo listesi' }]);
}

async function chooseRecipientAndAddress(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('radio', { name: /Elif Demir/ }));
  await user.click(await screen.findByRole('radio', { name: /Yeni Mah\./ }));
}

describe('NewShipmentPage', () => {
  beforeEach(() => {
    signedInAs('COMPANY');
    server.use(
      http.get('/api/v1/recipients', () => HttpResponse.json(recipients)),
      http.get('/api/v1/recipients/12/addresses', () => HttpResponse.json([
        address(31, 'Yeni Mah.', 'DOGRULANDI', true),
        address(32, 'Çınarlı Mah.', 'DOGRULAMA_BEKLIYOR', false),
      ])),
    );
  });

  it('should not allow choosing an unverified address', async () => {
    renderPage();
    await userEvent.setup().click(await screen.findByRole('radio', { name: /Elif Demir/ }));

    expect(await screen.findByRole('radio', { name: /Çınarlı Mah\./ })).toBeDisabled();
    expect(screen.getByRole('radio', { name: /Yeni Mah\./ })).toBeEnabled();
  });

  it('should create shipment with the chosen ids and no order number', async () => {
    let sent: unknown;
    server.use(http.post('/api/v1/shipments', async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json(shipment, { status: 201 });
    }));
    const user = userEvent.setup();
    renderPage();

    await chooseRecipientAndAddress(user);
    await user.type(screen.getByLabelText('Ağırlık (kg)'), '4.2');
    await user.click(screen.getByRole('button', { name: 'Kargoyu oluştur' }));

    expect(await screen.findByText('kargo listesi')).toBeInTheDocument();
    expect(sent).toEqual({ recipientId: 12, addressId: 31, weightKg: 4.2, priority: 0, serviceTimeMin: 5 });
  });

  it('should block submit with field errors when required values are missing', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: 'Kargoyu oluştur' }));

    expect(await screen.findByText('Alıcı seçilmeli.')).toBeInTheDocument();
    expect(screen.getByText('Teslimat adresi seçilmeli.')).toBeInTheDocument();
    expect(screen.getByText('Ağırlık gerekli.')).toBeInTheDocument();
  });

  it('should show the server rule when the address lost its approval meanwhile', async () => {
    server.use(http.post('/api/v1/shipments', () => HttpResponse.json(
      apiError('ADDRESS_NOT_VERIFIED', 'Bu adres henüz doğrulanmadı. Doğrulandıktan sonra kargo oluşturabilirsin.'),
      { status: 409 })));
    const user = userEvent.setup();
    renderPage();

    await chooseRecipientAndAddress(user);
    await user.type(screen.getByLabelText('Ağırlık (kg)'), '1');
    await user.click(screen.getByRole('button', { name: 'Kargoyu oluştur' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Bu adres henüz doğrulanmadı');
    expect(screen.queryByText('kargo listesi')).not.toBeInTheDocument();
  });

  it('should place server field errors under their fields', async () => {
    server.use(http.post('/api/v1/shipments', () => HttpResponse.json(
      apiError('VALIDATION_FAILED', 'Girdiğin bilgileri kontrol et.', [{ field: 'weightKg', message: 'Ağırlık sıfırdan büyük olmalı.' }]),
      { status: 400 })));
    const user = userEvent.setup();
    renderPage();

    await chooseRecipientAndAddress(user);
    await user.type(screen.getByLabelText('Ağırlık (kg)'), '3');
    await user.click(screen.getByRole('button', { name: 'Kargoyu oluştur' }));

    expect(await screen.findByText('Ağırlık sıfırdan büyük olmalı.')).toBeInTheDocument();
  });
});

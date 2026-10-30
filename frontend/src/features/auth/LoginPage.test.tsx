import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { authStore } from '../../lib/authStore';
import { renderAt } from '../../test/render';
import { apiError, server } from '../../test/server';
import { LoginPage } from './LoginPage';

function renderLogin() {
  return renderAt('/giris', <LoginPage />, [
    { path: '/firma/kargolar', text: 'firma ana sayfası' },
    { path: '/alici/adresler', text: 'alıcı ana sayfası' },
  ]);
}

async function fill(email: string, password: string) {
  const user = userEvent.setup();
  if (email) await user.type(screen.getByLabelText('E-posta'), email);
  if (password) await user.type(screen.getByLabelText('Parola'), password);
  await user.click(screen.getByRole('button', { name: 'Giriş yap' }));
}

describe('LoginPage', () => {
  it('should sign in and route each role to its own home', async () => {
    server.use(http.post('/api/v1/auth/login', () => HttpResponse.json({
      accessToken: 'a', refreshToken: 'r', expiresInSeconds: 900, role: 'RECIPIENT', displayName: 'Elif Demir',
    })));
    renderLogin();

    await fill('alici@ornek.com', 'parola');

    expect(await screen.findByText('alıcı ana sayfası')).toBeInTheDocument();
    expect(authStore.get()).toMatchObject({ role: 'RECIPIENT', displayName: 'Elif Demir' });
  });

  it('should show validation errors without calling the server', async () => {
    // Tanımsız uç çağrılırsa MSW testi düşürür (onUnhandledRequest: error).
    renderLogin();

    await fill('gecersiz', '');

    expect(await screen.findByText('Geçerli bir e-posta gir.')).toBeInTheDocument();
    expect(screen.getByText('Parola gerekli.')).toBeInTheDocument();
  });

  it('should show the server message when credentials are wrong', async () => {
    server.use(http.post('/api/v1/auth/login', () =>
      HttpResponse.json(apiError('UNAUTHENTICATED', 'E-posta veya parola hatalı.'), { status: 401 })));
    renderLogin();

    await fill('firma@ornek.com', 'yanlis');

    expect(await screen.findByRole('alert')).toHaveTextContent('E-posta veya parola hatalı.');
    expect(authStore.get()).toBeNull();
  });
});

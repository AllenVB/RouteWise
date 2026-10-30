import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import { apiError, server } from '../test/server';
import { signedInAs } from '../test/render';
import { api, ApiError } from './apiClient';
import { authStore } from './authStore';

const ok = z.object({ ok: z.boolean() });

function tokens(n: number) {
  return { accessToken: `erisim-${n}`, refreshToken: `yenile-${n}`, expiresInSeconds: 900, role: 'COMPANY', displayName: 'Firma' };
}

describe('api', () => {
  it('should send bearer token and parse response with schema', async () => {
    signedInAs('COMPANY');
    server.use(http.get('/api/v1/x', ({ request }) =>
      HttpResponse.json({ ok: request.headers.get('authorization') === 'Bearer erisim' })));

    await expect(api('/x', { schema: ok })).resolves.toEqual({ ok: true });
  });

  it('should surface server ApiError code and message', async () => {
    server.use(http.post('/api/v1/shipments', () =>
      HttpResponse.json(apiError('ADDRESS_NOT_VERIFIED', 'Bu adres henüz doğrulanmadı.'), { status: 409 })));

    const err = await api('/shipments', { method: 'POST', body: {} }).catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 409, code: 'ADDRESS_NOT_VERIFIED', message: 'Bu adres henüz doğrulanmadı.' });
  });

  it('should give a generic message when the body is not an ApiError', async () => {
    server.use(http.get('/api/v1/x', () => new HttpResponse('Bad Gateway', { status: 502 })));

    await expect(api('/x')).rejects.toMatchObject({ status: 502, code: 'HTTP_502' });
  });

  describe('token refresh', () => {
    it('should refresh once and retry when access token expired', async () => {
      signedInAs('COMPANY');
      server.use(
        http.get('/api/v1/x', ({ request }) =>
          request.headers.get('authorization') === 'Bearer erisim-1'
            ? HttpResponse.json({ ok: true })
            : HttpResponse.json(apiError('UNAUTHENTICATED', 'Giriş yap.'), { status: 401 })),
        http.post('/api/v1/auth/refresh', () => HttpResponse.json(tokens(1))),
      );

      await expect(api('/x', { schema: ok })).resolves.toEqual({ ok: true });
      expect(authStore.get()?.refreshToken).toBe('yenile-1');
    });

    it('should share one refresh between concurrent 401s — rotated token is single use', async () => {
      signedInAs('COMPANY');
      let refreshCalls = 0;
      server.use(
        http.get('/api/v1/x', ({ request }) =>
          request.headers.get('authorization') === 'Bearer erisim-1'
            ? HttpResponse.json({ ok: true })
            : HttpResponse.json(apiError('UNAUTHENTICATED', 'Giriş yap.'), { status: 401 })),
        http.post('/api/v1/auth/refresh', async ({ request }) => {
          refreshCalls += 1;
          const body = (await request.json()) as { refreshToken: string };
          // Sunucu gibi davran: aynı refresh token ikinci kez kullanılamaz.
          return body.refreshToken === 'yenile' && refreshCalls === 1
            ? HttpResponse.json(tokens(1))
            : HttpResponse.json(apiError('UNAUTHENTICATED', 'Oturum sona erdi.'), { status: 401 });
        }),
      );

      const results = await Promise.all([api('/x', { schema: ok }), api('/x', { schema: ok }), api('/x', { schema: ok })]);

      expect(results).toEqual([{ ok: true }, { ok: true }, { ok: true }]);
      expect(refreshCalls).toBe(1);
    });

    it('should sign out when refresh is rejected', async () => {
      signedInAs('COMPANY');
      server.use(
        http.get('/api/v1/x', () => HttpResponse.json(apiError('UNAUTHENTICATED', 'Giriş yap.'), { status: 401 })),
        http.post('/api/v1/auth/refresh', () =>
          HttpResponse.json(apiError('UNAUTHENTICATED', 'Oturum sona erdi.'), { status: 401 })),
      );

      await expect(api('/x')).rejects.toMatchObject({ status: 401 });
      expect(authStore.get()).toBeNull();
    });

    it('should not try to refresh without a session', async () => {
      let refreshed = false;
      server.use(
        http.post('/api/v1/auth/login', () =>
          HttpResponse.json(apiError('UNAUTHENTICATED', 'E-posta veya parola hatalı.'), { status: 401 })),
        http.post('/api/v1/auth/refresh', () => {
          refreshed = true;
          return HttpResponse.json(tokens(1));
        }),
      );

      await expect(api('/auth/login', { method: 'POST', body: {} })).rejects.toMatchObject({ status: 401 });
      expect(refreshed).toBe(false);
    });
  });
});

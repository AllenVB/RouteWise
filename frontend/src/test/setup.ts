import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, vi } from 'vitest';
import { authStore } from '../lib/authStore';
import { server } from './server';

// MapLibre WebGL ister; jsdom'da yoktur. Harita testlerde tıklanabilir bir
// sahteyle değiştirilir — harita davranışı değil, ekranın tepkisi sınanır.
vi.mock('../components/map/MapView', () => import('./MapViewMock'));

// Yakalanmayan istek testi düşürür: sahte sunucuda tanımsız bir uç, ekranın
// sözleşmede olmayan bir çağrı yaptığı anlamına gelir.
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  cleanup();
  server.resetHandlers();
  authStore.signOut();   // oturum modül belleğinde de tutulur; localStorage'ı silmek yetmez
  localStorage.clear();
});
afterAll(() => server.close());

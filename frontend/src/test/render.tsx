import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { authStore } from '../lib/authStore';
import type { Role } from '../types/api';

export function signedInAs(role: Role) {
  authStore.signIn({ accessToken: 'erisim', refreshToken: 'yenile', expiresInSeconds: 900, role, displayName: 'Test' });
}

/**
 * Ekranı gerçek yönlendirici ve sorgu istemcisiyle çizer. `routes` verilirse
 * hedef yollar gözlenebilir (ör. başarılı kayıttan sonra listeye dönüş).
 */
export function renderAt(path: string, element: ReactElement, extra: { path: string; text: string }[] = []) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter(
    [{ path, element }, ...extra.map((r) => ({ path: r.path, element: <p>{r.text}</p> }))],
    { initialEntries: [path] },
  );
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

import { QueryClient } from '@tanstack/react-query';
import { ApiError } from './apiClient';

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        // 4xx tekrar denemekle düzelmez (yetki, doğrulama, bulunamadı); yalnızca ağ ve 5xx denenir.
        retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
        refetchOnWindowFocus: false,
      },
    },
  });
}

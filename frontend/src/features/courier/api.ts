import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../lib/apiClient';
import { runDetailSchema, stopDetailSchema } from '../../types/api';

/** Kuryenin bugünkü rotası; sevk edilmemişse `null` — 404 burada "bugün rotan yok" demektir. */
export function useMyRoute() {
  return useQuery({
    queryKey: ['courier', 'route'],
    queryFn: async () => {
      try {
        return await api('/courier/route', { schema: runDetailSchema });
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
  });
}

export function useStop(id: number) {
  return useQuery({
    queryKey: ['courier', 'stop', id],
    queryFn: () => api(`/courier/stops/${id}`, { schema: stopDetailSchema }),
  });
}

/** Teslim / ulaşılamadı: rota ekranı da güncellensin diye kurye sorguları tazelenir. */
export function useCloseStop(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (outcome: { kind: 'deliver' } | { kind: 'fail'; reason: string }) =>
      outcome.kind === 'deliver'
        ? api(`/courier/stops/${id}/deliver`, { method: 'POST', schema: stopDetailSchema })
        : api(`/courier/stops/${id}/fail`, { method: 'POST', body: { reason: outcome.reason }, schema: stopDetailSchema }),
    onSuccess: (stop) => {
      qc.setQueryData(['courier', 'stop', id], stop);
      void qc.invalidateQueries({ queryKey: ['courier', 'route'] });
    },
  });
}

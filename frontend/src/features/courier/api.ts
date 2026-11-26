import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
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

const removedSchema = z.object({ stopId: z.number(), shipmentId: z.number(), orderNumber: z.string().nullable(), reason: z.string() });
const movedSchema = z.object({ stopId: z.number(), shipmentId: z.number(), orderNumber: z.string().nullable() });
const reorderedSchema = movedSchema.extend({ fromSeq: z.number(), toSeq: z.number() });

export const routeChangeSchema = z.object({
  id: z.number(),
  trigger: z.enum(['ERTELEME', 'ADRES_DEGISIKLIGI']),
  at: z.string(),
  /** Günlük limit doldu: çıkan çıktı, sıra değişmedi. */
  orderKept: z.boolean(),
  openStops: z.number(),
  removed: z.array(removedSchema),
  moved: z.array(movedSchema),
  reordered: z.array(reorderedSchema),
});
export type RouteChange = z.infer<typeof routeChangeSchema>;

export const routeChangesSchema = z.object({
  routeId: z.number(),
  /** Bir değişiklik geldi, yeni sıra henüz hesaplanıyor. */
  recomputing: z.boolean(),
  changes: z.array(routeChangeSchema),
});
export type RouteChanges = z.infer<typeof routeChangesSchema>;

/** Hesaplama sürerken sık, sonra seyrek sorulur; canlı bağlantı (SSE) Faz 5 kapsamında yok. */
export const CHANGES_POLL_BUSY_MS = 2_000;
export const CHANGES_POLL_IDLE_MS = 30_000;

/**
 * K5 — günün rota değişiklikleri. Yeni bir değişiklik geldiğinde ya da hesaplama
 * bittiğinde rota da tazelenir: kurye eski sırayla yola devam etmesin.
 */
export function useRouteChanges(enabled: boolean) {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ['courier', 'route', 'changes'],
    enabled,
    queryFn: async () => {
      try {
        return await api('/courier/route/changes', { schema: routeChangesSchema });
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    refetchInterval: (query) => (query.state.data?.recomputing ? CHANGES_POLL_BUSY_MS : CHANGES_POLL_IDLE_MS),
  });

  const last = q.data ? `${q.data.changes.at(-1)?.id ?? 0}:${q.data.recomputing}` : null;
  const seen = useRef<string | null>(null);
  useEffect(() => {
    if (last === null) return;
    if (seen.current !== null && seen.current !== last) {
      void qc.invalidateQueries({ queryKey: ['courier', 'route'], exact: true });
    }
    seen.current = last;
  }, [last, qc]);
  return q;
}

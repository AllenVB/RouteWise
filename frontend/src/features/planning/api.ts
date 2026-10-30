import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { api, ApiError } from '../../lib/apiClient';
import { optimizeResponseSchema, planSchema, runDetailSchema, type Algorithm } from '../../types/api';

const runSummarySchema = z.object({ runId: z.number(), isApplied: z.boolean() });

/** Günün planı; yoksa `null` — 404 burada hata değil, "henüz açılmadı" demektir. */
export function usePlanForDate(date: string) {
  return useQuery({
    queryKey: ['plan', date],
    queryFn: async () => {
      try {
        return await api(`/plans?date=${date}`, { schema: planSchema });
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
  });
}

export function useOpenPlan() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (date: string) => api('/plans', { method: 'POST', body: { date }, schema: planSchema }),
    onSuccess: (plan) => qc.setQueryData(['plan', plan.date], plan),
  });
}

/** Planın uygulanan koşusu — sayfa yenilense de son optimizasyon görünsün. */
export function useAppliedRunId(planId: number | undefined) {
  return useQuery({
    queryKey: ['plan', planId, 'runs'],
    queryFn: () => api(`/plans/${planId}/runs`, { schema: z.array(runSummarySchema) }),
    enabled: planId !== undefined,
    select: (runs) => runs.find((r) => r.isApplied)?.runId ?? null,
  });
}

export interface OptimizeBody {
  algorithm: Algorithm;
  /** Yalnızca ROUTEWISE: yalnızca yeni kargo alan rotalar değişir. */
  warmStartFromRunId?: number;
}

export function useOptimize(planId: number | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: OptimizeBody) =>
      api(`/plans/${planId}/optimize`, { method: 'POST', body, schema: optimizeResponseSchema }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['plan', planId, 'runs'] }),
  });
}

export function useRunDetail(runId: number | null | undefined) {
  return useQuery({
    queryKey: ['run', runId],
    queryFn: () => api(`/runs/${runId}`, { schema: runDetailSchema }),
    enabled: typeof runId === 'number',
    staleTime: Infinity,   // koşular yazıldıktan sonra değişmez
  });
}

import { useMutation, useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { api, apiFile, saveFile } from '../../lib/apiClient';

export const periodSchema = z.enum(['WEEK', 'MONTH', 'YEAR']);
export type Period = z.infer<typeof periodSchema>;

const bucketSchema = z.object({
  start: z.string(),
  end: z.string(),
  plans: z.number(),
  shipments: z.number(),
  optimizedCostTry: z.number(),
  baselineCostTry: z.number(),
  savingsTry: z.number(),
  /** Ağırlıklı: (Σ naive − Σ uygulanan) / Σ naive. */
  savingsPct: z.number().nullable(),
  optimizedDistanceM: z.number(),
  baselineDistanceM: z.number(),
  distanceSavingsPct: z.number().nullable(),
});
export type Bucket = z.infer<typeof bucketSchema>;

export const savingsSchema = z.object({
  period: periodSchema,
  from: z.string(),
  to: z.string(),
  result: z.object({ period: periodSchema, buckets: z.array(bucketSchema), total: bucketSchema.nullable() }),
});
export type Savings = z.infer<typeof savingsSchema>;

export interface Range {
  period: Period;
  /** Boşsa sunucu varsayılanı: bugüne kadar 12 hafta / 12 ay / 5 yıl. */
  from: string;
  to: string;
}

function query(r: Range, extra: Record<string, string> = {}) {
  const p = new URLSearchParams({ period: r.period, ...extra });
  if (r.from) p.set('from', r.from);
  if (r.to) p.set('to', r.to);
  return p.toString();
}

export function useSavings(r: Range) {
  return useQuery({
    queryKey: ['savings', r],
    queryFn: () => api(`/analytics/savings?${query(r)}`, { schema: savingsSchema }),
  });
}

export type Format = 'XLSX' | 'PDF';

/** Raporu indirir ve tarayıcıya kaydettirir; dosya adı sunucudan. */
export function useDownloadReport() {
  return useMutation({
    mutationFn: async ({ range, format }: { range: Range; format: Format }) => {
      const { blob, filename } = await apiFile(`/analytics/export?${query(range, { format })}`,
        `routewise-rapor.${format === 'XLSX' ? 'xlsx' : 'pdf'}`);
      saveFile(blob, filename);
      return filename;
    },
  });
}

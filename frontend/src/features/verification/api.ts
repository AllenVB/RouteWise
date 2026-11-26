import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { api } from '../../lib/apiClient';
import { addressSchema, validationResultSchema, type VerificationStatus } from '../../types/api';

export function useAddressQueue(status: VerificationStatus) {
  return useQuery({
    queryKey: ['addresses', 'queue', status],
    queryFn: () => api(`/addresses?status=${status}`, { schema: z.array(addressSchema) }),
  });
}

/** Kaydetmeden dört otomatik kontrol — F5'te kayıtlı noktayı, A4'te tıklanan noktayı sınar. */
export function useValidatePoint(lat: number | null, lon: number | null, excludeAddressId?: number) {
  return useQuery({
    queryKey: ['validate', lat, lon, excludeAddressId],
    queryFn: () =>
      api(`/addresses/validate${excludeAddressId ? `?excludeAddressId=${excludeAddressId}` : ''}`, {
        method: 'POST',
        body: { lat, lon },
        schema: validationResultSchema,
      }),
    enabled: lat !== null && lon !== null,
    staleTime: Infinity,
  });
}

export function useVerifyAddress() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api(`/addresses/${id}/verify`, { method: 'POST', schema: addressSchema }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['addresses'] }),
  });
}

export function useRejectAddress() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) =>
      api(`/addresses/${id}/reject`, { method: 'POST', body: { reason }, schema: addressSchema }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['addresses'] }),
  });
}

// --- Maliyet artışı eşiği: firmaya erteleme önerileri ---------------------------

export const postponeSuggestionSchema = z.object({
  id: z.number(),
  routeId: z.number(),
  stopId: z.number(),
  shipmentId: z.number(),
  orderNumber: z.string().nullable(),
  addressText: z.string().nullable(),
  vehiclePlate: z.string().nullable(),
  costWithoutTry: z.number(),
  costWithTry: z.number(),
  extraCostTry: z.number(),
  extraRatio: z.number().nullable(),
  status: z.enum(['ACIK', 'ERTELENDI', 'KALDI', 'GECERSIZ']),
  createdAt: z.string(),
});
export type PostponeSuggestion = z.infer<typeof postponeSuggestionSchema>;

/** Onaydan sonra yeniden rotalama arka planda biter; öneri birkaç saniye sonra düşer. */
export const SUGGESTIONS_POLL_MS = 10_000;

export function usePostponeSuggestions() {
  return useQuery({
    queryKey: ['reroute', 'suggestions'],
    queryFn: () => api('/reroute/suggestions', { schema: z.array(postponeSuggestionSchema) }),
    refetchInterval: SUGGESTIONS_POLL_MS,
  });
}

export function useDecideSuggestion() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, decision }: { id: number; decision: 'postpone' | 'keep' }) =>
      api(`/reroute/suggestions/${id}/${decision}`, { method: 'POST', schema: postponeSuggestionSchema }),
    onSettled: () => qc.invalidateQueries({ queryKey: ['reroute', 'suggestions'] }),
  });
}

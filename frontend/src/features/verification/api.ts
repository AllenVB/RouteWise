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
